package com.kiran.risk;

import com.kiran.risk.bureau.BureauGateway;
import com.kiran.risk.config.RiskPolicy;
import com.kiran.risk.decision.DecisionEngine;
import com.kiran.risk.model.Models.Bureau;
import com.kiran.risk.model.Models.BureauReport;
import com.kiran.risk.model.Models.Decision;
import com.kiran.risk.model.Models.LoanApplication;
import com.kiran.risk.model.Models.Outcome;
import com.kiran.risk.model.Models.Product;
import com.kiran.risk.model.Models.RiskTier;
import com.kiran.risk.model.ReasonCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionEngineTest {

    static final RiskPolicy POLICY = new RiskPolicy(
            Bureau.EXPERIAN, "FICO 8", 640, 3, new BigDecimal("0.45"), 1, 4, Set.of("IA"),
            List.of(new RiskPolicy.TierRule("A", 740, new BigDecimal("10000"), BigDecimal.ZERO, new BigDecimal("9.99")),
                    new RiskPolicy.TierRule("B", 680, new BigDecimal("5000"), BigDecimal.ZERO, new BigDecimal("19.99")),
                    new RiskPolicy.TierRule("C", 640, new BigDecimal("1500"), BigDecimal.ZERO, new BigDecimal("29.99"))),
            new RiskPolicy.SecondLook(true, Set.of(Product.PAY_MONTHLY), List.of(
                    new RiskPolicy.Alternate(Bureau.EQUIFAX, "EFX-ALT-01", "Insight Score"),
                    new RiskPolicy.Alternate(Bureau.DATAX, "DTX-02", "DataX Alt Credit")), 640));

    final DecisionEngine engine = new DecisionEngine(POLICY);
    final List<Bureau> pulled = new ArrayList<>();

    static BureauReport report(Bureau b, Integer score, int tradelines, int delinquencies, int debt) {
        return new BureauReport(b, "v", "p", score, tradelines, delinquencies, 0, BigDecimal.valueOf(debt), false);
    }

    BureauGateway gateway(Map<Bureau, BureauReport> reports) {
        return (app, bureau, vendor, pkg) -> {
            pulled.add(bureau);
            return reports.getOrDefault(bureau, report(bureau, null, 0, 0, 0));
        };
    }

    static LoanApplication app(Product product, String amount, String state) {
        return new LoanApplication("app-1", "cust-1", product, new BigDecimal(amount), 12, new BigDecimal("5000"), state);
    }

    @Test
    void primeApplicantApprovedTierAWithoutSecondLook() {
        Decision d = engine.decide(app(Product.PAY_MONTHLY, "2400", "CA"),
                gateway(Map.of(Bureau.EXPERIAN, report(Bureau.EXPERIAN, 781, 12, 0, 800))), false);

        assertThat(d.outcome()).isEqualTo(Outcome.APPROVED);
        assertThat(d.tier()).isEqualTo(RiskTier.A);
        assertThat(d.apr()).isEqualByComparingTo("9.99");
        assertThat(d.secondLookApplied()).isFalse();
        assertThat(pulled).containsExactly(Bureau.EXPERIAN);
        assertThat(d.adverseAction()).isNull();
    }

    @Test
    void thinFileGetsSecondLookAndIsApprovedOnAlternateBureau() {
        Decision d = engine.decide(app(Product.PAY_MONTHLY, "1200", "CA"), gateway(Map.of(
                Bureau.EXPERIAN, report(Bureau.EXPERIAN, null, 1, 0, 0),
                Bureau.EQUIFAX, report(Bureau.EQUIFAX, 702, 0, 0, 300))), false);

        assertThat(d.outcome()).isEqualTo(Outcome.APPROVED);
        assertThat(d.secondLookApplied()).isTrue();
        assertThat(d.bureauUsed()).isEqualTo(Bureau.EQUIFAX);
        assertThat(d.tier()).isEqualTo(RiskTier.B);
        assertThat(pulled).containsExactly(Bureau.EXPERIAN, Bureau.EQUIFAX); // stops at the first hit
    }

    @Test
    void secondLookFallsThroughAlternatesInConfiguredOrder() {
        Decision d = engine.decide(app(Product.PAY_MONTHLY, "1000", "CA"), gateway(Map.of(
                Bureau.EXPERIAN, report(Bureau.EXPERIAN, null, 0, 0, 0),
                Bureau.DATAX, report(Bureau.DATAX, 660, 0, 0, 100))), false);

        assertThat(pulled).containsExactly(Bureau.EXPERIAN, Bureau.EQUIFAX, Bureau.DATAX);
        assertThat(d.outcome()).isEqualTo(Outcome.APPROVED);
        assertThat(d.bureauUsed()).isEqualTo(Bureau.DATAX);
        assertThat(d.tier()).isEqualTo(RiskTier.C);
    }

    @Test
    void payIn4IsNotEligibleForSecondLook() {
        Decision d = engine.decide(app(Product.PAY_IN_4, "200", "CA"),
                gateway(Map.of(Bureau.EXPERIAN, report(Bureau.EXPERIAN, null, 1, 0, 0))), false);

        assertThat(d.outcome()).isEqualTo(Outcome.DECLINED);
        assertThat(pulled).containsExactly(Bureau.EXPERIAN);
        assertThat(d.adverseAction().principalReasons())
                .containsExactly(ReasonCode.INSUFFICIENT_CREDIT_HISTORY.consumerText);
    }

    @Test
    void hardStopSkipsSecondLookAndNoticeListsReasonsBySeverity() {
        Decision d = engine.decide(app(Product.PAY_MONTHLY, "3000", "CA"), gateway(Map.of(
                Bureau.EXPERIAN, report(Bureau.EXPERIAN, 600, 2, 3, 2600),
                Bureau.EQUIFAX, report(Bureau.EQUIFAX, 760, 0, 0, 0))), false);

        assertThat(d.outcome()).isEqualTo(Outcome.DECLINED);
        assertThat(pulled).containsExactly(Bureau.EXPERIAN);
        assertThat(d.adverseAction().principalReasons()).containsExactly(
                ReasonCode.SERIOUS_DELINQUENCY.consumerText,
                ReasonCode.SCORE_BELOW_THRESHOLD.consumerText,
                ReasonCode.INSUFFICIENT_CREDIT_HISTORY.consumerText,
                ReasonCode.DEBT_TO_INCOME_TOO_HIGH.consumerText);
        assertThat(d.adverseAction().bureauName()).isEqualTo("Experian");
    }

    @Test
    void requestAboveTierLineBecomesCounterOfferWithNotice() {
        Decision d = engine.decide(app(Product.PAY_MONTHLY, "8000", "CA"),
                gateway(Map.of(Bureau.EXPERIAN, report(Bureau.EXPERIAN, 700, 8, 0, 200))), false);

        assertThat(d.outcome()).isEqualTo(Outcome.COUNTER_OFFER);
        assertThat(d.approvedAmount()).isEqualByComparingTo("5000");
        assertThat(d.adverseAction().principalReasons())
                .containsExactly(ReasonCode.AMOUNT_EXCEEDS_CAPACITY.consumerText);
    }

    @Test
    void unsupportedStateIsDeclined() {
        Map<Bureau, BureauReport> reports = new EnumMap<>(Bureau.class);
        reports.put(Bureau.EXPERIAN, report(Bureau.EXPERIAN, 800, 10, 0, 0));
        Decision d = engine.decide(app(Product.PAY_MONTHLY, "500", "IA"), gateway(reports), false);

        assertThat(d.outcome()).isEqualTo(Outcome.DECLINED);
        assertThat(d.adverseAction().principalReasons()).containsExactly(ReasonCode.STATE_NOT_SUPPORTED.consumerText);
    }
}
