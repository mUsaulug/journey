package com.banking.journey.application.port.out;

import com.banking.journey.domain.entity.CardApplicationState;

/**
 * Secondary (outbound) port: State persistence abstraction.
 * <p>
 * Decouples domain logic from the state storage mechanism.
 * Implementation could be Redis, database, in-memory, etc.
 * </p>
 */
public interface StateStore {

    /**
     * Retrieves the current journey state for a customer.
     *
     * @param customerId customer identifier
     * @return current state, or null if customer has no active journey
     */
    CardApplicationState getState(String customerId);

    /**
     * Persists the journey state.
     *
     * @param state state to save
     */
    void saveState(CardApplicationState state);

    /**
     * Deletes state (for journey completion or expiry).
     *
     * @param customerId customer identifier
     */
    void deleteState(String customerId);
}
