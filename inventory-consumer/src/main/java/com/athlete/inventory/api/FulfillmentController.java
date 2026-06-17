package com.athlete.inventory.api;

import com.athlete.inventory.consumer.FulfillmentStore;
import com.athlete.inventory.consumer.FulfillmentStore.FulfillmentView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read endpoint exposing the downstream fulfillment view built from order events. */
@RestController
@RequestMapping("/api/fulfillment")
public class FulfillmentController {

    private final FulfillmentStore fulfillment;

    public FulfillmentController(FulfillmentStore fulfillment) {
        this.fulfillment = fulfillment;
    }

    @GetMapping("/orders")
    public List<FulfillmentView> orders() {
        return fulfillment.findAll();
    }
}
