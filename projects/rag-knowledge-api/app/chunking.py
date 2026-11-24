"""Markdown-aware chunking: split on headings first, then window long sections with overlap."""
from __future__ import annotations

import re
from dataclasses import dataclass, field

HEADING = re.compile(r"^(#{1,6})\s+(.*)$")


@dataclass(frozen=True)
class Chunk:
    doc_id: str
    chunk_id: str
    title: str
    section: str
    text: str
    tags: tuple[str, ...] = field(default_factory=tuple)


def split_sections(markdown: str) -> list[tuple[str, str]]:
    """Returns (heading path, body) pairs. The heading path looks like 'Kafka consumer lag > Mitigation'."""
    sections: list[tuple[str, str]] = []
    path: list[str] = []
    buf: list[str] = []

    def flush() -> None:
        body = "\n".join(buf).strip()
        if body:
            sections.append((" > ".join(path) or "(intro)", body))
        buf.clear()

    for line in markdown.splitlines():
        m = HEADING.match(line)
        if m:
            flush()
            level = len(m.group(1))
            path[:] = path[: level - 1] + [m.group(2).strip()]
        else:
            buf.append(line)
    flush()
    return sections


def window(words: list[str], size: int, overlap: int) -> list[list[str]]:
    if len(words) <= size:
        return [words]
    step = max(size - overlap, 1)
    out = []
    for start in range(0, len(words), step):
        out.append(words[start : start + size])
        if start + size >= len(words):
            break
    return out


def chunk_document(doc_id: str, title: str, markdown: str, tags: tuple[str, ...] = (),
                   max_words: int = 160, overlap: int = 30) -> list[Chunk]:
    chunks: list[Chunk] = []
    for section, body in split_sections(markdown):
        for i, piece in enumerate(window(body.split(), max_words, overlap)):
            chunks.append(Chunk(
                doc_id=doc_id,
                chunk_id=f"{doc_id}#{len(chunks)}",
                title=title,
                section=section if i == 0 else f"{section} (cont. {i})",
                text=" ".join(piece),
                tags=tags,
            ))
    return chunks
