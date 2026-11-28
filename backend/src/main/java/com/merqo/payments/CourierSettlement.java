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
import com.merqo.payments.domain.SettlementCalculator.DifferenceKind;
import com.merqo.payments.domain.SettlementCalculator.ReceiveMethod;

/** Append-only courier COD settlement batch header. */
@Entity
@Table(name = "courier_settlements")
public class CourierSettlement {

    @Id private UUID id;

    @Column(name = "settlement_number", nullable = false, length = 30)
    private String settlementNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "courier", nullable = false, length = 20)
    private Courier courier;

    @Column(name = "reference", nullable = false, length = 80)
    private String reference;

    @Column(name = "received_on", nullable = false)
    private LocalDate receivedOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "receive_method", nullable = false, length = 20)
    private ReceiveMethod receiveMethod;

    @Column(name = "amount_received", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountReceived;

    @Column(name = "fees_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal feesTotal;

    @Column(name = "difference", nullable = false, precision = 14, scale = 2)
    private BigDecimal difference;

    @Enumerated(EnumType.STRING)
    @Column(name = "difference_kind", nullable = false, length = 10)
    private DifferenceKind differenceKind;

    @Column(name = "note")
    private String note;

    @Column(name = "recorded_by", nullable = false)
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    protected CourierSettlement() {}

    public CourierSettlement(
            UUID id,
            String settlementNumber,
            Courier courier,
            String reference,
            LocalDate receivedOn,
            ReceiveMethod receiveMethod,
            BigDecimal amountReceived,
            BigDecimal feesTotal,
            BigDecimal difference,
            DifferenceKind differenceKind,
            String note,
            UUID recordedBy,
            Instant recordedAt,
            String demoKey) {
        this.id = id;
        this.settlementNumber = settlementNumber;
        this.courier = courier;
        this.reference = reference;
        this.receivedOn = receivedOn;
        this.receiveMethod = receiveMethod;
        this.amountReceived = amountReceived;
        this.feesTotal = feesTotal;
        this.difference = difference;
        this.differenceKind = differenceKind;
        this.note = note;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
        this.demoKey = demoKey;
    }

    public UUID getId() {
        return id;
    }

    public String getSettlementNumber() {
        return settlementNumber;
    }

    public Courier getCourier() {
        return courier;
    }

    public String getReference() {
        return reference;
    }

    public LocalDate getReceivedOn() {
        return receivedOn;
    }

    public ReceiveMethod getReceiveMethod() {
        return receiveMethod;
    }

    public BigDecimal getAmountReceived() {
        return amountReceived;
    }

    public BigDecimal getFeesTotal() {
        return feesTotal;
    }

    public BigDecimal getDifference() {
        return difference;
    }

    public DifferenceKind getDifferenceKind() {
        return differenceKind;
    }

    public String getNote() {
        return note;
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
