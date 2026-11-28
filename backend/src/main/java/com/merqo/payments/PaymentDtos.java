package com.merqo.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.merqo.orders.domain.OrderStatus;
import com.merqo.orders.domain.PaymentTerms.ClaimedAdvanceMethod;
import com.merqo.orders.domain.PaymentTerms.PaymentMode;
import com.merqo.payments.domain.CodRules.Courier;
import com.merqo.payments.domain.OnlinePaymentMethod;
import com.merqo.payments.domain.OrderPaymentPosition;
import com.merqo.payments.domain.PaymentDecision;
import com.merqo.payments.domain.PaymentStatus;
import com.merqo.payments.domain.RefundPayoutMethod;
import com.merqo.payments.domain.RefundRules.RefundReason;
import com.merqo.payments.domain.RefundStatus;
import com.merqo.payments.domain.RefundStatus.RefundEventType;
import com.merqo.payments.domain.SettlementCalculator.DifferenceKind;
import com.merqo.payments.domain.SettlementCalculator.ReceiveMethod;

/** API DTOs for order payments, refunds, COD tracking, and read-only payment history. */
public final class PaymentDtos {

    private PaymentDtos() {}

    public record OrderPaymentPositionView(
            BigDecimal requiredPrepayment,
            BigDecimal verifiedPaid,
            BigDecimal pendingPaid,
            BigDecimal rejectedPaid,
            BigDecimal refundedPaid,
            BigDecimal refundReserved,
            BigDecimal netHeld,
            BigDecimal refundable,
            boolean prepaymentSatisfied,
            BigDecimal shortfall,
            BigDecimal codExpected,
            BigDecimal codCollected,
            BigDecimal codSettledGross,
            BigDecimal codFeesDeducted,
            BigDecimal codWithCourier,
            BigDecimal codUncollected,
            boolean readyForShipment,
            boolean refundDue) {

        public static OrderPaymentPositionView from(
                OrderPaymentPosition pos, OrderStatus orderStatus) {
            boolean closedWithBalance =
                    (orderStatus == OrderStatus.CANCELLED || orderStatus == OrderStatus.DECLINED)
                            && pos.refundable().compareTo(com.merqo.common.money.Money.ZERO) > 0;
            return new OrderPaymentPositionView(
                    pos.requiredPrepayment().amount(),
                    pos.verifiedPaid().amount(),
                    pos.pendingPaid().amount(),
                    pos.rejectedPaid().amount(),
                    pos.refundedPaid().amount(),
                    pos.refundReserved().amount(),
                    pos.netHeld().amount(),
                    pos.refundable().amount(),
                    pos.prepaymentSatisfied(),
                    pos.shortfall().amount(),
                    pos.codExpected().amount(),
                    pos.codCollected().amount(),
                    pos.codSettledGross().amount(),
                    pos.codFeesDeducted().amount(),
                    pos.codWithCourier().amount(),
                    pos.codUncollected().amount(),
                    pos.readyForShipment(),
                    closedWithBalance);
        }
    }

    public record PaymentDecisionView(
            UUID id,
            PaymentDecision decision,
            UUID decidedBy,
            String decidedByUsername,
            String deciderKind,
            String evidence,
            String providerReference,
            Instant decidedAt) {}

    public record OrderPaymentView(
            UUID id,
            String paymentNumber,
            UUID orderId,
            String orderNumber,
            OnlinePaymentMethod method,
            BigDecimal amount,
            String reference,
            PaymentSource source,
            String payerNote,
            UUID recordedBy,
            String recordedByUsername,
            Instant recordedAt,
            PaymentStatus status,
            PaymentDecisionView decision) {}

    public record ClaimedAdvanceView(
            boolean hasClaim,
            ClaimedAdvanceMethod method,
            String reference,
            BigDecimal claimedAmount,
            BigDecimal remainingAllowed) {}

    public record PendingPaymentView(
            UUID id,
            String paymentNumber,
            UUID orderId,
            String orderNumber,
            OrderStatus orderStatus,
            String customerName,
            OnlinePaymentMethod method,
            BigDecimal amount,
            String reference,
            PaymentSource source,
            UUID recordedBy,
            String recordedByUsername,
            Instant recordedAt,
            long hoursPending,
            boolean overdue) {}

