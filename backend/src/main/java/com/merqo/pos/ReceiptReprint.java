package com.merqo.pos;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Append-only JPA entity recording every duplicate receipt reprint.
 *
 * <p>Responsibility: audit and count duplicate prints without logging the initial print. Database
 * triggers forbid UPDATE and DELETE.
 *
 * <p>OOP concept: <b>Immutability / Event Log</b> — represents an append-only reprint event.
 */
@Entity
@Table(name = "receipt_reprints")
public class ReceiptReprint {

    @Id private UUID id;

    @Column(name = "sale_id", nullable = false)
    private UUID saleId;

    @Column(name = "printed_by", nullable = false)
    private UUID printedBy;

    @Column(name = "printed_at", nullable = false)
    private Instant printedAt;

    /** Required by JPA. */
    protected ReceiptReprint() {}

    public ReceiptReprint(UUID id, UUID saleId, UUID printedBy, Instant printedAt) {
        this.id = id;
        this.saleId = saleId;
        this.printedBy = printedBy;
        this.printedAt = printedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public UUID getPrintedBy() {
        return printedBy;
    }

    public Instant getPrintedAt() {
        return printedAt;
    }
}
