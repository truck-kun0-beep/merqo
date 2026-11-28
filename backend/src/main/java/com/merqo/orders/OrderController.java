package com.merqo.orders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.common.web.PageRequest;
import com.merqo.common.web.PageResponse;
import com.merqo.orders.OrderDtos.OrderCancelResponse;
import com.merqo.orders.OrderDtos.OrderCountsView;
import com.merqo.orders.OrderDtos.OrderDetailView;
import com.merqo.orders.OrderDtos.OrderSummaryView;
import com.merqo.orders.OrderDtos.WarehouseAvailabilityResponse;
import com.merqo.orders.domain.CallResult;
import com.merqo.orders.domain.CancelReason;
import com.merqo.orders.domain.DeclineReason;
import com.merqo.orders.domain.OrderChannel;
import com.merqo.orders.domain.OrderStatus;
import com.merqo.user.CurrentUserService;

/**
 * Authenticated staff endpoints for reviewing, calling, editing, confirming, and cancelling orders.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Online and manual order fulfillment workflows")
public class OrderController {

    private final OrderStaffService orderStaffService;
    private final CurrentUserService currentUserService;

    public OrderController(
            OrderStaffService orderStaffService, CurrentUserService currentUserService) {
        this.orderStaffService = orderStaffService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    @Operation(summary = "List orders visible to the caller's warehouse scope")
    public PageResponse<OrderSummaryView> list(
            @RequestParam(name = "status", required = false) OrderStatus status,
            @RequestParam(name = "channel", required = false) OrderChannel channel,
            @RequestParam(name = "needsAction", required = false) Boolean needsAction,
            @RequestParam(name = "priceMismatch", required = false) Boolean priceMismatch,
            @RequestParam(name = "invalidPhone", required = false) Boolean invalidPhone,
            @RequestParam(name = "cancellationRequested", required = false)
                    Boolean cancellationRequested,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "fromDate", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate fromDate,
            @RequestParam(name = "toDate", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate toDate,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return orderStaffService.listOrders(
                currentUserService.requireAccessContext(),
                status,
                channel,
                needsAction,
                priceMismatch,
                invalidPhone,
                cancellationRequested,
                q,
                fromDate,
                toDate,
                PageRequest.of(page, size, "createdAt", PageRequest.DESC));
    }

    @GetMapping("/counts")
    @Operation(summary = "Order queue counts for overview and badges")
    public OrderCountsView counts() {
        return orderStaffService.counts(currentUserService.requireAccessContext());
    }

    @GetMapping("/availability")
    @Operation(summary = "Inspect warehouse online stock availability alongside soft holds")
    public WarehouseAvailabilityResponse availability(
            @RequestParam(name = "sku", required = false) List<String> sku,
            @RequestParam(name = "q", required = false) String q) {
        return orderStaffService.warehouseAvailability(
                currentUserService.requireAccessContext(), sku, q);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "View full order detail, call logs, status timeline, flags, and phone risk")
    public OrderDetailView detail(@PathVariable UUID id) {
        return orderStaffService.getDetail(currentUserService.requireAccessContext(), id);
    }

    @PostMapping("/{id}/calls")
    @Operation(summary = "Record a confirmation call attempt on an order")
    public OrderDetailView logCall(@PathVariable UUID id, @Valid @RequestBody LogCallRequest body) {
        return orderStaffService.logCall(
                currentUserService.requireAccessContext(),
                id,
                body.result(),
                body.note(),
                body.followUpAt());
    }

    @PutMapping("/{id}/customer")
    @Operation(
            summary = "Correct customer name, phone number, address, or note before confirmation")
    public OrderDetailView editCustomer(
            @PathVariable UUID id,
            @Valid @RequestBody EditCustomerRequest body,
            HttpServletRequest request) {
        return orderStaffService.editCustomer(
                currentUserService.requireAccessContext(),
                id,
                body.customerName(),
                body.phone(),
                body.address(),
                body.district(),
                body.note(),
                request);
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm order and atomically hard-reserve warehouse stock")
    public OrderDetailView confirm(
            @PathVariable UUID id,
            @RequestBody(required = false) ConfirmOrderRequest body,
            HttpServletRequest request) {
        return orderStaffService.confirm(
                currentUserService.requireAccessContext(),
                id,
                body == null ? null : body.note(),
                request);
    }

    @PostMapping("/{id}/decline")
    @Operation(summary = "Decline an unconfirmed order with standardized reason")
    public OrderDetailView decline(
            @PathVariable UUID id,
            @Valid @RequestBody DeclineOrderRequest body,
            HttpServletRequest request) {
        return orderStaffService.decline(
                currentUserService.requireAccessContext(), id, body.reason(), body.note(), request);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order and release any hard stock reservation if confirmed")
    public OrderCancelResponse cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelOrderRequest body,
            HttpServletRequest request) {
        return orderStaffService.cancel(
                currentUserService.requireAccessContext(), id, body.reason(), body.note(), request);
    }

    @PostMapping("/{id}/cancellation-request")
    @Operation(summary = "Flag an order with a customer cancellation request")
    public OrderDetailView requestCancellation(
            @PathVariable UUID id,
            @Valid @RequestBody CancellationRequestBody body,
            HttpServletRequest request) {
        return orderStaffService.requestCancellation(
                currentUserService.requireAccessContext(), id, body.note(), request);
    }

    public record LogCallRequest(@NotNull CallResult result, String note, Instant followUpAt) {}

    public record EditCustomerRequest(
            @NotBlank String customerName,
            @NotBlank String phone,
            @NotBlank String address,
            String district,
            String note) {}

    public record ConfirmOrderRequest(String note) {}

    public record DeclineOrderRequest(@NotNull DeclineReason reason, String note) {}

    public record CancelOrderRequest(@NotNull CancelReason reason, String note) {}

    public record CancellationRequestBody(@NotBlank String note) {}
}
