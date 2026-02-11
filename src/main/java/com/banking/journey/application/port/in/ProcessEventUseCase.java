package com.banking.journey.application.port.in;

import com.banking.journey.domain.entity.CustomerEvent;

/**
 * Primary (inbound) port: Entry point for event processing.
 * <p>
 * Implements the Evam "Sense-Analyze-Act" pattern:
 * <ol>
 * <li><b>Sense</b>: Receive and audit the customer event</li>
 * <li><b>Analyze</b>: Determine next state via state machine</li>
 * <li><b>Act</b>: Execute the appropriate action (notification)</li>
 * </ol>
 * </p>
 */
public interface ProcessEventUseCase {

    /**
     * Processes a customer event through the journey orchestration engine.
     *
     * @param event the customer event to process
     * @throws IllegalStateException if event processing fails due to invalid state
     */
    void process(CustomerEvent event);
}
