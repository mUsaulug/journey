package com.banking.journey.bootstrap.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.banking.journey.application.port.out.ActionPublisher;
import com.banking.journey.application.port.out.EventStore;
import com.banking.journey.application.port.out.StateStore;
import com.banking.journey.application.service.CardApplicationOrchestrator;
import com.banking.journey.application.service.StateMachineEngine;
import com.banking.journey.domain.entity.CardApplicationState;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
@EnableConfigurationProperties(JourneyProperties.class)
public class ApplicationConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
        return mapper;
    }

    @Bean
    public StateMachineEngine stateMachineEngine(JourneyProperties journeyProperties) {
        CardApplicationState.configureRequiredDocumentCount(journeyProperties.getRequiredDocumentCount());
        return new StateMachineEngine(journeyProperties);
    }

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
