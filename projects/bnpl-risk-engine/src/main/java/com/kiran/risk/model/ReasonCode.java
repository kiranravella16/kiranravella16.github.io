package com.kiran.risk.model;

/**
 * Decline reason codes with the consumer-facing wording used on adverse action notices (ECOA / Reg B style).
 * Ordered roughly by severity so the notice lists the most important reasons first.
 */
public enum ReasonCode {
    FRAUD_ALERT_ON_FILE("A fraud alert or security freeze is on your credit file"),
    SERIOUS_DELINQUENCY("Serious delinquency or past-due payments reported"),
    SCORE_BELOW_THRESHOLD("Credit score does not meet our minimum requirement"),
    INSUFFICIENT_CREDIT_HISTORY("Insufficient credit history to evaluate"),
    DEBT_TO_INCOME_TOO_HIGH("Ratio of debt obligations to income is too high"),
    TOO_MANY_INQUIRIES("Number of recent credit inquiries"),
    AMOUNT_EXCEEDS_CAPACITY("Requested amount exceeds the amount we can approve"),
    STATE_NOT_SUPPORTED("This product is not available in your state");

    public final String consumerText;

    ReasonCode(String consumerText) {
        this.consumerText = consumerText;
    }
}
