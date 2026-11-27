package com.merqo.pos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.pos.domain.PaymentMethod;

/**
 * Append-only JPA entity representing a POS return against a completed sale.
 *
 * <p>Responsibility: hold return header, refund method (always matching original sale payment),
 * refund total, optional cash session link, and idempotency metadata. Database triggers forbid
 * UPDATE and DELETE.
 *
 * <p>OOP concept: <b>Immutability &amp; Mapping</b> — represents an immutable refund record.
 */
@Entity
@Table(name = "sale_returns")
public class SaleReturn {

    @Id private UUID id;

    @Column(name = "return_number", nullable = false, length = 20)
    private String returnNumber;

    @Column(name = "sale_id", nullable = false)
    private UUID saleId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private ReturnReason reason;

    @Column(name = "note")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_method", nullable = false, length = 20)
    private PaymentMethod refundMethod;

    @Column(name = "refund_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "cash_session_id")
    private UUID cashSessionId;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 128)
    private String requestHash;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    /** Required by JPA. */
    protected SaleReturn() {}

    public SaleReturn(
            UUID id,
            String returnNumber,
            UUID saleId,
            UUID locationId,
            ReturnReason reason,
            String note,
            PaymentMethod refundMethod,
            BigDecimal refundAmount,
            UUID cashSessionId,
            String idempotencyKey,
            String requestHash,
            UUID createdBy,
            Instant createdAt,
            String demoKey) {
        this.id = id;
        this.returnNumber = returnNumber;
        this.saleId = saleId;
        this.locationId = locationId;
        this.reason = reason;
        this.note = note;
        this.refundMethod = refundMethod;
        this.refundAmount = refundAmount;
        this.cashSessionId = cashSessionId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.demoKey = demoKey;
    }

    public UUID getId() {
        return id;
    }

    public String getReturnNumber() {
        return returnNumber;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public ReturnReason getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public PaymentMethod getRefundMethod() {
        return refundMethod;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public UUID getCashSessionId() {
        return cashSessionId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getDemoKey() {
        return demoKey;
    }
}
