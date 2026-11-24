# Projects

Five projects built around the work on my resume: PKI, fintech risk, PII security, event-driven catalog data, and GenAI / RAG.

| # | Project | Stack | Maps to |
|---|---|---|---|
| 1 | [`cert-lifecycle-manager`](cert-lifecycle-manager) | Java 21, Spring Boot 3, BouncyCastle, JPA, React, Prometheus, K8s | PKI and certificate lifecycle at Regions and Cleveland Clinic |
| 2 | [`bnpl-risk-engine`](bnpl-risk-engine) | Java 21, Spring Boot 3, Kafka, Micrometer | PayPal BNPL risk: 2nd Look, adverse action, simulate mode |
| 3 | [`pii-vault`](pii-vault) | Java 21, Spring Boot 3, Spring Security (JWT), AES-GCM, JPA | PayPal NTPP: two-layer encryption, director/manager approvals |
| 4 | [`catalog-feed-pipeline`](catalog-feed-pipeline) | TypeScript, Node.js, Kafka, GraphQL, Zod, Terraform (GKE) | Walmart US/MX/CA feed onboarding, Catalog Acceleration single item ID |
| 5 | [`rag-knowledge-api`](rag-knowledge-api) | Python, FastAPI, NumPy, Claude API | GenAI/RAG integration, production support, Release Captain |

## Highlights

**Cert Lifecycle Manager**: internal CA and certificate lifecycle service
- Built an X.509 CA on BouncyCastle that issues mTLS certificates from server-generated keys or client CSRs (with proof-of-possession), renews and revokes them, and publishes signed CRLs.
- Added a reconciliation loop that auto-renews certificates inside a 30-day window, Prometheus expiry metrics, and a React dashboard showing inventory and expiry countdowns.

**BNPL Risk Engine**: credit decisioning with an alternate-bureau second look
- Designed a rules-driven underwriting engine for Pay-in-4 and installment loans. Thin-file applicants fall through a configurable alternate-bureau chain (Equifax Insight Score → DataX → TransUnion) before a decision.
- Generated adverse action notices with severity-ordered reasons, made decisions idempotent per application, added a simulate mode that replays recorded bureau responses, and published decision events to Kafka.

**PII Vault**: encrypted PII store with tiered approvals
- Implemented two-layer envelope encryption (per-record AES-256-GCM data keys wrapped by versioned KEKs) with context-bound ciphertexts, HMAC blind-index search, and KEK rotation by re-wrapping keys only.
- Built a least-privilege decryption workflow: SSN needs director approval and other PII needs a manager, with no self-approval, single-use grants that expire, and a hash-chained audit log that detects tampering.

**Catalog Feed Pipeline**: multi-region supplier ingestion
- Built a Kafka pipeline that normalizes US, MX, and CA supplier feeds (units, currencies, languages, timezones) and gives every product one canonical ID across regions using GS1-validated GTIN-14 and UUIDv5.
- Made processing idempotent and order-safe with per-region last-writer-wins merges, routed bad rows to a dead-letter queue with field-level errors, exposed a GraphQL API, and provisioned a private GKE cluster with Terraform.

**Runbook RAG API**: on-call assistant over team runbooks
- Built hybrid retrieval (BM25 + dense vectors fused with Reciprocal Rank Fusion), plus document-level grouping and mitigation-section expansion, so answers come from the right runbook and include the fix.
- Added guardrails: PII and secret redaction before any LLM call, untrusted-context prompting, validated citations, and an offline extractive fallback. Claude is a pluggable provider.
