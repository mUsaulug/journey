package com.banking.journey.application.port.out;

import java.util.List;

import com.banking.journey.domain.entity.CustomerEvent;

/**
 * Secondary (outbound) port: Event audit trail persistence.
 * <p>
 * Stores all customer events for compliance, debugging, and analytics.
 * Implementation must be idempotent (same eventId = no-op).
 * </p>
 */
public interface EventStore {

    /**
     * Persists an event for audit trail.
     * Must be idempotent — duplicate eventId should be silently ignored.
     *
     * @param event event to store
     */
    void save(CustomerEvent event);

    /**
     * Retrieves a customer's event history, ordered by timestamp descending.
     *
     * @param customerId customer identifier
     * @param limit      maximum events to return
     * @return events ordered by timestamp desc
     */
    List<CustomerEvent> findByCustomerId(String customerId, int limit);

    /**
     * Counts total events in the store.
     *
     * @return total event count
     */
    long countAll();

    /**
     * Counts events grouped by event type.
     *
     * @return list of type-count pairs (implementation-defined format)
     */
    List<Object[]> countByEventType();
}
