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

import com.merqo.catalog.domain.DiscountType;
import com.merqo.pos.domain.PaymentMethod;

/**
 * Append-only JPA entity representing a completed POS sale header.
 *
 * <p>Responsibility: hold server-computed totals, receipt number, cashier, branch, session,
 * optional discount approval reference, and idempotency metadata. Database triggers forbid UPDATE
 * and DELETE on {@code sales}.
 *
 * <p>OOP concept: <b>Immutability &amp; Encapsulation</b> — expose read-only getters with no public
 * mutators for monetary or identity fields.
 */
@Entity
@Table(name = "sales")
public class Sale {

    @Id private UUID id;

    @Column(name = "receipt_number", nullable = false, length = 30)
    private String receiptNumber;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "cash_session_id", nullable = false)
    private UUID cashSessionId;

    @Column(name = "cashier_id", nullable = false)
    private UUID cashierId;

    @Column(name = "subtotal", nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "promotion_discount", nullable = false, precision = 14, scale = 2)
    private BigDecimal promotionDiscount;

    @Column(name = "manual_discount", nullable = false, precision = 14, scale = 2)
    private BigDecimal manualDiscount;

    @Enumerated(EnumType.STRING)
    @Column(name = "bill_discount_type", length = 20)
    private DiscountType billDiscountType;

    @Column(name = "bill_discount_value", precision = 14, scale = 2)
    private BigDecimal billDiscountValue;

    @Column(name = "tax_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxTotal;

    @Column(name = "total", nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "discount_approved_by")
    private UUID discountApprovedBy;

    @Column(name = "approval_token_id")
    private UUID approvalTokenId;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 128)
    private String requestHash;

    @Column(name = "note")
    private String note;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected Sale() {}

    public Sale(
            UUID id,
            String receiptNumber,
            UUID locationId,
            UUID cashSessionId,
            UUID cashierId,
            BigDecimal subtotal,
            BigDecimal promotionDiscount,
            BigDecimal manualDiscount,
            DiscountType billDiscountType,
            BigDecimal billDiscountValue,
            BigDecimal taxTotal,
            BigDecimal total,
            PaymentMethod paymentMethod,
            UUID discountApprovedBy,
            UUID approvalTokenId,
            String idempotencyKey,
            String requestHash,
            String note,
            String demoKey,
            Instant createdAt) {
        this.id = id;
        this.receiptNumber = receiptNumber;
        this.locationId = locationId;
        this.cashSessionId = cashSessionId;
        this.cashierId = cashierId;
        this.subtotal = subtotal;
        this.promotionDiscount = promotionDiscount;
        this.manualDiscount = manualDiscount;
        this.billDiscountType = billDiscountType;
        this.billDiscountValue = billDiscountValue;
        this.taxTotal = taxTotal;
        this.total = total;
        this.paymentMethod = paymentMethod;
        this.discountApprovedBy = discountApprovedBy;
        this.approvalTokenId = approvalTokenId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.note = note;
        this.demoKey = demoKey;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public UUID getCashSessionId() {
        return cashSessionId;
    }

    public UUID getCashierId() {
        return cashierId;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getPromotionDiscount() {
        return promotionDiscount;
    }

    public BigDecimal getManualDiscount() {
        return manualDiscount;
    }

    public DiscountType getBillDiscountType() {
        return billDiscountType;
    }

    public BigDecimal getBillDiscountValue() {
        return billDiscountValue;
    }

    public BigDecimal getTaxTotal() {
        return taxTotal;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public UUID getDiscountApprovedBy() {
        return discountApprovedBy;
    }

    public UUID getApprovalTokenId() {
        return approvalTokenId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getNote() {
        return note;
    }

    public String getDemoKey() {
        return demoKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
