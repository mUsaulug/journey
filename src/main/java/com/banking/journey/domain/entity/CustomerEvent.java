package com.banking.journey.domain.entity;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.banking.journey.domain.valueobject.EventType;

/**
 * Immutable event entity representing a customer action in the journey
 * pipeline.
 * <p>
 * Events flow through the system: captured from external channels (mobile,
 * web),
 * processed by the state machine engine, and persisted in the audit trail.
 * </p>
 *
 * <p>
 * <b>Invariants:</b>
 * </p>
 * <ul>
 * <li>eventId is a valid UUID string</li>
 * <li>customerId is non-null, non-blank</li>
 * <li>eventType is non-null</li>
 * <li>timestamp is non-null</li>
 * <li>metadata is never null (empty map if not provided)</li>
 * </ul>
 */
public final class CustomerEvent {

    private final String eventId;
    private final String customerId;
    private final EventType eventType;
    private final Instant timestamp;
    private final Map<String, String> metadata;

    /**
     * Full constructor with validation.
     */
    public CustomerEvent(String eventId, String customerId, EventType eventType,
            Instant timestamp, Map<String, String> metadata) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId cannot be null or blank");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId cannot be null or blank");
        }
        if (eventType == null) {
            throw new IllegalArgumentException("eventType cannot be null");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp cannot be null");
        }

        this.eventId = eventId;
        this.customerId = customerId;
        this.eventType = eventType;
        this.timestamp = timestamp;
        this.metadata = metadata != null
                ? Collections.unmodifiableMap(metadata)
                : Collections.emptyMap();
    }

    // ─────────────────── Factory Method ───────────────────

    /**
     * Creates a new CustomerEvent with auto-generated UUID and current timestamp.
     *
     * @param customerId customer identifier
     * @param eventType  type of event
     * @param metadata   optional context data
     * @return new CustomerEvent instance
     */
    public static CustomerEvent create(String customerId, EventType eventType,
            Map<String, String> metadata) {
        return new CustomerEvent(
                UUID.randomUUID().toString(),
                customerId,
                eventType,
                Instant.now(),
                metadata);
    }

    /**
     * Creates a new CustomerEvent without metadata.
     */
    public static CustomerEvent create(String customerId, EventType eventType) {
        return create(customerId, eventType, null);
    }

    // ─────────────────── Behavior Methods ───────────────────

    /** @return true if this event is a card application submission */
    public boolean isCardApplication() {
        return eventType == EventType.CARD_APPLY;
    }

    /** @return true if this event is a document upload */
    public boolean isDocumentUpload() {
        return eventType == EventType.DOCUMENT_UPLOAD;
    }

    /** @return true if this event is an approval decision */
    public boolean isApproval() {
        return eventType == EventType.APPROVAL;
    }

    /** @return true if this event is a rejection decision */
    public boolean isRejection() {
        return eventType == EventType.REJECTION;
    }

    /**
     * Delegates to EventType to check if immediate processing is required.
     *
     * @return true if the event requires priority handling
     */
    public boolean requiresImmediateAction() {
        return eventType.requiresImmediateAction();
    }

    /**
     * Checks if the event occurred more than the specified duration ago.
     *
     * @param duration the threshold duration
     * @return true if event is older than the given duration
     */
    public boolean isOlderThan(Duration duration) {
        return Duration.between(timestamp, Instant.now()).compareTo(duration) > 0;
    }

    // ─────────────────── Getters ───────────────────

    public String getEventId() {
        return eventId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public Instant getTimestamp() {
        return timestamp;
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
        CustomerEvent that = (CustomerEvent) o;
        return Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId);
    }

    @Override
    public String toString() {
        return "CustomerEvent{eventId='" + eventId
                + "', customerId='" + customerId
                + "', eventType=" + eventType
                + ", timestamp=" + timestamp + "}";
    }
}
