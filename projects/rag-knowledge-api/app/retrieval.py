"""Hybrid retrieval: BM25 (lexical) + dense vectors, fused with Reciprocal Rank Fusion.

BM25 is good at exact terms (error codes, metric names like ``consumer_lag``), and dense vectors catch paraphrases.
RRF combines the two rankings without having to calibrate their scores against each other.
"""
from __future__ import annotations

import hashlib
import math
import re
from collections import Counter
from dataclasses import dataclass
from typing import Protocol

import numpy as np

from .chunking import Chunk

TOKEN = re.compile(r"[a-z0-9_]+")
STOPWORDS = frozenset(
    "a an and are as at be by for from has how i if in into is it its of on or our that the their then this to "
    "was we what when where which who why will with you your do does can should".split()
)


def tokenize(text: str) -> list[str]:
    return [t for t in TOKEN.findall(text.lower()) if t not in STOPWORDS]


class Embedder(Protocol):
    dim: int

    def embed(self, texts: list[str]) -> np.ndarray: ...


class HashingEmbedder:
    """Dependency-free local embedder: signed feature hashing over word unigrams + bigrams + char trigrams,
    L2-normalized. It's not a neural model, but it's deterministic, fast, and lets the service run offline.
    Swap in a hosted embedding model through the same ``Embedder`` protocol."""

    def __init__(self, dim: int = 1024):
        self.dim = dim

    def _features(self, text: str) -> list[str]:
        toks = tokenize(text)
        feats = toks + [f"{a}_{b}" for a, b in zip(toks, toks[1:])]
        for t in toks:
            padded = f"#{t}#"
            feats += [padded[i : i + 3] for i in range(len(padded) - 2)]
        return feats

    def embed(self, texts: list[str]) -> np.ndarray:
        out = np.zeros((len(texts), self.dim), dtype=np.float32)
        for row, text in enumerate(texts):
            for f in self._features(text):
                h = int.from_bytes(hashlib.blake2b(f.encode(), digest_size=8).digest(), "little")
                out[row, h % self.dim] += 1.0 if (h >> 63) & 1 else -1.0
            norm = np.linalg.norm(out[row])
            if norm:
                out[row] /= norm
        return out


class BM25:
    def __init__(self, k1: float = 1.5, b: float = 0.75):
        self.k1, self.b = k1, b
        self.docs: list[Counter[str]] = []
        self.lengths: list[int] = []
        self.df: Counter[str] = Counter()

    def add(self, text: str) -> None:
        tf = Counter(tokenize(text))
        self.docs.append(tf)
        self.lengths.append(sum(tf.values()))
        self.df.update(tf.keys())

    def scores(self, query: str) -> np.ndarray:
        n = len(self.docs)
        if n == 0:
            return np.zeros(0)
        avgdl = sum(self.lengths) / n
        out = np.zeros(n)
        for term in set(tokenize(query)):
            df = self.df.get(term, 0)
            if not df:
                continue
            idf = math.log(1 + (n - df + 0.5) / (df + 0.5))
            for i, tf in enumerate(self.docs):
                f = tf.get(term, 0)
                if f:
                    out[i] += idf * f * (self.k1 + 1) / (f + self.k1 * (1 - self.b + self.b * self.lengths[i] / avgdl))
        return out


@dataclass
class Hit:
    chunk: Chunk
    score: float
    lexical_rank: int | None
    dense_rank: int | None
    dense_score: float = 0.0


class HybridIndex:
    def __init__(self, embedder: Embedder | None = None, rrf_k: int = 60):
        self.embedder = embedder or HashingEmbedder()
        self.rrf_k = rrf_k
        self.chunks: list[Chunk] = []
        self.bm25 = BM25()
        self.vectors = np.zeros((0, self.embedder.dim), dtype=np.float32)

    def add(self, chunks: list[Chunk]) -> None:
        if not chunks:
            return
        for c in chunks:
            self.bm25.add(f"{c.title} {c.title} {c.section} {c.text}")  # titles count double
        vecs = self.embedder.embed([f"{c.title}. {c.section}. {c.text}" for c in chunks])
        self.vectors = np.vstack([self.vectors, vecs])
        self.chunks.extend(chunks)

    def remove_document(self, doc_id: str) -> int:
        keep = [c for c in self.chunks if c.doc_id != doc_id]
        removed = len(self.chunks) - len(keep)
        if removed:
            self.chunks, self.bm25 = [], BM25()
            self.vectors = np.zeros((0, self.embedder.dim), dtype=np.float32)
            self.add(keep)
        return removed

    def search(self, query: str, top_k: int = 5, tags: set[str] | None = None, candidates: int = 50) -> list[Hit]:
        if not self.chunks:
            return []
        allowed = np.array([not tags or bool(tags & set(c.tags)) for c in self.chunks])

        lexical = self.bm25.scores(query)
        dense = self.vectors @ self.embedder.embed([query])[0]

        def ranking(scores: np.ndarray, require_positive: bool) -> dict[int, int]:
            order = [int(i) for i in np.argsort(-scores) if allowed[i] and (scores[i] > 0 or not require_positive)]
            return {idx: rank for rank, idx in enumerate(order[:candidates])}

        lex_rank, dense_rank = ranking(lexical, True), ranking(dense, False)
        fused: dict[int, float] = {}
        for ranks in (lex_rank, dense_rank):
            for idx, rank in ranks.items():
                fused[idx] = fused.get(idx, 0.0) + 1.0 / (self.rrf_k + rank + 1)

        best = sorted(fused.items(), key=lambda kv: -kv[1])[:top_k]
        return [Hit(self.chunks[i], s, lex_rank.get(i), dense_rank.get(i), float(dense[i])) for i, s in best]
