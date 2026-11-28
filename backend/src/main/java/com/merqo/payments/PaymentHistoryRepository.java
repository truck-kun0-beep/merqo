package com.merqo.payments;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import org.springframework.stereotype.Repository;

import com.merqo.payments.PaymentDtos.PaymentHistoryRowView;
import com.merqo.payments.PaymentDtos.PaymentSummaryBucketView;
import com.merqo.payments.PaymentDtos.PaymentSummaryResponse;

/**
 * Read-only native SQL union repository for unified POS payments, order advance payments, refund
 * payouts, and courier COD settlements, with SQL-level location and global permission scoping.
 */
@Repository
public class PaymentHistoryRepository {

    private static final UUID DUMMY_UUID = new UUID(0L, 0L);

    @PersistenceContext private EntityManager entityManager;

    private static final String UNION_CTE =
            """
            WITH unified_payments AS (
                SELECT
                    'POS_SALE'::text AS row_type,
                    s.created_at AS occurred_at,
                    sp.method::text AS method,
                    sp.amount AS amount,
                    sp.reference::text AS reference,
                    s.receipt_number::text AS document_number,
                    ''::text AS aux_number,
                    ''::text AS phone_raw,
                    ''::text AS phone_normalized,
                    CAST(NULL AS UUID) AS order_id,
                    s.id AS sale_id,
                    CAST(NULL AS UUID) AS settlement_id,
                    s.location_id AS location_id,
                    COALESCE(l.name, '')::text AS location_name,
                    'COMPLETED'::text AS status,
                    COALESCE(u.username, 'unknown')::text AS recorded_by
                FROM sale_payments sp
                JOIN sales s ON s.id = sp.sale_id
                LEFT JOIN locations l ON l.id = s.location_id
                LEFT JOIN app_users u ON u.id = s.cashier_id
                WHERE :hasLocScope = TRUE AND s.location_id IN (:locIds)

                UNION ALL

                SELECT
                    'ORDER_PAYMENT'::text AS row_type,
                    op.recorded_at AS occurred_at,
                    op.method::text AS method,
                    op.amount AS amount,
                    op.reference::text AS reference,
                    o.order_number::text AS document_number,
                    op.payment_number::text AS aux_number,
                    COALESCE(o.phone_raw, '')::text AS phone_raw,
                    COALESCE(o.phone_normalized, '')::text AS phone_normalized,
                    o.id AS order_id,
                    CAST(NULL AS UUID) AS sale_id,
                    CAST(NULL AS UUID) AS settlement_id,
                    o.location_id AS location_id,
                    COALESCE(l.name, '')::text AS location_name,
                    COALESCE(pd.decision, 'PENDING_VERIFICATION')::text AS status,
                    COALESCE(u.username, 'unknown')::text AS recorded_by
                FROM order_payments op
                JOIN orders o ON o.id = op.order_id
                LEFT JOIN payment_decisions pd ON pd.payment_id = op.id
                LEFT JOIN locations l ON l.id = o.location_id
                LEFT JOIN app_users u ON u.id = op.recorded_by
                WHERE :hasLocScope = TRUE AND o.location_id IN (:locIds)

                UNION ALL

                SELECT
                    'REFUND_PAYOUT'::text AS row_type,
                    re.created_at AS occurred_at,
                    COALESCE(re.payout_method, 'CASH')::text AS method,
                    rr.amount AS amount,
                    re.payout_reference::text AS reference,
                    o.order_number::text AS document_number,
                    rr.refund_number::text AS aux_number,
                    COALESCE(o.phone_raw, '')::text AS phone_raw,
                    COALESCE(o.phone_normalized, '')::text AS phone_normalized,
                    o.id AS order_id,
                    CAST(NULL AS UUID) AS sale_id,
                    CAST(NULL AS UUID) AS settlement_id,
                    o.location_id AS location_id,
                    COALESCE(l.name, '')::text AS location_name,
                    'PAID'::text AS status,
                    COALESCE(u.username, 'unknown')::text AS recorded_by
                FROM refund_events re
                JOIN refund_requests rr ON rr.id = re.refund_id
                JOIN orders o ON o.id = rr.order_id
                LEFT JOIN locations l ON l.id = o.location_id
                LEFT JOIN app_users u ON u.id = re.actor_id
                WHERE re.event_type = 'PAID'
                  AND :hasLocScope = TRUE AND o.location_id IN (:locIds)

                UNION ALL

                SELECT
                    'COD_SETTLEMENT'::text AS row_type,
                    cs.recorded_at AS occurred_at,
                    cs.receive_method::text AS method,
                    cs.amount_received AS amount,
                    cs.reference::text AS reference,
                    cs.settlement_number::text AS document_number,
                    cs.courier::text AS aux_number,
                    ''::text AS phone_raw,
                    ''::text AS phone_normalized,
                    CAST(NULL AS UUID) AS order_id,
                    CAST(NULL AS UUID) AS sale_id,
                    cs.id AS settlement_id,
                    CAST(NULL AS UUID) AS location_id,
                    'Company-wide'::text AS location_name,
                    'SETTLED'::text AS status,
                    COALESCE(u.username, 'unknown')::text AS recorded_by
                FROM courier_settlements cs
                LEFT JOIN app_users u ON u.id = cs.recorded_by
                WHERE :canManageCod = TRUE AND :locationFilterSet = FALSE
            )
            """;

