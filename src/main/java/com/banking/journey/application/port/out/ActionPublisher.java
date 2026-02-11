package com.banking.journey.application.port.out;

import java.util.List;

import com.banking.journey.domain.entity.Action;

/**
 * Secondary (outbound) port: Action publishing abstraction.
 * <p>
 * Publishes customer actions with idempotency guarantee.
 * Implementation could publish to Kafka, SQS, direct HTTP, etc.
 * Same actionId must never result in duplicate sends.
 * </p>
 */
public interface ActionPublisher {

    /**
     * Publishes an action with idempotency guarantee.
     * Same actionId must not result in duplicate sends.
     *
     * @param action action to publish
     */
    void publish(Action action);

    /**
     * Retrieves recently published actions for dashboard display.
     *
     * @param limit maximum actions to return
     * @return recent actions ordered by creation time desc
     */
    List<Action> getRecentActions(int limit);

    /**
     * Counts total published actions.
     *
     * @return total action count
     */
    long countAll();
}
