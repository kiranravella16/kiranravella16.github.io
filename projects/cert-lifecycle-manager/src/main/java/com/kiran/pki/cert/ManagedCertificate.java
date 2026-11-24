package com.kiran.pki.cert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "managed_certificate")
public class ManagedCertificate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String serialHex;

    @Column(nullable = false)
    private String commonName;

    /** Comma-separated DNS subject alternative names. */
    @Column(length = 2000)
    private String dnsNames;

    @Column(nullable = false)
    private Instant notBefore;

    @Column(nullable = false)
    private Instant notAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CertificateStatus status;

    private boolean autoRenew;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private RevocationReason revocationReason;

    private Instant revokedAt;

    /** Id of the certificate this one replaced during renewal, if any. */
    private Long renewedFromId;

    /** Owning team / application, used for alert routing. */
    private String owner;

    @Column(nullable = false, length = 8000)
    private String pem;

    protected ManagedCertificate() {
    }

    public ManagedCertificate(String serialHex, String commonName, String dnsNames, Instant notBefore,
                              Instant notAfter, boolean autoRenew, String owner, String pem) {
        this.serialHex = serialHex;
        this.commonName = commonName;
        this.dnsNames = dnsNames;
        this.notBefore = notBefore;
        this.notAfter = notAfter;
        this.autoRenew = autoRenew;
        this.owner = owner;
        this.pem = pem;
        this.status = CertificateStatus.ACTIVE;
    }

    public void revoke(RevocationReason reason, Instant at) {
        if (status == CertificateStatus.REVOKED) {
            throw new IllegalStateException("Certificate " + serialHex + " is already revoked");
        }
        this.status = CertificateStatus.REVOKED;
        this.revocationReason = reason;
        this.revokedAt = at;
    }

    public void supersede() {
        this.status = CertificateStatus.SUPERSEDED;
    }

    public void expire() {
        this.status = CertificateStatus.EXPIRED;
    }

    public void setRenewedFromId(Long renewedFromId) {
        this.renewedFromId = renewedFromId;
    }

    public Long getId() { return id; }
    public String getSerialHex() { return serialHex; }
    public String getCommonName() { return commonName; }
    public String getDnsNames() { return dnsNames; }
    public Instant getNotBefore() { return notBefore; }
    public Instant getNotAfter() { return notAfter; }
    public CertificateStatus getStatus() { return status; }
    public boolean isAutoRenew() { return autoRenew; }
    public RevocationReason getRevocationReason() { return revocationReason; }
    public Instant getRevokedAt() { return revokedAt; }
    public Long getRenewedFromId() { return renewedFromId; }
    public String getOwner() { return owner; }
    public String getPem() { return pem; }
}
