package com.merqo.payments;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.common.web.PageRequest;
import com.merqo.common.web.PageResponse;
import com.merqo.payments.PaymentDtos.PaymentHistoryRowView;
import com.merqo.payments.PaymentDtos.PaymentSummaryResponse;
import com.merqo.user.CurrentUserService;

/** Read-only endpoints for scoped unified payment history and summary aggregates. */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payment History", description = "Unified read-only payments history and summary")
public class PaymentHistoryController {

    private final PaymentHistoryService historyService;
    private final CurrentUserService currentUserService;

    public PaymentHistoryController(
            PaymentHistoryService historyService, CurrentUserService currentUserService) {
        this.historyService = historyService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/history")
    @Operation(summary = "Unified, scoped, paginated payments history newest-first")
    public PageResponse<PaymentHistoryRowView> history(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "method", required = false) String method,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "locationId", required = false) UUID locationId,
            @RequestParam(name = "fromDate", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate fromDate,
            @RequestParam(name = "toDate", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate toDate,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return historyService.history(
                currentUserService.requireAccessContext(),
                type,
                method,
                status,
                locationId,
                fromDate,
                toDate,
                q,
                PageRequest.of(page, size, "occurredAt", PageRequest.DESC));
    }

    @GetMapping("/summary")
    @Operation(summary = "Scoped payment counts and totals across the filtered range")
    public PaymentSummaryResponse summary(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "method", required = false) String method,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "locationId", required = false) UUID locationId,
            @RequestParam(name = "fromDate", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate fromDate,
            @RequestParam(name = "toDate", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate toDate,
            @RequestParam(name = "q", required = false) String q) {
        return historyService.summary(
                currentUserService.requireAccessContext(),
                type,
                method,
                status,
                locationId,
                fromDate,
                toDate,
                q);
    }
}
