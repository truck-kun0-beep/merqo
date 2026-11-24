package com.merqo.transfer;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.transfer.domain.TransferStatus;

/**
 * One row of the status history of a transfer. The table is append only.
 *
 * <p>Responsibility: hold one change of the state of a transfer: who did it, when, from which state
 * to which state and why.
 *
 * <p>The table is append only. A database trigger raises an error on any UPDATE and on any DELETE,
 * and the repository has no update and no delete method.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 *   <li><b>Encapsulation</b> - the history is written once and never rewritten.
 * </ul>
 */
@Entity
@Table(name = "transfer_status_history")
public class TransferStatusHistory {

    @Id private UUID id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private TransferStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private TransferStatus toStatus;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_username", length = 40)
    private String actorUsername;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected TransferStatusHistory() {}

    /**
     * Creates a history row.
     *
     * @param id the id
     * @param transferId the transfer
     * @param fromStatus the state before, may be {@code null} for the first row
     * @param toStatus the state after
     * @param actorUserId who did it
     * @param actorUsername the username of that user, kept as a snapshot
     * @param note the note, may be {@code null}
     * @param createdAt when it happened
     */
    public TransferStatusHistory(
            UUID id,
            UUID transferId,
            TransferStatus fromStatus,
            TransferStatus toStatus,
            UUID actorUserId,
            String actorUsername,
            String note,
            Instant createdAt) {
        this.id = id;
        this.transferId = transferId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorUserId = actorUserId;
        this.actorUsername = actorUsername;
        this.note = note;
        this.createdAt = createdAt;
    }

    /**
     * The id of the row.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * The transfer of the row.
     *
     * @return the transfer id
     */
    public UUID getTransferId() {
        return transferId;
    }

    /**
     * The state before the change.
     *
     * @return the old state, or {@code null}
     */
    public TransferStatus getFromStatus() {
        return fromStatus;
    }

    /**
     * The state after the change.
     *
     * @return the new state
     */
    public TransferStatus getToStatus() {
        return toStatus;
    }

    /**
     * Who did it.
     *
     * @return the user id, or {@code null}
     */
    public UUID getActorUserId() {
        return actorUserId;
    }

    /**
     * The username of that user.
     *
     * @return the username, or {@code null}
     */
    public String getActorUsername() {
        return actorUsername;
    }

    /**
     * The note of the change.
     *
     * @return the note, or {@code null}
     */
    public String getNote() {
        return note;
    }

    /**
     * When the change happened.
     *
     * @return the moment of the change
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
