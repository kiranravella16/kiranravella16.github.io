package com.kiran.risk.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kiran.risk.model.Models.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka publishing is opt-in ({@code risk.events.kafka-enabled=true}) so the service also runs standalone.
 * Events are keyed by applicationId so every event for one application lands on the same partition, in order.
 */
@Configuration
public class EventsConfig {

    private static final Logger log = LoggerFactory.getLogger(EventsConfig.class);

    @Bean
    @ConditionalOnProperty(name = "risk.events.kafka-enabled", havingValue = "true")
    DecisionPublisher kafkaPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                                     @Value("${risk.events.topic:risk.decisions.v1}") String topic) {
        return decision -> {
            try {
                kafka.send(topic, decision.applicationId(), mapper.writeValueAsString(redact(decision)));
            } catch (Exception e) {
                // decisions must not fail because the event bus is down; alert on this log / metric instead
                log.error("Failed to publish decision {} to {}", decision.decisionId(), topic, e);
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "risk.events.kafka-enabled", havingValue = "false", matchIfMissing = true)
    DecisionPublisher loggingPublisher() {
        return d -> log.info("decision applicationId={} outcome={} tier={} bureau={} secondLook={} simulated={}",
                d.applicationId(), d.outcome(), d.tier(), d.bureauUsed(), d.secondLookApplied(), d.simulated());
    }

    /** Strip the detailed rule trace from the event; consumers fetch it from the API if authorized. */
    private static Decision redact(Decision d) {
        return new Decision(d.decisionId(), d.applicationId(), d.outcome(), d.tier(), d.approvedAmount(), d.apr(),
                d.bureauUsed(), d.secondLookApplied(), d.simulated(), java.util.List.of(), d.adverseAction(),
                d.decidedAt());
    }
}
