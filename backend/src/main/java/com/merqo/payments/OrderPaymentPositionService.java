package com.merqo.payments;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Service;

import com.merqo.common.money.Money;
import com.merqo.orders.Order;
import com.merqo.payments.PaymentDtos.OrderPaymentPositionView;
import com.merqo.payments.domain.ExpectedPrepayment;
import com.merqo.payments.domain.OrderPaymentPosition;

/**
 * Computes derived {@link OrderPaymentPosition} metrics from append-only payment, refund, and COD
 * ledger tables without storing derived columns on orders.
 */
@Service
public class OrderPaymentPositionService {

    @PersistenceContext private EntityManager entityManager;

    /** Computes the authoritative payment/refund/COD position for a single order. */
    public OrderPaymentPosition computePosition(Order order) {
        ExpectedPrepayment expected =
                ExpectedPrepayment.from(
                        order.getPaymentMode(),
                        Money.of(order.getTotal()),
                        Money.of(order.getAdvanceAmount()),
                        Money.of(order.getCodAmount()));

        Object[] paymentSums =
                (Object[])
                        entityManager
                                .createNativeQuery(
                                        """
                                        SELECT
                                          COALESCE(SUM(CASE WHEN d.decision = 'VERIFIED' THEN p.amount ELSE 0 END), 0),
                                          COALESCE(SUM(CASE WHEN d.decision IS NULL THEN p.amount ELSE 0 END), 0),
                                          COALESCE(SUM(CASE WHEN d.decision = 'REJECTED' THEN p.amount ELSE 0 END), 0)
                                        FROM order_payments p
                                        LEFT JOIN payment_decisions d ON d.payment_id = p.id
                                        WHERE p.order_id = ?1
                                        """)
                                .setParameter(1, order.getId())
                                .getSingleResult();

        Object[] refundSums =
                (Object[])
                        entityManager
                                .createNativeQuery(
                                        """
                                        SELECT
                                          COALESCE(SUM(CASE WHEN EXISTS (
                                              SELECT 1 FROM refund_events e WHERE e.refund_id = r.id AND e.event_type = 'PAID'
                                          ) THEN r.amount ELSE 0 END), 0),
                                          COALESCE(SUM(CASE WHEN NOT EXISTS (
                                              SELECT 1 FROM refund_events e WHERE e.refund_id = r.id AND e.event_type IN ('PAID', 'REJECTED')
                                          ) THEN r.amount ELSE 0 END), 0)
                                        FROM refund_requests r
                                        WHERE r.order_id = ?1
                                        """)
                                .setParameter(1, order.getId())
                                .getSingleResult();

        BigDecimal codCollected =
                toBigDecimal(
                        entityManager
                                .createNativeQuery(
                                        "SELECT COALESCE(SUM(amount), 0) FROM cod_collections WHERE order_id = ?1")
                                .setParameter(1, order.getId())
                                .getSingleResult());

        Object[] settledSums =
                (Object[])
                        entityManager
                                .createNativeQuery(
                                        """
                                        SELECT
                                          COALESCE(SUM(cod_collected_amount), 0),
                                          COALESCE(SUM(fee), 0)
                                        FROM courier_settlement_lines
                                        WHERE order_id = ?1
                                        """)
                                .setParameter(1, order.getId())
                                .getSingleResult();

        return OrderPaymentPosition.compute(
                expected,
                Money.of(toBigDecimal(paymentSums[0])),
                Money.of(toBigDecimal(paymentSums[1])),
                Money.of(toBigDecimal(paymentSums[2])),
                Money.of(toBigDecimal(refundSums[0])),
                Money.of(toBigDecimal(refundSums[1])),
                Money.of(codCollected),
                Money.of(toBigDecimal(settledSums[0])),
                Money.of(toBigDecimal(settledSums[1])));
    }

    /** Convenience helper returning the API view DTO for an order's derived position. */
    public OrderPaymentPositionView computePositionView(Order order) {
        return OrderPaymentPositionView.from(computePosition(order), order.getStatus());
    }

    /** Batch-computes payment positions for a collection of orders. */
    public Map<UUID, OrderPaymentPosition> computePositions(Collection<Order> orders) {
        Map<UUID, OrderPaymentPosition> map = new HashMap<>();
        for (Order order : orders) {
            map.put(order.getId(), computePosition(order));
        }
        return map;
    }

    private static BigDecimal toBigDecimal(Object raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        if (raw instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(raw.toString());
    }
}
