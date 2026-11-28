package com.merqo.payments.domain;

import java.util.Optional;
import java.util.UUID;

import com.merqo.common.money.Money;

/**
 * Simulated {@link PaymentVerifier} backed by an abstract transaction lookup when {@code
 * PAYMENT_MODE=mock}.
 *
 * <p>Responsibility: match a claim by {@code (method, reference)}, detect unclaimed/mismatched/
 * already-claimed states, and mark the simulated transaction claimed upon confirmation.
 *
 * <p>OOP concept: <b>Dependency Inversion</b> — depends only on {@link SimulatedTransactionStore},
 * keeping simulated provider matching unit-testable without Spring or JPA.
 */
public final class MockPaymentVerifier implements PaymentVerifier {

    /** Port for reading and claiming simulated provider transactions. */
    public interface SimulatedTransactionStore {

        Optional<SimulatedTransaction> findByMethodAndReference(
                OnlinePaymentMethod method, String reference);

        void markClaimed(UUID transactionId, UUID paymentId);
    }

    /**
     * Minimal projection of a simulated provider transaction.
     *
     * @param id row identifier
     * @param method channel
     * @param reference normalized provider reference
     * @param amount simulated amount
     * @param claimedByPaymentId claiming payment id, if already used
     */
    public record SimulatedTransaction(
            UUID id,
            OnlinePaymentMethod method,
            String reference,
            Money amount,
            UUID claimedByPaymentId) {}

    private final SimulatedTransactionStore store;

    public MockPaymentVerifier(SimulatedTransactionStore store) {
        this.store = store;
    }

    @Override
    public String verifierName() {
        return "mock";
    }

    @Override
    public VerificationResult verify(PaymentClaim claim) {
        if (claim == null || claim.method() == null || claim.reference() == null) {
            return VerificationResult.notFound("Payment claim is missing method or reference.");
        }
        Optional<SimulatedTransaction> match =
                store.findByMethodAndReference(claim.method(), claim.reference());
        if (match.isEmpty()) {
            return VerificationResult.notFound(
                    "No simulated provider transaction matched this reference.");
        }
        SimulatedTransaction tx = match.get();
        if (tx.claimedByPaymentId() != null && !tx.claimedByPaymentId().equals(claim.paymentId())) {
            return VerificationResult.alreadyClaimed(
                    tx.reference(), "Provider transaction is already claimed by another payment.");
        }
        if (!tx.amount().equals(claim.amount())) {
            return VerificationResult.amountMismatch(
                    tx.reference(),
                    "Provider transaction amount ("
                            + tx.amount().amount().toPlainString()
                            + ") does not match recorded amount ("
                            + claim.amount().amount().toPlainString()
                            + ").");
        }
        if (tx.claimedByPaymentId() == null) {
            store.markClaimed(tx.id(), claim.paymentId());
        }
        return VerificationResult.confirmed(
                tx.reference(), "Confirmed via mock provider transaction.");
    }
}
