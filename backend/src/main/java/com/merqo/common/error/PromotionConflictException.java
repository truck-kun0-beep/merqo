package com.merqo.common.error;

import java.util.List;
import java.util.Map;

/**
 * The failure that names the promotions a new promotion fights.
 *
 * <p>Responsibility: carry the conflicts of a promotion in a shape the browser can show, so the
 * shopkeeper does not have to guess which promotions have to be ended first.
 *
 * <p>The conflict carries the id and the name of the promotion, the ids of the variants they have
 * in common and the SKUs of those variants. The SKUs are the names a shopkeeper recognises, and
 * they are the same SKUs the rest of the application shows.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Inheritance</b> - the class extends {@link ConflictException}, so the global handler
 *       treats it like every other conflict and the HTTP status stays 409.
 *   <li><b>Composition</b> - the class holds a list of small records instead of one long string.
 *   <li><b>Immutability</b> - the lists are copied when the exception is created, so nobody can
 *       change a conflict after it was reported.
 * </ul>
 */
public class PromotionConflictException extends ConflictException {

    private static final long serialVersionUID = 1L;

    private final List<Conflict> conflicts;

    /**
     * Creates the failure.
     *
     * @param message the sentence the reader sees
     * @param conflicts the promotions that are in the way
     */
    public PromotionConflictException(String message, List<Conflict> conflicts) {
        super(ErrorCodes.PROMOTION_CONFLICT, message);
        this.conflicts = List.copyOf(conflicts);
    }

    /**
     * The promotions that are in the way.
     *
     * @return the conflicts
     */
    public List<Conflict> getConflicts() {
        return conflicts;
    }

    /**
     * One promotion that is in the way, with the variants it shares with the new one.
     *
     * @param id the id of the promotion
     * @param name the name of the promotion
     * @param variantIds the ids of the variants the two promotions have in common
     * @param skus the SKUs of those variants
     */
    public record Conflict(String id, String name, List<String> variantIds, List<String> skus) {

        /**
         * Canonical constructor. The lists are copied.
         *
         * @param id the id of the promotion
         * @param name the name of the promotion
         * @param variantIds the ids of the shared variants
         * @param skus the SKUs of the shared variants
         */
        public Conflict {
            variantIds = List.copyOf(variantIds);
            skus = List.copyOf(skus);
        }

        /**
         * The conflict as the browser reads it.
         *
         * @return the map
         */
        public Map<String, Object> toMap() {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("id", id);
            body.put("name", name);
            body.put("variantIds", variantIds);
            body.put("skus", skus);
            return body;
        }
    }
}
