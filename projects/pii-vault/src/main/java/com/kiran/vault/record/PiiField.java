package com.kiran.vault.record;

/**
 * Data classification per field. The classification decides who must approve a decryption request:
 * RESTRICTED (SSN) needs a director; CONFIDENTIAL needs a manager (or a director).
 */
public enum PiiField {
    SSN(Sensitivity.RESTRICTED),
    DATE_OF_BIRTH(Sensitivity.CONFIDENTIAL),
    EMAIL(Sensitivity.CONFIDENTIAL),
    PHONE(Sensitivity.CONFIDENTIAL);

    public final Sensitivity sensitivity;

    PiiField(Sensitivity sensitivity) {
        this.sensitivity = sensitivity;
    }

    public enum Sensitivity {
        CONFIDENTIAL("MANAGER"),
        RESTRICTED("DIRECTOR");

        /** Minimum approver role, without the ROLE_ prefix. */
        public final String approverRole;

        Sensitivity(String approverRole) {
            this.approverRole = approverRole;
        }
    }
}
