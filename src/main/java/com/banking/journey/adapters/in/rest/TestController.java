package com.banking.journey.adapters.in.rest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.banking.journey.application.port.out.StateStore;
import com.banking.journey.domain.entity.CardApplicationState;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST controller for testing and simulating customer events.
 * <p>
 * Use these endpoints to manually push events into the system
 * and verify the journey orchestration flow.
 * </p>
 */
@RestController
@RequestMapping("/api/test")
public class TestController {

    private static final Logger log = LoggerFactory.getLogger(TestController.class);
    private static final String CUSTOMER_EVENTS_TOPIC = "customer-events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final StateStore stateStore;
    private final ObjectMapper objectMapper;

    public TestController(KafkaTemplate<String, String> kafkaTemplate,
            StateStore stateStore,
            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.stateStore = stateStore;
        this.objectMapper = objectMapper;
    }

    /**
     * Simulates sending a customer event by publishing to Kafka.
     * <p>
     * Usage: POST /api/test/events/{customerId}/{eventType}?segment=VIP
     * </p>
     *
     * @param customerId customer identifier (used as partition key)
     * @param eventType  event type: CARD_APPLY, DOCUMENT_UPLOAD, APPROVAL,
     *                   REJECTION
     * @param segment    optional customer segment for metadata
     * @return the published event details
     */
    @PostMapping("/events/{customerId}/{eventType}")
    public ResponseEntity<Map<String, Object>> publishEvent(
            @PathVariable String customerId,
            @PathVariable String eventType,
            @RequestParam(required = false, defaultValue = "REGULAR") String segment) {

        String eventId = UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();

        try {
            Map<String, Object> event = Map.of(
                    "event_id", eventId,
                    "customer_id", customerId,
                    "event_type", eventType.toUpperCase(),
                    "timestamp", timestamp,
                    "metadata", Map.of("segment", segment.toUpperCase(), "channel", "rest_api"));

            String json = objectMapper.writeValueAsString(event);

            // Publish to Kafka with customerId as partition key (ordering guarantee)
            kafkaTemplate.send(CUSTOMER_EVENTS_TOPIC, customerId, json);

            log.info("action=test_event_published eventId={} customerId={} eventType={}",
                    eventId, customerId, eventType);

            Map<String, Object> response = Map.of(
                    "status", "published",
                    "eventId", eventId,
                    "customerId", customerId,
                    "eventType", eventType.toUpperCase(),
                    "timestamp", timestamp,
                    "topic", CUSTOMER_EVENTS_TOPIC);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("action=test_event_error customerId={} error={}", customerId, e.getMessage());
            return ResponseEntity.internalServerError().body(
                    Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * Retrieves the current journey state for a customer.
     * <p>
     * Usage: GET /api/test/state/{customerId}
     * </p>
     */
    @GetMapping("/state/{customerId}")
    public ResponseEntity<Map<String, Object>> getState(@PathVariable String customerId) {
        CardApplicationState state = stateStore.getState(customerId);

        if (state == null) {
            return ResponseEntity.ok(Map.of(
                    "customerId", customerId,
                    "status", "no_active_journey"));
        }

        Map<String, Object> response = Map.of(
                "customerId", state.getCustomerId(),
                "currentStep", state.getCurrentStep().name(),
                "documentCount", state.getDocumentCount(),
                "remainingDocuments", state.remainingDocuments(),
                "isComplete", state.isComplete(),
                "startedAt", state.getStartedAt().toString(),
                "updatedAt", state.getUpdatedAt().toString());

        return ResponseEntity.ok(response);
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "journey-orchestrator",
                "timestamp", Instant.now().toString()));
    }
}
