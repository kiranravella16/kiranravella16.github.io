"""Knowledge base: document registry + hybrid index + guarded answer generation."""
from __future__ import annotations

import hashlib
import logging
import re
import time
from dataclasses import dataclass, field
from pathlib import Path

from .chunking import chunk_document
from .llm import ExtractiveProvider, LLMProvider, Passage
from .redaction import redact
from .retrieval import Hit, HybridIndex

log = logging.getLogger(__name__)
CITATION = re.compile(r"\[(\d+)\]")


@dataclass
class Document:
    doc_id: str
    title: str
    source: str
    tags: tuple[str, ...]
    chunks: int
    sha256: str


@dataclass
class Answer:
    answer: str
    citations: list[dict]
    provider: str
    redactions: dict[str, int] = field(default_factory=dict)
    grounded: bool = True
    latency_ms: int = 0


class KnowledgeBase:
    def __init__(self, provider: LLMProvider | None = None, index: HybridIndex | None = None,
                 min_relevance: float = 0.15):
        self.provider = provider or ExtractiveProvider()
        self.index = index or HybridIndex()
        self.docs: dict[str, Document] = {}
        self.min_relevance = min_relevance

    def add_document(self, title: str, text: str, source: str = "api", tags: tuple[str, ...] = ()) -> Document:
        """Idempotent on content: re-uploading the same source replaces its chunks."""
        doc_id = re.sub(r"[^a-z0-9]+", "-", (source if source != "api" else title).lower()).strip("-")
        sha = hashlib.sha256(text.encode()).hexdigest()
        existing = self.docs.get(doc_id)
        if existing and existing.sha256 == sha:
            return existing
        if existing:
            self.index.remove_document(doc_id)
        body = re.sub(r"(?im)^tags:.*$", "", text)
        chunks = chunk_document(doc_id, title, body, tuple(t.lower() for t in tags))
        self.index.add(chunks)
        doc = Document(doc_id, title, source, tuple(tags), len(chunks), sha)
        self.docs[doc_id] = doc
        return doc

    def load_directory(self, path: str | Path) -> int:
        n = 0
        for f in sorted(Path(path).glob("**/*.md")):
            text = f.read_text(encoding="utf-8")
            title, tags = _front_matter(text, default_title=f.stem.replace("-", " ").title())
            self.add_document(title, text, source=str(f.relative_to(path)), tags=tags)
            n += 1
        return n

    @staticmethod
    def _group_by_document(hits: list[Hit]) -> list[Hit]:
        """Orders passages by their document's total fused score, so the runbook with the most supporting chunks
        leads, instead of whichever single chunk happened to rank first."""
        totals: dict[str, float] = {}
        for h in hits:
            totals[h.chunk.doc_id] = totals.get(h.chunk.doc_id, 0.0) + h.score
        return sorted(hits, key=lambda h: (-totals[h.chunk.doc_id], -h.score))

    def _expand_with_mitigation(self, question: str, hits: list[Hit]) -> list[Hit]:
        """Small-to-big expansion: for "how do I fix…" questions, make sure the top runbook's mitigation section
        is in context even if the question's wording only matched its symptoms."""
        if not hits or not _ACTION_Q.search(question):
            return hits
        top_doc = hits[0].chunk.doc_id
        have = {h.chunk.chunk_id for h in hits}
        extra = [Hit(c, 0.0, None, None) for c in self.index.chunks
                 if c.doc_id == top_doc and _ACTION_SECTION.search(c.section) and c.chunk_id not in have]
        return hits + extra[:2]

    def search(self, query: str, top_k: int = 5, tags: set[str] | None = None) -> list[Hit]:
        return self.index.search(query, top_k=top_k, tags={t.lower() for t in tags} if tags else None)

    def ask(self, question: str, top_k: int = 4, tags: set[str] | None = None) -> Answer:
        started = time.perf_counter()
        hits = self.search(question, top_k=top_k, tags=tags)
        dense_best = max((h.dense_score for h in hits), default=0.0)
        has_lexical = any(h.lexical_rank is not None for h in hits)
        if not hits or (not has_lexical and dense_best < self.min_relevance):
            return Answer("I couldn't find anything relevant in the indexed runbooks.", [], self.provider.name,
                          grounded=False, latency_ms=_ms(started))

        hits = self._group_by_document(hits)
        hits = self._expand_with_mitigation(question, hits)
        safe_question, q_red = redact(question)
        passages, redactions = [], dict(q_red)
        for n, h in enumerate(hits, start=1):
            text, r = redact(h.chunk.text)
            for k, v in r.items():
                redactions[k] = redactions.get(k, 0) + v
            passages.append(Passage(n, h.chunk.title, h.chunk.section, text))

        raw = self.provider.answer(safe_question, passages)
        cited = sorted({int(m) for m in CITATION.findall(raw) if 1 <= int(m) <= len(passages)})
        citations = [{
            "n": n,
            "doc_id": hits[n - 1].chunk.doc_id,
            "title": hits[n - 1].chunk.title,
            "section": hits[n - 1].chunk.section,
            "score": round(hits[n - 1].score, 5),
            "snippet": passages[n - 1].text[:240],
        } for n in cited]
        if redactions:
            log.info("redacted before generation: %s", redactions)
        return Answer(raw, citations, self.provider.name, redactions, grounded=bool(cited), latency_ms=_ms(started))


_ACTION_Q = re.compile(r"(?i)\b(how|fix|do|mitigat\w*|resolve|recover|steps?)\b")
_ACTION_SECTION = re.compile(r"(?i)mitigation|rollback steps|resolution")


def _front_matter(text: str, default_title: str) -> tuple[str, tuple[str, ...]]:
    """Reads the first '# Heading' as the title and an optional 'tags: a, b' line."""
    title = next((line[2:].strip() for line in text.splitlines() if line.startswith("# ")), default_title)
    m = re.search(r"(?im)^tags:\s*(.+)$", text)
    tags = tuple(t.strip().lower() for t in m.group(1).split(",")) if m else ()
    return title, tags


def _ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)
