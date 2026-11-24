# PII Vault

An **envelope-encrypted PII store** with **tiered, approval-based decryption** and a **tamper-evident audit log**. Built with Java 21, Spring Boot 3, Spring Security (OAuth2 resource server / JWT) and JPA.

Sensitive fields (SSN, date of birth, email, phone) are never stored in plaintext. Reading any of them needs a justified request and an approval from someone senior enough for the most sensitive field requested. Every step is recorded in a hash-chained audit log.

## Security model

| Layer | Mechanism |
|---|---|
| **Field encryption** (layer 1) | Each record gets its own random 256-bit **data key (DEK)**. Each field is encrypted with **AES-256-GCM**, using `recordId:field` as associated data, so a ciphertext copied into another row or column won't decrypt |
| **Key wrapping** (layer 2) | The DEK is wrapped by a **versioned KEK**. Only the wrapped DEK is stored. **Rotation re-wraps DEKs** without re-encrypting any field |
| **Search without decryption** | A keyed **HMAC-SHA256 blind index** on the normalized SSN supports exact-match lookup |
| **Masking by default** | Reads return `***-**-6789`, `j***@example.com`, `(***) ***-0199` from hints stored at write time. The default path never decrypts |
| **Tiered approval** | Data classification sets the approver: **SSN → DIRECTOR**; DOB, email, phone → **MANAGER** (a director can approve either) |
| **Segregation of duties** | Requesters can't approve their own requests, even if they hold an approver role |
| **Least privilege grants** | Each grant covers only the fields requested, only the requester can redeem it, it works once, and it expires (15 min TTL) |
| **Audit** | Append-only events, each hash covering the previous one. `GET /api/admin/audit/verify` finds the first altered row. Denied attempts are logged too |
| **AuthN/Z** | Stateless JWT. A `roles` claim maps to `ROLE_INGEST / ANALYST / MANAGER / DIRECTOR / ADMIN`, enforced with `@PreAuthorize` |

```
                 ┌─────────── PiiRecord row ───────────┐
 plaintext ──►   │ ssnCipher   = AES-GCM(DEK, "123…",  aad=id:SSN)
                 │ emailCipher = AES-GCM(DEK, "jane…", aad=id:EMAIL)
                 │ ssnIndex    = HMAC(indexKey, "123456789")
                 │ wrappedDek  = AES-GCM(KEK_v2, DEK,  aad=dek:id:v2)
                 │ kekVersion  = 2      ssnLast4 = 6789
                 └─────────────────────────────────────┘
```

## Workflow

```
ANALYST  ── POST /api/access-requests {recordId, fields:[SSN,EMAIL], justification} ──► PENDING (requires DIRECTOR)
DIRECTOR ── POST /api/access-requests/{id}/approve ─────────────────────────────────► APPROVED (expires in 15m)
ANALYST  ── POST /api/access-requests/{id}/reveal ──────────────────────────────────► {SSN, EMAIL} plaintext → USED
```

## Run it

```bash
mvn spring-boot:run      # H2 in-memory, dev tokens enabled, port 8082
```

```bash
tok() { curl -s -X POST localhost:8082/dev/token -H 'Content-Type: application/json' \
  -d "{\"subject\":\"$1\",\"roles\":[\"$2\"]}" | jq -r .access_token; }
INGEST=$(tok ingest-svc INGEST); ALICE=$(tok alice ANALYST); DANA=$(tok dana DIRECTOR); ROOT=$(tok root ADMIN)

# store
ID=$(curl -s -X POST localhost:8082/api/records -H "Authorization: Bearer $INGEST" -H 'Content-Type: application/json' \
  -d '{"customerRef":"cust-1","ssn":"123-45-6789","email":"jane.doe@example.com"}' | tee /dev/stderr | jq -r .id)

# request → approve → reveal
REQ=$(curl -s -X POST localhost:8082/api/access-requests -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d "{\"recordId\":\"$ID\",\"fields\":[\"SSN\"],\"justification\":\"Fraud case FR-2231\"}" | jq -r .id)
curl -s -X POST localhost:8082/api/access-requests/$REQ/approve -H "Authorization: Bearer $DANA" | jq .status
curl -s -X POST localhost:8082/api/access-requests/$REQ/reveal  -H "Authorization: Bearer $ALICE" | jq

# audit
curl -s localhost:8082/api/admin/audit/verify -H "Authorization: Bearer $ROOT" | jq
```

### Rotating the KEK

1. Add the new key version under `pii.keys.kek` (e.g. `PII_KEK_V3`) and set `PII_ACTIVE_KEK=3`. New records wrap under v3.
2. `POST /api/admin/kek/rewrap` re-wraps every older DEK under v3. Field ciphertext isn't touched.
3. Once nothing references the old version, remove it.

## Tests

```bash
mvn test
```

- `ApprovalWorkflowTest`: the director requirement for SSN, the manager tier for other fields, self-approval blocking, single-use and requester-bound grants, rejection, role enforcement and 401s, blind-index search with a check that no plaintext reaches the database, and tamper detection in the audit chain.
- `KeyRotationAndTokenTest`: migrating a legacy v1 row to v2 (data still readable afterwards, rerun is a no-op), plus real HS256 tokens validated end to end.

## Production notes

- The demo keys in `application.yml` are placeholders. Load KEKs from **Cloud KMS / an HSM** (or have KMS do the wrap/unwrap) and the index and JWT keys from a secrets manager such as **Akeyless / Vault**.
- Set `PII_DEV_TOKENS=false` and point `spring.security.oauth2.resourceserver.jwt.issuer-uri` at your IdP.
- Ship audit events to a WORM store or SIEM (Splunk) as well as the database.
