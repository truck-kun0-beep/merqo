package com.merqo.pos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.access.AccessContext;
import com.merqo.catalog.domain.DiscountType;
import com.merqo.common.web.PageRequest;
import com.merqo.common.web.PageResponse;
import com.merqo.user.CurrentUserService;

/**
 * REST endpoints for POS sales, receipts, duplicate reprints, sales history, and receipt lookup.
 *
 * <p>Responsibility: map HTTP requests under {@code /api/v1/pos/sales} to {@link SaleService}.
 *
 * <p>OOP concept: <b>Thin Controller &amp; Delegation</b> — holds no pricing, stock, or scope
 * logic.
 */
@RestController
@RequestMapping("/api/v1/pos/sales")
@Tag(
        name = "POS Sales",
        description = "Complete POS sales, view receipts, reprint, and search history")
public class SaleController {

    private final SaleService saleService;
    private final CurrentUserService currentUserService;

    public SaleController(SaleService saleService, CurrentUserService currentUserService) {
        this.saleService = saleService;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    @Operation(summary = "Complete a POS sale with server-side pricing and stock removal")
    public ResponseEntity<Map<String, Object>> createSale(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateSaleRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        SaleService.CreateSaleCommand command = toCommand(request);
        SaleService.SaleCreationOutcome outcome =
                saleService.createSale(context, command, idempotencyKey);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.body());
    }

    @GetMapping
    @Operation(summary = "Search POS sales history within caller scope")
    public PageResponse<Map<String, Object>> listSales(
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate toDate,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        AccessContext context = currentUserService.requireAccessContext();
        PageRequest pageRequest = PageRequest.of(page, size, "createdAt", PageRequest.DESC);
        return saleService.listSales(
                context, locationId, fromDate, toDate, q, pageRequest.toSpring());
    }

    @GetMapping("/lookup")
    @Operation(summary = "Look up a sale by exact receipt number (case-insensitive)")
    public Map<String, Object> lookupByReceipt(@RequestParam String receiptNumber) {
        AccessContext context = currentUserService.requireAccessContext();
        return saleService.lookupByReceipt(context, receiptNumber);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a POS sale by id")
    public Map<String, Object> getSale(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        return saleService.getSale(context, id);
    }

    @GetMapping("/{id}/receipt")
    @Operation(summary = "Get receipt payload for a sale without logging a reprint")
    public Map<String, Object> getReceipt(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        return saleService.getReceipt(context, id);
    }

    @PostMapping("/{id}/reprint")
    @Operation(summary = "Log a duplicate receipt reprint and return receipt payload")
    public Map<String, Object> reprintReceipt(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        return saleService.reprintReceipt(context, id);
    }

    private SaleService.CreateSaleCommand toCommand(CreateSaleRequest request) {
        List<SaleService.SaleLineCommand> lines = new ArrayList<>();
        if (request.lines() != null) {
            for (CreateSaleLineRequest l : request.lines()) {
                DiscountType type =
                        l.manualDiscount() != null
                                ? l.manualDiscount().type()
                                : l.manualDiscountType();
                BigDecimal value =
                        l.manualDiscount() != null
                                ? l.manualDiscount().value()
                                : l.manualDiscountValue();
                lines.add(
                        new SaleService.SaleLineCommand(l.variantId(), l.quantity(), type, value));
            }
        }
        DiscountType billType =
                request.billDiscount() != null
                        ? request.billDiscount().type()
                        : request.billDiscountType();
        BigDecimal billValue =
                request.billDiscount() != null
                        ? request.billDiscount().value()
                        : request.billDiscountValue();

        SaleService.PaymentCommand payment = null;
        if (request.payment() != null) {
            BigDecimal tendered =
                    request.payment().tendered() != null
                            ? request.payment().tendered()
                            : request.payment().amountGiven();
            String reference = request.payment().reference();
            if (reference == null) {
                reference =
                        request.payment().trxId() != null
                                ? request.payment().trxId()
                                : request.payment().cardReference();
            }
            payment =
                    new SaleService.PaymentCommand(request.payment().method(), tendered, reference);
        }

        return new SaleService.CreateSaleCommand(
                request.locationId(),
                lines,
                billType,
                billValue,
                request.approvalToken(),
                payment,
                request.idempotencyKey(),
                request.note());
    }

    public record DiscountInput(DiscountType type, BigDecimal value) {}

    public record CreateSaleLineRequest(
            @NotNull UUID variantId,
            int quantity,
            DiscountType manualDiscountType,
            BigDecimal manualDiscountValue,
            DiscountInput manualDiscount) {}

    public record CreateSalePaymentRequest(
            String method,
            BigDecimal tendered,
            BigDecimal amountGiven,
            String reference,
            String trxId,
            String cardReference) {}

    public record CreateSaleRequest(
            @NotNull UUID locationId,
            List<@Valid CreateSaleLineRequest> lines,
            DiscountType billDiscountType,
            BigDecimal billDiscountValue,
            DiscountInput billDiscount,
            String approvalToken,
            @Valid CreateSalePaymentRequest payment,
            String idempotencyKey,
            String note) {}
}
