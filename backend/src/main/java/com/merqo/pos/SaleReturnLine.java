package com.merqo.pos;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Append-only JPA entity representing a single returned line item.
 *
 * <p>Responsibility: persist the returned quantity, exact line refund amount, and stock disposition
 * ({@code RESTOCK} or {@code INSPECTION}). Database triggers forbid UPDATE and DELETE.
 *
 * <p>OOP concept: <b>Immutability &amp; Mapping</b> — models an immutable return line entry.
 */
@Entity
@Table(name = "sale_return_lines")
public class SaleReturnLine {

    @Id private UUID id;

    @Column(name = "sale_return_id", nullable = false)
    private UUID saleReturnId;

    @Column(name = "sale_line_id", nullable = false)
    private UUID saleLineId;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "refund_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal refundAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposition", nullable = false, length = 20)
    private ReturnDisposition disposition;

    /** Required by JPA. */
    protected SaleReturnLine() {}

    public SaleReturnLine(
            UUID id,
            UUID saleReturnId,
            UUID saleLineId,
            UUID variantId,
            int quantity,
            BigDecimal refundAmount,
            ReturnDisposition disposition) {
        this.id = id;
        this.saleReturnId = saleReturnId;
        this.saleLineId = saleLineId;
        this.variantId = variantId;
        this.quantity = quantity;
        this.refundAmount = refundAmount;
        this.disposition = disposition;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSaleReturnId() {
        return saleReturnId;
    }

    public UUID getSaleLineId() {
        return saleLineId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public ReturnDisposition getDisposition() {
        return disposition;
    }
}
