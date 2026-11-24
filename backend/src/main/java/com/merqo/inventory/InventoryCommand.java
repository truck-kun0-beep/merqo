package com.merqo.inventory;

import java.math.BigDecimal;
import java.util.UUID;

import com.merqo.inventory.domain.MovementType;

/**
 * One request to change the stock of one variant at one location.
 *
 * <p>Responsibility: carry everything a movement needs, so the service methods stay short and the
 * caller cannot forget one of the parts.
 *
 * <p>The quantity is signed. A positive number adds stock and a negative number takes it away. A
 * count correction may go in both directions, so the sign is what decides the direction and not the
 * kind of movement.
 *
 * <p>The command is a value object. It is built once and never changed, so the same command always
 * produces the same movement.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Immutability / value object</b> - a record with immutable components.
 *   <li><b>Composition</b> - the command holds the pieces of the request instead of spreading them
 *       over seven parameters.
 * </ul>
 *
 * @param locationId where the stock is
 * @param variantId which stock is changed
 * @param quantityDelta the signed change of the stock, never zero
 * @param movementType why the stock changes
 * @param referenceType what document caused the change, may be {@code null}
 * @param referenceId the id of that document, may be {@code null}
 * @param reason the reason, may be {@code null}
 * @param note the note, may be {@code null}
 * @param unitCost the cost of one piece at that moment, may be {@code null}
 * @param actorUserId who caused the change
 * @param correlationId the id of the request, may be {@code null}
 */
public record InventoryCommand(
        UUID locationId,
        UUID variantId,
        int quantityDelta,
        MovementType movementType,
        String referenceType,
        UUID referenceId,
        String reason,
        String note,
        BigDecimal unitCost,
        UUID actorUserId,
        String correlationId) {

    /**
     * Canonical constructor. The quantity may not be zero.
     *
     * @param locationId where the stock is
     * @param variantId which stock is changed
     * @param quantityDelta the signed change of the stock
     * @param movementType why the stock changes
     * @param referenceType what document caused the change
     * @param referenceId the id of that document
     * @param reason the reason
     * @param note the note
     * @param unitCost the cost of one piece
     * @param actorUserId who caused the change
     * @param correlationId the id of the request
     */
    public InventoryCommand {
        if (locationId == null) {
            throw new IllegalArgumentException("The location must not be null");
        }
        if (variantId == null) {
            throw new IllegalArgumentException("The variant must not be null");
        }
        if (quantityDelta == 0) {
            throw new IllegalArgumentException("The quantity may not be zero");
        }
        if (movementType == null) {
            throw new IllegalArgumentException("The movement type must not be null");
        }
    }

    /**
     * The number of pieces that change, without the sign.
     *
     * @return the number of pieces
     */
    public int quantity() {
        return Math.abs(quantityDelta);
    }

    /**
     * Whether the stock grows.
     *
     * @return {@code true} when the stock grows
     */
    public boolean isInbound() {
        return quantityDelta > 0;
    }

    /**
     * Builds a command without a reference to a document.
     *
     * @param locationId where the stock is
     * @param variantId which stock is changed
     * @param quantityDelta the signed change of the stock
     * @param movementType why the stock changes
     * @param actorUserId who caused the change
     * @return the command
     */
    public static InventoryCommand of(
            UUID locationId,
            UUID variantId,
            int quantityDelta,
            MovementType movementType,
            UUID actorUserId) {
        return new InventoryCommand(
                locationId,
                variantId,
                quantityDelta,
                movementType,
                null,
                null,
                null,
                null,
                null,
                actorUserId,
                null);
    }
}
