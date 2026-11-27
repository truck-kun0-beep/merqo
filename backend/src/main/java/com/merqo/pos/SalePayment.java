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
 * Append-only JPA entity representing the single payment of a completed POS sale.
 *
 * <p>Responsibility: store method, amount, cash tendered/change, and normalized payment reference
 * (never a full card number). Database triggers forbid UPDATE and DELETE.
 *
 * <p>OOP concept: <b>Immutability &amp; Mapping</b> — represents an unchangeable financial ledger
 * entry.
 */
@Entity
@Table(name = "sale_payments")
public class SalePayment {

    @Id private UUID id;

    @Column(name = "sale_id", nullable = false)
    private UUID saleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20)
    private PaymentMethod method;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "tendered", precision = 14, scale = 2)
    private BigDecimal tendered;

    @Column(name = "change_given", nullable = false, precision = 14, scale = 2)
    private BigDecimal changeGiven;

    @Column(name = "reference", length = 64)
    private String reference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected SalePayment() {}

    public SalePayment(
            UUID id,
            UUID saleId,
            PaymentMethod method,
            BigDecimal amount,
            BigDecimal tendered,
            BigDecimal changeGiven,
            String reference,
            Instant createdAt) {
        this.id = id;
        this.saleId = saleId;
        this.method = method;
        this.amount = amount;
        this.tendered = tendered;
        this.changeGiven = changeGiven;
        this.reference = reference;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getTendered() {
        return tendered;
    }

    public BigDecimal getChangeGiven() {
        return changeGiven;
    }

    public String getReference() {
        return reference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
