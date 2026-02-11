package com.banking.journey.adapters.out.redis;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.banking.journey.application.port.out.StateStore;
import com.banking.journey.domain.entity.CardApplicationState;
import com.banking.journey.domain.valueobject.StateType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Redis implementation of the StateStore outbound port.
 * <p>
 * Key pattern: {@code journey:state:{customerId}}
 * TTL: 30 days
 * Serialization: JSON via Jackson
 * </p>
 */
@Component
public class RedisStateStore implements StateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisStateStore.class);
    private static final String KEY_PREFIX = "journey:state:";
    private static final long STATE_TTL_DAYS = 30;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisStateStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public CardApplicationState getState(String customerId) {
        String key = buildKey(customerId);
        String json = redisTemplate.opsForValue().get(key);

        if (json == null) {
            log.debug("action=state_not_found customerId={}", customerId);
            return null;
        }

        try {
            StateDto dto = objectMapper.readValue(json, StateDto.class);
            CardApplicationState state = dto.toDomain();
            log.debug("action=state_retrieved customerId={} step={}", customerId, state.getCurrentStep());
            return state;
        } catch (JsonProcessingException e) {
            log.error("action=state_deserialize_error customerId={} error={}", customerId, e.getMessage());
            throw new RuntimeException("Failed to deserialize state for customer: " + customerId, e);
        }
    }

    @Override
    public void saveState(CardApplicationState state) {
        String key = buildKey(state.getCustomerId());

        try {
            StateDto dto = StateDto.fromDomain(state);
            String json = objectMapper.writeValueAsString(dto);
            redisTemplate.opsForValue().set(key, json, STATE_TTL_DAYS, TimeUnit.DAYS);
            log.debug("action=state_saved customerId={} step={} ttlDays={}",
                    state.getCustomerId(), state.getCurrentStep(), STATE_TTL_DAYS);
        } catch (JsonProcessingException e) {
            log.error("action=state_serialize_error customerId={} error={}",
                    state.getCustomerId(), e.getMessage());
            throw new RuntimeException("Failed to serialize state for customer: " + state.getCustomerId(), e);
        }
    }

    @Override
    public void deleteState(String customerId) {
        String key = buildKey(customerId);
        Boolean deleted = redisTemplate.delete(key);
        log.info("action=state_deleted customerId={} existed={}", customerId, deleted);
    }

    private String buildKey(String customerId) {
        return KEY_PREFIX + customerId;
    }

    // ─────────────────── Inner DTO ───────────────────

    /**
     * Serialization DTO for CardApplicationState → Redis JSON.
     */
    public static class StateDto {

        @JsonProperty("customer_id")
        private String customerId;

        @JsonProperty("current_step")
        private String currentStep;

        @JsonProperty("document_count")
        private int documentCount;

        @JsonProperty("started_at")
        private String startedAt;

        @JsonProperty("updated_at")
        private String updatedAt;

        @JsonProperty("metadata")
        private Map<String, String> metadata;

        public StateDto() {
        } // Jackson

        public static StateDto fromDomain(CardApplicationState state) {
            StateDto dto = new StateDto();
            dto.customerId = state.getCustomerId();
            dto.currentStep = state.getCurrentStep().name();
            dto.documentCount = state.getDocumentCount();
            dto.startedAt = state.getStartedAt().toString();
            dto.updatedAt = state.getUpdatedAt().toString();
            dto.metadata = state.getMetadata();
            return dto;
        }

        public CardApplicationState toDomain() {
            return CardApplicationState.reconstruct(
                    customerId,
                    StateType.valueOf(currentStep),
                    documentCount,
                    Instant.parse(startedAt),
                    Instant.parse(updatedAt),
                    metadata);
        }

        // Getters/Setters for Jackson
        public String getCustomerId() {
            return customerId;
        }

        public void setCustomerId(String customerId) {
            this.customerId = customerId;
        }

        public String getCurrentStep() {
            return currentStep;
        }

        public void setCurrentStep(String currentStep) {
            this.currentStep = currentStep;
        }

        public int getDocumentCount() {
            return documentCount;
        }

        public void setDocumentCount(int documentCount) {
            this.documentCount = documentCount;
        }

        public String getStartedAt() {
            return startedAt;
        }

        public void setStartedAt(String startedAt) {
            this.startedAt = startedAt;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(String updatedAt) {
            this.updatedAt = updatedAt;
        }

        public Map<String, String> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, String> metadata) {
            this.metadata = metadata;
        }
    }
}
