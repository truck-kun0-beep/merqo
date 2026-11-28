package com.merqo.payments;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;
import com.merqo.common.money.Money;
import com.merqo.common.time.BusinessTime;
import com.merqo.orders.Order;
import com.merqo.orders.OrderRepository;
import com.merqo.orders.domain.OrderStatus;
import com.merqo.orders.domain.PaymentTerms.PaymentMode;
import com.merqo.payments.PaymentDtos.CodCollectionView;
import com.merqo.payments.PaymentDtos.CodCourierTotalView;
import com.merqo.payments.PaymentDtos.CodOutstandingOrderView;
import com.merqo.payments.PaymentDtos.CodOutstandingResponse;
import com.merqo.payments.PaymentDtos.SettlementDetailView;
import com.merqo.payments.PaymentDtos.SettlementLineView;
import com.merqo.payments.PaymentDtos.SettlementSummaryView;
import com.merqo.payments.domain.CardNumberGuard;
import com.merqo.payments.domain.CodGapPolicy;
import com.merqo.payments.domain.CodRules;
import com.merqo.payments.domain.CodRules.Courier;
import com.merqo.payments.domain.OrderPaymentPosition;
import com.merqo.payments.domain.SettlementCalculator;
import com.merqo.payments.domain.SettlementCalculator.ComputedSettlementLine;
import com.merqo.payments.domain.SettlementCalculator.ReceiveMethod;
import com.merqo.payments.domain.SettlementCalculator.SettlementCalculation;
import com.merqo.payments.domain.SettlementCalculator.SettlementLineInput;
import com.merqo.settings.SettingKey;
import com.merqo.settings.SettingService;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * Application service for Cash-on-Delivery collection entries, all-or-nothing courier settlement
 * batches, and derived COD outstanding/gap reporting.
 */
@Service
public class CodService {

    private static final Logger log = LoggerFactory.getLogger(CodService.class);

    public record SettlementLineRequest(
            UUID orderId, String orderNumber, BigDecimal codCollectedAmount, BigDecimal fee) {}

    private final CodCollectionRepository collectionRepository;
    private final CourierSettlementRepository settlementRepository;
    private final CourierSettlementLineRepository settlementLineRepository;
    private final OrderRepository orderRepository;
    private final OrderPaymentPositionService positionService;
    private final AppUserRepository userRepository;
    private final SettingService settingService;
    private final AuditService auditService;
    private final Clock clock;

    public CodService(
            CodCollectionRepository collectionRepository,
            CourierSettlementRepository settlementRepository,
            CourierSettlementLineRepository settlementLineRepository,
            OrderRepository orderRepository,
            OrderPaymentPositionService positionService,
            AppUserRepository userRepository,
            SettingService settingService,
            AuditService auditService,
            Clock clock) {
        this.collectionRepository = collectionRepository;
        this.settlementRepository = settlementRepository;
        this.settlementLineRepository = settlementLineRepository;
        this.orderRepository = orderRepository;
        this.positionService = positionService;
        this.userRepository = userRepository;
        this.settingService = settingService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** Records an append-only COD collection entry on a {@code CONFIRMED} order. */
    @Transactional
    public CodCollectionView recordCollection(
            AccessContext context,
            UUID orderId,
            String rawCourier,
            BigDecimal rawAmount,
            LocalDate collectedOn,
            String note,
            HttpServletRequest httpRequest) {
        return recordCollectionInternal(
                context,
                orderId,
                rawCourier,
                rawAmount,
                collectedOn,
                note,
                Instant.now(clock),
                null,
                httpRequest);
    }

    /** Internal helper supporting back-dated idempotent demo seeding via real validation. */
    @Transactional
    public CodCollectionView recordCollectionInternal(
            AccessContext context,
            UUID orderId,
            String rawCourier,
            BigDecimal rawAmount,
            LocalDate collectedOn,
            String note,
            Instant recordedAt,
            String demoKey,
            HttpServletRequest httpRequest) {
        if (demoKey != null) {
            Optional<CodCollection> existing = collectionRepository.findByDemoKey(demoKey);
            if (existing.isPresent()) {
                return toCollectionViews(List.of(existing.get())).getFirst();
            }
        }

        Order order =
                orderRepository
                        .findByIdForUpdate(orderId)
                        .orElseThrow(() -> new NotFoundException("Order not found."));
        OrderPaymentService.requireWarehouseLocationAndPermission(
                context, order.getLocationId(), Permission.ORDER_VIEW);
        context.requireGlobal(Permission.COD_SETTLEMENT_MANAGE);

        Courier courier = Courier.fromString(rawCourier);
        LocalDate effectiveDate =
                collectedOn != null ? collectedOn : BusinessTime.toBusinessDate(recordedAt);
        if (rawAmount == null) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "Collection amount is required.");
        }
        Money amount = Money.of(rawAmount);
        String cleanNote = trimToNull(note);

