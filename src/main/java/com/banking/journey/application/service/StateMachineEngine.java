package com.banking.journey.application.service;

import com.banking.journey.domain.entity.Action;
import com.banking.journey.domain.entity.CardApplicationState;
import com.banking.journey.domain.entity.Customer;
import com.banking.journey.domain.entity.CustomerEvent;
import com.banking.journey.domain.valueobject.EventType;
import com.banking.journey.domain.valueobject.StateType;

/**
 * Stateless decision engine for the credit card application journey.
 * <p>
 * Implements the "Analyze" step of the Sense-Analyze-Act pattern.
 * Determines state transitions and generates appropriate customer actions
 * based on current state and incoming events.
 * </p>
 *
 * <p>
 * <b>Thread-safe:</b> This service has no mutable state.
 * </p>
 * <p>
 * <b>Deterministic:</b> Same inputs always produce the same outputs.
 * </p>
 */
public class StateMachineEngine {

    // ─────────────────── Message Templates ───────────────────
    private static final String MSG_APPLIED = "Başvurunuz alındı! Tracking ID: %s";
    private static final String MSG_DOCUMENT_PENDING = "Lütfen %d adet belge yükleyin.";
    private static final String MSG_UNDER_REVIEW = "Başvurunuz inceleniyor, 24 saat içinde sonuç alacaksınız.";
    private static final String MSG_APPROVED = "\uD83C\uDF89 Tebrikler! Kredi kartınız onaylandı.";
    private static final String MSG_APPROVED_VIP = " \uD83C\uDF1F VIP müşterimizsiniz! Kartınız 2 iş günü içinde adresinize ulaşacak.";
    private static final String MSG_REJECTED = "Üzgünüz, başvurunuz şu anda onaylanamadı.";

    private static final String CAMPAIGN_CARD_ONBOARDING = "campaign-card-onboarding";

    /**
     * Determines the next state based on current state and incoming event.
     * <p>
     * Returns null if no valid transition exists (caller should skip the event).
     * </p>
     *
     * @param currentState current journey state (null if new journey)
     * @param event        the incoming customer event
     * @return next StateType, or null if transition is invalid/not applicable
     */
    public StateType determineNextStep(CardApplicationState currentState, CustomerEvent event) {
        // ── New journey: only CARD_APPLY can start a journey
        if (currentState == null) {
            return event.isCardApplication() ? StateType.APPLIED : null;
        }

        StateType currentStep = currentState.getCurrentStep();

        // ── Terminal states: no further transitions
        if (currentStep.isTerminal()) {
            return null;
        }

        // ── State-specific transition logic
        return switch (currentStep) {
            case APPLIED -> StateType.DOCUMENT_PENDING;

            case DOCUMENT_PENDING -> handleDocumentPending(currentState, event);

            case UNDER_REVIEW -> handleUnderReview(event);

            default -> null;
        };
    }

    /**
     * Generates an appropriate customer action for the given state.
     * <p>
     * VIP customers receive personalized messages. Returns null if
     * no action is needed for the current state.
     * </p>
     *
     * @param state    current journey state
     * @param customer customer entity (for VIP detection)
     * @return Action to publish, or null if no action needed
     */
    public Action generateAction(CardApplicationState state, Customer customer) {
        if (state == null) {
            return null;
        }

        String customerId = state.getCustomerId();
        String message = buildMessage(state, customer);

        if (message == null) {
            return null;
        }

        return Action.create(
                customerId,
                Action.TYPE_PUSH_NOTIFICATION,
                message,
                Action.CHANNEL_MOBILE_APP,
                CAMPAIGN_CARD_ONBOARDING,
                null);
    }

    // ─────────────────── Private Helpers ───────────────────

    /**
     * Handles transitions from DOCUMENT_PENDING state.
     * Requires DOCUMENT_UPLOAD events and checks document count threshold.
     */
    private StateType handleDocumentPending(CardApplicationState currentState,
            CustomerEvent event) {
        if (!event.isDocumentUpload()) {
            return null; // Only document uploads are valid here
        }

        // After this upload, will we have enough documents?
        int nextDocumentCount = currentState.getDocumentCount() + 1;
        if (nextDocumentCount >= 2) {
            return StateType.UNDER_REVIEW;
        }
        return StateType.DOCUMENT_PENDING; // Stay, need more documents
    }

    /**
     * Handles transitions from UNDER_REVIEW state.
     * Only APPROVAL and REJECTION events are valid.
     */
    private StateType handleUnderReview(CustomerEvent event) {
        if (event.isApproval()) {
            return StateType.APPROVED;
        }
        if (event.isRejection()) {
            return StateType.REJECTED;
        }
        return null; // Other events are invalid in this state
    }

    /**
     * Builds the appropriate notification message for a given state.
     * VIP customers receive enhanced messages.
     */
    private String buildMessage(CardApplicationState state, Customer customer) {
        return switch (state.getCurrentStep()) {
            case APPLIED ->
                String.format(MSG_APPLIED, state.getCustomerId().substring(0,
                        Math.min(8, state.getCustomerId().length())));

            case DOCUMENT_PENDING ->
                String.format(MSG_DOCUMENT_PENDING, state.remainingDocuments());

            case UNDER_REVIEW -> MSG_UNDER_REVIEW;

            case APPROVED -> {
                String msg = MSG_APPROVED;
                if (customer != null && customer.isVip()) {
                    msg += MSG_APPROVED_VIP;
                }
                yield msg;
            }

            case REJECTED -> MSG_REJECTED;
        };
    }
}
