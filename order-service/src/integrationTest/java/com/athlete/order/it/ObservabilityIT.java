package com.athlete.order.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the actuator + Micrometer wiring against a running context: liveness/readiness probes
 * report UP, and the Prometheus endpoint exposes the platform's domain metrics after traffic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void health_probes_report_up() {
        assertThat(rest.getForObject("/actuator/health", JsonNode.class).get("status").asText()).isEqualTo("UP");
        assertThat(rest.getForObject("/actuator/health/liveness", JsonNode.class).get("status").asText()).isEqualTo("UP");
        assertThat(rest.getForObject("/actuator/health/readiness", JsonNode.class).get("status").asText()).isEqualTo("UP");
    }

    @Test
    void prometheus_endpoint_exposes_domain_metrics_after_an_order() {
        rest.postForEntity("/api/orders", Map.of(
                "athleteId", UUID.randomUUID().toString(),
                "lines", List.of(Map.of(
                        "sku", "SHOE-1", "quantity", 1,
                        "unitPriceAmount", 60.00, "currency", "USD", "fulfillmentType", "SHIP"))),
                String.class);

        ResponseEntity<String> scrape = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scrape.getBody())
                .contains("aoep_orders_placed_total")
                .contains("aoep_orders_confirmed_total")
                .contains("aoep_saga_completed_total");
    }
}
