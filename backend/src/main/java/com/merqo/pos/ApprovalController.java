package com.merqo.pos;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.access.AccessContext;
import com.merqo.user.CurrentUserService;

/**
 * REST endpoint for issuing single-use manager discount approval tokens.
 *
 * <p>Responsibility: expose {@code POST /api/v1/pos/approvals} and delegate verification,
 * throttling, and token generation to {@link ApprovalService}.
 *
 * <p>OOP concept: <b>Thin Controller</b> — handles HTTP serialization only.
 */
@RestController
@RequestMapping("/api/v1/pos/approvals")
@Tag(name = "POS Approvals", description = "Issue single-use manager discount approval tokens")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final CurrentUserService currentUserService;

    public ApprovalController(
            ApprovalService approvalService, CurrentUserService currentUserService) {
        this.approvalService = approvalService;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    @Operation(summary = "Verify a manager approval PIN and issue a single-use approval token")
    public ResponseEntity<Map<String, Object>> approve(
            @Valid @RequestBody ApprovalRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        Map<String, Object> body =
                approvalService.issueApprovalToken(
                        context,
                        request.locationId(),
                        request.pin(),
                        request.maxDiscountPercent(),
                        null);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    public record ApprovalRequest(
            @NotNull UUID locationId,
            @NotBlank String pin,
            @NotNull BigDecimal maxDiscountPercent) {}
}
