package com.banking.journey.domain.valueobject;

/**
 * Classification of customer events in the journey orchestration pipeline.
 * <p>
 * Each event type maps to a specific customer action and triggers
 * corresponding state machine transitions.
 * </p>
 */
public enum EventType {

    /** Customer submits a new credit card application */
    CARD_APPLY,

    /** Customer uploads a required document */
    DOCUMENT_UPLOAD,

    /** Application receives approval from the review process */
    APPROVAL,

    /** Application receives rejection from the review process */
    REJECTION;

    /**
     * Determines if this event type requires immediate system action.
     * <p>
     * CARD_APPLY and APPROVAL/REJECTION events require immediate
     * processing to maintain <20ms latency SLA.
     * </p>
     *
     * @return true if the event should be prioritized
     */
    public boolean requiresImmediateAction() {
        return this == CARD_APPLY || this == APPROVAL || this == REJECTION;
    }

    /**
     * Checks if this event initiates a new journey.
     *
     * @return true if this is a journey-starting event
     */
    public boolean isJourneyStart() {
        return this == CARD_APPLY;
    }

    /**
     * Checks if this event represents a terminal decision.
     *
     * @return true if APPROVAL or REJECTION
     */
    public boolean isDecision() {
        return this == APPROVAL || this == REJECTION;
    }
}
