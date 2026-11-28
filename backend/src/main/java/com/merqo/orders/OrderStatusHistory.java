package com.merqo.orders;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.orders.domain.OrderStatus;

/** Append-only status transition record protected by PostgreSQL trigger. */
@Entity
@Table(name = "order_status_history")
public class OrderStatusHistory {

    @Id private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private OrderStatus toStatus;

    @Column(name = "changed_by")
    private UUID changedBy;

    @Column(name = "actor_label", nullable = false, length = 80)
    private String actorLabel;

    @Column(name = "reason", length = 60)
    private String reason;

    @Column(name = "note")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OrderStatusHistory() {}

    public OrderStatusHistory(
            UUID id,
            UUID orderId,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            UUID changedBy,
            String actorLabel,
            String reason,
            String note,
            Instant createdAt) {
        this.id = id;
        this.orderId = orderId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBy = changedBy;
        this.actorLabel = actorLabel;
        this.reason = reason;
        this.note = note;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public OrderStatus getFromStatus() {
        return fromStatus;
    }

    public OrderStatus getToStatus() {
        return toStatus;
    }

    public UUID getChangedBy() {
        return changedBy;
    }

    public String getActorLabel() {
        return actorLabel;
    }

    public String getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
