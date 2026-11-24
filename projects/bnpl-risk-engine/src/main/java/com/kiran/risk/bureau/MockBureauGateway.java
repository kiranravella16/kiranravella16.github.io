package com.kiran.risk.bureau;

import com.kiran.risk.model.Models.Bureau;
import com.kiran.risk.model.Models.BureauReport;
import com.kiran.risk.model.Models.LoanApplication;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Deterministic stand-in for live bureau integrations so the service runs with no vendor credentials.
 * <ul>
 *     <li>{@code thin-*} customers come back no-hit / thin at the primary bureau but scorable at alternates</li>
 *     <li>{@code fraud-*} customers carry a fraud alert</li>
 *     <li>everyone else gets a stable pseudo-random profile derived from their id</li>
 * </ul>
 */
@Component
public class MockBureauGateway implements BureauGateway {

    @Override
    public BureauReport pull(LoanApplication app, Bureau bureau, String vendorId, String packageName) {
        int h = Math.abs(Objects.hash(app.customerId(), bureau.name()));
        boolean primary = bureau == Bureau.EXPERIAN;
        String id = app.customerId();

        if (id.startsWith("thin-") && primary) {
            return new BureauReport(bureau, vendorId, packageName, null, 1, 0, 0, BigDecimal.ZERO, false);
        }
        int score = id.startsWith("thin-") ? 650 + h % 120 : 560 + h % 260;
        int tradelines = id.startsWith("thin-") ? 0 : 3 + h % 12;
        int delinquencies = score < 600 ? 2 : 0;
        BigDecimal debt = app.monthlyIncome().multiply(BigDecimal.valueOf(h % 30))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new BureauReport(bureau, vendorId, packageName, score, tradelines, delinquencies, h % 5, debt,
                id.startsWith("fraud-"));
    }
}
