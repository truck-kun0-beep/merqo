package com.merqo.payments.domain;

/**
 * Domain guard ensuring global TrxID uniqueness across POS sales and non-rejected order payments.
 *
 * <p>Responsibility: serialize concurrent writers for the same {@code (method, reference)} pair via
 * transactional PostgreSQL advisory locking and check both {@code sale_payments} and non-rejected
 * {@code order_payments} before persisting.
 *
 * <p>OOP concept: <b>Interface / Dependency Inversion</b> — allows POS {@code SaleService} and
 * online payment services to share one concurrency-safe uniqueness guard without coupling domains
 * to SQL details.
 */
public interface PaymentReferenceGuard {

    /**
     * Checks that {@code (method, normalizedReference)} is currently unused for methods that share
     * the global TrxID namespace ({@code BKASH}, {@code NAGAD}), acquiring a transaction-scoped
     * lock so concurrent writers cannot both win.
     *
     * @param method payment method code ({@code BKASH}, {@code NAGAD}, {@code CARD}, {@code BANK},
     *     etc.)
     * @param normalizedReference upper-cased normalized reference (nullable for CASH)
     */
    void requireUniqueTrxId(String method, String normalizedReference);
}
