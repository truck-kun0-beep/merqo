package com.merqo.inventory.domain;

/**
 * The stock of one variant at one location.
 *
 * <p>Responsibility: hold the three numbers of a balance and keep them correct. Every change goes
 * through a method of this class, so the invariants always hold:
 *
 * <ul>
 *   <li>Nothing is ever negative.
 *   <li>The reservation never exceeds the stock on the shelf.
 *   <li>The available stock is the stock on the shelf minus the reservation.
 * </ul>
 *
 * <p>The class is a plain Java object with no Spring in it, so the rules are unit tested with
 * {@code new} and no context.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the three numbers are private and there is no setter. A caller
 *       cannot break an invariant, because the only way to change the balance is one of the
 *       methods.
 *   <li><b>Fail fast</b> - {@link #reserve(int)} throws when there is not enough stock, so the
 *       caller cannot forget to check.
 *   <li><b>Value object behaviour</b> - the getters read like properties of the balance.
 * </ul>
 */
public class InventoryBalance {

    private final int onHand;
    private final int reserved;
    private final int inInspection;

    /**
     * Creates a balance.
     *
     * @param onHand the stock on the shelf
     * @param reserved the stock that is promised to somebody
     * @param inInspection the goods that are in inspection
     * @throws IllegalArgumentException when one of the numbers breaks an invariant
     */
    public InventoryBalance(int onHand, int reserved, int inInspection) {
        if (onHand < 0 || reserved < 0 || inInspection < 0) {
            throw new IllegalArgumentException("A stock number must not be negative");
        }
        if (reserved > onHand) {
            throw new IllegalArgumentException("The reservation must not exceed the stock");
        }
        this.onHand = onHand;
        this.reserved = reserved;
        this.inInspection = inInspection;
    }

    /**
     * The stock on the shelf.
     *
     * @return the stock on the shelf
     */
    public int getOnHand() {
        return onHand;
    }

    /**
     * The stock that is promised to somebody.
     *
     * @return the reserved stock
     */
    public int getReserved() {
        return reserved;
    }

    /**
     * The goods that are in inspection.
     *
     * @return the goods in inspection
     */
    public int getInInspection() {
        return inInspection;
    }

    /**
     * The stock that may be sold.
     *
     * @return the stock on the shelf minus the reservation
     */
    public int getAvailable() {
        return onHand - reserved;
    }

    /**
     * Adds stock to the shelf.
     *
     * @param quantity how many pieces arrive, always positive
     * @return the new balance
     */
    public InventoryBalance receive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("The quantity must be positive");
        }
        return new InventoryBalance(onHand + quantity, reserved, inInspection);
    }

    /**
     * Takes stock off the shelf.
     *
     * @param quantity how many pieces leave, always positive
     * @return the new balance
     * @throws InsufficientStockException when the stock would go below the reservation
     */
    public InventoryBalance remove(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("The quantity must be positive");
        }
        if (quantity > getAvailable()) {
            throw new InsufficientStockException(
                    "Only "
                            + getAvailable()
                            + " piece(s) are available, but "
                            + quantity
                            + " were asked for.");
        }
        return new InventoryBalance(onHand - quantity, reserved, inInspection);
    }

    /**
     * Promises stock to somebody.
     *
     * @param quantity how many pieces are promised, always positive
     * @return the new balance
     * @throws InsufficientStockException when there is not enough available stock
     */
    public InventoryBalance reserve(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("The quantity must be positive");
        }
        if (quantity > getAvailable()) {
            throw new InsufficientStockException(
                    "Only "
                            + getAvailable()
                            + " piece(s) are available, but "
                            + quantity
                            + " were asked for.");
        }
        return new InventoryBalance(onHand, reserved + quantity, inInspection);
    }

    /**
     * Gives a promise back.
     *
     * @param quantity how many pieces are released, always positive
     * @return the new balance
     */
    public InventoryBalance release(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("The quantity must be positive");
        }
        int newReserved = reserved - quantity;
        if (newReserved < 0) {
            throw new IllegalArgumentException("There is not enough reserved stock to release");
        }
        return new InventoryBalance(onHand, newReserved, inInspection);
    }

    /**
     * Fulfils a promise, so the stock really leaves the shelf.
     *
     * @param quantity how many pieces leave, always positive
     * @return the new balance
     * @throws InsufficientStockException when there is not enough reserved stock
     */
    public InventoryBalance fulfillReservation(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("The quantity must be positive");
        }
        if (quantity > reserved) {
            throw new InsufficientStockException(
                    "Only "
                            + reserved
                            + " piece(s) are reserved, but "
                            + quantity
                            + " were asked for.");
        }
        return new InventoryBalance(onHand - quantity, reserved - quantity, inInspection);
    }

    /**
     * Moves goods from the shelf into inspection.
     *
     * @param quantity how many pieces move, always positive
     * @return the new balance
     * @throws InsufficientStockException when there is not enough available stock
     */
    public InventoryBalance moveToInspection(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("The quantity must be positive");
        }
        if (quantity > getAvailable()) {
            throw new InsufficientStockException(
                    "Only "
                            + getAvailable()
                            + " piece(s) are available, but "
                            + quantity
                            + " were asked for.");
        }
        return new InventoryBalance(onHand - quantity, reserved, inInspection + quantity);
    }

    /**
     * Receives returned goods directly into inspection without making them sellable.
     *
     * @param quantity how many returned pieces enter inspection, always positive
     * @return the new balance
     */
    public InventoryBalance receiveToInspection(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("The quantity must be positive");
        }
        return new InventoryBalance(onHand, reserved, inInspection + quantity);
    }

    /**
     * Finishes an inspection: the goods either go back on the shelf or are written off.
     *
     * @param quantity how many pieces the inspection covered, always positive
     * @param accepted how many of them passed the inspection
     * @return the new balance
     */
    public InventoryBalance resolveInspection(int quantity, int accepted) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("The quantity must be positive");
        }
        if (accepted < 0 || accepted > quantity) {
            throw new IllegalArgumentException(
                    "The accepted number must be between zero and the quantity");
        }
        if (quantity > inInspection) {
            throw new IllegalArgumentException("There is not enough stock in inspection");
        }
        return new InventoryBalance(onHand + accepted, reserved, inInspection - quantity);
    }
}
