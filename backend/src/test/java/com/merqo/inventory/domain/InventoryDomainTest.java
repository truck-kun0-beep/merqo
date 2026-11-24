package com.merqo.inventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The tests of the inventory rules.
 *
 * <p>These tests run without Spring and without a database, because the classes under test are
 * plain Java objects.
 */
class InventoryDomainTest {

    @Nested
    @DisplayName("the balance")
    class Balance {

        @Test
        @DisplayName("starts with the numbers it was given")
        void startsWithTheNumbersItWasGiven() {
            InventoryBalance balance = new InventoryBalance(10, 3, 2);

            assertEquals(10, balance.getOnHand());
            assertEquals(3, balance.getReserved());
            assertEquals(2, balance.getInInspection());
            assertEquals(7, balance.getAvailable());
        }

        @Test
        @DisplayName("refuses a negative number")
        void refusesANegativeNumber() {
            assertThrows(IllegalArgumentException.class, () -> new InventoryBalance(-1, 0, 0));
        }

        @Test
        @DisplayName("refuses a reservation that is bigger than the stock")
        void refusesAReservationThatIsTooBig() {
            assertThrows(IllegalArgumentException.class, () -> new InventoryBalance(2, 3, 0));
        }

        @Test
        @DisplayName("adds stock when it arrives")
        void addsStockWhenItArrives() {
            InventoryBalance balance = new InventoryBalance(10, 0, 0).receive(5);

            assertEquals(15, balance.getOnHand());
        }

        @Test
        @DisplayName("takes stock away when it leaves")
        void takesStockAwayWhenItLeaves() {
            InventoryBalance balance = new InventoryBalance(10, 0, 0).remove(4);

            assertEquals(6, balance.getOnHand());
        }

        @Test
        @DisplayName("refuses to take away more than the available stock")
        void refusesToTakeAwayTooMuch() {
            InventoryBalance balance = new InventoryBalance(10, 3, 0);

            assertThrows(InsufficientStockException.class, () -> balance.remove(8));
        }

        @Test
        @DisplayName("promises stock when it is reserved")
        void promisesStockWhenItIsReserved() {
            InventoryBalance balance = new InventoryBalance(10, 0, 0).reserve(4);

            assertEquals(4, balance.getReserved());
            assertEquals(6, balance.getAvailable());
        }

        @Test
        @DisplayName("refuses to reserve more than the available stock")
        void refusesToReserveTooMuch() {
            InventoryBalance balance = new InventoryBalance(10, 3, 0);

            assertThrows(InsufficientStockException.class, () -> balance.reserve(8));
        }

        @Test
        @DisplayName("gives the promise back when it is released")
        void givesThePromiseBack() {
            InventoryBalance balance = new InventoryBalance(10, 3, 0).release(2);

            assertEquals(1, balance.getReserved());
        }

        @Test
        @DisplayName("takes the stock and the promise away when a reservation is fulfilled")
        void takesTheStockAwayWhenAReservationIsFulfilled() {
            InventoryBalance balance = new InventoryBalance(10, 3, 0).fulfillReservation(3);

            assertEquals(7, balance.getOnHand());
            assertEquals(0, balance.getReserved());
        }

        @Test
        @DisplayName("moves goods into inspection")
        void movesGoodsIntoInspection() {
            InventoryBalance balance = new InventoryBalance(10, 0, 0).moveToInspection(3);

            assertEquals(7, balance.getOnHand());
            assertEquals(3, balance.getInInspection());
        }

        @Test
        @DisplayName("puts the goods that passed the inspection back on the shelf")
        void putsTheAcceptedGoodsBackOnTheShelf() {
            InventoryBalance balance = new InventoryBalance(10, 0, 3).resolveInspection(3, 2);

            assertEquals(12, balance.getOnHand());
            assertEquals(0, balance.getInInspection());
        }

        @Test
        @DisplayName("writes the goods that failed the inspection off")
        void writesTheRejectedGoodsOff() {
            InventoryBalance balance = new InventoryBalance(10, 0, 3).resolveInspection(3, 1);

            assertEquals(11, balance.getOnHand());
            assertEquals(0, balance.getInInspection());
        }
    }

    @Nested
    @DisplayName("the low stock rule")
    class LowStock {

        @Test
        @DisplayName("is low when the available stock is at the reorder point")
        void isLowAtTheReorderPoint() {
            assertTrue(LowStockPolicy.isLowStock(5, 5));
        }

        @Test
        @DisplayName("is not low when there is more than the reorder point")
        void isNotLowAboveTheReorderPoint() {
            assertFalse(LowStockPolicy.isLowStock(6, 5));
        }

