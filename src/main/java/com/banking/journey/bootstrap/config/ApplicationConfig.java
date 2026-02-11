package com.banking.journey.bootstrap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.banking.journey.application.port.out.ActionPublisher;
import com.banking.journey.application.port.out.EventStore;
import com.banking.journey.application.port.out.StateStore;
import com.banking.journey.application.service.CardApplicationOrchestrator;
import com.banking.journey.application.service.StateMachineEngine;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Application-level bean configuration.
 * <p>
 * Wires application services (domain layer) with adapter implementations
 * via constructor injection, maintaining hexagonal architecture boundaries.
 * </p>
 */
@Configuration
public class ApplicationConfig {

    /**
     * Jackson ObjectMapper configured for production use.
     * - Java 8 Time support (Instant, LocalDateTime, etc.)
     * - Snake_case property naming
     * - Lenient deserialization (ignore unknown properties)
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
        return mapper;
    }

    /**
     * StateMachineEngine — stateless domain service.
     * No dependencies, pure business logic.
     */
    @Bean
    public StateMachineEngine stateMachineEngine() {
        return new StateMachineEngine();
    }

    /**
     * CardApplicationOrchestrator — the core use-case implementation.
     * Wired with all outbound port implementations via DI.
     */
    @Bean
    public CardApplicationOrchestrator cardApplicationOrchestrator(
            EventStore eventStore,
            StateStore stateStore,
            StateMachineEngine stateMachineEngine,
            ActionPublisher actionPublisher) {
        return new CardApplicationOrchestrator(
                eventStore, stateStore, stateMachineEngine, actionPublisher);
    }
}
