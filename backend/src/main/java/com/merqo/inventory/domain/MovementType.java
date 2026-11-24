package com.merqo.inventory.domain;

/**
 * The kinds of stock movement.
 *
 * <p>Responsibility: name every kind of movement, say how much it changes the stock and which
 * bucket of the balance it touches.
 *
 * <p>The list is written out in full now, so the later phases only add their own movement types and
 * never change this one. The names of the later types are already here, so the history of a
 * movement never changes its meaning.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Enums with behaviour</b> - each constant knows its own sign and its own bucket, so a
 *       switch over the type is never needed anywhere else.
 *   <li><b>Open/closed principle</b> - a new movement type is a new constant.
 *   <li><b>Type safety</b> - a movement type is an enum value, never a string that can be
 *       misspelled.
 * </ul>
 */
public enum MovementType {

    /** The stock the shop started with. */
    OPENING_BALANCE(1, Bucket.ON_HAND),

    /** A correction that adds stock. */
    ADJUSTMENT_IN(1, Bucket.ON_HAND),

    /** A correction that takes stock away. */
    ADJUSTMENT_OUT(-1, Bucket.ON_HAND),

    /** The difference a stock count found. */
    COUNT_CORRECTION(1, Bucket.ON_HAND),

    /** Stock that left a location in a transfer. */
    TRANSFER_OUT(-1, Bucket.ON_HAND),

    /** Stock that arrived at a location in a transfer. */
    TRANSFER_IN(1, Bucket.ON_HAND),

    /** Goods that arrived from a supplier. Used from Phase 7. */
    PURCHASE_RECEIPT(1, Bucket.ON_HAND),

    /** Stock that left a location because of a sale. Used from Phase 3. */
    POS_SALE(-1, Bucket.ON_HAND),

    /** Stock that came back because of a return. Used from Phase 3. */
    POS_RETURN(1, Bucket.ON_HAND),

    /** Goods that left the shelf and went into inspection. Used from Phase 4. */
    INSPECTION_RESTOCK(-1, Bucket.INSPECTION),

    /** Goods that failed the inspection and were written off. Used from Phase 4. */
    INSPECTION_WRITE_OFF(-1, Bucket.INSPECTION);

    /** The bucket of the balance a movement touches. */
    public enum Bucket {
        /** The stock on the shelf. */
        ON_HAND,
        /** The goods that are in inspection. */
        INSPECTION
    }

    private final int sign;
    private final Bucket bucket;

    MovementType(int sign, Bucket bucket) {
        this.sign = sign;
        this.bucket = bucket;
    }

    /**
     * The sign of the movement.
     *
     * @return 1 when the movement adds stock, -1 when it takes stock away
     */
    public int getSign() {
        return sign;
    }

    /**
     * The bucket of the balance this movement touches.
     *
     * @return the bucket
     */
    public Bucket getBucket() {
        return bucket;
    }

    /**
     * Whether the movement adds stock.
     *
     * @param quantity the number of pieces, always a positive number
     * @return the signed quantity
     */
    public int signedQuantity(int quantity) {
        return sign * Math.abs(quantity);
    }
}
