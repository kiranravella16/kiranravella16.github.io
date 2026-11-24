package com.kiran.pki.cert;

public enum CertificateStatus {
    ACTIVE,
    /** Replaced by a renewed certificate; still cryptographically valid until notAfter. */
    SUPERSEDED,
    REVOKED,
    EXPIRED
}
