package com.merqo.location;

/**
 * A veto on deactivating a location.
 *
 * <p>Responsibility: let a module say "this location may not be deactivated yet". Later prompts add
 * their own guards, for example one that refuses when stock is still present.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Strategy</b> - every guard is one small object with one answer.
 *   <li><b>Observer</b> - Spring discovers the guards, so adding one needs no change anywhere else.
 *   <li><b>Interface</b> - the location service only knows this idea.
 *   <li><b>Open/closed principle</b> - a new module adds a guard instead of changing the location
 *       service.
 * </ul>
 */
public interface LocationDeactivationGuard {

    /**
     * Whether the location may be deactivated.
     *
     * @param location the location
     * @return the answer
     */
    GuardResult check(Location location);

    /**
     * The answer of one guard.
     *
     * @param allowed whether the location may be deactivated
     * @param reason why it may not, {@code null} when it may
     */
    record GuardResult(boolean allowed, String reason) {

        /**
         * Creates an answer.
         *
         * @param allowed whether the location may be deactivated
         * @param reason the reason
         */
        public GuardResult {
            if (allowed && reason != null) {
                throw new IllegalArgumentException("An allowed result has no reason");
            }
            if (!allowed && reason == null) {
                throw new IllegalArgumentException("A refused result needs a reason");
            }
        }

        /**
         * The location may be deactivated.
         *
         * @return the answer
         */
        public static GuardResult allow() {
            return new GuardResult(true, null);
        }

        /**
         * The location may not be deactivated.
         *
         * @param reason why it may not
         * @return the answer
         */
        public static GuardResult refuse(String reason) {
            return new GuardResult(false, reason);
        }
    }
}
