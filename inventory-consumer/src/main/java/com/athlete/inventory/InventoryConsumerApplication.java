package com.athlete.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Downstream consumer of the public order integration events. It shares no code with the order
 * service — it depends only on the wire contract — and maintains its own read model, deduplicating
 * events to process each effectively once (ADR 0008, ADR 0010).
 */
@SpringBootApplication
public class InventoryConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryConsumerApplication.class, args);
    }
}
