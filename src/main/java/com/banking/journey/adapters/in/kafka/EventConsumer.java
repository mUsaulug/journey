package com.banking.journey.adapters.in.kafka;

import java.time.Instant;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.banking.journey.application.port.in.ProcessEventUseCase;
import com.banking.journey.domain.entity.CustomerEvent;
import com.banking.journey.domain.valueobject.EventType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Kafka inbound adapter: Consumes customer events from the 'customer-events'
 * topic.
 * <p>
 * Implements layered error handling:
 * <ol>
 * <li><b>Parse Error:</b> DLQ + skip</li>
 * <li><b>Business Logic Error:</b> DLQ + skip</li>
 * <li><b>Transient Error:</b> Throw → Kafka retry</li>
 * <li><b>Unknown Error:</b> DLQ + skip</li>
 * </ol>
 * </p>
 */
@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);
    private static final String DLQ_TOPIC = "customer-events-dlq";

    private final ProcessEventUseCase processEventUseCase;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public EventConsumer(ProcessEventUseCase processEventUseCase,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.processEventUseCase = processEventUseCase;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Main Kafka listener for customer events.
     * Uses manual acknowledgment for fine-grained offset control.
     */
    @KafkaListener(topics = "${journey.kafka.topics.customer-events:customer-events}", groupId = "journey-orchestrator", containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String key = record.key();
        String value = record.value();

        // Set MDC context for structured logging
        try {
            MDC.put("kafkaTopic", record.topic());
            MDC.put("kafkaPartition", String.valueOf(record.partition()));
            MDC.put("kafkaOffset", String.valueOf(record.offset()));

            log.info("action=event_received key={} partition={} offset={}",
                    key, record.partition(), record.offset());

            // Step 1: Parse the event
            CustomerEvent event = parseEvent(value);
            MDC.put("customerId", event.getCustomerId());
            MDC.put("eventId", event.getEventId());
            MDC.put("eventType", event.getEventType().name());

            // Step 2: Process through orchestrator
            processEventUseCase.process(event);

            // Step 3: Acknowledge on success
            ack.acknowledge();
            log.info("action=event_acknowledged eventId={} customerId={}",
                    event.getEventId(), event.getCustomerId());

        } catch (JsonProcessingException e) {
            // ── PARSE ERROR: Bad JSON → DLQ, skip ──
            log.error("action=parse_error key={} error={}", key, e.getMessage());
            sendToDlq(record, "PARSE_ERROR", e);
            ack.acknowledge(); // Skip the bad message

        } catch (IllegalStateException | IllegalArgumentException e) {
            // ── BUSINESS LOGIC ERROR: Invalid state/data → DLQ, skip ──
            log.error("action=business_error key={} error={}", key, e.getMessage());
            sendToDlq(record, "BUSINESS_ERROR", e);
            ack.acknowledge(); // Skip, retry won't fix this

        } catch (org.springframework.data.redis.RedisConnectionFailureException
                | org.springframework.dao.DataAccessException e) {
            // ── TRANSIENT ERROR: Redis/DB down → throw for Kafka retry ──
            log.error("action=transient_error key={} error={}", key, e.getMessage());
            throw new RuntimeException("Transient infrastructure error", e);
            // Don't acknowledge → Kafka will redeliver

        } catch (Exception e) {
            // ── UNKNOWN ERROR: Catch-all → DLQ, skip (prevent infinite loop) ──
            log.error("action=unknown_error key={} error={}", key, e.getMessage(), e);
            sendToDlq(record, "UNKNOWN_ERROR", e);
            ack.acknowledge(); // Skip to prevent infinite retry loop

        } finally {
            MDC.clear();
        }
    }

    // ─────────────────── Private Helpers ───────────────────

    /**
     * Parses raw JSON into a domain CustomerEvent entity.
     */
    private CustomerEvent parseEvent(String json) throws JsonProcessingException {
        EventDto dto = objectMapper.readValue(json, EventDto.class);
        return dto.toDomain();
    }

    /**
     * Sends a failed message to the Dead Letter Queue with error context.
     */
    private void sendToDlq(ConsumerRecord<String, String> record,
            String errorType, Exception error) {
        try {
            DlqMessage dlqMessage = new DlqMessage(
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    record.value(),
                    errorType,
                    error.getMessage(),
                    getStackTrace(error),
                    Instant.now().toString());

            String dlqJson = objectMapper.writeValueAsString(dlqMessage);
            kafkaTemplate.send(DLQ_TOPIC, record.key(), dlqJson);
            log.warn("action=sent_to_dlq errorType={} key={} originalTopic={}",
                    errorType, record.key(), record.topic());
        } catch (Exception dlqError) {
            // If DLQ itself fails, just log — nothing more we can do
            log.error("action=dlq_send_failed key={} error={}",
                    record.key(), dlqError.getMessage());
        }
    }

    private String getStackTrace(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append(element.toString()).append("\n");
            if (sb.length() > 500)
                break; // Limit stacktrace size
        }
        return sb.toString();
    }

    // ─────────────────── Inner DTO Classes ───────────────────

    /**
     * DTO for deserializing Kafka messages into domain events.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EventDto {

        @JsonProperty("event_id")
        private String eventId;

        @JsonProperty("customer_id")
        private String customerId;

        @JsonProperty("event_type")
        private String eventType;

        @JsonProperty("timestamp")
        private String timestamp;

        @JsonProperty("metadata")
        private Map<String, String> metadata;

        public CustomerEvent toDomain() {
            return new CustomerEvent(
                    eventId,
                    customerId,
                    EventType.valueOf(eventType),
                    Instant.parse(timestamp),
                    metadata);
        }

        // Getters/Setters for Jackson
        public String getEventId() {
            return eventId;
        }

        public void setEventId(String eventId) {
            this.eventId = eventId;
        }

        public String getCustomerId() {
            return customerId;
        }

        public void setCustomerId(String customerId) {
            this.customerId = customerId;
        }

        public String getEventType() {
            return eventType;
        }

        public void setEventType(String eventType) {
            this.eventType = eventType;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(String timestamp) {
            this.timestamp = timestamp;
        }

        public Map<String, String> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, String> metadata) {
            this.metadata = metadata;
        }
    }

    /**
     * DLQ message envelope with error context.
     */
    public record DlqMessage(
            String originalTopic,
            int originalPartition,
            long originalOffset,
            String originalKey,
            String originalValue,
            String errorType,
            String errorMessage,
            String stackTrace,
            String timestamp) {
    }
}
