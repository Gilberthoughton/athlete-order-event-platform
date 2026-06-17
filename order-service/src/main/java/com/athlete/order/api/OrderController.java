package com.athlete.order.api;

import com.athlete.order.api.dto.CancelOrderRequest;
import com.athlete.order.api.dto.OrderResponse;
import com.athlete.order.api.dto.PlaceOrderRequest;
import com.athlete.order.application.OrderApplicationService;
import com.athlete.order.application.OrderNotFoundException;
import com.athlete.order.domain.model.OrderId;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** Command + query endpoints for orders. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderApplicationService orders;

    public OrderController(OrderApplicationService orders) {
        this.orders = orders;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> place(@Valid @RequestBody PlaceOrderRequest request) {
        OrderId orderId = orders.placeOrder(request.toCommand());
        OrderResponse body = orders.find(orderId)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return ResponseEntity.created(URI.create("/api/orders/" + orderId)).body(body);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        OrderId orderId = new OrderId(id);
        return orders.find(orderId)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable UUID id, @RequestBody(required = false) CancelOrderRequest request) {
        OrderId orderId = new OrderId(id);
        String reason = request == null ? "CSR_REQUEST" : request.reasonOrDefault();
        orders.cancel(orderId, reason);
        return orders.find(orderId)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
