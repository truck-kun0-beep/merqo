package com.merqo.transfer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The tests of the transfer rules.
 *
 * <p>These tests run without Spring and without a database.
 */
class TransferDomainTest {

    @Nested
    @DisplayName("the transition table")
    class Transitions {

        @Test
        @DisplayName("allows a request to be approved")
        void allowsARequestToBeApproved() {
            assertTrue(
                    TransferTransitions.isAllowed(
                            TransferStatus.REQUESTED, TransferStatus.APPROVED));
        }

        @Test
        @DisplayName("allows a request to be refused and to be called off")
        void allowsARequestToBeRefusedOrCalledOff() {
            assertTrue(
                    TransferTransitions.isAllowed(
                            TransferStatus.REQUESTED, TransferStatus.REJECTED));
            assertTrue(
                    TransferTransitions.isAllowed(
                            TransferStatus.REQUESTED, TransferStatus.CANCELLED));
        }

        @Test
        @DisplayName("does not allow a request to be dispatched")
        void doesNotAllowARequestToBeDispatched() {
            assertFalse(
                    TransferTransitions.isAllowed(
                            TransferStatus.REQUESTED, TransferStatus.DISPATCHED));
        }

        @Test
        @DisplayName("allows an approval to be dispatched and to be called off")
        void allowsAnApprovalToBeDispatched() {
            assertTrue(
                    TransferTransitions.isAllowed(
                            TransferStatus.APPROVED, TransferStatus.DISPATCHED));
            assertTrue(
                    TransferTransitions.isAllowed(
                            TransferStatus.APPROVED, TransferStatus.CANCELLED));
        }

        @Test
        @DisplayName("does not allow a dispatch to be approved again")
        void doesNotAllowADispatchToBeApprovedAgain() {
            assertFalse(
                    TransferTransitions.isAllowed(
                            TransferStatus.DISPATCHED, TransferStatus.APPROVED));
        }

        @Test
        @DisplayName("allows a dispatch to be received or to be closed with a shortage")
        void allowsADispatchToBeReceived() {
            assertTrue(
                    TransferTransitions.isAllowed(
                            TransferStatus.DISPATCHED, TransferStatus.RECEIVED));
            assertTrue(
                    TransferTransitions.isAllowed(
                            TransferStatus.DISPATCHED, TransferStatus.PARTIALLY_RECEIVED));
            assertTrue(
                    TransferTransitions.isAllowed(
                            TransferStatus.DISPATCHED, TransferStatus.CLOSED_WITH_SHORTAGE));
        }

        @Test
        @DisplayName("allows a partial receipt to become a full receipt")
        void allowsAPartialReceiptToBecomeAFullReceipt() {
            assertTrue(
                    TransferTransitions.isAllowed(
                            TransferStatus.PARTIALLY_RECEIVED, TransferStatus.RECEIVED));
        }

        @Test
        @DisplayName("does not allow anything after a final state")
        void doesNotAllowAnythingAfterAFinalState() {
            for (TransferStatus finalState :
                    new TransferStatus[] {
                        TransferStatus.REJECTED,
                        TransferStatus.RECEIVED,
                        TransferStatus.CLOSED_WITH_SHORTAGE,
                        TransferStatus.CANCELLED
                    }) {
                assertTrue(finalState.isFinal());
                for (TransferStatus target : TransferStatus.values()) {
                    assertFalse(
                            TransferTransitions.isAllowed(finalState, target),
                            finalState + " must not go to " + target);
                }
            }
        }

        @Test
        @DisplayName("throws when the transition is not allowed")
        void throwsWhenTheTransitionIsNotAllowed() {
            assertThrows(
                    InvalidTransitionException.class,
                    () ->
                            TransferTransitions.require(
                                    TransferStatus.REQUESTED, TransferStatus.DISPATCHED));
        }
    }

    @Nested
    @DisplayName("the line of a transfer")
    class Line {

        @Test
        @DisplayName("starts with the quantity that was asked for")
        void startsWithTheRequestedQuantity() {
            TransferLine line = new TransferLine(10);

            assertEquals(10, line.getRequested());
            assertEquals(0, line.getApproved());
            assertEquals(0, line.getInTransit());
        }

        @Test
        @DisplayName("refuses an approval that is bigger than the request")
        void refusesAnApprovalThatIsTooBig() {
            assertThrows(IllegalArgumentException.class, () -> new TransferLine(10).approve(11));
        }

        @Test
        @DisplayName("refuses a dispatch that is bigger than the approval")
        void refusesADispatchThatIsTooBig() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TransferLine(10).approve(5).dispatch(6));
        }

        @Test
        @DisplayName("the goods in transit are the dispatched minus the received")
        void theGoodsInTransitAreTheDispatchedMinusTheReceived() {
            TransferLine line = new TransferLine(10).approve(10).dispatch(10).receive(4);

            assertEquals(6, line.getInTransit());
        }

        @Test
        @DisplayName("the shortage is the part that never arrived")
        void theShortageIsThePartThatNeverArrived() {
            TransferLine line =
                    new TransferLine(10).approve(10).dispatch(10).receive(4).closeWithShortage();

            assertEquals(6, line.getShortage());
            assertEquals(0, line.getInTransit());
            assertEquals(4, line.getReceived());
        }

        @Test
        @DisplayName("refuses a receipt that is bigger than what was dispatched")
        void refusesAReceiptThatIsTooBig() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TransferLine(10).approve(10).dispatch(10).receive(11));
        }

        @Test
        @DisplayName("refuses a requested quantity of zero")
        void refusesARequestedQuantityOfZero() {
            assertThrows(IllegalArgumentException.class, () -> new TransferLine(0));
        }
    }
}
