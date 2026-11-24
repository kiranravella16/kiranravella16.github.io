package com.kiran.risk.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Domain types for the decisioning flow. Kept together because they are small immutable records.
 */
public final class Models {

    private Models() {
    }

    public enum Product {
        /** Short-term, interest-free split into 4 payments. */
        PAY_IN_4,
        /** Installment loan, 6–24 months, interest-bearing. */
        PAY_MONTHLY
    }

    public enum Bureau {
        EXPERIAN("Experian", "P.O. Box 2002, Allen, TX 75013", "1-888-397-3742"),
        EQUIFAX("Equifax", "P.O. Box 740241, Atlanta, GA 30374", "1-800-685-1111"),
        TRANSUNION("TransUnion", "P.O. Box 1000, Chester, PA 19016", "1-800-888-4213"),
        DATAX("DataX (Equifax alternative data)", "325 E Warm Springs Rd, Las Vegas, NV 89119", "1-800-295-4790");

        public final String displayName;
        public final String address;
        public final String phone;

        Bureau(String displayName, String address, String phone) {
            this.displayName = displayName;
            this.address = address;
            this.phone = phone;
        }
    }

    public enum Outcome { APPROVED, COUNTER_OFFER, DECLINED }

    public enum RiskTier { A, B, C }

    public record LoanApplication(
            String applicationId,
            String customerId,
            Product product,
            BigDecimal amount,
            int termMonths,
            BigDecimal monthlyIncome,
            String state) {

        /** Approximate new monthly obligation, used in the debt-to-income check. */
        public BigDecimal monthlyPayment() {
            if (product == Product.PAY_IN_4) {
                // four bi-weekly installments ⇒ about half the purchase lands in any one month
                return amount.divide(BigDecimal.TWO, 2, java.math.RoundingMode.HALF_UP);
            }
            return amount.divide(BigDecimal.valueOf(Math.max(termMonths, 1)), 2, java.math.RoundingMode.HALF_UP);
        }
    }

    /**
     * Normalized bureau response. {@code score == null} means no-hit / unscorable.
     *
     * @param packageName the product package pulled, e.g. "Insight Score" for Equifax alternative data
     */
    public record BureauReport(
            Bureau bureau,
            String vendorId,
            String packageName,
            Integer score,
            int tradelines,
            int delinquencies24m,
            int inquiries6m,
            BigDecimal monthlyDebt,
            boolean fraudAlert) {

        public boolean thinFile(int minTradelines) {
            return score == null || tradelines < minTradelines;
        }
    }

    public record RuleTrace(String rule, boolean passed, ReasonCode reason, String detail) {
    }

    public record AdverseActionNotice(
            List<String> principalReasons,
            String bureauName,
            String bureauAddress,
            String bureauPhone,
            Integer scoreUsed,
            String scoreRange,
            String disclosure) {
    }

    public record Decision(
            String decisionId,
            String applicationId,
            Outcome outcome,
            RiskTier tier,
            BigDecimal approvedAmount,
            BigDecimal apr,
            Bureau bureauUsed,
            boolean secondLookApplied,
            boolean simulated,
            List<RuleTrace> trace,
            AdverseActionNotice adverseAction,
            Instant decidedAt) {
    }
}
