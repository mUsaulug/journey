package com.banking.journey.application.service;

import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.banking.journey.application.port.in.ProcessEventUseCase;
import com.banking.journey.application.port.out.ActionPublisher;
import com.banking.journey.application.port.out.EventStore;
import com.banking.journey.application.port.out.StateStore;
import com.banking.journey.domain.entity.Action;
import com.banking.journey.domain.entity.CardApplicationState;
import com.banking.journey.domain.entity.Customer;
import com.banking.journey.domain.entity.CustomerEvent;
import com.banking.journey.domain.valueobject.Segment;
import com.banking.journey.domain.valueobject.StateType;

public class CardApplicationOrchestrator implements ProcessEventUseCase {

    private static final Logger log = Logger.getLogger(CardApplicationOrchestrator.class.getName());

    private final EventStore eventStore;
    private final StateStore stateStore;
    private final StateMachineEngine stateMachineEngine;
    private final ActionPublisher actionPublisher;

    public CardApplicationOrchestrator(EventStore eventStore,
            StateStore stateStore,
            StateMachineEngine stateMachineEngine,
            ActionPublisher actionPublisher) {
        if (eventStore == null)
            throw new IllegalArgumentException("eventStore cannot be null");
        if (stateStore == null)
            throw new IllegalArgumentException("stateStore cannot be null");
        if (stateMachineEngine == null)
            throw new IllegalArgumentException("stateMachineEngine cannot be null");
        if (actionPublisher == null)
            throw new IllegalArgumentException("actionPublisher cannot be null");

        this.eventStore = eventStore;
        this.stateStore = stateStore;
        this.stateMachineEngine = stateMachineEngine;
        this.actionPublisher = actionPublisher;
    }

    @Override
    public void process(CustomerEvent event) {
        Instant startTime = Instant.now();
        String customerId = event.getCustomerId();
        String eventId = event.getEventId();

        log.info(String.format("action=process_start eventId=%s customerId=%s eventType=%s",
                eventId, customerId, event.getEventType()));

        ProcessingOutcome outcome = ProcessingOutcome.SUCCESS;

        try {
            auditEvent(event);
            CardApplicationState currentState = retrieveState(customerId);
            StateType nextStep = decideNextStep(currentState, event);

            if (nextStep == null) {
                outcome = ProcessingOutcome.SKIPPED_INVALID_TRANSITION;
                log.warning(String.format(
                        "action=skip_invalid_event eventId=%s customerId=%s eventType=%s currentStep=%s reason=no_valid_transition",
                        eventId, customerId, event.getEventType(),
                        currentState != null ? currentState.getCurrentStep() : "null"));
                return;
            }

            CardApplicationState newState = transitionState(currentState, nextStep, event);
            persistState(newState);
            Action action = generateAction(newState);

            if (action != null) {
                publishAction(action);
            }

            log.info(String.format(
                    "action=process_complete eventId=%s customerId=%s eventType=%s oldStep=%s newStep=%s outcome=%s",
                    eventId, customerId, event.getEventType(),
                    currentState != null ? currentState.getCurrentStep() : "null",
                    newState.getCurrentStep(), outcome));

        } catch (IllegalStateException e) {
            outcome = ProcessingOutcome.SKIPPED_BUSINESS_RULE;
            log.log(Level.WARNING, String.format(
                    "action=invalid_transition eventId=%s customerId=%s error=%s",
                    eventId, customerId, e.getMessage()), e);

        } catch (Exception e) {
            outcome = ProcessingOutcome.RETRYABLE_INFRA_FAILURE;
            log.log(Level.SEVERE, String.format(
                    "action=process_error eventId=%s customerId=%s error=%s outcome=%s",
                    eventId, customerId, e.getMessage(), outcome), e);
            throw e;

        } finally {
            long latencyMs = Duration.between(startTime, Instant.now()).toMillis();
            log.info(String.format(
                    "action=process_end eventId=%s customerId=%s outcome=%s latency=%dms",
                    eventId, customerId, outcome, latencyMs));
        }
    }

    private void auditEvent(CustomerEvent event) {
        eventStore.save(event);
    }

    private CardApplicationState retrieveState(String customerId) {
        return stateStore.getState(customerId);
    }

    private StateType decideNextStep(CardApplicationState currentState, CustomerEvent event) {
        return stateMachineEngine.determineNextStep(currentState, event);
    }

    private CardApplicationState transitionState(CardApplicationState currentState,
            StateType nextStep,
            CustomerEvent event) {
        if (currentState == null) {
            log.info(String.format("action=journey_start customerId=%s", event.getCustomerId()));
            return CardApplicationState.start(event.getCustomerId(), event);
        }

        return currentState.transitionTo(nextStep, event);
    }

    private void persistState(CardApplicationState state) {
        stateStore.saveState(state);
    }

    private Action generateAction(CardApplicationState state) {
        Segment segment = resolveSegment(state);
        Customer customer = new Customer(state.getCustomerId(), segment);
        return stateMachineEngine.generateAction(state, customer);
    }

    private void publishAction(Action action) {
        log.info(String.format("action=publish_action actionId=%s customerId=%s type=%s",
                action.getActionId(), action.getCustomerId(), action.getActionType()));
        actionPublisher.publish(action);
    }

    private Segment resolveSegment(CardApplicationState state) {
        String segmentStr = state.getMetadata().get("segment");
        if (segmentStr != null) {
            try {
                return Segment.valueOf(segmentStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warning(String.format("action=invalid_segment customerId=%s segment=%s",
                        state.getCustomerId(), segmentStr));
            }
        }
        return Segment.REGULAR;
    }

    private enum ProcessingOutcome {
        SUCCESS,
        SKIPPED_INVALID_TRANSITION,
        SKIPPED_BUSINESS_RULE,
        RETRYABLE_INFRA_FAILURE
    }
}