        OrderPaymentPosition position = positionService.computePosition(order);
        CodRules.validateCollection(
                order.getStatus(),
                position.codExpected(),
                position.codCollected(),
                amount,
                courier,
                effectiveDate,
                cleanNote);

        CodCollection saved =
                collectionRepository.saveAndFlush(
                        new CodCollection(
                                UUID.randomUUID(),
                                order.getId(),
                                courier,
                                amount.amount(),
                                effectiveDate,
                                context.getUserId(),
                                recordedAt,
                                cleanNote,
                                demoKey));

        Map<String, Object> auditValues = new LinkedHashMap<>();
        auditValues.put("orderNumber", order.getOrderNumber());
        auditValues.put("courier", courier.name());
        auditValues.put("amount", amount.amount().toPlainString());
        auditValues.put("collectedOn", effectiveDate.toString());
        auditService.record(
                new AuditEvent(
                        AuditAction.COD_COLLECTION_RECORDED,
                        "COD_COLLECTION",
                        saved.getId(),
                        order.getLocationId(),
                        context.getUserId(),
                        context.getUsername(),
                        null,
                        auditValues,
                        httpRequest == null ? null : httpRequest.getRemoteAddr(),
                        null));
        log.info("Recorded COD collection id={} orderId={}", saved.getId(), order.getId());

