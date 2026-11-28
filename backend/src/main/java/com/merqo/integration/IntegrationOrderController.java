package com.merqo.integration;

import jakarta.servlet.http.HttpServletRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.orders.OrderIntakeService;
import com.merqo.orders.OrderIntakeService.ExternalOrderStatusView;
import com.merqo.orders.OrderIntakeService.OrderIntakeOutcome;
import com.merqo.orders.OrderIntakeService.OrderIntakeView;

/** External website order submission and status polling endpoints protected by HMAC signatures. */
@RestController
@RequestMapping("/integration/v1/orders")
@Tag(name = "Website Integration", description = "HMAC-authenticated website order intake API")
public class IntegrationOrderController {

    private final OrderIntakeService orderIntakeService;

    public IntegrationOrderController(OrderIntakeService orderIntakeService) {
        this.orderIntakeService = orderIntakeService;
    }

    @PostMapping
    @Operation(summary = "Submit an online order from an authenticated external website")
    public ResponseEntity<OrderIntakeResponse> receive(
            @RequestAttribute(IntegrationAuthFilter.CLIENT_REQUEST_ATTRIBUTE)
                    IntegrationClient client,
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request) {
        OrderIntakeOutcome outcome = orderIntakeService.receiveOrder(client, rawBody, request);
        HttpStatus status = outcome.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .body(OrderIntakeResponse.from(outcome.order(), outcome.idempotentReplay()));
    }

    @GetMapping("/{externalOrderId}")
    @Operation(summary = "Check status of an external order scoped to this integration client")
    public ExternalOrderStatusView status(
            @RequestAttribute(IntegrationAuthFilter.CLIENT_REQUEST_ATTRIBUTE)
                    IntegrationClient client,
            @PathVariable String externalOrderId) {
        return orderIntakeService.getStatusForClient(client, externalOrderId);
    }

    public record OrderIntakeResponse(
            java.util.UUID orderId,
            String orderNumber,
            String externalOrderId,
            com.merqo.orders.domain.OrderStatus status,
            boolean priceMismatch,
            boolean invalidPhone,
            boolean idempotentReplay,
            java.math.BigDecimal total,
            java.time.Instant createdAt) {

        static OrderIntakeResponse from(OrderIntakeView view, boolean replay) {
            return new OrderIntakeResponse(
                    view.orderId(),
                    view.orderNumber(),
                    view.externalOrderId(),
                    view.status(),
                    view.priceMismatch(),
                    view.invalidPhone(),
                    replay,
                    view.total(),
                    view.createdAt());
        }
    }
}
