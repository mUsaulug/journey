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

/**
 * Core use-case implementation: Card Application Journey Orchestrator.
 * <p>
 * Implements the complete Sense-Analyze-Act cycle:
 * <ol>
 * <li><b>Sense</b>: Audit the incoming event</li>
 * <li><b>Analyze</b>: Retrieve state, determine next step</li>
 * <li><b>Act</b>: Transition state, persist, generate and publish action</li>
 * </ol>
 * </p>
 *
 * <p>
 * <b>Error Handling:</b>
 * </p>
 * <ul>
 * <li>EventStore/StateStore errors → rethrow (Kafka will retry)</li>
 * <li>IllegalStateException → log + skip (invalid transition, no retry
 * value)</li>
 * <li>ActionPublisher errors → log + continue (event already processed)</li>
 * </ul>
 */
public class CardApplicationOrchestrator implements ProcessEventUseCase {

    private static final Logger log = Logger.getLogger(CardApplicationOrchestrator.class.getName());

    private final EventStore eventStore;
    private final StateStore stateStore;
    private final StateMachineEngine stateMachineEngine;
    private final ActionPublisher actionPublisher;

    /**
     * Constructor injection — all dependencies provided externally.
     */
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

    /**
     * Processes a customer event through the full orchestration pipeline.
     * <p>
     * Steps: Audit → Retrieve State → Decide → Transition → Persist → Generate
     * Action → Publish
     * </p>
     *
     * @param event the customer event to process
     */
    @Override
    public void process(CustomerEvent event) {
        Instant startTime = Instant.now();
        String customerId = event.getCustomerId();
        String eventId = event.getEventId();

        log.info(String.format("action=process_start eventId=%s customerId=%s eventType=%s",
                eventId, customerId, event.getEventType()));

        try {
            // ── Step 1: AUDIT (Sense) ──
            auditEvent(event);

            // ── Step 2: RETRIEVE STATE ──
            CardApplicationState currentState = retrieveState(customerId);

            // ── Step 3: DECISION (Analyze) ──
            StateType nextStep = decideNextStep(currentState, event);
            if (nextStep == null) {
                log.warning(String.format(
                        "action=skip_invalid_event eventId=%s customerId=%s eventType=%s currentStep=%s reason=no_valid_transition",
                        eventId, customerId, event.getEventType(),
                        currentState != null ? currentState.getCurrentStep() : "null"));
                return;
            }

            // ── Step 4: TRANSITION ──
            CardApplicationState newState = transitionState(currentState, nextStep, event);

            // ── Step 5: PERSIST ──
            persistState(newState);

            // ── Step 6: GENERATE ACTION (Act) ──
            Action action = generateAction(newState);

            // ── Step 7: PUBLISH ACTION ──
            if (action != null) {
                publishAction(action);
            }

            // ── Metrics ──
            long latencyMs = Duration.between(startTime, Instant.now()).toMillis();
            log.info(String.format(
                    "action=process_complete eventId=%s customerId=%s eventType=%s oldStep=%s newStep=%s latency=%dms",
                    eventId, customerId, event.getEventType(),
                    currentState != null ? currentState.getCurrentStep() : "null",
                    newState.getCurrentStep(), latencyMs));

        } catch (IllegalStateException e) {
            // Business logic error: invalid transition → skip, don't retry
            log.log(Level.WARNING, String.format(
                    "action=invalid_transition eventId=%s customerId=%s error=%s",
                    eventId, customerId, e.getMessage()), e);
            // Don't rethrow — Kafka should acknowledge and move on

        } catch (Exception e) {
            // Infrastructure error → rethrow for Kafka retry
            log.log(Level.SEVERE, String.format(
                    "action=process_error eventId=%s customerId=%s error=%s",
                    eventId, customerId, e.getMessage()), e);
            throw e;
        }
    }

    // ─────────────────── Private Steps ───────────────────

    /**
     * Step 1: Save event to audit trail (idempotent via ON CONFLICT DO NOTHING).
     */
    private void auditEvent(CustomerEvent event) {
        log.fine(String.format("action=audit_event eventId=%s customerId=%s",
                event.getEventId(), event.getCustomerId()));
        eventStore.save(event);
    }

    /**
     * Step 2: Retrieve current journey state from state store.
     * Returns null for new journeys.
     */
    private CardApplicationState retrieveState(String customerId) {
        log.fine(String.format("action=retrieve_state customerId=%s", customerId));
        return stateStore.getState(customerId);
    }

    /**
     * Step 3: Use state machine engine to determine next step.
     */
    private StateType decideNextStep(CardApplicationState currentState, CustomerEvent event) {
        return stateMachineEngine.determineNextStep(currentState, event);
    }

    /**
     * Step 4: Execute state transition — creates a new immutable state.
     */
    private CardApplicationState transitionState(CardApplicationState currentState,
            StateType nextStep,
            CustomerEvent event) {
        if (currentState == null) {
            // New journey: create initial state
            log.info(String.format("action=journey_start customerId=%s", event.getCustomerId()));
            return CardApplicationState.start(event.getCustomerId(), event);
        }

        // Existing journey: immutable transition
        return currentState.transitionTo(nextStep, event);
    }

    /**
     * Step 5: Persist new state to state store.
     */
    private void persistState(CardApplicationState state) {
        log.fine(String.format("action=persist_state customerId=%s step=%s",
                state.getCustomerId(), state.getCurrentStep()));
        stateStore.saveState(state);
    }

    /**
     * Step 6: Generate action based on the new state.
     * Creates a dummy Customer for now (segment detection from event metadata).
     */
    private Action generateAction(CardApplicationState state) {
        // Derive segment from metadata or default to REGULAR
        Segment segment = resolveSegment(state);
        Customer customer = new Customer(state.getCustomerId(), segment);

        return stateMachineEngine.generateAction(state, customer);
    }

    /**
     * Step 7: Publish action via ActionPublisher.
     * Errors here are logged but NOT rethrown — the event is already processed.
     */
    private void publishAction(Action action) {
        try {
            log.info(String.format("action=publish_action actionId=%s customerId=%s type=%s",
                    action.getActionId(), action.getCustomerId(), action.getActionType()));
            actionPublisher.publish(action);
        } catch (Exception e) {
            // Action publishing failure should NOT cause event replay
            log.log(Level.SEVERE, String.format(
                    "action=publish_action_error actionId=%s customerId=%s error=%s",
                    action.getActionId(), action.getCustomerId(), e.getMessage()), e);
        }
    }

    /**
     * Resolves customer segment from state metadata.
     * Falls back to REGULAR if not found.
     */
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
}
