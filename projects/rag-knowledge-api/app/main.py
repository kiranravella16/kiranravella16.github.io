"""FastAPI surface for the runbook knowledge base."""
from __future__ import annotations

import hmac
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Query, status
from pydantic import BaseModel, Field

from .knowledge import KnowledgeBase
from .llm import provider_from_env

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("rag")


class DocumentIn(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    text: str = Field(min_length=1, max_length=500_000)
    source: str = Field(default="api", max_length=300)
    tags: list[str] = Field(default_factory=list, max_length=20)


class AskIn(BaseModel):
    question: str = Field(min_length=3, max_length=2000)
    top_k: int = Field(default=4, ge=1, le=10)
    tags: list[str] | None = None


class Citation(BaseModel):
    n: int
    doc_id: str
    title: str
    section: str
    score: float
    snippet: str


class AskOut(BaseModel):
    answer: str
    citations: list[Citation]
    provider: str
    grounded: bool
    redactions: dict[str, int]
    latency_ms: int


def create_app(kb: KnowledgeBase | None = None, api_keys: set[str] | None = None,
               corpus_dir: str | None = None) -> FastAPI:
    keys = api_keys if api_keys is not None else {
        k.strip() for k in os.getenv("API_KEYS", "dev-key").split(",") if k.strip()}

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.kb = kb or KnowledgeBase(provider=provider_from_env())
        directory = corpus_dir if corpus_dir is not None else os.getenv("CORPUS_DIR", str(Path(__file__).parent.parent / "corpus"))
        if directory and Path(directory).is_dir():
            n = app.state.kb.load_directory(directory)
            log.info("indexed %d documents (%d chunks) from %s using provider=%s",
                     n, len(app.state.kb.index.chunks), directory, app.state.kb.provider.name)
        if keys == {"dev-key"}:
            log.warning("API_KEYS not set: using the insecure default 'dev-key'")
        yield

    app = FastAPI(title="Runbook RAG API", version="1.0.0", lifespan=lifespan)

    def require_key(x_api_key: str | None = Header(default=None)) -> None:
        # constant-time comparison so response timing doesn't leak key prefixes
        if not x_api_key or not any(hmac.compare_digest(x_api_key, k) for k in keys):
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "missing or invalid X-API-Key")

    def get_kb() -> KnowledgeBase:
        return app.state.kb

    @app.get("/healthz")
    def health():
        kb = get_kb()
        return {"status": "ok", "documents": len(kb.docs), "chunks": len(kb.index.chunks), "provider": kb.provider.name}

    @app.post("/v1/documents", status_code=201, dependencies=[Depends(require_key)])
    def add_document(doc: DocumentIn):
        d = get_kb().add_document(doc.title, doc.text, doc.source, tuple(doc.tags))
        return {"doc_id": d.doc_id, "chunks": d.chunks, "sha256": d.sha256}

    @app.get("/v1/documents", dependencies=[Depends(require_key)])
    def list_documents():
        return [d.__dict__ for d in get_kb().docs.values()]

    @app.get("/v1/search", dependencies=[Depends(require_key)])
    def search(q: str = Query(min_length=2), top_k: int = Query(5, ge=1, le=20), tag: list[str] | None = Query(None)):
        return [{
            "doc_id": h.chunk.doc_id, "title": h.chunk.title, "section": h.chunk.section,
            "score": round(h.score, 5), "lexical_rank": h.lexical_rank, "dense_rank": h.dense_rank,
            "snippet": h.chunk.text[:240],
        } for h in get_kb().search(q, top_k, set(tag) if tag else None)]

    @app.post("/v1/ask", response_model=AskOut, dependencies=[Depends(require_key)])
    def ask(body: AskIn):
        try:
            a = get_kb().ask(body.question, body.top_k, set(body.tags) if body.tags else None)
        except Exception as e:  # provider outage: fail clearly rather than hallucinate
            log.exception("generation failed")
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"answer generation failed: {type(e).__name__}") from e
        return AskOut(answer=a.answer, citations=[Citation(**c) for c in a.citations], provider=a.provider,
                      grounded=a.grounded, redactions=a.redactions, latency_ms=a.latency_ms)

    return app


app = create_app()
