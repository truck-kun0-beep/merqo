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
 * One transfer of stock from one location to another.
 *
 * <p>Responsibility: hold the head of a transfer: the number, the two places and the state.
 *
 * <p>The state machine lives in {@link com.merqo.transfer.domain.TransferTransitions}, so there is
 * exactly one transition table in the code base. This class only holds the state.
 *
 * <p>The stock moves only at dispatch and at receive. A request and an approval move nothing, and
 * the goods that are on the way are derived from the lines and never stored as stock anywhere.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Enums with behaviour</b> - the state is an enum value.
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 *   <li><b>Encapsulation</b> - the state is only changed through the service, which also writes the
 *       status history.
 * </ul>
 */
@Entity
@Table(name = "stock_transfers")
public class StockTransfer {

    @Id private UUID id;

    @Column(name = "number", nullable = false, length = 20)
    private String number;

    @Column(name = "source_location_id", nullable = false)
    private UUID sourceLocationId;

    @Column(name = "destination_location_id", nullable = false)
    private UUID destinationLocationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TransferStatus status;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "dispatched_by")
    private UUID dispatchedBy;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "received_by")
    private UUID receivedBy;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    /** Required by JPA. */
    protected StockTransfer() {}

    /**
     * Creates a transfer that was requested.
     *
     * @param id the id
     * @param number the human readable number
     * @param sourceLocationId where the stock comes from
     * @param destinationLocationId where the stock goes
     * @param requestedBy who asked for it
     * @param createdAt when it was asked for
     */
    public StockTransfer(
            UUID id,
            String number,
            UUID sourceLocationId,
            UUID destinationLocationId,
            UUID requestedBy,
            Instant createdAt) {
        this.id = id;
        this.number = number;
        this.sourceLocationId = sourceLocationId;
        this.destinationLocationId = destinationLocationId;
        this.requestedBy = requestedBy;
        this.requestedAt = createdAt;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = TransferStatus.REQUESTED;
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
     * The human readable number of the transfer.
     *
     * @return the number
     */
    public String getNumber() {
        return number;
    }

    /**
     * Where the stock comes from.
     *
     * @return the source location id
     */
    public UUID getSourceLocationId() {
        return sourceLocationId;
    }

    /**
     * Where the stock goes.
     *
     * @return the destination location id
     */
    public UUID getDestinationLocationId() {
        return destinationLocationId;
    }

    /**
     * The state of the transfer.
     *
     * @return the status
     */
    public TransferStatus getStatus() {
        return status;
    }

    /**
     * Sets the state of the transfer.
     *
     * @param status the new state
     */
    public void setStatus(TransferStatus status) {
        this.status = status;
    }

    /**
     * The note of the transfer.
     *
     * @return the note, or {@code null}
     */
    public String getNote() {
        return note;
    }

    /**
     * Sets the note of the transfer.
     *
     * @param note the note
     */
    public void setNote(String note) {
        this.note = note;
    }

    /**
     * Who asked for the transfer.
     *
     * @return the user id, or {@code null}
     */
    public UUID getRequestedBy() {
        return requestedBy;
    }

    /**
     * When the transfer was asked for.
     *
     * @return the moment of the request
     */
    public Instant getRequestedAt() {
        return requestedAt;
    }

    /**
     * Marks the transfer as approved.
     *
     * @param approvedBy who approved it
     * @param approvedAt when it was approved
     */
    public void markApproved(UUID approvedBy, Instant approvedAt) {
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
    }

    /**
     * Marks the transfer as dispatched.
     *
     * @param dispatchedBy who dispatched it
     * @param dispatchedAt when it was dispatched
     */
    public void markDispatched(UUID dispatchedBy, Instant dispatchedAt) {
        this.dispatchedBy = dispatchedBy;
        this.dispatchedAt = dispatchedAt;
    }

    /**
     * Marks the transfer as received.
     *
     * @param receivedBy who received it
     * @param receivedAt when it was received
     */
    public void markReceived(UUID receivedBy, Instant receivedAt) {
        this.receivedBy = receivedBy;
        this.receivedAt = receivedAt;
    }

    /**
     * Marks the transfer as closed.
     *
     * @param closedBy who closed it
     * @param closedAt when it was closed
     */
    public void markClosed(UUID closedBy, Instant closedAt) {
        this.closedBy = closedBy;
        this.closedAt = closedAt;
    }

    /**
     * Who approved the transfer.
     *
     * @return the user id, or {@code null}
     */
    public UUID getApprovedBy() {
        return approvedBy;
    }

    /**
     * When the transfer was approved.
     *
     * @return the moment of the approval, or {@code null}
     */
    public Instant getApprovedAt() {
        return approvedAt;
    }

    /**
     * Who dispatched the transfer.
     *
     * @return the user id, or {@code null}
     */
    public UUID getDispatchedBy() {
        return dispatchedBy;
    }

    /**
     * When the transfer was dispatched.
     *
     * @return the moment of the dispatch, or {@code null}
     */
    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    /**
     * Who received the transfer.
     *
     * @return the user id, or {@code null}
     */
    public UUID getReceivedBy() {
        return receivedBy;
    }

    /**
     * When the transfer was received.
     *
     * @return the moment of the receive, or {@code null}
     */
    public Instant getReceivedAt() {
        return receivedAt;
    }

    /**
     * Who closed the transfer.
     *
     * @return the user id, or {@code null}
     */
    public UUID getClosedBy() {
        return closedBy;
    }

    /**
     * When the transfer was closed.
     *
     * @return the moment of the close, or {@code null}
     */
    public Instant getClosedAt() {
        return closedAt;
    }

    /**
     * When the transfer was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * When the transfer was last changed.
     *
     * @return the update moment
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Records that the transfer was changed.
     *
     * @param updatedAt when it happened
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * The stable key of the demo row.
     *
     * @return the demo key, or {@code null}
     */
    public String getDemoKey() {
        return demoKey;
    }

    /**
     * Sets the stable key of the demo row.
     *
     * @param demoKey the demo key
     */
    public void setDemoKey(String demoKey) {
        this.demoKey = demoKey;
    }
}
