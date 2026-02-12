package com.banking.journey.domain.entity;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.banking.journey.domain.valueobject.EventType;
import com.banking.journey.domain.valueobject.StateType;

/**
 * Core state machine entity for credit card application journey.
 * <p>
 * <b>IMMUTABLE:</b> Every state transition returns a NEW instance.
 * This entity is the heart of the journey orchestration system,
 * enforcing valid state transitions and business rules.
 * </p>
 *
 * <pre>
 * State Machine:
 *   null ──[CARD_APPLY]──→ APPLIED
 *   APPLIED ──[auto]──→ DOCUMENT_PENDING
 *   DOCUMENT_PENDING ──[DOCUMENT_UPLOAD, count&lt;2]──→ DOCUMENT_PENDING
 *   DOCUMENT_PENDING ──[DOCUMENT_UPLOAD, count≥2]──→ UNDER_REVIEW
 *   UNDER_REVIEW ──[APPROVAL]──→ APPROVED
 *   UNDER_REVIEW ──[REJECTION]──→ REJECTED
 * </pre>
 */
public final class CardApplicationState {

    /** Required number of documents to advance past DOCUMENT_PENDING */
    private static volatile int REQUIRED_DOCUMENT_COUNT = 2;

    /** Valid state transition map: from → set of allowed to-states */
    private static final Map<StateType, Set<StateType>> VALID_TRANSITIONS;

    static {
        Map<StateType, Set<StateType>> transitions = new HashMap<>();
        transitions.put(StateType.APPLIED, Set.of(StateType.DOCUMENT_PENDING));
        transitions.put(StateType.DOCUMENT_PENDING, Set.of(StateType.DOCUMENT_PENDING, StateType.UNDER_REVIEW));
        transitions.put(StateType.UNDER_REVIEW, Set.of(StateType.APPROVED, StateType.REJECTED));
        // Terminal states have no valid transitions
        transitions.put(StateType.APPROVED, Collections.emptySet());
        transitions.put(StateType.REJECTED, Collections.emptySet());
        VALID_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    private final String customerId;
    private final StateType currentStep;
    private final int documentCount;
    private final Instant startedAt;
    private final Instant updatedAt;
    private final Map<String, String> metadata;

    // ─────────────────── Private Constructor ───────────────────

    private CardApplicationState(String customerId, StateType currentStep,
            int documentCount, Instant startedAt,
            Instant updatedAt, Map<String, String> metadata) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId cannot be null or blank");
        }
        if (currentStep == null) {
            throw new IllegalArgumentException("currentStep cannot be null");
        }
        if (documentCount < 0 || documentCount > REQUIRED_DOCUMENT_COUNT) {
            throw new IllegalArgumentException(
                    "documentCount must be between 0 and " + REQUIRED_DOCUMENT_COUNT
                            + ", got: " + documentCount);
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt cannot be null");
        }

