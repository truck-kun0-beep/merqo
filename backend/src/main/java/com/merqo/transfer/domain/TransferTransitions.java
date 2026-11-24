package com.merqo.transfer.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The transition table of a stock transfer.
 *
 * <p>Responsibility: answer "may a transfer go from this state to that state?".
 *
 * <p>The table:
 *
 * <pre>
 *   REQUESTED           -> APPROVED, REJECTED, CANCELLED
 *   APPROVED            -> DISPATCHED, CANCELLED
 *   DISPATCHED          -> PARTIALLY_RECEIVED, RECEIVED, CLOSED_WITH_SHORTAGE
 *   PARTIALLY_RECEIVED  -> PARTIALLY_RECEIVED, RECEIVED, CLOSED_WITH_SHORTAGE
 *   REJECTED            -> (nothing)
 *   RECEIVED            -> (nothing)
 *   CLOSED_WITH_SHORTAGE-> (nothing)
 *   CANCELLED          -> (nothing)
 * </pre>
 *
 * <p>Every invalid transition throws {@link InvalidTransitionException}, so a service can never
 * change a transfer into a state it may not have. The double submit of a dispatch is therefore
 * safe: the second click finds the state it expected to find and throws, and the client answers
 * with a 409 instead of moving the stock twice.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>State pattern as a table</b> - the whole state machine is one map, so it can be read in
 *       one look and printed in the documentation.
 *   <li><b>Open/closed principle</b> - a new state or a new transition is one line of the table.
 *   <li><b>Fail fast</b> - {@link #require(TransferStatus, TransferStatus)} throws instead of
 *       returning {@code false}, so a caller cannot forget to check.
 * </ul>
 */
public final class TransferTransitions {

    private static final Map<TransferStatus, Set<TransferStatus>> TABLE =
            new EnumMap<>(TransferStatus.class);

    static {
        TABLE.put(
                TransferStatus.REQUESTED,
                EnumSet.of(
                        TransferStatus.APPROVED,
                        TransferStatus.REJECTED,
                        TransferStatus.CANCELLED));
        TABLE.put(
                TransferStatus.APPROVED,
                EnumSet.of(TransferStatus.DISPATCHED, TransferStatus.CANCELLED));
        TABLE.put(
                TransferStatus.DISPATCHED,
                EnumSet.of(
                        TransferStatus.PARTIALLY_RECEIVED,
                        TransferStatus.RECEIVED,
                        TransferStatus.CLOSED_WITH_SHORTAGE));
        TABLE.put(
                TransferStatus.PARTIALLY_RECEIVED,
                EnumSet.of(
                        TransferStatus.PARTIALLY_RECEIVED,
                        TransferStatus.RECEIVED,
                        TransferStatus.CLOSED_WITH_SHORTAGE));
        TABLE.put(TransferStatus.REJECTED, EnumSet.noneOf(TransferStatus.class));
        TABLE.put(TransferStatus.RECEIVED, EnumSet.noneOf(TransferStatus.class));
        TABLE.put(TransferStatus.CLOSED_WITH_SHORTAGE, EnumSet.noneOf(TransferStatus.class));
        TABLE.put(TransferStatus.CANCELLED, EnumSet.noneOf(TransferStatus.class));
    }

    private TransferTransitions() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Whether a transfer may go from one state to another.
     *
     * @param from the current state
     * @param to the wanted state
     * @return {@code true} when the transition is allowed
     */
    public static boolean isAllowed(TransferStatus from, TransferStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<TransferStatus> allowed = TABLE.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * The states a transfer may go to from the given state.
     *
     * @param from the current state
     * @return the allowed states, never {@code null}
     */
    public static Set<TransferStatus> allowedFrom(TransferStatus from) {
        if (from == null) {
            return Set.of();
        }
        Set<TransferStatus> allowed = TABLE.get(from);
        return allowed == null ? Set.of() : Set.copyOf(allowed);
    }

    /**
     * Refuses a transition that is not allowed.
     *
     * @param from the current state
     * @param to the wanted state
     * @throws InvalidTransitionException when the transition is not allowed
     */
    public static void require(TransferStatus from, TransferStatus to) {
        if (!isAllowed(from, to)) {
            throw new InvalidTransitionException(
                    "A transfer cannot go from " + from + " to " + to + ".");
        }
    }
}
