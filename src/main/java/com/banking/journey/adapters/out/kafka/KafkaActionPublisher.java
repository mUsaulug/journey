package com.banking.journey.adapters.out.kafka;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.banking.journey.application.port.out.ActionPublisher;
import com.banking.journey.bootstrap.config.JourneyProperties;
import com.banking.journey.domain.entity.Action;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class KafkaActionPublisher implements ActionPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaActionPublisher.class);

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
    private final String actionsTopic;
    private final String idempotencyPrefix;
    private final long idempotencyTtlHours;
    private final long processingTtlMinutes;
    private final long publishAckTimeoutMs;

    private final Counter actionPublishSuccess;
    private final Counter actionPublishFailure;
    private final Counter actionPublishDuplicate;
    private final Timer actionPublishLatency;

    public KafkaActionPublisher(KafkaTemplate<String, String> kafkaTemplate,
            StringRedisTemplate redisTemplate,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            JourneyProperties journeyProperties,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.actionsTopic = journeyProperties.getKafka().getTopics().getActions();
        this.idempotencyPrefix = journeyProperties.getRedis().getIdempotencyPrefix();
        this.idempotencyTtlHours = journeyProperties.getRedis().getIdempotencyTtlHours();
        this.processingTtlMinutes = journeyProperties.getRedis().getProcessingTtlMinutes();
        this.publishAckTimeoutMs = journeyProperties.getKafka().getPublishAckTimeoutMs();

        this.actionPublishSuccess = meterRegistry.counter("journey.action.publish.outcome", "status", "success");
        this.actionPublishFailure = meterRegistry.counter("journey.action.publish.outcome", "status", "failure");
        this.actionPublishDuplicate = meterRegistry.counter("journey.action.publish.outcome", "status", "duplicate");
        this.actionPublishLatency = meterRegistry.timer("journey.action.publish.latency");
    }

    @Override
    public void publish(Action action) {
        Timer.Sample sample = Timer.start();
        String actionStatusKey = idempotencyPrefix + action.getActionId();
        String processingValue = "PROCESSING";

        Boolean lockAcquired = redisTemplate.opsForValue()
                .setIfAbsent(actionStatusKey, processingValue, processingTtlMinutes, TimeUnit.MINUTES);

        if (Boolean.FALSE.equals(lockAcquired)) {
            String existingStatus = redisTemplate.opsForValue().get(actionStatusKey);
            if ("DONE".equals(existingStatus)) {
                actionPublishDuplicate.increment();
                log.warn("action=duplicate_action_skipped actionId={} customerId={} status={}",
                        action.getActionId(), action.getCustomerId(), existingStatus);
                return;
            }
            log.warn("action=action_inflight_skipped actionId={} customerId={} status={}",
                    action.getActionId(), action.getCustomerId(), existingStatus);
            throw new IllegalStateException("Action publish already in progress for actionId=" + action.getActionId());
        }

        try {
            String actionJson = serializeAction(action);
            SendResult<String, String> sendResult = kafkaTemplate.send(actionsTopic, action.getCustomerId(), actionJson)
                    .completable()
                    .get(publishAckTimeoutMs, TimeUnit.MILLISECONDS);

            log.info("action=action_published actionId={} customerId={} type={} channel={} topic={} partition={} offset={}",
                    action.getActionId(), action.getCustomerId(),
                    action.getActionType(), action.getChannel(),
                    sendResult.getRecordMetadata().topic(),
                    sendResult.getRecordMetadata().partition(),
                    sendResult.getRecordMetadata().offset());

            jdbcTemplate.update(INSERT_ACTION_SQL,
                    action.getActionId(),
                    action.getCustomerId(),
                    action.getActionType(),
                    action.getMessage(),
                    action.getChannel(),
                    Timestamp.from(action.getCreatedAt()));

            redisTemplate.opsForValue().set(actionStatusKey, "DONE", idempotencyTtlHours, TimeUnit.HOURS);
            actionPublishSuccess.increment();

        } catch (Exception e) {
            actionPublishFailure.increment();
            redisTemplate.delete(actionStatusKey);
            log.error("action=action_publish_failed actionId={} customerId={} error={}",
                    action.getActionId(), action.getCustomerId(), e.getMessage(), e);
            throw new RuntimeException("Action publish failed for actionId=" + action.getActionId(), e);
        } finally {
            sample.stop(actionPublishLatency);
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
                        null,
                        rs.getTimestamp("sent_at").toInstant(),
                        null),
                limit);
    }

    @Override
    public long countAll() {
        Long count = jdbcTemplate.queryForObject(COUNT_ALL_SQL, Long.class);
        return count != null ? count : 0;
    }

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
