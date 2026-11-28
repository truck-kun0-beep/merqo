package com.merqo.payments;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.payments.CodService.SettlementLineRequest;
import com.merqo.payments.PaymentDtos.CodCollectionView;
import com.merqo.payments.PaymentDtos.CodOutstandingResponse;
import com.merqo.payments.PaymentDtos.SettlementDetailView;
import com.merqo.payments.PaymentDtos.SettlementSummaryView;
import com.merqo.user.CurrentUserService;

/** Authenticated endpoints for COD collections and courier settlements. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "COD & Settlements", description = "COD collections, settlements, and outstanding gaps")
public class CodController {

    private final CodService codService;
    private final CurrentUserService currentUserService;

    public CodController(CodService codService, CurrentUserService currentUserService) {
        this.codService = codService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/orders/{id}/cod-collections")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a COD collection on a confirmed order")
    public CodCollectionView recordCollection(
            @PathVariable UUID id,
            @Valid @RequestBody RecordCodCollectionRequest body,
            HttpServletRequest request) {
        return codService.recordCollection(
                currentUserService.requireAccessContext(),
                id,
                body.courier(),
                body.amount(),
                body.collectedOn(),
                body.note(),
                request);
    }

    @PostMapping("/cod/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record an all-or-nothing courier settlement batch")
    public SettlementDetailView recordSettlement(
            @Valid @RequestBody CreateSettlementRequest body, HttpServletRequest request) {
        return codService.recordSettlement(
                currentUserService.requireAccessContext(),
                body.courier(),
                body.reference(),
                body.receivedOn(),
                body.receiveMethod(),
                body.amountReceived(),
                body.note(),
                body.lines(),
                request);
    }

    @GetMapping("/cod/outstanding")
    @Operation(summary = "View derived COD expected, collected, with-courier, and gap balances")
    public CodOutstandingResponse outstanding() {
        return codService.getOutstanding(currentUserService.requireAccessContext());
    }

    @GetMapping("/cod/settlements")
    @Operation(summary = "List courier settlement batches newest-first")
    public List<SettlementSummaryView> listSettlements() {
        return codService.listSettlements(currentUserService.requireAccessContext());
    }

    @GetMapping("/cod/settlements/{id}")
    @Operation(summary = "Inspect full settlement detail and allocated order lines")
    public SettlementDetailView getSettlement(@PathVariable UUID id) {
        return codService.getSettlement(currentUserService.requireAccessContext(), id);
    }

    public record RecordCodCollectionRequest(
            @NotBlank String courier,
            @NotNull BigDecimal amount,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate collectedOn,
            String note) {}

    public record CreateSettlementRequest(
            @NotBlank String courier,
            @NotBlank String reference,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedOn,
            @NotBlank String receiveMethod,
            @NotNull BigDecimal amountReceived,
            String note,
            @NotEmpty List<@Valid SettlementLineRequest> lines) {}
}
