package com.merqo.transfer.domain;

/**
 * The quantities of one line of a transfer.
 *
 * <p>Responsibility: hold the four quantities of one line and keep them in step.
 *
 * <p>The rules:
 *
 * <ul>
 *   <li>The approved quantity is never more than the requested quantity.
 *   <li>The dispatched quantity is never more than the approved quantity.
 *   <li>The received quantity plus the shortage is never more than the dispatched quantity.
 *   <li>The goods in transit are the dispatched quantity minus the received quantity minus the
 *       shortage.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the quantities are private and only the methods of this class change
 *       them, so the rules always hold.
 *   <li><b>Derived value</b> - the goods in transit are computed, never stored, so they can never
 *       disagree with the other three numbers.
 *   <li><b>Fail fast</b> - every method that would break a rule throws.
 * </ul>
 */
public class TransferLine {

    private final int requested;
    private final int approved;
    private final int dispatched;
    private final int received;
    private final int shortage;

    /**
     * Creates a line that was only requested.
     *
     * @param requested the quantity that was asked for
     */
    public TransferLine(int requested) {
        this(requested, 0, 0, 0, 0);
    }

    /**
     * Creates a line.
     *
     * @param requested the quantity that was asked for
     * @param approved the quantity that was approved
     * @param dispatched the quantity that left the source
     * @param received the quantity that arrived
     * @param shortage the quantity that never arrived
     * @throws IllegalArgumentException when one of the quantities breaks a rule
     */
    public TransferLine(int requested, int approved, int dispatched, int received, int shortage) {
        if (requested <= 0) {
            throw new IllegalArgumentException("The requested quantity must be positive");
        }
        if (approved < 0 || approved > requested) {
            throw new IllegalArgumentException(
                    "The approved quantity must be between zero and the requested quantity");
        }
        if (dispatched < 0 || dispatched > approved) {
            throw new IllegalArgumentException(
                    "The dispatched quantity must be between zero and the approved quantity");
        }
        if (received < 0 || shortage < 0) {
            throw new IllegalArgumentException(
                    "The received quantity and the shortage must not be negative");
        }
        if (received + shortage > dispatched) {
            throw new IllegalArgumentException(
                    "The received quantity and the shortage must not exceed the dispatched quantity");
        }
        this.requested = requested;
        this.approved = approved;
        this.dispatched = dispatched;
        this.received = received;
        this.shortage = shortage;
    }

    /**
     * The quantity that was asked for.
     *
     * @return the requested quantity
     */
    public int getRequested() {
        return requested;
    }

    /**
     * The quantity that was approved.
     *
     * @return the approved quantity
     */
    public int getApproved() {
        return approved;
    }

    /**
     * The quantity that left the source.
     *
     * @return the dispatched quantity
     */
    public int getDispatched() {
        return dispatched;
    }

    /**
     * The quantity that arrived at the destination.
     *
     * @return the received quantity
     */
    public int getReceived() {
        return received;
    }

    /**
     * The quantity that never arrived.
     *
     * @return the shortage
     */
    public int getShortage() {
        return shortage;
    }

    /**
     * The goods that are on the way.
     *
     * <p>The value is derived, never stored, so it can never disagree with the other numbers.
     *
     * @return the dispatched quantity minus the received quantity minus the shortage
     */
    public int getInTransit() {
        return dispatched - received - shortage;
    }

    /**
     * The quantity that may still be dispatched.
     *
     * @return the approved quantity minus the dispatched quantity
     */
    public int getOutstandingToDispatch() {
        return approved - dispatched;
    }

    /**
     * The quantity that may still be received.
     *
     * @return the dispatched quantity minus the received quantity minus the shortage
     */
    public int getOutstandingToReceive() {
        return getInTransit();
    }

    /**
     * Approves the line.
     *
     * @param approvedQuantity the quantity that is approved, may not be more than the requested one
     * @return the new line
     */
    public TransferLine approve(int approvedQuantity) {
        return new TransferLine(requested, approvedQuantity, dispatched, received, shortage);
    }

    /**
     * Records that the stock left the source.
     *
     * @param dispatchedQuantity the quantity that leaves, may not be more than the approved one
     * @return the new line
     */
    public TransferLine dispatch(int dispatchedQuantity) {
        return new TransferLine(requested, approved, dispatchedQuantity, received, shortage);
    }

    /**
     * Records that the stock arrived at the destination.
     *
     * @param receivedQuantity the quantity that arrives
     * @return the new line
     */
    public TransferLine receive(int receivedQuantity) {
        return new TransferLine(
                requested, approved, dispatched, received + receivedQuantity, shortage);
    }

    /**
     * Records that the rest of the stock never arrived.
     *
     * @return the new line
     */
    public TransferLine closeWithShortage() {
        return new TransferLine(requested, approved, dispatched, received, dispatched - received);
    }
}
