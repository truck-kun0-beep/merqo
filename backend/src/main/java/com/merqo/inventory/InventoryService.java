package com.merqo.inventory;

import java.util.UUID;

/**
 * The way the later phases change the stock.
 *
 * <p>Responsibility: give the later phases one narrow door into the stock. Phases 3, 4, 6 and 7
 * never touch a balance and never write a movement themselves: they ask this interface and the
 * rules stay in one place.
 *
 * <p>Every method of this interface does the same three things:
 *
 * <ol>
 *   <li>It changes the balance of one variant at one location.
 *   <li>It writes exactly one row in the append only movement table, in the same transaction.
 *   <li>It keeps the invariants: nothing is negative, the reservation never exceeds the stock on
 *       the shelf, and the goods in inspection are never sellable.
 * </ol>
 *
 * <p>The methods that take a plain number work with whole pieces and always grow or always shrink
 * the stock. The {@link #apply(InventoryCommand)} method takes a signed number, so a correction may
 * go in both directions.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface</b> - the later phases depend on this idea, not on the implementation.
 *   <li><b>Open/closed principle</b> - a new phase adds a call, never a new way of writing to the
 *       balances.
 *   <li><b>Encapsulation</b> - the balance rows and the movement table are private to the ledger.
 * </ul>
 */
public interface InventoryService {

    /**
     * Adds stock to a location.
     *
     * @param command the change
     * @return the stock on the shelf after the change
     */
    int receive(InventoryCommand command);

    /**
     * Takes stock away from a location.
     *
     * @param command the change
     * @return the stock on the shelf after the change
     */
    int remove(InventoryCommand command);

    /**
     * Applies a signed change of the stock.
     *
     * <p>A positive number adds stock and a negative number takes it away. This is what a count
     * correction uses.
     *
     * @param command the change
     * @return the stock on the shelf after the change
     */
    int apply(InventoryCommand command);

    /**
     * Promises stock to somebody. The stock stays on the shelf but it may not be sold again.
     *
     * @param command the change
     */
    void reserve(InventoryCommand command);

    /**
     * Gives a promise back.
     *
     * @param command the change
     */
    void release(InventoryCommand command);

    /**
     * Fulfils a promise, so the stock really leaves the shelf.
     *
     * @param command the change
     * @return the stock on the shelf after the change
     */
    int fulfillReservation(InventoryCommand command);

    /**
     * Moves goods from the shelf into inspection. They are not sellable any more.
     *
     * @param command the change
     * @return the stock on the shelf after the change
     */
    int moveToInspection(InventoryCommand command);

    /**
     * Finishes an inspection. The accepted goods go back on the shelf, the rest is written off.
     *
     * @param command the change
     * @param accepted how many of the inspected pieces passed
     * @return the stock on the shelf after the change
     */
    int resolveInspection(InventoryCommand command, int accepted);

    /**
     * The stock of one variant at one location.
     *
     * @param locationId the location
     * @param variantId the variant
     * @return the balance, or {@code null} when the variant was never stocked there
     */
    StockBalance balanceOf(UUID locationId, UUID variantId);

    /**
     * How often the stock of a variant moved at a location.
     *
     * <p>The import uses this to decide whether an opening quantity may still be written. A
     * location that already had a movement keeps its stock, so importing the same file twice
     * changes nothing the second time.
     *
     * @param locationId the location
     * @param variantId the variant
     * @return the number of movements
     */
    long countMovementsAt(UUID locationId, UUID variantId);
}