    public record ProviderCheckResponse(
            String outcome, String message, String providerReference, OrderPaymentView payment) {}

    public record RefundEventView(
            UUID id,
            RefundEventType eventType,
            UUID actorId,
            String actorUsername,
            String note,
            RefundPayoutMethod payoutMethod,
            String payoutReference,
            Instant createdAt) {}

    public record RefundView(
            UUID id,
            String refundNumber,
            UUID orderId,
            String orderNumber,
            OrderStatus orderStatus,
            BigDecimal amount,
            RefundReason reason,
            String note,
            UUID requestedBy,
            String requestedByUsername,
            Instant requestedAt,
            RefundStatus status,
            List<RefundEventView> events) {}

    public record CodCollectionView(
            UUID id,
            UUID orderId,
            String orderNumber,
            Courier courier,
            BigDecimal amount,
            LocalDate collectedOn,
            UUID recordedBy,
            String recordedByUsername,
            Instant recordedAt,
            String note) {}

    public record OrderPaymentsDetailResponse(
            UUID orderId,
            String orderNumber,
            OrderStatus orderStatus,
            PaymentMode paymentMode,
            BigDecimal total,
            BigDecimal advanceAmount,
            BigDecimal codAmount,
            ClaimedAdvanceView claimedAdvance,
            OrderPaymentPositionView position,
            List<OrderPaymentView> payments,
            List<RefundView> refunds,
            List<CodCollectionView> codCollections) {}

    public record RefundDueRowView(
            UUID orderId,
            String orderNumber,
            OrderStatus orderStatus,
            String customerName,
            BigDecimal verifiedPaid,
            BigDecimal refundedPaid,
            BigDecimal refundable,
            Instant closedAt) {}

    public record SettlementLineView(
            UUID id,
            UUID orderId,
            String orderNumber,
            BigDecimal codCollectedAmount,
            BigDecimal fee,
            BigDecimal net) {}

    public record SettlementSummaryView(
            UUID id,
            String settlementNumber,
            Courier courier,
            String reference,
            LocalDate receivedOn,
            ReceiveMethod receiveMethod,
            BigDecimal amountReceived,
            BigDecimal feesTotal,
            BigDecimal difference,
            DifferenceKind differenceKind,
            String note,
            UUID recordedBy,
            String recordedByUsername,
            Instant recordedAt,
            int lineCount) {}

    public record SettlementDetailView(
            UUID id,
            String settlementNumber,
            Courier courier,
            String reference,
            LocalDate receivedOn,
            ReceiveMethod receiveMethod,
            BigDecimal amountReceived,
            BigDecimal feesTotal,
            BigDecimal sumNet,
            BigDecimal difference,
            DifferenceKind differenceKind,
            String note,
            UUID recordedBy,
            String recordedByUsername,
            Instant recordedAt,
            List<SettlementLineView> lines) {}

    public record CodOutstandingOrderView(
            UUID orderId,
            String orderNumber,
            String customerName,
            Courier courier,
            LocalDate earliestCollectionDate,
            BigDecimal expected,
            BigDecimal collected,
            BigDecimal settledGross,
            BigDecimal withCourier,
            BigDecimal uncollected,
            boolean gap) {}

    public record CodCourierTotalView(
            Courier courier,
            long orderCount,
            BigDecimal expected,
            BigDecimal collected,
            BigDecimal settledGross,
            BigDecimal withCourier,
            BigDecimal uncollected,
            long gapCount) {}

    public record CodOutstandingResponse(
            int gapThresholdDays,
            List<CodCourierTotalView> courierTotals,
            List<CodOutstandingOrderView> orders) {}

    public record PaymentHistoryRowView(
            String type,
            Instant occurredAt,
            String method,
            BigDecimal amount,
            String reference,
            String documentNumber,
            UUID orderId,
            UUID saleId,
            UUID settlementId,
            String locationName,
            String status,
            String recordedBy) {}

    public record PaymentSummaryBucketView(String key, long count, BigDecimal totalAmount) {}

    public record PaymentSummaryResponse(
            long totalCount,
            BigDecimal totalAmount,
            List<PaymentSummaryBucketView> byType,
            List<PaymentSummaryBucketView> byMethod) {}
}
