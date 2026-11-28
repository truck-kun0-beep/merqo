package com.merqo.integration;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;

/**
 * Manages external website integration API keys for the owner.
 *
 * <p>Plaintext HMAC secrets are generated with {@link SecureRandom}, encrypted via AES-GCM before
 * persistence, returned once on creation or rotation, and never serialized in lists or audit logs.
 */
@Service
public class IntegrationClientService {

    private final IntegrationClientRepository clientRepository;
    private final SecretCipher secretCipher;
    private final AuditService auditService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public IntegrationClientService(
            IntegrationClientRepository clientRepository,
            SecretCipher secretCipher,
            AuditService auditService,
            Clock clock) {
        this.clientRepository = clientRepository;
        this.secretCipher = secretCipher;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<IntegrationClientSummary> list(AccessContext context) {
        context.requireGlobal(Permission.SETTINGS_MANAGE);
        return clientRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(IntegrationClientSummary::from)
                .toList();
    }

    @Transactional
    public IntegrationClientSecretResponse create(
            AccessContext context, String rawName, HttpServletRequest request) {
        context.requireGlobal(Permission.SETTINGS_MANAGE);
        String name = normalizeName(rawName);
        String keyId = generateKeyId();
        String secret = generateSecret();
        String encrypted = secretCipher.encrypt(secret);
        Instant now = Instant.now(clock);

        IntegrationClient client =
                new IntegrationClient(
                        UUID.randomUUID(),
                        name,
                        keyId,
                        encrypted,
                        true,
                        context.getUserId(),
                        now,
                        null);
        clientRepository.save(client);

        auditService.record(
                new AuditEvent.Builder(AuditAction.INTEGRATION_CLIENT_CREATED, "integration_client")
                        .entityId(client.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("name", client.getName())
                        .added("keyId", client.getKeyId())
                        .added("active", client.isActive())
                        .build(),
                request);

        return new IntegrationClientSecretResponse(IntegrationClientSummary.from(client), secret);
    }

    @Transactional
    public IntegrationClientSecretResponse rotate(
            AccessContext context, UUID id, HttpServletRequest request) {
        context.requireGlobal(Permission.SETTINGS_MANAGE);
        IntegrationClient client =
                clientRepository
                        .findById(id)
                        .orElseThrow(() -> new NotFoundException("Integration client not found."));

        String newSecret = generateSecret();
        String encrypted = secretCipher.encrypt(newSecret);
        Instant now = Instant.now(clock);
        client.setSecretEncrypted(encrypted);
        client.setRotatedAt(now);
        clientRepository.save(client);

        auditService.record(
                new AuditEvent.Builder(AuditAction.INTEGRATION_CLIENT_ROTATED, "integration_client")
                        .entityId(client.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("keyId", client.getKeyId())
                        .added("rotatedAt", now.toString())
                        .build(),
                request);

        return new IntegrationClientSecretResponse(
                IntegrationClientSummary.from(client), newSecret);
    }

    @Transactional
    public IntegrationClientSummary setActive(
            AccessContext context, UUID id, boolean active, HttpServletRequest request) {
        context.requireGlobal(Permission.SETTINGS_MANAGE);
        IntegrationClient client =
                clientRepository
                        .findById(id)
                        .orElseThrow(() -> new NotFoundException("Integration client not found."));

        boolean previous = client.isActive();
        client.setActive(active);
        clientRepository.save(client);

        AuditAction action =
                active
                        ? AuditAction.INTEGRATION_CLIENT_ENABLED
                        : AuditAction.INTEGRATION_CLIENT_DISABLED;
        auditService.record(
                new AuditEvent.Builder(action, "integration_client")
                        .entityId(client.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("keyId", client.getKeyId())
                        .changed("active", previous, active)
                        .build(),
                request);

        return IntegrationClientSummary.from(client);
    }

    private String normalizeName(String rawName) {
        String trimmed = rawName == null ? "" : rawName.trim();
        if (trimmed.isEmpty() || trimmed.length() > 120) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "Client name must be between 1 and 120 characters.");
        }
        return trimmed;
    }

    private String generateKeyId() {
        byte[] bytes = new byte[12];
        secureRandom.nextBytes(bytes);
        return "mk_" + HexFormat.of().formatHex(bytes);
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "ms_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Public safe view of an integration client; never includes secrets. */
    public record IntegrationClientSummary(
            UUID id,
            String name,
            String keyId,
            boolean active,
            UUID createdBy,
            Instant createdAt,
            Instant lastUsedAt,
            Instant rotatedAt) {

        static IntegrationClientSummary from(IntegrationClient client) {
            return new IntegrationClientSummary(
                    client.getId(),
                    client.getName(),
                    client.getKeyId(),
                    client.isActive(),
                    client.getCreatedBy(),
                    client.getCreatedAt(),
                    client.getLastUsedAt(),
                    client.getRotatedAt());
        }
    }

    /** Returned only on client creation or secret rotation. */
    public record IntegrationClientSecretResponse(IntegrationClientSummary client, String secret) {}
}
