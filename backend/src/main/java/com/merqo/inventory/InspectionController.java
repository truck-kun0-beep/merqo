package com.merqo.inventory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.access.AccessContext;
import com.merqo.user.CurrentUserService;

/**
 * REST endpoints for listing and resolving stock held in inspection.
 *
 * <p>Responsibility: expose {@code GET /api/v1/inventory/inspections} and {@code POST
 * /api/v1/inventory/inspections/resolve} under {@link InspectionService}.
 *
 * <p>OOP concept: <b>Thin Controller &amp; Delegation</b> — maps HTTP requests to {@link
 * InspectionService}.
 */
@RestController
@RequestMapping("/api/v1/inventory/inspections")
@Tag(name = "Inspections", description = "View and resolve returned items held in inspection")
public class InspectionController {

    private final InspectionService inspectionService;
    private final CurrentUserService currentUserService;

    public InspectionController(
            InspectionService inspectionService, CurrentUserService currentUserService) {
        this.inspectionService = inspectionService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    @Operation(summary = "List items currently in inspection within caller scope")
    public List<Map<String, Object>> listInspections(
            @RequestParam(required = false) UUID locationId) {
        AccessContext context = currentUserService.requireAccessContext();
        return inspectionService.listInspections(context, locationId);
    }

    @PostMapping("/resolve")
    @Operation(summary = "Resolve inspected units as RESTOCK or WRITE_OFF")
    public Map<String, Object> resolve(@Valid @RequestBody ResolveInspectionRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        return inspectionService.resolve(
                context,
                request.locationId(),
                request.variantId(),
                request.quantity(),
                request.outcome(),
                request.note());
    }

    public record ResolveInspectionRequest(
            @NotNull UUID locationId,
            @NotNull UUID variantId,
            int quantity,
            @NotNull InspectionService.ResolutionOutcome outcome,
            String note) {}
}
