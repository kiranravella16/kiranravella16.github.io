package com.kiran.vault.record;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A customer's PII as stored at rest: ciphertext only, plus non-sensitive masking hints and a blind index.
 * Nothing in this row can be read without the DEK, and the DEK can't be read without the KEK.
 */
@Entity
@Table(name = "pii_record")
public class PiiRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String customerRef;

    @Column(nullable = false, length = 256)
    private String wrappedDek;

    private int kekVersion;

    @Column(length = 512)
    private String ssnCipher;
    @Column(length = 64)
    private String ssnIndex;
    @Column(length = 4)
    private String ssnLast4;

    @Column(length = 512)
    private String dobCipher;

    @Column(length = 1024)
    private String emailCipher;
    private String emailMasked;

    @Column(length = 512)
    private String phoneCipher;
    @Column(length = 4)
    private String phoneLast4;

    private Instant createdAt;

    protected PiiRecord() {
    }

    public PiiRecord(String id, String customerRef) {
        this.id = id;
        this.customerRef = customerRef;
        this.createdAt = Instant.now();
    }

    public String cipherFor(PiiField f) {
        return switch (f) {
            case SSN -> ssnCipher;
            case DATE_OF_BIRTH -> dobCipher;
            case EMAIL -> emailCipher;
            case PHONE -> phoneCipher;
        };
    }

    public void setWrappedDek(String wrappedDek, int kekVersion) {
        this.wrappedDek = wrappedDek;
        this.kekVersion = kekVersion;
    }

    public void setSsn(String cipher, String index, String last4) {
        this.ssnCipher = cipher;
        this.ssnIndex = index;
        this.ssnLast4 = last4;
    }

    public void setDob(String cipher) {
        this.dobCipher = cipher;
    }

    public void setEmail(String cipher, String masked) {
        this.emailCipher = cipher;
        this.emailMasked = masked;
    }

    public void setPhone(String cipher, String last4) {
        this.phoneCipher = cipher;
        this.phoneLast4 = last4;
    }

    public String getId() { return id; }
    public String getCustomerRef() { return customerRef; }
    public String getWrappedDek() { return wrappedDek; }
    public int getKekVersion() { return kekVersion; }
    public String getSsnIndex() { return ssnIndex; }
    public String getSsnLast4() { return ssnLast4; }
    public String getEmailMasked() { return emailMasked; }
    public String getPhoneLast4() { return phoneLast4; }
    public boolean hasDob() { return dobCipher != null; }
    public Instant getCreatedAt() { return createdAt; }
}
