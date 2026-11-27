package com.merqo.pos;

import java.math.BigDecimal;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.access.AccessContext;
import com.merqo.user.CurrentUserService;

/**
 * REST endpoints for POS cash drawer sessions.
 *
 * <p>Responsibility: expose open, current, summary, and close endpoints under {@code
 * /api/v1/pos/sessions} and delegate all rules to {@link CashSessionService}.
 *
 * <p>OOP concept: <b>Thin Controller &amp; Delegation</b> — translates HTTP requests into service
 * calls without embedding business rules.
 */
@RestController
@RequestMapping("/api/v1/pos/sessions")
@Tag(name = "POS Cash Sessions", description = "Open, inspect, and close cashier cash sessions")
public class CashSessionController {

    private final CashSessionService cashSessionService;
    private final CurrentUserService currentUserService;

    public CashSessionController(
            CashSessionService cashSessionService, CurrentUserService currentUserService) {
        this.cashSessionService = cashSessionService;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    @Operation(summary = "Open a cash session for the signed-in cashier at a branch")
    public ResponseEntity<Map<String, Object>> open(
            @Valid @RequestBody OpenCashSessionRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        Map<String, Object> body =
                cashSessionService.open(context, request.locationId(), request.openingCash());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/current")
    @Operation(summary = "Get the signed-in cashier's currently open cash session")
    public Map<String, Object> current(@RequestParam(required = false) UUID locationId) {
        AccessContext context = currentUserService.requireAccessContext();
        return cashSessionService.current(context, locationId);
    }

    @GetMapping("/{id}/summary")
    @Operation(summary = "Get live expected figures or closed reconciliation for a cash session")
    public Map<String, Object> summary(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        return cashSessionService.summary(context, id);
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Close an open cash session with counted drawer reconciliation")
    public Map<String, Object> close(
            @PathVariable UUID id, @Valid @RequestBody CloseCashSessionRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        return cashSessionService.close(
                context,
                id,
                request.countedCash(),
                request.countedBkash(),
                request.countedNagad(),
                request.countedCard(),
                request.note());
    }

    public record OpenCashSessionRequest(
            @NotNull UUID locationId, @NotNull BigDecimal openingCash) {}

    public record CloseCashSessionRequest(
            @NotNull BigDecimal countedCash,
            BigDecimal countedBkash,
            BigDecimal countedNagad,
            BigDecimal countedCard,
            String note) {}
}
