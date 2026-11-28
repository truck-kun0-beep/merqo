package com.merqo.payments;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.payments.PaymentDtos.RefundDueRowView;
import com.merqo.payments.PaymentDtos.RefundView;
import com.merqo.payments.domain.RefundStatus;
import com.merqo.user.CurrentUserService;

/** Authenticated endpoints for online/manual order refund workflows. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Refunds", description = "Order refund requests, four-eyes approvals, and payouts")
public class RefundController {

    private final RefundService refundService;
    private final CurrentUserService currentUserService;

    public RefundController(RefundService refundService, CurrentUserService currentUserService) {
        this.refundService = refundService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/orders/{id}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Request a refund against verified advance payments on an order")
    public RefundView requestRefund(
            @PathVariable UUID id,
            @Valid @RequestBody CreateRefundRequest body,
            HttpServletRequest request) {
        return refundService.requestRefund(
                currentUserService.requireAccessContext(),
                id,
                body.amount(),
                body.reason(),
                body.note(),
                request);
    }

    @PostMapping("/refunds/{id}/approve")
    @Operation(summary = "Approve a requested refund (four-eyes enforced for non-owners)")
    public RefundView approve(
            @PathVariable UUID id,
            @RequestBody(required = false) RefundDecisionRequest body,
            HttpServletRequest request) {
        return refundService.approve(
                currentUserService.requireAccessContext(),
                id,
                body == null ? null : body.note(),
                request);
    }

    @PostMapping("/refunds/{id}/reject")
    @Operation(summary = "Reject a requested or approved refund with an explanatory note")
    public RefundView reject(
            @PathVariable UUID id,
            @RequestBody(required = false) RefundDecisionRequest body,
            HttpServletRequest request) {
        return refundService.reject(
                currentUserService.requireAccessContext(),
                id,
                body == null ? null : body.note(),
                request);
    }

    @PostMapping("/refunds/{id}/pay")
    @Operation(summary = "Record completed payout for an approved refund")
    public RefundView pay(
            @PathVariable UUID id,
            @Valid @RequestBody PayRefundRequest body,
            HttpServletRequest request) {
        return refundService.pay(
                currentUserService.requireAccessContext(),
                id,
                body.payoutMethod(),
                body.payoutReference(),
                body.note(),
                request);
    }

    @GetMapping("/refunds")
    @Operation(summary = "List scoped refunds filtered by derived status or order number")
    public List<RefundView> list(
            @RequestParam(name = "status", required = false) RefundStatus status,
            @RequestParam(name = "orderNumber", required = false) String orderNumber) {
        return refundService.listRefunds(
                currentUserService.requireAccessContext(), status, orderNumber);
    }

    @GetMapping("/refunds/due")
    @Operation(
            summary =
                    "List cancelled or declined orders holding unrefunded balance without open requests")
    public List<RefundDueRowView> due() {
        return refundService.listDueRefunds(currentUserService.requireAccessContext());
    }

    public record CreateRefundRequest(
            @NotNull BigDecimal amount, @NotBlank String reason, String note) {}

    public record RefundDecisionRequest(String note) {}

    public record PayRefundRequest(
            @NotBlank String payoutMethod, String payoutReference, String note) {}
}
