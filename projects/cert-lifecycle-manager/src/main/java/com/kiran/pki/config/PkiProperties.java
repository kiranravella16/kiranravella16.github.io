package com.kiran.pki.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param caSubject          X.500 subject of the root CA, e.g. "CN=Kiran Internal Root CA, O=Demo"
 * @param keystorePath       PKCS#12 file where the CA key pair is persisted between restarts
 * @param keystorePassword   password for the CA keystore (inject from a secrets manager in production)
 * @param rootValidityDays   lifetime of the self-signed root
 * @param defaultValidityDays default lifetime of issued leaf certificates
 * @param renewBeforeDays    auto-renew window: certificates expiring inside it are re-issued
 * @param crlValidityHours   nextUpdate horizon for published CRLs
 */
@ConfigurationProperties(prefix = "pki")
public record PkiProperties(
        String caSubject,
        String keystorePath,
        String keystorePassword,
        int rootValidityDays,
        int defaultValidityDays,
        int renewBeforeDays,
        int crlValidityHours) {
}