        @Test
        @DisplayName("is not low when the reorder point is zero")
        void isNotLowWhenTheReorderPointIsZero() {
            assertFalse(LowStockPolicy.isLowStock(0, 0));
        }

        @Test
        @DisplayName("uses the default reorder point when there is no policy")
        void usesTheDefaultReorderPoint() {
            assertTrue(LowStockPolicy.isLowStock(5, null));
            assertFalse(LowStockPolicy.isLowStock(6, null));
        }
    }

    @Nested
    @DisplayName("the unusual adjustment rule")
    class UnusualAdjustment {

        @Test
        @DisplayName("is unusual when the value is above the threshold")
        void isUnusualAboveTheThreshold() {
            assertTrue(
                    AdjustmentFlagPolicy.isUnusual(
                            new BigDecimal("5000.01"), new BigDecimal("5000.00")));
        }

        @Test
        @DisplayName("is not unusual when the value is exactly the threshold")
        void isNotUnusualAtTheThreshold() {
            assertFalse(
                    AdjustmentFlagPolicy.isUnusual(
                            new BigDecimal("5000.00"), new BigDecimal("5000.00")));
        }

        @Test
        @DisplayName("uses the absolute value, so a big correction counts as well")
        void usesTheAbsoluteValue() {
            assertTrue(
                    AdjustmentFlagPolicy.isUnusual(
                            new BigDecimal("-6000.00"), new BigDecimal("5000.00")));
        }
    }

    @Nested
    @DisplayName("the stock count rule")
    class StockCount {

        @Test
        @DisplayName("the difference is the counted quantity minus the quantity at the start")
        void theDifferenceIsCountedMinusStart() {
            assertEquals(3, StockCountCalculator.variance(10, 13));
        }

        @Test
        @DisplayName("the difference is empty while the line is not counted yet")
        void theDifferenceIsEmptyWhileNotCounted() {
            assertEquals(null, StockCountCalculator.variance(10, null));
        }

        @Test
        @DisplayName("the correction keeps the sales that happened during the count")
        void theCorrectionKeepsTheSalesOfTheCount() {
            // The count started with 10, the counter counted 8, and a sale of 2 happened in
            // between.
            // The correction is 8 - 10 = -2, applied to the stock of the moment (8), so the stock
            // becomes 6. The two pieces that were sold are not given back.
            int correction = StockCountCalculator.correction(10, 8, 8);

            assertEquals(-2, correction);
            assertEquals(6, 8 + correction);
        }

        @Test
        @DisplayName("the correction is refused when it would go under the reservation")
        void theCorrectionIsRefusedUnderTheReservation() {
            // The count started with 10, the counter counted 2, so the correction is -8. Applied
            // to the stock of the moment (5) the stock would become -3, which is below the
            // reservation of 1, so the line may not be applied.
            assertFalse(StockCountCalculator.mayApply(10, 2, 5, 1));
            // A count that found the same number as the start may always be applied.
            assertTrue(StockCountCalculator.mayApply(10, 10, 5, 1));
        }
    }

    @Nested
    @DisplayName("the movement types")
    class Movements {

        @Test
        @DisplayName("a movement in adds stock and a movement out takes it away")
        void theSignsAreRight() {
            assertEquals(1, MovementType.ADJUSTMENT_IN.getSign());
            assertEquals(-1, MovementType.ADJUSTMENT_OUT.getSign());
            assertEquals(-1, MovementType.TRANSFER_OUT.getSign());
            assertEquals(1, MovementType.TRANSFER_IN.getSign());
        }

        @Test
        @DisplayName("a movement that takes stock away gives a negative number")
        void theSignedQuantityIsNegative() {
            assertEquals(-5, MovementType.ADJUSTMENT_OUT.signedQuantity(5));
            assertEquals(5, MovementType.ADJUSTMENT_IN.signedQuantity(5));
        }

        @Test
        @DisplayName("the names of the later phases are already there")
        void theNamesOfTheLaterPhasesAreThere() {
            assertEquals(
                    MovementType.Bucket.INSPECTION, MovementType.INSPECTION_RESTOCK.getBucket());
            assertEquals(
                    MovementType.Bucket.INSPECTION, MovementType.INSPECTION_WRITE_OFF.getBucket());
            assertEquals(MovementType.Bucket.ON_HAND, MovementType.POS_SALE.getBucket());
            assertEquals(MovementType.Bucket.ON_HAND, MovementType.PURCHASE_RECEIPT.getBucket());
        }
    }
}
