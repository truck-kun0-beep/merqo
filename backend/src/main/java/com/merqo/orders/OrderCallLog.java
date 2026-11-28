package com.merqo.orders;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.orders.domain.CallResult;

/** Append-only confirmation call log entry protected by PostgreSQL trigger. */
@Entity
@Table(name = "order_call_logs")
public class OrderCallLog {

    @Id private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 40)
    private CallResult result;

    @Column(name = "note")
    private String note;

    @Column(name = "follow_up_at")
    private Instant followUpAt;

    @Column(name = "called_by", nullable = false)
    private UUID calledBy;

    @Column(name = "called_at", nullable = false)
    private Instant calledAt;

    protected OrderCallLog() {}

    public OrderCallLog(
            UUID id,
            UUID orderId,
            int attemptNumber,
            CallResult result,
            String note,
            Instant followUpAt,
            UUID calledBy,
            Instant calledAt) {
        this.id = id;
        this.orderId = orderId;
        this.attemptNumber = attemptNumber;
        this.result = result;
        this.note = note;
        this.followUpAt = followUpAt;
        this.calledBy = calledBy;
        this.calledAt = calledAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public CallResult getResult() {
        return result;
    }

    public String getNote() {
        return note;
    }

    public Instant getFollowUpAt() {
        return followUpAt;
    }

    public UUID getCalledBy() {
        return calledBy;
    }

    public Instant getCalledAt() {
        return calledAt;
    }
}
