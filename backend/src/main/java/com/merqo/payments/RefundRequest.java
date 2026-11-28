package com.merqo.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.payments.domain.RefundRules.RefundReason;

/** Append-only refund request header for an online or manual order. */
@Entity
@Table(name = "refund_requests")
public class RefundRequest {

    @Id private UUID id;

    @Column(name = "refund_number", nullable = false, length = 30)
    private String refundNumber;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private RefundReason reason;

    @Column(name = "note")
    private String note;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    protected RefundRequest() {}

    public RefundRequest(
            UUID id,
            String refundNumber,
            UUID orderId,
            BigDecimal amount,
            RefundReason reason,
            String note,
            UUID requestedBy,
            Instant requestedAt,
            String demoKey) {
        this.id = id;
        this.refundNumber = refundNumber;
        this.orderId = orderId;
        this.amount = amount;
        this.reason = reason;
        this.note = note;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.demoKey = demoKey;
    }

    public UUID getId() {
        return id;
    }

    public String getRefundNumber() {
        return refundNumber;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public RefundReason getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public String getDemoKey() {
        return demoKey;
    }
}