        this.customerId = customerId;
        this.currentStep = currentStep;
        this.documentCount = documentCount;
        this.startedAt = startedAt;
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
        this.metadata = metadata != null
                ? Collections.unmodifiableMap(metadata)
                : Collections.emptyMap();
    }


    public static void configureRequiredDocumentCount(int requiredDocumentCount) {
        if (requiredDocumentCount < 1) {
            throw new IllegalArgumentException("requiredDocumentCount must be >= 1");
        }
        REQUIRED_DOCUMENT_COUNT = requiredDocumentCount;
    }

    // ─────────────────── Factory Methods ───────────────────

    /**
     * Creates the initial state for a new card application journey.
     * <p>
     * This is the ONLY way to create the first state. The event must be
     * a CARD_APPLY event; otherwise, an exception is thrown.
     * </p>
     *
     * @param customerId the customer starting the journey
     * @param event      the triggering CARD_APPLY event
     * @return new CardApplicationState in APPLIED step
     * @throws IllegalArgumentException if the event is not CARD_APPLY
     */
    public static CardApplicationState start(String customerId, CustomerEvent event) {
        if (!event.isCardApplication()) {
            throw new IllegalArgumentException(
                    "Journey can only start with CARD_APPLY event, got: " + event.getEventType());
        }
        return new CardApplicationState(
                customerId,
                StateType.APPLIED,
                0,
                Instant.now(),
                Instant.now(),
                event.getMetadata());
    }

    /**
     * Reconstructs a CardApplicationState from stored data (e.g., Redis
     * deserialization).
     * Bypasses event validation since this is a reconstruction, not a transition.
     *
     * @return reconstructed CardApplicationState
     */
    public static CardApplicationState reconstruct(String customerId, StateType currentStep,
            int documentCount, Instant startedAt,
            Instant updatedAt, Map<String, String> metadata) {
        return new CardApplicationState(customerId, currentStep, documentCount,
                startedAt, updatedAt, metadata);
    }

    // ─────────────────── State Transition (CRITICAL!) ───────────────────

    /**
     * Transitions to a new state, returning a NEW immutable instance.
     * <p>
     * <b>Never mutates the current instance.</b> Validates the transition
     * is legal according to state machine rules and business constraints.
     * </p>
     *
     * @param newStep the target state
     * @param event   the event triggering the transition
     * @return a NEW CardApplicationState instance with updated step
     * @throws IllegalStateException if the transition is invalid
     */
    public CardApplicationState transitionTo(StateType newStep, CustomerEvent event) {
        // Validate the transition is allowed by the state machine
        if (!isValidTransition(this.currentStep, newStep)) {
            throw new IllegalStateException(
                    "Invalid transition: " + this.currentStep + " → " + newStep
                            + " for customer " + this.customerId);
        }

        // Calculate new document count
        int newDocumentCount = this.documentCount;
        if (event.isDocumentUpload()) {
            newDocumentCount = Math.min(this.documentCount + 1, REQUIRED_DOCUMENT_COUNT);
        }

        // Business rule: can only move to UNDER_REVIEW with enough documents
        if (newStep == StateType.UNDER_REVIEW && newDocumentCount < REQUIRED_DOCUMENT_COUNT) {
            throw new IllegalStateException(
                    "Cannot transition to UNDER_REVIEW: only " + newDocumentCount
                            + " of " + REQUIRED_DOCUMENT_COUNT + " documents uploaded"
                            + " for customer " + this.customerId);
        }

        // Return NEW instance (immutability!)
        return new CardApplicationState(
                this.customerId,
                newStep,
                newDocumentCount,
                this.startedAt,
                Instant.now(),
                this.metadata);
    }

    // ─────────────────── Transition Validation ───────────────────

    /**
     * Validates if a state transition is allowed by the state machine rules.
     */
    private boolean isValidTransition(StateType from, StateType to) {
        Set<StateType> allowed = VALID_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    // ─────────────────── Query Methods ───────────────────

    /**
     * Checks if the journey has reached a terminal state.
     *
     * @return true if APPROVED or REJECTED
     */
    public boolean isComplete() {
        return currentStep.isTerminal();
    }

    /**
     * Checks if more documents are needed to advance.
     *
     * @return true if in DOCUMENT_PENDING with fewer than required documents
     */
    public boolean needsMoreDocuments() {
        return currentStep == StateType.DOCUMENT_PENDING
                && documentCount < REQUIRED_DOCUMENT_COUNT;
    }

    /**
     * Checks if the journey can progress to the next state.
     *
     * @return true if not in a terminal state
     */
    public boolean canProgress() {
        return !isComplete();
    }

    /**
     * Calculates remaining documents needed to advance.
     *
     * @return number of documents still needed (0 if not applicable)
     */
    public int remainingDocuments() {
        if (currentStep != StateType.DOCUMENT_PENDING) {
            return 0;
        }
        return Math.max(0, REQUIRED_DOCUMENT_COUNT - documentCount);
    }

    // ─────────────────── Getters ───────────────────

    public String getCustomerId() {
        return customerId;
    }

    public StateType getCurrentStep() {
        return currentStep;
    }

    public int getDocumentCount() {
        return documentCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    // ─────────────────── Identity ───────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        CardApplicationState that = (CardApplicationState) o;
        return Objects.equals(customerId, that.customerId)
                && currentStep == that.currentStep;
    }

    @Override
    public int hashCode() {
        return Objects.hash(customerId, currentStep);
    }

    @Override
    public String toString() {
        return "CardApplicationState{customerId='" + customerId
                + "', currentStep=" + currentStep
                + ", documentCount=" + documentCount
                + ", startedAt=" + startedAt + "}";
    }
}
