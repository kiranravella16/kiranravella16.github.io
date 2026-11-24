package com.kiran.risk.bureau;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kiran.risk.model.Models.Bureau;
import com.kiran.risk.model.Models.BureauReport;
import com.kiran.risk.model.Models.LoanApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * "Simulate mode": replays recorded bureau responses from {@code classpath:simulations/*.json} so true-case
 * scenarios (thin file → alternate bureau approve, etc.) can be exercised end-to-end without live pulls.
 */
@Component
public class SimulatedBureauGateway implements BureauGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedBureauGateway.class);

    public record Scenario(String customerId, String description, Map<Bureau, BureauReport> reports) {
    }

    private final Map<String, Scenario> scenarios = new HashMap<>();

    public SimulatedBureauGateway(ObjectMapper mapper) throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:simulations/*.json");
        for (Resource file : files) {
            try (InputStream in = file.getInputStream()) {
                Scenario s = mapper.readValue(in, Scenario.class);
                scenarios.put(s.customerId(), s);
            }
        }
        log.info("Loaded {} simulation scenarios: {}", scenarios.size(), scenarios.keySet());
    }

    public Map<String, Scenario> scenarios() {
        return Map.copyOf(scenarios);
    }

    @Override
    public BureauReport pull(LoanApplication app, Bureau bureau, String vendorId, String packageName) {
        Scenario s = scenarios.get(app.customerId());
        if (s == null) {
            throw new NoSuchElementException("No simulation scenario for customer " + app.customerId());
        }
        BureauReport r = s.reports().get(bureau);
        if (r == null) {
            // a bureau absent from the scenario behaves like a no-hit
            return new BureauReport(bureau, vendorId, packageName, null, 0, 0, 0, java.math.BigDecimal.ZERO, false);
        }
        return r;
    }
}
