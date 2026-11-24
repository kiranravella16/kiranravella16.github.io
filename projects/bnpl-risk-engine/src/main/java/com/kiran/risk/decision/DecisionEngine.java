package com.kiran.risk.decision;

import com.kiran.risk.bureau.BureauGateway;
import com.kiran.risk.config.RiskPolicy;
import com.kiran.risk.config.RiskPolicy.Alternate;
import com.kiran.risk.config.RiskPolicy.TierRule;
import com.kiran.risk.model.Models.AdverseActionNotice;
import com.kiran.risk.model.Models.Bureau;
import com.kiran.risk.model.Models.BureauReport;
import com.kiran.risk.model.Models.Decision;
import com.kiran.risk.model.Models.LoanApplication;
import com.kiran.risk.model.Models.Outcome;
import com.kiran.risk.model.Models.Product;
import com.kiran.risk.model.Models.RiskTier;
import com.kiran.risk.model.Models.RuleTrace;
import com.kiran.risk.model.ReasonCode;
import com.kiran.risk.rules.RuleBook;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure decisioning logic (no Spring), so every path is unit-testable:
 * <ol>
 *     <li>Pull the primary bureau and run the rule book.</li>
 *     <li>If the applicant is thin-file at the primary, the product is eligible and no hard stop fired,
 *         run the <b>second look</b>: try each configured alternate bureau / package in order until one
 *         returns a scorable report, then re-run the rules against it.</li>
 *     <li>Price the approval by risk tier, counter-offer when the request exceeds the tier line, and build an
 *         adverse action notice for every decline or counter offer.</li>
 * </ol>
 */
public class DecisionEngine {

    private static final int MAX_NOTICE_REASONS = 4;

    private final RiskPolicy policy;

    public DecisionEngine(RiskPolicy policy) {
        this.policy = policy;
    }

    public Decision decide(LoanApplication app, BureauGateway gateway, boolean simulated) {
        BureauReport primary = gateway.pull(app, policy.primaryBureau(), "primary", policy.primaryPackage());
        List<RuleTrace> traces = new ArrayList<>(RuleBook.evaluate(app, primary, policy, policy.minScore(), true));

        BureauReport used = primary;
        boolean secondLook = false;

        if (!RuleBook.allPassed(traces) && secondLookEligible(app, primary, traces)) {
            for (Alternate alt : policy.secondLook().alternates()) {
                BureauReport report = gateway.pull(app, alt.bureau(), alt.vendorId(), alt.packageName());
                traces.add(new RuleTrace("second-look:" + alt.bureau(), report.score() != null, null,
                        report.score() == null ? "no-hit at " + alt.packageName()
                                : "hit at " + alt.packageName() + " score=" + report.score()));
                if (report.score() != null) {
                    used = report;
                    secondLook = true;
                    // alternate data is evaluated on its own floor and without the tradeline-depth rule
                    List<RuleTrace> altTraces = RuleBook.evaluate(app, report, policy,
                            policy.secondLook().minScore(), false);
                    traces.addAll(prefix(altTraces, alt.bureau()));
                    return finish(app, used, altTraces, traces, true, simulated);
                }
            }
        }
        return finish(app, used, traces, traces, secondLook, simulated);
    }

    private boolean secondLookEligible(LoanApplication app, BureauReport primary, List<RuleTrace> traces) {
        RiskPolicy.SecondLook sl = policy.secondLook();
        return sl != null && sl.enabled()
                && sl.products().contains(app.product())
                && primary.thinFile(policy.minTradelines())
                && !RuleBook.hasHardStop(traces);
    }

    /**
     * @param deciding the traces that decide the outcome (primary, or the alternate bureau's after a hit)
     * @param all      the full audit trail returned to the caller
     */
    private Decision finish(LoanApplication app, BureauReport used, List<RuleTrace> deciding, List<RuleTrace> all,
                            boolean secondLook, boolean simulated) {
        String id = UUID.randomUUID().toString();
        if (!RuleBook.allPassed(deciding)) {
            List<ReasonCode> reasons = deciding.stream().filter(t -> !t.passed()).map(RuleTrace::reason)
                    .filter(Objects::nonNull).distinct().sorted().toList();
            return new Decision(id, app.applicationId(), Outcome.DECLINED, null, null, null, used.bureau(),
                    secondLook, simulated, all, notice(reasons, used), Instant.now());
        }

        TierRule tier = tierFor(used.score());
        var apr = app.product() == Product.PAY_IN_4 ? tier.payIn4Apr() : tier.payMonthlyApr();
        if (app.amount().compareTo(tier.maxAmount()) > 0) {
            return new Decision(id, app.applicationId(), Outcome.COUNTER_OFFER, RiskTier.valueOf(tier.tier()),
                    tier.maxAmount(), apr, used.bureau(), secondLook, simulated, all,
                    notice(List.of(ReasonCode.AMOUNT_EXCEEDS_CAPACITY), used), Instant.now());
        }
        return new Decision(id, app.applicationId(), Outcome.APPROVED, RiskTier.valueOf(tier.tier()), app.amount(),
                apr, used.bureau(), secondLook, simulated, all, null, Instant.now());
    }

    private TierRule tierFor(int score) {
        List<TierRule> sorted = policy.tiers().stream()
                .sorted(Comparator.comparingInt(TierRule::minScore).reversed()).toList();
        return sorted.stream().filter(t -> score >= t.minScore()).findFirst().orElse(sorted.get(sorted.size() - 1));
    }

    private AdverseActionNotice notice(List<ReasonCode> reasons, BureauReport used) {
        Bureau b = used.bureau();
        return new AdverseActionNotice(
                reasons.stream().limit(MAX_NOTICE_REASONS).map(r -> r.consumerText).toList(),
                b.displayName, b.address, b.phone,
                used.score(), "300-850",
                "Our decision was based in whole or in part on information obtained in a report from the consumer "
                        + "reporting agency listed above. The reporting agency did not make this decision and cannot "
                        + "explain why it was made. You have a right to a free copy of your report from the agency "
                        + "within 60 days, and to dispute the accuracy or completeness of any information in it.");
    }

    private static List<RuleTrace> prefix(List<RuleTrace> traces, Bureau b) {
        return traces.stream()
                .map(t -> new RuleTrace(b.name().toLowerCase() + ":" + t.rule(), t.passed(), t.reason(), t.detail()))
                .toList();
    }
}
