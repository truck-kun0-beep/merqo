package com.merqo.integration;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Per-client API request timestamp used for DB-backed rate limiting (60 requests/min). */
@Entity
@Table(name = "integration_rate_attempts")
public class IntegrationRateAttempt {

    @Id private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    protected IntegrationRateAttempt() {}

    public IntegrationRateAttempt(UUID id, UUID clientId, Instant attemptedAt) {
        this.id = id;
        this.clientId = clientId;
        this.attemptedAt = attemptedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getClientId() {
        return clientId;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }
}
