# Cert Lifecycle Manager

A self-hosted **internal Certificate Authority and certificate lifecycle service**: issue, renew, revoke and validate X.509 certificates for service-to-service TLS / mTLS, publish a signed CRL, and watch everything from a React dashboard with Prometheus metrics.

It's a compact take on the cert-manager + private CA setups used in enterprise microservice platforms, built with **Java 21, Spring Boot 3, and BouncyCastle**.

![stack](https://img.shields.io/badge/Java-21-orange) ![stack](https://img.shields.io/badge/Spring%20Boot-3.3-green) ![stack](https://img.shields.io/badge/BouncyCastle-1.78-blue)

## Features

| Capability | How it works |
|---|---|
| **Root CA** | EC P-256 root generated on first boot, persisted to a PKCS#12 keystore (password injected from env / secrets manager) |
| **Issue** | Server-generated key pair (private key returned once, never stored) **or** sign a client CSR after verifying proof-of-possession |
| **Leaf profile** | `serverAuth` + `clientAuth` EKU, SANs, SKI/AKI, `basicConstraints=CA:false`, so the certs work for mTLS |
| **Renew** | Re-signs the existing public key with a fresh validity window; old cert marked `SUPERSEDED` and linked via `renewedFromId` |
| **Revoke** | RFC 5280 reason codes; revoked serials go out in a signed **CRL** (`/api/ca/crl`, DER, or `/api/ca/crl.pem`) |
| **Validate** | Checks chain-to-root signature, validity window, and revocation status for any presented PEM |
| **Auto-renew loop** | Scheduled reconciler expires lapsed certs and renews opted-in certs inside the renewal window (default 30 days), one transaction per cert |
| **Observability** | `pki_certificates_expiring_soon`, `pki_certificates_active`, `pki_certificates_renewed_total`, `pki_certificate_renewal_failures_total` on `/actuator/prometheus` |
| **Dashboard** | React UI at `/`: status tiles, inventory with expiry countdown, issue / renew / revoke actions |

## Architecture

```
            ┌──────────────── React dashboard (/) ────────────────┐
            │                                                      │
 client ──► │  CertificateController  (/api/certificates, /api/ca) │
            └──────────────┬───────────────────────────────────────┘
                           │
                 CertificateService ──────► CertificateRepository (JPA → H2 / PostgreSQL)
                    │         ▲
                    ▼         │
          CertificateAuthority (BouncyCastle)        RenewalScheduler (@Scheduled)
          root key in PKCS#12 keystore               expire → renew → metrics
```

## Run it

```bash
# Local, H2 file database
mvn spring-boot:run
open http://localhost:8080

# With PostgreSQL + Prometheus
docker compose up --build
```

## API walkthrough

```bash
# Issue with a server-generated key
curl -s -X POST localhost:8080/api/certificates -H 'Content-Type: application/json' -d '{
  "commonName": "payments.internal",
  "dnsNames": ["payments.internal", "payments.svc.cluster.local"],
  "validityDays": 90, "autoRenew": true, "owner": "payments-team"
}' | jq

# Sign your own CSR (the key never leaves your machine)
openssl req -new -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
  -keyout orders.key -subj "/CN=orders.internal" -out orders.csr
jq -n --rawfile csr orders.csr '{csrPem:$csr, dnsNames:["orders.internal"]}' |
  curl -s -X POST localhost:8080/api/certificates/csr -H 'Content-Type: application/json' -d @- | jq -r .pem > orders.crt

# Verify with OpenSSL against the root
curl -s localhost:8080/api/ca/certificate > root.pem
openssl verify -CAfile root.pem orders.crt

# Renew / revoke / validate
curl -s -X POST localhost:8080/api/certificates/1/renew | jq
curl -s -X POST localhost:8080/api/certificates/1/revoke -H 'Content-Type: application/json' -d '{"reason":"KEY_COMPROMISE"}'
jq -n --rawfile pem orders.crt '{pem:$pem}' | curl -s -X POST localhost:8080/api/certificates/validate -H 'Content-Type: application/json' -d @- | jq

# CRL
curl -s localhost:8080/api/ca/crl.pem | openssl crl -noout -text
```

## Tests

```bash
mvn test
```

The tests cover chain verification, SAN contents, CSR proof-of-possession, renewal lineage and key reuse, CRL contents and signature, rejection of certificates from a foreign CA, the auto-renew reconciler, and API validation and error mapping.

## Production notes

- The CA key sits in a PKCS#12 file for portability. In production, move signing to **Cloud KMS / HSM** behind the `CertificateAuthority` class and inject `CA_KEYSTORE_PASSWORD` from **Akeyless / Vault / GCP Secret Manager**.
- Run a single writer replica (see `k8s/deployment.yaml`), or put the CA behind KMS so you can scale out.
- Put the API behind OAuth2 / mTLS and an approval workflow before issuing for production names.

## Project layout

```
src/main/java/com/kiran/pki
├── ca/        CertificateAuthority (BouncyCastle), PEM helpers
├── cert/      entity, repository, service, renewal scheduler
├── config/    typed PkiProperties
└── web/       REST controller, RFC 7807 error mapping
src/main/resources/static/index.html   React dashboard
k8s/                                   Deployment + Service
.github/workflows/ci.yml               build, test, docker build
```
