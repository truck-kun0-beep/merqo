package com.merqo.integration;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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

import com.merqo.integration.IntegrationClientService.IntegrationClientSecretResponse;
import com.merqo.integration.IntegrationClientService.IntegrationClientSummary;
import com.merqo.user.CurrentUserService;

/** Owner-only endpoints for creating, rotating, disabling, and enabling integration API keys. */
@RestController
@RequestMapping("/api/v1/integration/clients")
@Tag(name = "Integrations", description = "Website API key management (owner only)")
public class IntegrationClientController {

    private final IntegrationClientService clientService;
    private final CurrentUserService currentUserService;

    public IntegrationClientController(
            IntegrationClientService clientService, CurrentUserService currentUserService) {
        this.clientService = clientService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    @Operation(summary = "List integration API clients (never returns secrets)")
    public List<IntegrationClientSummary> list() {
        return clientService.list(currentUserService.requireAccessContext());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an integration API client (returns plaintext secret once)")
    public IntegrationClientSecretResponse create(
            @Valid @RequestBody CreateIntegrationClientRequest body, HttpServletRequest request) {
        return clientService.create(
                currentUserService.requireAccessContext(), body.name(), request);
    }

    @PostMapping("/{id}/rotate")
    @Operation(summary = "Rotate an integration API client secret (returns new secret once)")
    public IntegrationClientSecretResponse rotate(
            @PathVariable UUID id, HttpServletRequest request) {
        return clientService.rotate(currentUserService.requireAccessContext(), id, request);
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Disable an integration API client")
    public IntegrationClientSummary disable(@PathVariable UUID id, HttpServletRequest request) {
        return clientService.setActive(
                currentUserService.requireAccessContext(), id, false, request);
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "Re-enable an integration API client")
    public IntegrationClientSummary enable(@PathVariable UUID id, HttpServletRequest request) {
        return clientService.setActive(
                currentUserService.requireAccessContext(), id, true, request);
    }

    public record CreateIntegrationClientRequest(@NotBlank @Size(max = 120) String name) {}
}
