# Runbook RAG API

A **retrieval-augmented generation (RAG) service for on-call engineers**. Ask "Kafka consumer lag is growing, how do I fix it?" and get an answer from your team's runbooks, with a citation on every claim. Built with **Python, FastAPI, and NumPy**, with **Claude** as the optional generator.

It runs fully offline with no API key, using a local embedder and an extractive answer provider. Set `ANTHROPIC_API_KEY` and the same pipeline generates answers with Claude.

## Pipeline

```
 markdown runbooks ─► heading-aware chunking (section path + overlap)
                          │
                          ├─► BM25 index            (exact terms: error strings, metric names, flags)
                          └─► dense vectors          (paraphrases; pluggable Embedder)
                                     │
 question ─► hybrid search ─► Reciprocal Rank Fusion ─► group by document ─► mitigation-section expansion
                                     │
                          redact PII & secrets ─► LLM (Claude | extractive) ─► validate [n] citations ─► answer
```

| Stage | What it does and why |
|---|---|
| **Chunking** | Splits on Markdown headings, so every chunk keeps a path like `Kafka consumer lag > Mitigation`. Long sections are windowed with overlap |
| **Hybrid retrieval** | BM25 finds exact tokens such as `HikariPool` or `max.poll.interval.ms`. Dense vectors catch paraphrases. **RRF** fuses the two rankings without score calibration |
| **Document grouping** | Ranks passages by their runbook's *total* evidence, so one stray chunk from the wrong runbook can't take over the answer |
| **Small-to-big expansion** | For "how do I fix…" questions, the top runbook's Mitigation section is always included, even when the question only matched the Symptoms wording |
| **Guardrails** | Redacts SSNs, card numbers (Luhn-checked), emails, phones, AWS keys, bearer tokens / JWTs, passwords and private keys **before** anything reaches the LLM. Context is wrapped as untrusted `<passage>` data, and the system prompt says to ignore instructions inside it |
| **Grounding** | Only citations that map to a real passage are returned. `grounded=false` is returned instead of a guess when nothing relevant is found |
| **Ops** | API-key auth (constant-time compare), a 502 on provider outage, idempotent re-upload (SHA-256), and latency in every response |

## Run it

```bash
python -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt
pytest -q                                        # 22 tests
API_KEYS=demo uvicorn app.main:app --reload      # indexes ./corpus on startup
```

```bash
curl -s localhost:8000/v1/ask -H 'X-API-Key: demo' -H 'content-type: application/json' \
  -d '{"question":"Certificate expired and clients get PKIX errors. What do I do?"}' | jq
```

```json
{
  "answer": "Clients fail with `PKIX path validation failed` or `certificate has expired`. [1] Trigger a manual renewal: `POST /api/certificates/{id}/renew`. [5] Renewal re-signs the existing public key, so pods only need the new certificate file, not a new private key. [5] Roll the deployment so pods pick up the renewed secret: `kubectl rollout restart deploy/<service>`. [5]",
  "citations": [
    { "n": 1, "doc_id": "tls-certificate-expiry-md", "section": "TLS certificate expiry > Symptoms", "...": "..." },
    { "n": 5, "doc_id": "tls-certificate-expiry-md", "section": "TLS certificate expiry > Mitigation", "...": "..." }
  ],
  "provider": "extractive",
  "grounded": true,
  "redactions": {}
}
```

With Claude:

```bash
export ANTHROPIC_API_KEY=sk-ant-...   # optional ANTHROPIC_MODEL, defaults to claude-sonnet-5-5
uvicorn app.main:app
```

### API

| Method | Path | |
|---|---|---|
| `POST` | `/v1/ask` | `{question, top_k?, tags?}` → answer, citations, grounded, redactions |
| `GET` | `/v1/search?q=&top_k=&tag=` | Raw hybrid results with both rankers' positions (useful for tuning) |
| `POST` | `/v1/documents` | `{title, text, source?, tags?}`, idempotent by content |
| `GET` | `/v1/documents` | Indexed documents |
| `GET` | `/healthz` | Document and chunk counts, active provider |

Interactive docs are at `/docs`.

## Tests

- **Retrieval quality:** six questions, each expected to hit a specific runbook, including paraphrases and exact flags; tag filters; document grouping beating a misleading chunk; mitigation expansion
- **Guardrails:** every redaction type, no false positives on metrics, order numbers, or "Missing secret: recreate it"
- **Claude provider:** a mocked HTTP transport checks the prompt actually sent (redacted, passage-wrapped, untrusted-data system prompt) and the response parsing; outage → 502
- **API:** auth, validation, upload-then-ask, search diagnostics

## Extending

- **Embeddings:** implement `Embedder.embed(texts) -> np.ndarray` for a hosted model and pass it to `HybridIndex`.
- **Vector store:** swap the in-memory matrix for pgvector or a managed vector DB. The `HybridIndex` interface stays the same.
- **Ingestion:** feed postmortems from Confluence or Git through `POST /v1/documents`, or stream them in from Kafka.