    private static final String FILTER_CLAUSE =
            """
            WHERE (:hasTypeFilter = FALSE OR up.row_type = :typeFilter)
              AND (:hasMethodFilter = FALSE OR up.method = :methodFilter)
              AND (:hasStatusFilter = FALSE OR up.status = :statusFilter)
              AND (:locationFilterSet = FALSE OR up.location_id = :locationId)
              AND (:hasFrom = FALSE OR up.occurred_at >= :fromInstant)
              AND (:hasTo = FALSE OR up.occurred_at < :toInstant)
              AND (
                  :hasQ = FALSE
                  OR LOWER(COALESCE(up.document_number, '')) LIKE :qLike
                  OR LOWER(COALESCE(up.aux_number, '')) LIKE :qLike
                  OR LOWER(COALESCE(up.reference, '')) LIKE :qLike
                  OR LOWER(COALESCE(up.phone_raw, '')) LIKE :qLike
                  OR (:hasPhoneNorm = TRUE AND up.phone_normalized LIKE :phoneNormLike)
              )
            """;

    public record SearchCriteria(
            Collection<UUID> visibleLocationIds,
            boolean canManageCod,
            String type,
            String method,
            String status,
            UUID locationId,
            Instant fromInstant,
            Instant toInstant,
            String qLike,
            String phoneNormLike) {}

    public record HistoryPageResult(List<PaymentHistoryRowView> rows, long totalElements) {}

    public HistoryPageResult findHistoryPage(SearchCriteria c, int page, int size) {
        String countSql = UNION_CTE + " SELECT COUNT(*) FROM unified_payments up " + FILTER_CLAUSE;
        Query countQuery = entityManager.createNativeQuery(countSql);
        bindParameters(countQuery, c);
        long total = ((Number) countQuery.getSingleResult()).longValue();
        if (total == 0L) {
            return new HistoryPageResult(List.of(), 0L);
        }

        String dataSql =
                UNION_CTE
                        + """
                        SELECT
                            up.row_type,
                            up.occurred_at,
                            up.method,
                            up.amount,
                            up.reference,
                            up.document_number,
                            up.order_id,
                            up.sale_id,
                            up.settlement_id,
                            up.location_name,
                            up.status,
                            up.recorded_by
                        FROM unified_payments up
                        """
                        + FILTER_CLAUSE
                        + " ORDER BY up.occurred_at DESC, up.document_number DESC LIMIT :limit OFFSET :offset";

        Query dataQuery = entityManager.createNativeQuery(dataSql);
        bindParameters(dataQuery, c);
        dataQuery.setParameter("limit", size);
        dataQuery.setParameter("offset", page * size);

        @SuppressWarnings("unchecked")
        List<Object[]> rawRows = dataQuery.getResultList();
        List<PaymentHistoryRowView> rows = new ArrayList<>(rawRows.size());
        for (Object[] r : rawRows) {
            rows.add(
                    new PaymentHistoryRowView(
                            (String) r[0],
                            toInstant(r[1]),
                            (String) r[2],
                            toBigDecimal(r[3]),
                            (String) r[4],
                            (String) r[5],
                            (UUID) r[6],
                            (UUID) r[7],
                            (UUID) r[8],
                            (String) r[9],
                            (String) r[10],
                            (String) r[11]));
        }
        return new HistoryPageResult(rows, total);
    }