        return toCollectionViews(List.of(saved)).getFirst();
    }

    /**
     * Records an all-or-nothing courier settlement batch locking affected orders deterministically.
     */
    @Transactional
    public SettlementDetailView recordSettlement(
            AccessContext context,
            String rawCourier,
            String rawReference,
            LocalDate receivedOn,
            String rawReceiveMethod,
            BigDecimal rawAmountReceived,
            String note,
            List<SettlementLineRequest> lines,
            HttpServletRequest httpRequest) {
        return recordSettlementInternal(
                context,
                rawCourier,
                rawReference,
                receivedOn,
                rawReceiveMethod,
                rawAmountReceived,
                note,
                lines,
                Instant.now(clock),
                null,
                httpRequest);
    }

    /** Internal helper supporting back-dated idempotent demo seeding via real validation. */
    @Transactional
    public SettlementDetailView recordSettlementInternal(
            AccessContext context,
            String rawCourier,
            String rawReference,
            LocalDate receivedOn,
            String rawReceiveMethod,
            BigDecimal rawAmountReceived,
            String note,
            List<SettlementLineRequest> lines,
            Instant recordedAt,
            String demoKey,
            HttpServletRequest httpRequest) {
        context.requireGlobal(Permission.COD_SETTLEMENT_MANAGE);

        if (demoKey != null) {
            Optional<CourierSettlement> existing = settlementRepository.findByDemoKey(demoKey);
            if (existing.isPresent()) {
                return toDetailView(existing.get());
            }
        }

        Courier courier = Courier.fromString(rawCourier);
        ReceiveMethod receiveMethod = ReceiveMethod.fromString(rawReceiveMethod);
        LocalDate effectiveDate =
                receivedOn != null ? receivedOn : BusinessTime.toBusinessDate(recordedAt);
        CardNumberGuard.rejectCardNumberReference(rawReference);
        String reference = trimToNull(rawReference);
        if (reference == null) {
            throw new ValidationException(
                    ErrorCodes.PAYMENT_REFERENCE_REQUIRED, "Settlement reference is required.");
        }
        reference = reference.toUpperCase(Locale.ROOT);
        if (rawAmountReceived == null) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "Amount received is required.");
        }
        Money amountReceived = Money.of(rawAmountReceived);
        String cleanNote = trimToNull(note);

        if (settlementRepository
                .findByCourierAndReferenceIgnoreCase(courier, reference)
                .isPresent()) {
            throw new ConflictException(
                    ErrorCodes.DUPLICATE_REFERENCE,
                    "A settlement for this courier and reference already exists.");
        }

        if (lines == null || lines.isEmpty()) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "Settlement must include at least one order.");
        }

        // Resolve order IDs first, then lock distinct orders in UUID order to avoid deadlocks.
        List<UUID> resolvedIds = new ArrayList<>(lines.size());
        for (SettlementLineRequest lr : lines) {
            UUID resolvedId = resolveOrderId(lr);
            resolvedIds.add(resolvedId);
        }
        List<UUID> sortedDistinct =
                resolvedIds.stream().distinct().sorted(Comparator.naturalOrder()).toList();
        Map<UUID, Order> lockedOrders = new HashMap<>();
        for (UUID oid : sortedDistinct) {
            Order locked =
                    orderRepository
                            .findByIdForUpdate(oid)
                            .orElseThrow(() -> new NotFoundException("Order not found."));
            lockedOrders.put(oid, locked);
        }

        List<SettlementLineInput> calcInputs = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            SettlementLineRequest lr = lines.get(i);
            UUID oid = resolvedIds.get(i);
            Order order = lockedOrders.get(oid);
            OrderPaymentPosition pos = positionService.computePosition(order);
            Money collectedAmt =
                    lr.codCollectedAmount() == null
                            ? Money.ZERO
                            : Money.of(lr.codCollectedAmount());
            Money feeAmt = lr.fee() == null ? Money.ZERO : Money.of(lr.fee());
            calcInputs.add(
                    new SettlementLineInput(
                            order.getId(),
                            order.getOrderNumber(),
                            collectedAmt,
                            feeAmt,
                            pos.codWithCourier()));
        }

        SettlementCalculation calc =
                SettlementCalculator.calculate(calcInputs, amountReceived, cleanNote);

        UUID settlementId = UUID.randomUUID();
        String settlementNumber =
                formatSettlementNumber(settlementRepository.nextSettlementSequence());

        CourierSettlement saved;
        try {
            saved =
                    settlementRepository.saveAndFlush(
                            new CourierSettlement(
                                    settlementId,
                                    settlementNumber,
                                    courier,
                                    reference,
                                    effectiveDate,
                                    receiveMethod,
                                    calc.amountReceived().amount(),
                                    calc.feesTotal().amount(),
                                    calc.difference().amount(),
                                    calc.differenceKind(),
                                    cleanNote,
                                    context.getUserId(),
                                    recordedAt,
                                    demoKey));
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(
                    ErrorCodes.DUPLICATE_REFERENCE,
                    "A settlement for this courier and reference already exists.");
        }

        for (ComputedSettlementLine line : calc.lines()) {
            settlementLineRepository.save(
                    new CourierSettlementLine(
                            UUID.randomUUID(),
                            settlementId,
                            line.orderId(),
                            line.codCollectedAmount().amount(),
                            line.fee().amount(),
                            line.net().amount()));
        }
        settlementLineRepository.flush();

        Map<String, Object> auditValues = new LinkedHashMap<>();
        auditValues.put("settlementNumber", settlementNumber);
        auditValues.put("courier", courier.name());
        auditValues.put("reference", reference);
        auditValues.put("amountReceived", calc.amountReceived().amount().toPlainString());
        auditValues.put("feesTotal", calc.feesTotal().amount().toPlainString());
        auditValues.put("difference", calc.difference().amount().toPlainString());
        auditValues.put("differenceKind", calc.differenceKind().name());
        auditValues.put("lineCount", calc.lines().size());
        auditService.record(
                new AuditEvent(
                        AuditAction.COD_SETTLEMENT_RECORDED,
                        "COURIER_SETTLEMENT",
                        settlementId,
                        null,
                        context.getUserId(),
                        context.getUsername(),
                        null,
                        auditValues,
                        httpRequest == null ? null : httpRequest.getRemoteAddr(),
                        null));
        log.info("Recorded courier settlement id={} lines={}", settlementId, calc.lines().size());

        return toDetailView(saved);
    }

    /** Returns per-courier and per-order derived COD outstanding figures and gap flags. */
    @Transactional(readOnly = true)
    public CodOutstandingResponse getOutstanding(AccessContext context) {
        context.requireGlobal(Permission.COD_SETTLEMENT_MANAGE);
        int gapThresholdDays = settingService.get(SettingKey.THRESHOLD_COD_GAP_DAYS, Integer.class);

        List<Order> codOrders =
                orderRepository.findAll(
                        (root, query, cb) ->
                                cb.and(
                                        root.get("paymentMode")
                                                .in(
                                                        List.of(
                                                                PaymentMode.COD,
                                                                PaymentMode.ADVANCE_COD)),
                                        cb.equal(root.get("status"), OrderStatus.CONFIRMED)));
        List<CodCollection> allCollections =
                collectionRepository.findAllByOrderByCollectedOnAscRecordedAtAsc();

        Map<UUID, Courier> primaryCourier = new HashMap<>();
        Map<UUID, LocalDate> earliestCollection = new HashMap<>();
        Map<UUID, Order> relevantOrders = new HashMap<>();
        for (Order o : codOrders) {
            relevantOrders.put(o.getId(), o);
        }
        for (CodCollection c : allCollections) {
            primaryCourier.putIfAbsent(c.getOrderId(), c.getCourier());
            earliestCollection.merge(
                    c.getOrderId(), c.getCollectedOn(), (a, b) -> a.isBefore(b) ? a : b);
            if (!relevantOrders.containsKey(c.getOrderId())) {
                orderRepository
                        .findById(c.getOrderId())
                        .ifPresent(o -> relevantOrders.put(o.getId(), o));
            }
        }

        List<CodOutstandingOrderView> orderRows = new ArrayList<>();
        Map<Courier, CourierAccumulator> acc = new EnumMap<>(Courier.class);
        for (Courier c : Courier.values()) {
            acc.put(c, new CourierAccumulator());
        }

        List<Order> sortedOrders =
                relevantOrders.values().stream()
                        .sorted(Comparator.comparing(Order::getCreatedAt).reversed())
                        .toList();

        for (Order order : sortedOrders) {
            OrderPaymentPosition pos = positionService.computePosition(order);
            if (pos.codWithCourier().isZero() && pos.codUncollected().isZero()) {
                continue;
            }
            Courier courier = primaryCourier.get(order.getId());
            LocalDate earliestDate = earliestCollection.get(order.getId());
            boolean gap =
                    CodGapPolicy.hasGap(
                            pos.codWithCourier(), earliestDate, gapThresholdDays, clock);

            orderRows.add(
                    new CodOutstandingOrderView(
                            order.getId(),
                            order.getOrderNumber(),
                            order.getCustomerName(),
                            courier,
                            earliestDate,
                            pos.codExpected().amount(),
                            pos.codCollected().amount(),
                            pos.codSettledGross().amount(),
                            pos.codWithCourier().amount(),
                            pos.codUncollected().amount(),
                            gap));

            if (courier != null) {
                CourierAccumulator ca = acc.get(courier);
                ca.orderCount++;
                ca.expected = ca.expected.plus(pos.codExpected());
                ca.collected = ca.collected.plus(pos.codCollected());
                ca.settledGross = ca.settledGross.plus(pos.codSettledGross());
                ca.withCourier = ca.withCourier.plus(pos.codWithCourier());
                ca.uncollected = ca.uncollected.plus(pos.codUncollected());
                if (gap) {
                    ca.gapCount++;
                }
            }
        }

        List<CodCourierTotalView> courierTotals = new ArrayList<>(Courier.values().length);
        for (Courier c : Courier.values()) {
            CourierAccumulator ca = acc.get(c);
            courierTotals.add(
                    new CodCourierTotalView(
                            c,
                            ca.orderCount,
                            ca.expected.amount(),
                            ca.collected.amount(),
                            ca.settledGross.amount(),
                            ca.withCourier.amount(),
                            ca.uncollected.amount(),
                            ca.gapCount));
        }

        return new CodOutstandingResponse(gapThresholdDays, courierTotals, orderRows);
    }

    /** Lists courier settlements newest first. */
    @Transactional(readOnly = true)
    public List<SettlementSummaryView> listSettlements(AccessContext context) {
        context.requireGlobal(Permission.COD_SETTLEMENT_MANAGE);
        List<CourierSettlement> settlements =
                settlementRepository.findAllByOrderByReceivedOnDescRecordedAtDesc();
        if (settlements.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = settlements.stream().map(CourierSettlement::getId).toList();
        Map<UUID, Integer> lineCounts = new HashMap<>();
        for (CourierSettlementLine line : settlementLineRepository.findBySettlementIdIn(ids)) {
            lineCounts.merge(line.getSettlementId(), 1, Integer::sum);
        }
        Map<UUID, String> usernames =
                loadUsernames(settlements.stream().map(CourierSettlement::getRecordedBy).toList());
        List<SettlementSummaryView> rows = new ArrayList<>(settlements.size());
        for (CourierSettlement s : settlements) {
            rows.add(
                    new SettlementSummaryView(
                            s.getId(),
                            s.getSettlementNumber(),
                            s.getCourier(),
                            s.getReference(),
                            s.getReceivedOn(),
                            s.getReceiveMethod(),
                            s.getAmountReceived(),
                            s.getFeesTotal(),
                            s.getDifference(),
                            s.getDifferenceKind(),
                            s.getNote(),
                            s.getRecordedBy(),
                            usernames.getOrDefault(s.getRecordedBy(), "unknown"),
                            s.getRecordedAt(),
                            lineCounts.getOrDefault(s.getId(), 0)));
        }
        return rows;
    }

    /** Returns detailed settlement header and allocated lines. */
    @Transactional(readOnly = true)
    public SettlementDetailView getSettlement(AccessContext context, UUID settlementId) {
        context.requireGlobal(Permission.COD_SETTLEMENT_MANAGE);
        CourierSettlement settlement =
                settlementRepository
                        .findById(settlementId)
                        .orElseThrow(() -> new NotFoundException("Settlement not found."));
        return toDetailView(settlement);
    }

    /** Lists COD collection entries for an order (internal helper for order payments detail). */
    public List<CodCollectionView> listForOrder(UUID orderId) {
        return toCollectionViews(
                collectionRepository.findByOrderIdOrderByCollectedOnAscRecordedAtAsc(orderId));
    }

    private SettlementDetailView toDetailView(CourierSettlement s) {
        List<CourierSettlementLine> lines =
                settlementLineRepository.findBySettlementIdOrderByOrderIdAsc(s.getId());
        Map<UUID, Order> orders = new HashMap<>();
        for (Order o :
                orderRepository.findAllById(
                        lines.stream().map(CourierSettlementLine::getOrderId).toList())) {
            orders.put(o.getId(), o);
        }
        Money sumNet = Money.ZERO;
        List<SettlementLineView> lineViews = new ArrayList<>(lines.size());
        for (CourierSettlementLine l : lines) {
            sumNet = sumNet.plus(Money.of(l.getNet()));
            Order o = orders.get(l.getOrderId());
            lineViews.add(
                    new SettlementLineView(
                            l.getId(),
                            l.getOrderId(),
                            o != null ? o.getOrderNumber() : "",
                            l.getCodCollectedAmount(),
                            l.getFee(),
                            l.getNet()));
        }
        String recordedByUsername =
                userRepository
                        .findById(s.getRecordedBy())
                        .map(AppUser::getUsername)
                        .orElse("unknown");
        return new SettlementDetailView(
                s.getId(),
                s.getSettlementNumber(),
                s.getCourier(),
                s.getReference(),
                s.getReceivedOn(),
                s.getReceiveMethod(),
                s.getAmountReceived(),
                s.getFeesTotal(),
                sumNet.amount(),
                s.getDifference(),
                s.getDifferenceKind(),
                s.getNote(),
                s.getRecordedBy(),
                recordedByUsername,
                s.getRecordedAt(),
                lineViews);
    }

    private List<CodCollectionView> toCollectionViews(List<CodCollection> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, Order> orders = new HashMap<>();
        for (Order o :
                orderRepository.findAllById(
                        rows.stream().map(CodCollection::getOrderId).distinct().toList())) {
            orders.put(o.getId(), o);
        }
        Map<UUID, String> usernames =
                loadUsernames(rows.stream().map(CodCollection::getRecordedBy).toList());
        return rows.stream()
                .map(
                        c -> {
                            Order o = orders.get(c.getOrderId());
                            return new CodCollectionView(
                                    c.getId(),
                                    c.getOrderId(),
                                    o != null ? o.getOrderNumber() : "",
                                    c.getCourier(),
                                    c.getAmount(),
                                    c.getCollectedOn(),
                                    c.getRecordedBy(),
                                    usernames.getOrDefault(c.getRecordedBy(), "unknown"),
                                    c.getRecordedAt(),
                                    c.getNote());
                        })
                .toList();
    }

    private UUID resolveOrderId(SettlementLineRequest req) {
        if (req.orderId() != null) {
            return req.orderId();
        }
        if (req.orderNumber() != null && !req.orderNumber().isBlank()) {
            return orderRepository
                    .findByOrderNumber(req.orderNumber().trim().toUpperCase(Locale.ROOT))
                    .map(Order::getId)
                    .orElseThrow(
                            () ->
                                    new NotFoundException(
                                            "Order not found: " + req.orderNumber().trim()));
        }
        throw new ValidationException(
                ErrorCodes.VALIDATION_FAILED,
                "Each settlement line requires either orderId or orderNumber.");
    }

    private Map<UUID, String> loadUsernames(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (AppUser u : userRepository.findAllById(ids)) {
            names.put(u.getId(), u.getUsername());
        }
        return names;
    }

    private static String formatSettlementNumber(long sequenceValue) {
        return "STL-%06d".formatted(sequenceValue);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static class CourierAccumulator {
        long orderCount = 0;
        Money expected = Money.ZERO;
        Money collected = Money.ZERO;
        Money settledGross = Money.ZERO;
        Money withCourier = Money.ZERO;
        Money uncollected = Money.ZERO;
        long gapCount = 0;
    }
}
