package com.merqo.integration;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Registered external website API client holding a public key id and an AES-GCM encrypted secret.
 */
@Entity
@Table(name = "integration_clients")
public class IntegrationClient {

    @Id private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "key_id", nullable = false, length = 80)
    private String keyId;

    @Column(name = "secret_encrypted", nullable = false)
    private String secretEncrypted;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    protected IntegrationClient() {}

    public IntegrationClient(
            UUID id,
            String name,
            String keyId,
            String secretEncrypted,
            boolean active,
            UUID createdBy,
            Instant createdAt,
            String demoKey) {
        this.id = id;
        this.name = name;
        this.keyId = keyId;
        this.secretEncrypted = secretEncrypted;
        this.active = active;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.demoKey = demoKey;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getKeyId() {
        return keyId;
    }

    public String getSecretEncrypted() {
        return secretEncrypted;
    }

    public void setSecretEncrypted(String secretEncrypted) {
        this.secretEncrypted = secretEncrypted;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Instant getRotatedAt() {
        return rotatedAt;
    }

    public void setRotatedAt(Instant rotatedAt) {
        this.rotatedAt = rotatedAt;
    }

    public String getDemoKey() {
        return demoKey;
    }
}
