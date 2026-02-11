package com.banking.journey.adapters.out.kafka;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.banking.journey.application.port.out.ActionPublisher;
import com.banking.journey.domain.entity.Action;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Kafka + Redis implementation of the ActionPublisher outbound port.
 * <p>
 * Implements triple-write pattern:
 * <ol>
 * <li>Redis SETNX for idempotency check</li>
 * <li>Kafka publish for downstream consumers</li>
 * <li>PostgreSQL write for audit trail</li>
 * </ol>
 * </p>
 */
@Component
public class KafkaActionPublisher implements ActionPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaActionPublisher.class);
    private static final String ACTIONS_TOPIC = "actions";
    private static final String IDEMPOTENCY_KEY_PREFIX = "action:sent:";
    private static final long IDEMPOTENCY_TTL_HOURS = 24;

    private static final String INSERT_ACTION_SQL = "INSERT INTO actions (action_id, customer_id, action_type, message, channel, sent_at) "
            +
            "VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (action_id) DO NOTHING";

    private static final String SELECT_RECENT_SQL = "SELECT action_id, customer_id, action_type, message, channel, sent_at "
            +
            "FROM actions ORDER BY sent_at DESC LIMIT ?";

    private static final String COUNT_ALL_SQL = "SELECT COUNT(*) FROM actions";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public KafkaActionPublisher(KafkaTemplate<String, String> kafkaTemplate,
            StringRedisTemplate redisTemplate,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Publishes an action with idempotency guarantee using Redis SETNX.
     * <p>
     * Flow:
     * 1. Check Redis idempotency key (SETNX atomic operation)
     * 2. If new: publish to Kafka + persist to PostgreSQL
     * 3. If duplicate: skip silently
     * </p>
     */
    @Override
    public void publish(Action action) {
        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + action.getActionId();

        // ── SETNX: Atomic idempotency check ──
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(idempotencyKey, "1", IDEMPOTENCY_TTL_HOURS, TimeUnit.HOURS);

        if (Boolean.FALSE.equals(isNew)) {
            log.warn("action=duplicate_action_skipped actionId={} customerId={}",
                    action.getActionId(), action.getCustomerId());
            return;
        }

        // ── Publish to Kafka ──
        try {
            String actionJson = serializeAction(action);
            kafkaTemplate.send(ACTIONS_TOPIC, action.getCustomerId(), actionJson);
            log.info("action=action_published actionId={} customerId={} type={} channel={}",
                    action.getActionId(), action.getCustomerId(),
                    action.getActionType(), action.getChannel());
        } catch (Exception e) {
            log.error("action=kafka_publish_error actionId={} error={}",
                    action.getActionId(), e.getMessage());
            // Don't throw — we still want to persist the audit record
        }

        // ── Persist to PostgreSQL (audit trail) ──
        try {
            jdbcTemplate.update(INSERT_ACTION_SQL,
                    action.getActionId(),
                    action.getCustomerId(),
                    action.getActionType(),
                    action.getMessage(),
                    action.getChannel(),
                    Timestamp.from(action.getCreatedAt()));

            log.debug("action=action_persisted actionId={}", action.getActionId());
        } catch (Exception e) {
            log.error("action=action_persist_error actionId={} error={}",
                    action.getActionId(), e.getMessage());
            // Audit failure is logged but not critical
        }
    }

    @Override
    public List<Action> getRecentActions(int limit) {
        return jdbcTemplate.query(SELECT_RECENT_SQL,
                (rs, rowNum) -> new Action(
                        rs.getString("action_id"),
                        rs.getString("customer_id"),
                        rs.getString("action_type"),
                        rs.getString("message"),
                        rs.getString("channel"),
                        null, // campaignId not stored in table
                        rs.getTimestamp("sent_at").toInstant(),
                        null // metadata not stored in table
                ),
                limit);
    }

    @Override
    public long countAll() {
        Long count = jdbcTemplate.queryForObject(COUNT_ALL_SQL, Long.class);
        return count != null ? count : 0;
    }

    // ─────────────────── Private Helpers ───────────────────

    private String serializeAction(Action action) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "action_id", action.getActionId(),
                    "customer_id", action.getCustomerId(),
                    "action_type", action.getActionType(),
                    "message", action.getMessage(),
                    "channel", action.getChannel(),
                    "created_at", action.getCreatedAt().toString()));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize action", e);
        }
    }
}
