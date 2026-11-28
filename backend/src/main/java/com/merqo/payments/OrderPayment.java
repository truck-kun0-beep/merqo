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

import com.merqo.payments.domain.OnlinePaymentMethod;

/**
 * Append-only customer advance or prepayment record linked to an order.
 *
 * <p>Derived verification status lives in {@link PaymentDecisionRecord}, never a mutable column on
 * this entity.
 */
@Entity
@Table(name = "order_payments")
public class OrderPayment {

    @Id private UUID id;

    @Column(name = "payment_number", nullable = false, length = 30)
    private String paymentNumber;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20)
    private OnlinePaymentMethod method;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "reference", length = 64)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private PaymentSource source;

    @Column(name = "payer_note")
    private String payerNote;

    @Column(name = "recorded_by", nullable = false)
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    protected OrderPayment() {}

    public OrderPayment(
            UUID id,
            String paymentNumber,
            UUID orderId,
            OnlinePaymentMethod method,
            BigDecimal amount,
            String reference,
            PaymentSource source,
            String payerNote,
            UUID recordedBy,
            Instant recordedAt,
            String demoKey) {
        this.id = id;
        this.paymentNumber = paymentNumber;
        this.orderId = orderId;
        this.method = method;
        this.amount = amount;
        this.reference = reference;
        this.source = source;
        this.payerNote = payerNote;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
        this.demoKey = demoKey;
    }

    public UUID getId() {
        return id;
    }

    public String getPaymentNumber() {
        return paymentNumber;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public OnlinePaymentMethod getMethod() {
        return method;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReference() {
        return reference;
    }

    public PaymentSource getSource() {
        return source;
    }

    public String getPayerNote() {
        return payerNote;
    }

    public UUID getRecordedBy() {
        return recordedBy;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getDemoKey() {
        return demoKey;
    }
}
