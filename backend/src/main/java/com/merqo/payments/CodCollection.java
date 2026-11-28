package com.merqo.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.payments.domain.CodRules.Courier;

/** Append-only Cash-on-Delivery collection entry prior to courier settlement. */
@Entity
@Table(name = "cod_collections")
public class CodCollection {

    @Id private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "courier", nullable = false, length = 20)
    private Courier courier;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "collected_on", nullable = false)
    private LocalDate collectedOn;

    @Column(name = "recorded_by", nullable = false)
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "note")
    private String note;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    protected CodCollection() {}

    public CodCollection(
            UUID id,
            UUID orderId,
            Courier courier,
            BigDecimal amount,
            LocalDate collectedOn,
            UUID recordedBy,
            Instant recordedAt,
            String note,
            String demoKey) {
        this.id = id;
        this.orderId = orderId;
        this.courier = courier;
        this.amount = amount;
        this.collectedOn = collectedOn;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
        this.note = note;
        this.demoKey = demoKey;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Courier getCourier() {
        return courier;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getCollectedOn() {
        return collectedOn;
    }

    public UUID getRecordedBy() {
        return recordedBy;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getNote() {
        return note;
    }

    public String getDemoKey() {
        return demoKey;
    }
}
