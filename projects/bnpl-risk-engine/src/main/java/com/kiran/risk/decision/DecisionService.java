package com.kiran.risk.decision;

import com.kiran.risk.bureau.MockBureauGateway;
import com.kiran.risk.bureau.SimulatedBureauGateway;
import com.kiran.risk.config.RiskPolicy;
import com.kiran.risk.events.DecisionPublisher;
import com.kiran.risk.model.Models.Decision;
import com.kiran.risk.model.Models.LoanApplication;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DecisionService {

    private final DecisionEngine engine;
    private final MockBureauGateway liveGateway;
    private final SimulatedBureauGateway simulatedGateway;
    private final DecisionPublisher publisher;
    private final MeterRegistry metrics;
    /** In-memory decision store; swap for a database / BigQuery sink in a real deployment. */
    private final Map<String, Decision> store = new ConcurrentHashMap<>();
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();

    public DecisionService(RiskPolicy policy, MockBureauGateway liveGateway, SimulatedBureauGateway simulatedGateway,
                           DecisionPublisher publisher, MeterRegistry metrics) {
        this.engine = new DecisionEngine(policy);
        this.liveGateway = liveGateway;
        this.simulatedGateway = simulatedGateway;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    /**
     * Idempotent per applicationId: a retried request returns the original decision instead of re-pulling credit
     * (a second hard pull would hurt the customer's score).
     */
    public Decision decide(LoanApplication app, boolean simulate) {
        String existing = idempotency.get(app.applicationId());
        if (existing != null) {
            return store.get(existing);
        }
        Timer.Sample sample = Timer.start(metrics);
        Decision d = engine.decide(app, simulate ? simulatedGateway : liveGateway, simulate);
        sample.stop(Timer.builder("risk_decision_latency").tag("simulated", String.valueOf(simulate)).register(metrics));
        String winner = idempotency.putIfAbsent(app.applicationId(), d.decisionId());
        if (winner != null) {
            return store.get(winner);
        }
        store.put(d.decisionId(), d);
        metrics.counter("risk_decisions_total", "outcome", d.outcome().name(),
                "second_look", String.valueOf(d.secondLookApplied()), "product", app.product().name()).increment();
        publisher.publish(d);
        return d;
    }

    public Decision get(String decisionId) {
        Decision d = store.get(decisionId);
        if (d == null) {
            throw new NoSuchElementException("Decision " + decisionId + " not found");
        }
        return d;
    }
}
