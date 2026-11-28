package com.merqo.payments;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Component;

import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.payments.domain.PaymentReferenceGuard;

/**
 * PostgreSQL advisory-lock implementation of {@link PaymentReferenceGuard}.
 *
 * <p>Serializes concurrent writers for any {@code (method, reference)} in the global mobile-banking
 * TrxID namespace ({@code BKASH}, {@code NAGAD}) inside the caller's database transaction and
 * verifies the reference is absent from both POS {@code sale_payments} and non-rejected online
 * {@code order_payments}. Rejected payments release their TrxID so it may be reused cleanly.
 */
@Component
public class JpaPaymentReferenceGuard implements PaymentReferenceGuard {

    @PersistenceContext private EntityManager entityManager;

    @Override
    public void requireUniqueTrxId(String method, String normalizedReference) {
        if (method == null || normalizedReference == null || normalizedReference.isBlank()) {
            return;
        }
        String normalizedMethod = method.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"BKASH".equals(normalizedMethod) && !"NAGAD".equals(normalizedMethod)) {
            return;
        }
        String lockKey = normalizedMethod + ":" + normalizedReference;
        entityManager
                .createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(?1))")
                .setParameter(1, lockKey)
                .getSingleResult();

        Boolean usedInPos =
                (Boolean)
                        entityManager
                                .createNativeQuery(
                                        "SELECT EXISTS(SELECT 1 FROM sale_payments WHERE method = ?1 AND reference = ?2)")
                                .setParameter(1, normalizedMethod)
                                .setParameter(2, normalizedReference)
                                .getSingleResult();
        if (Boolean.TRUE.equals(usedInPos)) {
            throw new ConflictException(
                    ErrorCodes.DUPLICATE_REFERENCE,
                    "This " + normalizedMethod + " TrxID has already been used.");
        }

        Boolean usedOnline =
                (Boolean)
                        entityManager
                                .createNativeQuery(
                                        """
                                        SELECT EXISTS(
                                            SELECT 1 FROM order_payments p
                                            LEFT JOIN payment_decisions d ON d.payment_id = p.id
                                            WHERE p.method = ?1 AND p.reference = ?2
                                              AND (d.decision IS NULL OR d.decision <> 'REJECTED')
                                        )
                                        """)
                                .setParameter(1, normalizedMethod)
                                .setParameter(2, normalizedReference)
                                .getSingleResult();
        if (Boolean.TRUE.equals(usedOnline)) {
            throw new ConflictException(
                    ErrorCodes.DUPLICATE_REFERENCE,
                    "This " + normalizedMethod + " TrxID has already been used.");
        }
    }
}
