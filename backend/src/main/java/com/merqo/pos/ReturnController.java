package com.merqo.pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.access.AccessContext;
import com.merqo.user.CurrentUserService;

/**
 * REST endpoints for POS sale returns and refunds.
 *
 * <p>Responsibility: expose {@code POST /api/v1/pos/sales/{id}/returns} and {@code GET
 * /api/v1/pos/sales/{id}/returns} and delegate all business rules to {@link ReturnService}.
 *
 * <p>OOP concept: <b>Thin Controller &amp; Delegation</b> — translates HTTP requests into service
 * commands.
 */
@RestController
@RequestMapping("/api/v1/pos/sales/{saleId}/returns")
@Tag(name = "POS Returns", description = "Create and view returns against POS sales")
public class ReturnController {

    private final ReturnService returnService;
    private final CurrentUserService currentUserService;

    public ReturnController(ReturnService returnService, CurrentUserService currentUserService) {
        this.returnService = returnService;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    @Operation(summary = "Create a partial or full return for a POS sale")
    public ResponseEntity<Map<String, Object>> createReturn(
            @PathVariable UUID saleId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateReturnRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        List<ReturnService.ReturnLineCommand> lines = new ArrayList<>();
        if (request.lines() != null) {
            for (ReturnLineRequest l : request.lines()) {
                lines.add(
                        new ReturnService.ReturnLineCommand(
                                l.saleLineId(), l.quantity(), l.disposition()));
            }
        }
        ReturnService.CreateReturnCommand command =
                new ReturnService.CreateReturnCommand(
                        request.reason(), request.note(), lines, request.idempotencyKey());
        ReturnService.ReturnCreationOutcome outcome =
                returnService.createReturn(context, saleId, command, idempotencyKey);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.body());
    }

    @GetMapping
    @Operation(summary = "List returns created against a POS sale")
    public List<Map<String, Object>> listReturns(@PathVariable UUID saleId) {
        AccessContext context = currentUserService.requireAccessContext();
        return returnService.listForSale(context, saleId);
    }

    public record ReturnLineRequest(
            @NotNull UUID saleLineId, int quantity, ReturnDisposition disposition) {}

    public record CreateReturnRequest(
            @NotNull ReturnReason reason,
            String note,
            List<@Valid ReturnLineRequest> lines,
            String idempotencyKey) {}
}
