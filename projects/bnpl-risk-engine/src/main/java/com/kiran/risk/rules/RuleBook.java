package com.kiran.risk.rules;

import com.kiran.risk.config.RiskPolicy;
import com.kiran.risk.model.Models.BureauReport;
import com.kiran.risk.model.Models.LoanApplication;
import com.kiran.risk.model.Models.RuleTrace;
import com.kiran.risk.model.ReasonCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Underwriting rules. Every rule always runs and records a trace entry, so a decision can be explained and
 * audited after the fact. A failed rule becomes a candidate adverse action reason.
 */
public final class RuleBook {

    /** Failures that can never be cured by pulling a different bureau. */
    public static final Set<ReasonCode> HARD_STOPS = EnumSet.of(
            ReasonCode.FRAUD_ALERT_ON_FILE, ReasonCode.SERIOUS_DELINQUENCY, ReasonCode.STATE_NOT_SUPPORTED);

    private RuleBook() {
    }

    /**
     * @param scoreFloor      minimum acceptable score for this bureau
     * @param requireHistory  whether to enforce the min-tradeline rule (relaxed for alternate-data bureaus)
     */
    public static List<RuleTrace> evaluate(LoanApplication app, BureauReport r, RiskPolicy p,
                                           int scoreFloor, boolean requireHistory) {
        List<RuleTrace> t = new ArrayList<>();

        boolean stateOk = app.state() == null || !p.unsupportedStates().contains(app.state());
        t.add(trace("state-eligibility", stateOk, ReasonCode.STATE_NOT_SUPPORTED, "state=" + app.state()));

        t.add(trace("fraud-alert", !r.fraudAlert(), ReasonCode.FRAUD_ALERT_ON_FILE, "fraudAlert=" + r.fraudAlert()));

        t.add(trace("delinquency", r.delinquencies24m() <= p.maxDelinquencies24m(), ReasonCode.SERIOUS_DELINQUENCY,
                r.delinquencies24m() + " delinquencies in 24m (max " + p.maxDelinquencies24m() + ")"));

        if (requireHistory) {
            t.add(trace("credit-history", !r.thinFile(p.minTradelines()), ReasonCode.INSUFFICIENT_CREDIT_HISTORY,
                    r.tradelines() + " tradelines, score=" + r.score()));
        }

        boolean scoreOk = r.score() != null && r.score() >= scoreFloor;
        t.add(trace("min-score", scoreOk,
                r.score() == null ? ReasonCode.INSUFFICIENT_CREDIT_HISTORY : ReasonCode.SCORE_BELOW_THRESHOLD,
                "score=" + r.score() + " floor=" + scoreFloor));

        t.add(trace("inquiries", r.inquiries6m() <= p.maxInquiries6m(), ReasonCode.TOO_MANY_INQUIRIES,
                r.inquiries6m() + " inquiries in 6m (max " + p.maxInquiries6m() + ")"));

        BigDecimal dti = debtToIncome(app, r);
        t.add(trace("debt-to-income", dti.compareTo(p.maxDti()) <= 0, ReasonCode.DEBT_TO_INCOME_TOO_HIGH,
                "dti=" + dti + " max=" + p.maxDti()));
        return t;
    }

    public static BigDecimal debtToIncome(LoanApplication app, BureauReport r) {
        if (app.monthlyIncome().signum() <= 0) {
            return BigDecimal.valueOf(99);
        }
        return r.monthlyDebt().add(app.monthlyPayment()).divide(app.monthlyIncome(), 3, RoundingMode.HALF_UP);
    }

    public static boolean allPassed(List<RuleTrace> traces) {
        return traces.stream().allMatch(RuleTrace::passed);
    }

    public static boolean hasHardStop(List<RuleTrace> traces) {
        return traces.stream().anyMatch(x -> !x.passed() && HARD_STOPS.contains(x.reason()));
    }

    private static RuleTrace trace(String name, boolean passed, ReasonCode reason, String detail) {
        return new RuleTrace(name, passed, passed ? null : reason, detail);
    }
}
