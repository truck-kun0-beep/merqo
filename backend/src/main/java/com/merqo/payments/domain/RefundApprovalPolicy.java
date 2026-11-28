package com.merqo.payments.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Pure domain four-eyes approval policy for refund approve and reject actions.
 *
 * <p>Responsibility: verify the actor holds {@code REFUND_APPROVE} at the order location (or is the
 * owner) and forbid non-owner requesters from approving or rejecting their own refund requests
 * ({@code SELF_APPROVAL_NOT_ALLOWED}).
 *
 * <p>OOP concept: <b>Policy Object</b> — decouples separation-of-duties enforcement from HTTP and
 * database code.
 */
public final class RefundApprovalPolicy {

    private RefundApprovalPolicy() {}

    /**
     * Asserts that the caller may approve or reject the refund request.
     *
     * @param actorId caller user ID
     * @param isOwner whether caller is the owner
     * @param hasRefundApprove whether caller holds {@code REFUND_APPROVE} at the order location
     * @param requestedBy user ID of the staff member who submitted the refund request
     */
    public static void assertCanDecide(
            UUID actorId, boolean isOwner, boolean hasRefundApprove, UUID requestedBy) {
        if (isOwner) {
            return;
        }
        if (!hasRefundApprove) {
            throw new PaymentRuleException(
                    "FORBIDDEN", 403, "You do not have permission to decide refunds here.");
        }
        if (actorId != null && Objects.equals(actorId, requestedBy)) {
            throw new PaymentRuleException(
                    "SELF_APPROVAL_NOT_ALLOWED",
                    422,
                    "Requester may not approve or reject their own refund request.");
        }
    }
}
