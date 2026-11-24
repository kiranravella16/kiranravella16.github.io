# TLS certificate expiry

tags: pki, security, oncall

## Symptoms

Clients fail with `PKIX path validation failed` or `certificate has expired`. The alert `CertificateExpiringSoon` fires when `pki_certificates_expiring_soon` is above zero and auto-renewal has failed.

## Diagnosis

1. Confirm the expiry date: `openssl s_client -connect payments.internal:443 -servername payments.internal </dev/null | openssl x509 -noout -dates`.
2. Check the cert-lifecycle-manager dashboard for the certificate status and owner team.
3. Look at `pki_certificate_renewal_failures_total`. A rising counter means the reconciler is trying and failing.
4. Verify the chain against the internal root with `openssl verify -CAfile root.pem service.crt`.

## Mitigation

- Trigger a manual renewal: `POST /api/certificates/{id}/renew`. Renewal re-signs the existing public key, so pods only need the new certificate file, not a new private key.
- Roll the deployment so pods pick up the renewed secret: `kubectl rollout restart deploy/<service>`.
- If the private key may be compromised, revoke with reason KEY_COMPROMISE instead of renewing, issue a new key pair, and confirm the serial appears on the CRL.

## Prevention

Every production certificate should have auto-renew enabled with a 30-day renewal window, and an owner team for alert routing.
