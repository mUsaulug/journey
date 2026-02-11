package com.banking.journey.domain.valueobject;

/**
 * Journey state classification in the credit card application flow.
 * <p>
 * Represents discrete positions in the state machine. Terminal states
 * (APPROVED, REJECTED) cannot transition further.
 * </p>
 *
 * <pre>
 * State Machine Flow:
 *   APPLIED → DOCUMENT_PENDING → UNDER_REVIEW → APPROVED
 *                                              → REJECTED
 * </pre>
 */
public enum StateType {

    /** Application submitted, awaiting document collection */
    APPLIED,

    /** Waiting for required documents (2 needed) */
    DOCUMENT_PENDING,

    /** All documents received, under manual/automated review */
    UNDER_REVIEW,

    /** Application approved - terminal state */
    APPROVED,

    /** Application rejected - terminal state */
    REJECTED;

    /**
     * Checks if this is a terminal (final) state.
     * Terminal states cannot transition to any other state.
     *
     * @return true if APPROVED or REJECTED
     */
    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED;
    }

    /**
     * Checks if this state requires document uploads.
     *
     * @return true if DOCUMENT_PENDING
     */
    public boolean needsDocuments() {
        return this == DOCUMENT_PENDING;
    }

    /**
     * Checks if this state represents a positive outcome.
     *
     * @return true if APPROVED
     */
    public boolean isApproved() {
        return this == APPROVED;
    }

    /**
     * Checks if the journey is still active (non-terminal).
     *
     * @return true if the journey can still progress
     */
    public boolean isActive() {
        return !isTerminal();
    }
}
