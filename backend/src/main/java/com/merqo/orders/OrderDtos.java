package com.merqo.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.merqo.orders.domain.CallResult;
import com.merqo.orders.domain.OrderChannel;
import com.merqo.orders.domain.OrderStatus;
import com.merqo.orders.domain.PaymentTerms.ClaimedAdvanceMethod;
import com.merqo.orders.domain.PaymentTerms.PaymentMode;
import com.merqo.orders.domain.PhoneHistory;
import com.merqo.orders.domain.PhoneRiskPolicy.RiskTier;

/** Response DTOs for the staff Order Handling, Customer History, and Availability APIs. */
public final class OrderDtos {

    private OrderDtos() {}

    public record OrderFlagsView(
            boolean priceMismatch,
            boolean invalidPhone,
            boolean highRisk,
            boolean cancellationRequested) {}

    public record OrderSummaryView(
            UUID id,
            String orderNumber,
            OrderChannel channel,
            OrderStatus status,
            UUID locationId,
            String customerName,
            String phoneRaw,
            String phoneNormalized,
            String district,
            String sourceLabel,
            String externalOrderId,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal shippingFee,
            BigDecimal total,
            PaymentMode paymentMode,
            BigDecimal advanceAmount,
            BigDecimal codAmount,
            OrderFlagsView flags,
            RiskTier riskTier,
            int callAttempts,
            Instant followUpAt,
            Instant cancellationRequestedAt,
            Instant createdAt,
            Instant confirmedAt,
            Instant closedAt) {}

    public record StockSnapshotView(
            int onHand, int reserved, int softHolds, int safetyStock, int available) {}

    public record OrderLineView(
            UUID id,
            int lineIndex,
            UUID variantId,
            String productName,
            String sku,
            String barcode,
            String color,
            String size,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal catalogOnlinePrice,
            StockSnapshotView availability) {}

    public record OrderCallLogView(
            UUID id,
            int attemptNumber,
            CallResult result,
            String note,
            Instant followUpAt,
            UUID calledBy,
            String calledByUsername,
            Instant calledAt) {}

    public record OrderStatusHistoryView(
            UUID id,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            UUID changedBy,
            String actorLabel,
            String reason,
            String note,
            Instant createdAt) {}

    public record OrderDetailView(
            UUID id,
            String orderNumber,
            OrderChannel channel,
            OrderStatus status,
            UUID locationId,
            UUID customerId,
            String customerName,
            String phoneRaw,
            String phoneNormalized,
            String address,
            String district,
            String note,
            String sourceLabel,
            String externalOrderId,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal shippingFee,
            BigDecimal total,
            PaymentMode paymentMode,
            BigDecimal advanceAmount,
            BigDecimal codAmount,
            ClaimedAdvanceMethod claimedAdvanceMethod,
            String claimedAdvanceReference,
            OrderFlagsView flags,
            PhoneHistory phoneHistory,
            RiskTier riskTier,
            Instant followUpAt,
            Instant cancellationRequestedAt,
            String cancellationRequestNote,
            Instant createdAt,
            Instant confirmedAt,
            Instant closedAt,
            List<OrderLineView> lines,
            List<OrderCallLogView> callLogs,
            List<OrderStatusHistoryView> statusHistory) {}

    public record OrderCancelResponse(
            OrderDetailView order,
            boolean releasedReservation,
            boolean customerContacted,
            boolean advanceClaimed,
            BigDecimal advanceAmount) {}

    public record OrderCountsView(
            long pendingConfirmation, long followUp, long needsAction, long confirmed) {}

    public record WarehouseAvailabilityRow(
            UUID variantId,
            String sku,
            String productName,
            String color,
            String size,
            int onHand,
            int reserved,
            int softHolds,
            int safetyStock,
            int available) {}

    public record WarehouseAvailabilityResponse(
            UUID locationId, String locationCode, List<WarehouseAvailabilityRow> items) {}

    public record CustomerOrderRefView(
            UUID id,
            String orderNumber,
            OrderChannel channel,
            OrderStatus status,
            BigDecimal total,
            Instant createdAt) {}

    public record CustomerHistoryResponse(
            String phoneRaw,
            String phoneNormalized,
            boolean validPhone,
            String customerName,
            PhoneHistory history,
            RiskTier riskTier,
            List<CustomerOrderRefView> recentOrders) {}
}
