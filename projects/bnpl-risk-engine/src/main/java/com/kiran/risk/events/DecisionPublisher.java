package com.kiran.risk.events;

import com.kiran.risk.model.Models.Decision;

/** Publishes decision events for downstream consumers (servicing, notifications, analytics, model monitoring). */
public interface DecisionPublisher {

    void publish(Decision decision);
}
