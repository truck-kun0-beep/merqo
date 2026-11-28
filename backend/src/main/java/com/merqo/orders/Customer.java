package com.merqo.orders;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Persistent customer record keyed by normalized Bangladeshi mobile number ({@code 01XXXXXXXXX}).
 */
@Entity
@Table(name = "customers")
public class Customer {

    @Id private UUID id;

    @Column(name = "normalized_phone", nullable = false, length = 20)
    private String normalizedPhone;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Customer() {}

    public Customer(
            UUID id, String normalizedPhone, String name, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.normalizedPhone = normalizedPhone;
        this.name = name;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getNormalizedPhone() {
        return normalizedPhone;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
