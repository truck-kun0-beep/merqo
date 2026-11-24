package com.merqo.transfer;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One line of a stock transfer.
 *
 * <p>Responsibility: hold the four quantities of one variant in one transfer: what was asked for,
 * what was approved, what was dispatched and what arrived.
 *
 * <p>The rules that keep the four numbers in step live in {@link
 * com.merqo.transfer.domain.TransferLine}, which is plain Java and unit tested on its own. This row
 * only holds the numbers.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 *   <li><b>Composition</b> - the line belongs to the transfer and is never used on its own.
 * </ul>
 */
@Entity
@Table(name = "stock_transfer_items")
public class StockTransferItem {

    @Id private UUID id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "requested_qty", nullable = false)
    private int requestedQuantity;

    @Column(name = "approved_qty")
    private Integer approvedQuantity;

    @Column(name = "dispatched_qty", nullable = false)
    private int dispatchedQuantity;

    @Column(name = "received_qty", nullable = false)
    private int receivedQuantity;

    @Column(name = "shortage_qty", nullable = false)
    private int shortageQuantity;

    /** Required by JPA. */
    protected StockTransferItem() {}

    /**
     * Creates a line that was only requested.
     *
     * @param id the id
     * @param transferId the transfer
     * @param variantId the variant
     * @param requestedQuantity the quantity that was asked for
     */
    public StockTransferItem(UUID id, UUID transferId, UUID variantId, int requestedQuantity) {
        this.id = id;
        this.transferId = transferId;
        this.variantId = variantId;
        this.requestedQuantity = requestedQuantity;
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
     * The transfer of the line.
     *
     * @return the transfer id
     */
    public UUID getTransferId() {
        return transferId;
    }

    /**
     * The variant of the line.
     *
     * @return the variant id
     */
    public UUID getVariantId() {
        return variantId;
    }

    /**
     * The quantity that was asked for.
     *
     * @return the requested quantity
     */
    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    /**
     * The quantity that was approved.
     *
     * @return the approved quantity, or {@code null} when the line was never approved
     */
    public Integer getApprovedQuantity() {
        return approvedQuantity;
    }

    /**
     * Sets the approved quantity.
     *
     * @param approvedQuantity the approved quantity
     */
    public void setApprovedQuantity(Integer approvedQuantity) {
        this.approvedQuantity = approvedQuantity;
    }

    /**
     * The quantity that left the source.
     *
     * @return the dispatched quantity
     */
    public int getDispatchedQuantity() {
        return dispatchedQuantity;
    }

    /**
     * Sets the dispatched quantity.
     *
     * @param dispatchedQuantity the dispatched quantity
     */
    public void setDispatchedQuantity(int dispatchedQuantity) {
        this.dispatchedQuantity = dispatchedQuantity;
    }

    /**
     * The quantity that arrived at the destination.
     *
     * @return the received quantity
     */
    public int getReceivedQuantity() {
        return receivedQuantity;
    }

    /**
     * Sets the received quantity.
     *
     * @param receivedQuantity the received quantity
     */
    public void setReceivedQuantity(int receivedQuantity) {
        this.receivedQuantity = receivedQuantity;
    }

    /**
     * The quantity that never arrived.
     *
     * @return the shortage quantity
     */
    public int getShortageQuantity() {
        return shortageQuantity;
    }

    /**
     * Sets the shortage quantity.
     *
     * @param shortageQuantity the shortage quantity
     */
    public void setShortageQuantity(int shortageQuantity) {
        this.shortageQuantity = shortageQuantity;
    }
}