    public PaymentSummaryResponse summarize(SearchCriteria c) {
        String byTypeSql =
                UNION_CTE
                        + " SELECT up.row_type, COUNT(*), COALESCE(SUM(up.amount), 0) FROM unified_payments up "
                        + FILTER_CLAUSE
                        + " GROUP BY up.row_type ORDER BY up.row_type";
        Query byTypeQuery = entityManager.createNativeQuery(byTypeSql);
        bindParameters(byTypeQuery, c);
        @SuppressWarnings("unchecked")
        List<Object[]> typeRows = byTypeQuery.getResultList();
        List<PaymentSummaryBucketView> byType = new ArrayList<>(typeRows.size());
        long totalCount = 0L;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Object[] r : typeRows) {
            long count = ((Number) r[1]).longValue();
            BigDecimal sum = toBigDecimal(r[2]);
            totalCount += count;
            totalAmount = totalAmount.add(sum);
            byType.add(new PaymentSummaryBucketView((String) r[0], count, sum));
        }

        String byMethodSql =
                UNION_CTE
                        + " SELECT up.method, COUNT(*), COALESCE(SUM(up.amount), 0) FROM unified_payments up "
                        + FILTER_CLAUSE
                        + " GROUP BY up.method ORDER BY up.method";
        Query byMethodQuery = entityManager.createNativeQuery(byMethodSql);
        bindParameters(byMethodQuery, c);
        @SuppressWarnings("unchecked")
        List<Object[]> methodRows = byMethodQuery.getResultList();
        List<PaymentSummaryBucketView> byMethod = new ArrayList<>(methodRows.size());
        for (Object[] r : methodRows) {
            byMethod.add(
                    new PaymentSummaryBucketView(
                            (String) r[0], ((Number) r[1]).longValue(), toBigDecimal(r[2])));
        }

        return new PaymentSummaryResponse(totalCount, totalAmount, byType, byMethod);
    }

    private static void bindParameters(Query query, SearchCriteria c) {
        boolean hasLocScope = c.visibleLocationIds() != null && !c.visibleLocationIds().isEmpty();
        Collection<UUID> locIds = hasLocScope ? c.visibleLocationIds() : List.of(DUMMY_UUID);
        query.setParameter("hasLocScope", hasLocScope);
        query.setParameter("locIds", locIds);
        query.setParameter("canManageCod", c.canManageCod());
        query.setParameter("locationFilterSet", c.locationId() != null);
        query.setParameter("locationId", c.locationId() != null ? c.locationId() : DUMMY_UUID);
        query.setParameter("hasTypeFilter", c.type() != null);
        query.setParameter("typeFilter", c.type() != null ? c.type() : "");
        query.setParameter("hasMethodFilter", c.method() != null);
        query.setParameter("methodFilter", c.method() != null ? c.method() : "");
        query.setParameter("hasStatusFilter", c.status() != null);
        query.setParameter("statusFilter", c.status() != null ? c.status() : "");
        query.setParameter("hasFrom", c.fromInstant() != null);
        query.setParameter(
                "fromInstant",
                c.fromInstant() != null
                        ? Timestamp.from(c.fromInstant())
                        : Timestamp.from(Instant.EPOCH));
        query.setParameter("hasTo", c.toInstant() != null);
        query.setParameter(
                "toInstant",
                c.toInstant() != null
                        ? Timestamp.from(c.toInstant())
                        : Timestamp.from(Instant.EPOCH));
        query.setParameter("hasQ", c.qLike() != null);
        query.setParameter("qLike", c.qLike() != null ? c.qLike() : "");
        query.setParameter("hasPhoneNorm", c.phoneNormLike() != null);
        query.setParameter("phoneNormLike", c.phoneNormLike() != null ? c.phoneNormLike() : "");
    }

    private static Instant toInstant(Object raw) {
        if (raw instanceof Instant ins) {
            return ins;
        }
        if (raw instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (raw instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        return Instant.parse(raw.toString());
    }

    private static BigDecimal toBigDecimal(Object raw) {
        if (raw == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (raw instanceof BigDecimal bd) {
            return bd.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return new BigDecimal(raw.toString()).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
