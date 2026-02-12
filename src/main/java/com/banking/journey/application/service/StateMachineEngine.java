package com.banking.journey.application.service;

import com.banking.journey.bootstrap.config.JourneyProperties;
import com.banking.journey.domain.entity.Action;
import com.banking.journey.domain.entity.CardApplicationState;
import com.banking.journey.domain.entity.Customer;
import com.banking.journey.domain.entity.CustomerEvent;
import com.banking.journey.domain.valueobject.StateType;

/**
 * Stateless decision engine for the credit card application journey.
 */
public class StateMachineEngine {

    private static final String MSG_APPLIED = "Başvurunuz alındı! Tracking ID: %s";
    private static final String MSG_DOCUMENT_PENDING = "Lütfen %d adet belge yükleyin.";
    private static final String MSG_UNDER_REVIEW = "Başvurunuz inceleniyor, 24 saat içinde sonuç alacaksınız.";
    private static final String MSG_APPROVED = "\uD83C\uDF89 Tebrikler! Kredi kartınız onaylandı.";
    private static final String MSG_APPROVED_VIP = " \uD83C\uDF1F VIP müşterimizsiniz! Kartınız 2 iş günü içinde adresinize ulaşacak.";
    private static final String MSG_REJECTED = "Üzgünüz, başvurunuz şu anda onaylanamadı.";

    private static final String CAMPAIGN_CARD_ONBOARDING = "campaign-card-onboarding";

    private final int requiredDocumentCount;

    public StateMachineEngine(JourneyProperties journeyProperties) {
        this.requiredDocumentCount = Math.max(1, journeyProperties.getRequiredDocumentCount());
    }

    public StateType determineNextStep(CardApplicationState currentState, CustomerEvent event) {
        if (currentState == null) {
            return event.isCardApplication() ? StateType.APPLIED : null;
        }

        StateType currentStep = currentState.getCurrentStep();
        if (currentStep.isTerminal()) {
            return null;
        }

        return switch (currentStep) {
            case APPLIED -> StateType.DOCUMENT_PENDING;
            case DOCUMENT_PENDING -> handleDocumentPending(currentState, event);
            case UNDER_REVIEW -> handleUnderReview(event);
            default -> null;
        };
    }

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

    private StateType handleDocumentPending(CardApplicationState currentState, CustomerEvent event) {
        if (!event.isDocumentUpload()) {
            return null;
        }

        int nextDocumentCount = currentState.getDocumentCount() + 1;
        if (nextDocumentCount >= requiredDocumentCount) {
            return StateType.UNDER_REVIEW;
        }
        return StateType.DOCUMENT_PENDING;
    }

    private StateType handleUnderReview(CustomerEvent event) {
        if (event.isApproval()) {
            return StateType.APPROVED;
        }
        if (event.isRejection()) {
            return StateType.REJECTED;
        }
        return null;
    }

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
