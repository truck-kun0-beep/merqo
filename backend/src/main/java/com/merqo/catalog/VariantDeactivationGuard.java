package com.merqo.catalog;

/**
 * A veto on deactivating a variant.
 *
 * <p>Responsibility: let a module say "this variant may not be deactivated yet". The transfer
 * module registers one of these, so a variant that is on an open transfer keeps its barcode and
 * stays sellable until the transfer is finished.
 *
 * <p>The idea is the same as the location guards of Phase 1: the guard is found by Spring, so a
 * later module adds its own veto without changing the catalog service.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Strategy</b> - every guard is one small object with one answer.
 *   <li><b>Observer</b> - Spring discovers the guards, so adding one needs no change anywhere else.
 *   <li><b>Interface</b> - the catalog service only knows this idea.
 *   <li><b>Open/closed principle</b> - a new module adds a guard instead of changing the catalog
 *       service.
 * </ul>
 */
public interface VariantDeactivationGuard {

    /**
     * Whether the variant may be deactivated.
     *
     * @param variant the variant
     * @return the answer
     */
    GuardResult check(ProductVariant variant);

    /**
     * The answer of one guard.
     *
     * @param allowed whether the variant may be deactivated
     * @param reason why it may not, {@code null} when it may
     */
    record GuardResult(boolean allowed, String reason) {

        /**
         * Creates an answer.
         *
         * @param allowed whether the variant may be deactivated
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
         * The variant may be deactivated.
         *
         * @return the answer
         */
        public static GuardResult allow() {
            return new GuardResult(true, null);
        }

        /**
         * The variant may not be deactivated.
         *
         * @param reason why it may not
         * @return the answer
         */
        public static GuardResult refuse(String reason) {
            return new GuardResult(false, reason);
        }
    }
}
