package com.banking.journey.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Mini Banking Journey Orchestrator - Application Entry Point.
 * <p>
 * Evam-style real-time customer journey orchestration system.
 * Implements hexagonal architecture with Kafka, Redis, and PostgreSQL.
 * </p>
 *
 * <pre>
 * Architecture: Hexagonal (Ports & Adapters)
 * Pattern:      Event-Driven (Sense-Analyze-Act)
 * Tech:         Spring Boot 3.2 + Kafka + Redis + PostgreSQL
 * </pre>
 */
@SpringBootApplication(scanBasePackages = "com.banking.journey")
public class JourneyOrchestratorApp {

    public static void main(String[] args) {
        SpringApplication.run(JourneyOrchestratorApp.class, args);
    }
}
