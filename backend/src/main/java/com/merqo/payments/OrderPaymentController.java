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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.payments.PaymentDtos.ClaimedAdvanceView;
import com.merqo.payments.PaymentDtos.OrderPaymentView;
import com.merqo.payments.PaymentDtos.OrderPaymentsDetailResponse;
import com.merqo.payments.PaymentDtos.PendingPaymentView;
import com.merqo.payments.PaymentDtos.ProviderCheckResponse;
import com.merqo.user.CurrentUserService;

/** Authenticated staff endpoints for order advance payment recording and verification. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Order Payments", description = "Record, verify, and check order advance payments")
public class OrderPaymentController {

    private final OrderPaymentService paymentService;
    private final CurrentUserService currentUserService;

    public OrderPaymentController(
            OrderPaymentService paymentService, CurrentUserService currentUserService) {
        this.paymentService = paymentService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/orders/{id}/payments")
    @Operation(summary = "View payments, derived status/decisions, and order payment position")
    public OrderPaymentsDetailResponse listForOrder(@PathVariable UUID id) {
        return paymentService.getOrderPayments(currentUserService.requireAccessContext(), id);
    }

    @GetMapping("/orders/{id}/payments/claim-prefill")
    @Operation(summary = "Prefill details from a claimed customer advance on an order")
    public ClaimedAdvanceView claimPrefill(@PathVariable UUID id) {
        return paymentService.getClaimPrefill(currentUserService.requireAccessContext(), id);
    }

    @PostMapping("/orders/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a customer payment against an order")
    public OrderPaymentView record(
            @PathVariable UUID id,
            @Valid @RequestBody RecordPaymentRequest body,
            HttpServletRequest request) {
        return paymentService.recordPayment(
                currentUserService.requireAccessContext(),
                id,
                body.method(),
                body.amount(),
                body.reference(),
                body.payerNote(),
                body.fromClaim(),
                request);
    }

    @PostMapping("/payments/{id}/verify")
    @Operation(summary = "Manually verify or reject a pending payment with immutable evidence")
    public OrderPaymentView verify(
            @PathVariable UUID id,
            @Valid @RequestBody VerifyPaymentRequest body,
            HttpServletRequest request) {
        return paymentService.verifyPayment(
                currentUserService.requireAccessContext(),
                id,
                body.decision(),
                body.confirmedAmount(),
                body.evidence(),
                request);
    }

    @PostMapping("/payments/{id}/check")
    @Operation(summary = "Check a pending payment using the configured PaymentVerifier strategy")
    public ProviderCheckResponse check(@PathVariable UUID id, HttpServletRequest request) {
        return paymentService.checkWithProvider(
                currentUserService.requireAccessContext(), id, request);
    }

    @GetMapping("/payments/pending")
    @Operation(summary = "List pending verification payments oldest-first with overdue flag")
    public List<PendingPaymentView> pending() {
        return paymentService.listPending(currentUserService.requireAccessContext());
    }

    public record RecordPaymentRequest(
            @NotBlank String method,
            @NotNull BigDecimal amount,
            String reference,
            String payerNote,
            Boolean fromClaim) {}

    public record VerifyPaymentRequest(
            @NotBlank String decision, BigDecimal confirmedAmount, String evidence) {}
}
