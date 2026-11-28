package com.merqo.payments;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Append-only per-order line allocated inside a {@link CourierSettlement}. */
@Entity
@Table(name = "courier_settlement_lines")
public class CourierSettlementLine {

    @Id private UUID id;

    @Column(name = "settlement_id", nullable = false)
    private UUID settlementId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "cod_collected_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal codCollectedAmount;

    @Column(name = "fee", nullable = false, precision = 14, scale = 2)
    private BigDecimal fee;

    @Column(name = "net", nullable = false, precision = 14, scale = 2)
    private BigDecimal net;

    protected CourierSettlementLine() {}

    public CourierSettlementLine(
            UUID id,
            UUID settlementId,
            UUID orderId,
            BigDecimal codCollectedAmount,
            BigDecimal fee,
            BigDecimal net) {
        this.id = id;
        this.settlementId = settlementId;
        this.orderId = orderId;
        this.codCollectedAmount = codCollectedAmount;
        this.fee = fee;
        this.net = net;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSettlementId() {
        return settlementId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public BigDecimal getCodCollectedAmount() {
        return codCollectedAmount;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public BigDecimal getNet() {
        return net;
    }
}
