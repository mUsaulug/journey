package com.banking.journey.adapters.out.postgres;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.banking.journey.application.port.out.EventStore;
import com.banking.journey.domain.entity.CustomerEvent;
import com.banking.journey.domain.valueobject.EventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * PostgreSQL implementation of the EventStore outbound port.
 * <p>
 * Uses JDBC with ON CONFLICT DO NOTHING for idempotent writes.
 * JSONB column stores full event payload/metadata.
 * </p>
 */
@Component
public class PostgresEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresEventStore.class);

    // Idempotent upsert: duplicate eventId silently ignored
    private static final String INSERT_EVENT_SQL = "INSERT INTO events (event_id, customer_id, event_type, timestamp, payload) "
            +
            "VALUES (?, ?, ?, ?, ?::jsonb) " +
            "ON CONFLICT (event_id) DO NOTHING";

    private static final String SELECT_BY_CUSTOMER_SQL = "SELECT event_id, customer_id, event_type, timestamp, payload "
            +
            "FROM events WHERE customer_id = ? " +
            "ORDER BY timestamp DESC LIMIT ?";

    private static final String COUNT_ALL_SQL = "SELECT COUNT(*) FROM events";

    private static final String COUNT_BY_TYPE_SQL = "SELECT event_type, COUNT(*) as cnt FROM events GROUP BY event_type ORDER BY cnt DESC";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresEventStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(CustomerEvent event) {
        try {
            String payloadJson = objectMapper.writeValueAsString(
                    Map.of(
                            "event_id", event.getEventId(),
                            "customer_id", event.getCustomerId(),
                            "event_type", event.getEventType().name(),
                            "timestamp", event.getTimestamp().toString(),
                            "metadata", event.getMetadata()));

            int rows = jdbcTemplate.update(
                    INSERT_EVENT_SQL,
                    event.getEventId(),
                    event.getCustomerId(),
                    event.getEventType().name(),
                    Timestamp.from(event.getTimestamp()),
                    payloadJson);

            if (rows > 0) {
                log.debug("action=event_saved eventId={} customerId={}",
                        event.getEventId(), event.getCustomerId());
            } else {
                log.debug("action=event_duplicate_skipped eventId={}", event.getEventId());
            }
        } catch (JsonProcessingException e) {
            log.error("action=event_serialize_error eventId={} error={}",
                    event.getEventId(), e.getMessage());
            throw new RuntimeException("Failed to serialize event payload", e);
        }
    }

    @Override
    public List<CustomerEvent> findByCustomerId(String customerId, int limit) {
        return jdbcTemplate.query(
                SELECT_BY_CUSTOMER_SQL,
                (rs, rowNum) -> mapRowToEvent(rs),
                customerId,
                limit);
    }

    @Override
    public long countAll() {
        Long count = jdbcTemplate.queryForObject(COUNT_ALL_SQL, Long.class);
        return count != null ? count : 0;
    }

    @Override
    public List<Object[]> countByEventType() {
        return jdbcTemplate.query(COUNT_BY_TYPE_SQL,
                (rs, rowNum) -> new Object[] { rs.getString("event_type"), rs.getLong("cnt") });
    }

    // ─────────────────── Private Helpers ───────────────────

    private CustomerEvent mapRowToEvent(ResultSet rs) throws SQLException {
        String payloadStr = rs.getString("payload");
        Map<String, String> metadata = parseMetadata(payloadStr);

        return new CustomerEvent(
                rs.getString("event_id"),
                rs.getString("customer_id"),
                EventType.valueOf(rs.getString("event_type")),
                rs.getTimestamp("timestamp").toInstant(),
                metadata);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseMetadata(String payloadJson) {
        if (payloadJson == null)
            return Collections.emptyMap();
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson,
                    new TypeReference<Map<String, Object>>() {
                    });
            Object meta = payload.get("metadata");
            if (meta instanceof Map) {
                return (Map<String, String>) meta;
            }
            return Collections.emptyMap();
        } catch (JsonProcessingException e) {
            log.warn("action=metadata_parse_error error={}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
