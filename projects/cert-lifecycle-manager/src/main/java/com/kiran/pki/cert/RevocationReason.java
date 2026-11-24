package com.kiran.pki.cert;

import org.bouncycastle.asn1.x509.CRLReason;

/** RFC 5280 revocation reasons, mapped to their CRL reason codes. */
public enum RevocationReason {
    UNSPECIFIED(CRLReason.unspecified),
    KEY_COMPROMISE(CRLReason.keyCompromise),
    CA_COMPROMISE(CRLReason.cACompromise),
    AFFILIATION_CHANGED(CRLReason.affiliationChanged),
    SUPERSEDED(CRLReason.superseded),
    CESSATION_OF_OPERATION(CRLReason.cessationOfOperation);

    private final int code;

    RevocationReason(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
