package com.athlete.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Event-sourced order service: owns the order lifecycle, persists immutable domain events to
 * PostgreSQL, publishes integration events via a transactional outbox + polling relay, and builds
 * read-model projections. Scheduling drives the relay and the projector.
 */
@SpringBootApplication
@EnableScheduling
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
