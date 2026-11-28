package com.merqo.payments;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.orders.Order;
import com.merqo.orders.OrderRepository;
import com.merqo.orders.domain.PaymentTerms.ClaimedAdvanceMethod;
import com.merqo.payments.PaymentDtos.ClaimedAdvanceView;
import com.merqo.payments.PaymentDtos.CodCollectionView;
import com.merqo.payments.PaymentDtos.OrderPaymentPositionView;
import com.merqo.payments.PaymentDtos.OrderPaymentView;
import com.merqo.payments.PaymentDtos.OrderPaymentsDetailResponse;
import com.merqo.payments.PaymentDtos.PaymentDecisionView;
import com.merqo.payments.PaymentDtos.PendingPaymentView;
import com.merqo.payments.PaymentDtos.ProviderCheckResponse;
import com.merqo.payments.domain.CardNumberGuard;
import com.merqo.payments.domain.ExpectedPrepayment;
import com.merqo.payments.domain.OnlinePaymentMethod;
import com.merqo.payments.domain.OrderPaymentPosition;
import com.merqo.payments.domain.PaymentDecision;
import com.merqo.payments.domain.PaymentReferenceGuard;
import com.merqo.payments.domain.PaymentRuleException;
import com.merqo.payments.domain.PaymentStatus;
import com.merqo.payments.domain.PaymentVerifier;
import com.merqo.payments.domain.PaymentVerifier.PaymentClaim;
import com.merqo.payments.domain.PaymentVerifier.VerificationKind;
import com.merqo.payments.domain.PaymentVerifier.VerificationResult;
import com.merqo.payments.domain.PaymentVerifierRegistry;
import com.merqo.payments.domain.RecordedPaymentRules;
import com.merqo.payments.domain.VerificationPolicy;
import com.merqo.settings.SettingKey;
import com.merqo.settings.SettingService;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * Application service for recording, inspecting, verifying, and checking customer advance payments
 * on online and manual orders.
 */
@Service
public class OrderPaymentService {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentService.class);

    private final OrderRepository orderRepository;
    private final OrderPaymentRepository paymentRepository;
    private final PaymentDecisionRepository decisionRepository;
    private final OrderPaymentPositionService positionService;
    private final PaymentReferenceGuard referenceGuard;
    private final PaymentVerifierRegistry verifierRegistry;
    private final LocationRepository locationRepository;
    private final AppUserRepository userRepository;
    private final SettingService settingService;
    private final AuditService auditService;
    private final RefundService refundService;
    private final Clock clock;

    public OrderPaymentService(
            OrderRepository orderRepository,
            OrderPaymentRepository paymentRepository,
            PaymentDecisionRepository decisionRepository,
            OrderPaymentPositionService positionService,
            PaymentReferenceGuard referenceGuard,
            PaymentVerifierRegistry verifierRegistry,
            LocationRepository locationRepository,
            AppUserRepository userRepository,
            SettingService settingService,
            AuditService auditService,
            RefundService refundService,
            Clock clock) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.decisionRepository = decisionRepository;
        this.positionService = positionService;
        this.referenceGuard = referenceGuard;
        this.verifierRegistry = verifierRegistry;
        this.locationRepository = locationRepository;
        this.userRepository = userRepository;
        this.settingService = settingService;
        this.auditService = auditService;
        this.refundService = refundService;
        this.clock = clock;
    }

    /**
     * Returns an order's payment terms, claimed advance, recorded payments, and derived position.
     */
    @Transactional(readOnly = true)
    public OrderPaymentsDetailResponse getOrderPayments(AccessContext context, UUID orderId) {
        Order order = loadWarehouseOrder(context, orderId, Permission.PAYMENT_VIEW, false);
        OrderPaymentPosition position = positionService.computePosition(order);
        OrderPaymentPositionView positionView =
                OrderPaymentPositionView.from(position, order.getStatus());

        List<OrderPayment> rawPayments =
                paymentRepository.findByOrderIdOrderByRecordedAtAsc(orderId);
        List<OrderPaymentView> paymentViews = mapPaymentViews(rawPayments, Map.of(orderId, order));

        ClaimedAdvanceView claim = buildClaimPrefill(order, position);

        return new OrderPaymentsDetailResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getPaymentMode(),
                order.getTotal(),
                order.getAdvanceAmount(),
                order.getCodAmount(),
                claim,
                positionView,
                paymentViews,
                refundService.listForOrder(orderId),
                List.<CodCollectionView>of());
    }

    /** Returns prefill fields from any claimed advance on an order for payment recording forms. */
    @Transactional(readOnly = true)
    public ClaimedAdvanceView getClaimPrefill(AccessContext context, UUID orderId) {
        Order order = loadWarehouseOrder(context, orderId, Permission.PAYMENT_RECORD, false);
        OrderPaymentPosition position = positionService.computePosition(order);
        return buildClaimPrefill(order, position);
    }

    /** Records an immutable customer advance payment on an order (any order status allowed). */
    @Transactional
    public OrderPaymentView recordPayment(
            AccessContext context,
            UUID orderId,
            String rawMethod,
            BigDecimal rawAmount,
            String rawReference,
            String payerNote,
            Boolean fromClaim,
            HttpServletRequest httpRequest) {
        return recordPaymentInternal(
                context,
                orderId,
                rawMethod,
                rawAmount,
                rawReference,
                payerNote,
                fromClaim,
                Instant.now(clock),
                null,
                httpRequest);
    }

    /** Internal helper allowing back-dated idempotent demo seeding via real service validation. */
    @Transactional
    public OrderPaymentView recordPaymentInternal(
            AccessContext context,
            UUID orderId,
            String rawMethod,
            BigDecimal rawAmount,
            String rawReference,
            String payerNote,
            Boolean fromClaim,
            Instant recordedAt,
            String demoKey,
            HttpServletRequest httpRequest) {
        if (demoKey != null) {
            Optional<OrderPayment> existingDemo = paymentRepository.findByDemoKey(demoKey);
            if (existingDemo.isPresent()) {
                Order order =
                        orderRepository
                                .findById(existingDemo.get().getOrderId())
                                .orElseThrow(() -> new NotFoundException("Order not found."));
                return mapPaymentViews(List.of(existingDemo.get()), Map.of(order.getId(), order))
                        .getFirst();
            }
        }

        Order order = loadWarehouseOrder(context, orderId, Permission.PAYMENT_RECORD, true);

        OnlinePaymentMethod method = OnlinePaymentMethod.fromString(rawMethod);
        String normalizedRef = method.normalizeAndValidateReference(rawReference);
        CardNumberGuard.rejectCardNumberInText(payerNote);
        String cleanNote = trimToNull(payerNote);

        if (rawAmount == null) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "Payment amount is required.");
        }
        Money amount = Money.of(rawAmount);

        OrderPaymentPosition position = positionService.computePosition(order);
        ExpectedPrepayment expected =
                ExpectedPrepayment.from(
                        order.getPaymentMode(),
                        Money.of(order.getTotal()),
                        Money.of(order.getAdvanceAmount()),
                        Money.of(order.getCodAmount()));
        Money nonRejectedTotal = position.verifiedPaid().plus(position.pendingPaid());
        RecordedPaymentRules.validate(expected, nonRejectedTotal, amount);

        // Enforce system-wide TrxID uniqueness across POS sales and non-rejected order payments.
        referenceGuard.requireUniqueTrxId(method.name(), normalizedRef);

        PaymentSource source = resolvePaymentSource(order, method, normalizedRef, fromClaim);
        UUID paymentId = UUID.randomUUID();
        String paymentNumber = formatPaymentNumber(paymentRepository.nextPaymentSequence());

        OrderPayment saved =
                paymentRepository.saveAndFlush(
                        new OrderPayment(
                                paymentId,
                                paymentNumber,
                                order.getId(),
                                method,
                                amount.amount(),
                                normalizedRef,
                                source,
                                cleanNote,
                                context.getUserId(),
                                recordedAt,
                                demoKey));

        Map<String, Object> auditValues = new LinkedHashMap<>();
        auditValues.put("paymentNumber", paymentNumber);
        auditValues.put("orderNumber", order.getOrderNumber());
        auditValues.put("method", method.name());
        auditValues.put("amount", amount.amount().toPlainString());
        if (normalizedRef != null) {
            auditValues.put("reference", normalizedRef);
        }
        auditValues.put("source", source.name());
        auditService.record(
                new AuditEvent(
                        AuditAction.PAYMENT_RECORDED,
                        "ORDER_PAYMENT",
                        paymentId,
                        order.getLocationId(),
                        context.getUserId(),
                        context.getUsername(),
                        null,
                        auditValues,
                        httpRequest == null ? null : httpRequest.getRemoteAddr(),
                        null));
        log.info("Recorded order payment id={} orderId={}", paymentId, order.getId());

        return mapPaymentViews(List.of(saved), Map.of(order.getId(), order)).getFirst();
    }

    /** Records a one-way manual verification or rejection decision on a payment. */
    @Transactional
    public OrderPaymentView verifyPayment(
            AccessContext context,
            UUID paymentId,
            String rawDecision,
            BigDecimal confirmedAmount,
            String evidence,
            HttpServletRequest httpRequest) {
        return decidePaymentInternal(
                context,
                paymentId,
                rawDecision,
                confirmedAmount,
                evidence,
                "STAFF_MANUAL",
                null,
                Instant.now(clock),
                httpRequest);
    }

    /** Internal helper supporting back-dated demo decisions through normal validation. */
    @Transactional
    public OrderPaymentView decidePaymentInternal(
            AccessContext context,
            UUID paymentId,
            String rawDecision,
            BigDecimal confirmedAmount,
            String evidence,
            String deciderKind,
            String providerReference,
            Instant decidedAt,
            HttpServletRequest httpRequest) {
        OrderPayment payment =
                paymentRepository
                        .findByIdForUpdate(paymentId)
                        .orElseThrow(() -> new NotFoundException("Payment not found."));
        Order order =
                orderRepository
                        .findByIdForUpdate(payment.getOrderId())
                        .orElseThrow(() -> new NotFoundException("Order not found."));

        // Enforce scope before state checks so unauthorized branch users cannot probe existence.
        requireWarehouseLocationAndPermission(
                context, order.getLocationId(), Permission.PAYMENT_VERIFY);

        if (decisionRepository.findByPaymentId(payment.getId()).isPresent()) {
            throw new ConflictException(
                    ErrorCodes.PAYMENT_ALREADY_DECIDED,
                    "This payment already has an immutable decision.");
        }

        boolean requireSeparateVerifier =
                settingService.get(SettingKey.PAYMENTS_REQUIRE_SEPARATE_VERIFIER, Boolean.class);
        VerificationPolicy.assertCanDecide(
                context.getUserId(),
                context.isOwner(),
                context.can(Permission.PAYMENT_VERIFY, order.getLocationId()),
                payment.getRecordedBy(),
                requireSeparateVerifier);

        PaymentDecision decision = PaymentDecision.fromString(rawDecision);
        CardNumberGuard.rejectCardNumberInText(evidence);
        CardNumberGuard.rejectCardNumberReference(providerReference);
        String cleanEvidence = trimToNull(evidence);
        if (cleanEvidence == null) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "Verification or rejection evidence is required.");
        }

        if (decision == PaymentDecision.VERIFIED) {
            if (confirmedAmount == null
                    || !Money.of(confirmedAmount).equals(Money.of(payment.getAmount()))) {
                throw new PaymentRuleException(
                        ErrorCodes.AMOUNT_MISMATCH,
                        422,
                        "Confirmed amount does not match recorded payment amount.");
            }
        }

        PaymentDecisionRecord record;
        try {
            record =
                    decisionRepository.saveAndFlush(
                            new PaymentDecisionRecord(
                                    UUID.randomUUID(),
                                    payment.getId(),
                                    decision,
                                    context.getUserId(),
                                    deciderKind,
                                    cleanEvidence,
                                    trimToNull(providerReference),
                                    decidedAt));
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(
                    ErrorCodes.PAYMENT_ALREADY_DECIDED,
                    "This payment already has an immutable decision.");
        }

        auditDecision(context, order, payment, record, httpRequest);
        return toPaymentView(
                payment, order, record, loadUsernames(List.of(payment), List.of(record)));
    }

    /** Checks a pending payment via the configured {@link PaymentVerifier} strategy. */
    @Transactional
    public ProviderCheckResponse checkWithProvider(
            AccessContext context, UUID paymentId, HttpServletRequest httpRequest) {
        OrderPayment payment =
                paymentRepository
                        .findByIdForUpdate(paymentId)
                        .orElseThrow(() -> new NotFoundException("Payment not found."));
        Order order =
                orderRepository
                        .findByIdForUpdate(payment.getOrderId())
                        .orElseThrow(() -> new NotFoundException("Order not found."));

        requireWarehouseLocationAndPermission(
                context, order.getLocationId(), Permission.PAYMENT_VERIFY);

        if (decisionRepository.findByPaymentId(payment.getId()).isPresent()) {
            throw new ConflictException(
                    ErrorCodes.PAYMENT_ALREADY_DECIDED,
                    "This payment already has an immutable decision.");
        }

        PaymentVerifier verifier = verifierRegistry.getVerifier();
        VerificationResult result =
                verifier.verify(
                        new PaymentClaim(
                                payment.getId(),
                                payment.getMethod(),
                                payment.getReference(),
                                Money.of(payment.getAmount()),
                                order.getOrderNumber()));

        if (result.kind() == VerificationKind.UNAVAILABLE) {
            throw new ConflictException(ErrorCodes.VERIFIER_UNAVAILABLE, result.message());
        }

        if (result.kind() == VerificationKind.CONFIRMED) {
            Instant now = Instant.now(clock);
            PaymentDecisionRecord record =
                    decisionRepository.saveAndFlush(
                            new PaymentDecisionRecord(
                                    UUID.randomUUID(),
                                    payment.getId(),
                                    PaymentDecision.VERIFIED,
                                    context.getUserId(),
                                    "PROVIDER:" + verifier.verifierName(),
                                    result.message(),
                                    result.providerReference(),
                                    now));
            auditDecision(context, order, payment, record, httpRequest);
            OrderPaymentView view =
                    toPaymentView(
                            payment,
                            order,
                            record,
                            loadUsernames(List.of(payment), List.of(record)));
            return new ProviderCheckResponse(
                    result.kind().name(), result.message(), result.providerReference(), view);
        }

        OrderPaymentView current =
                toPaymentView(payment, order, null, loadUsernames(List.of(payment), List.of()));
        return new ProviderCheckResponse(
                result.kind().name(), result.message(), result.providerReference(), current);
    }

    /** Returns unverified payments across the active online warehouse ordered oldest first. */
    @Transactional(readOnly = true)
    public List<PendingPaymentView> listPending(AccessContext context) {
        Location warehouse = requireOnlineWarehouseInScope(context, Permission.PAYMENT_VIEW);
        List<OrderPayment> pending =
                paymentRepository.findPendingByLocationIds(List.of(warehouse.getId()));
        return toPendingViews(pending);
    }

    /** Returns count of pending verification payments for overview cards (0 when out of scope). */
    @Transactional(readOnly = true)
    public long countPendingVisible(AccessContext context) {
        Optional<Location> wh = locationRepository.findFirstByOnlineFulfillmentTrueAndActiveTrue();
        if (wh.isEmpty()) {
            return 0L;
        }
        UUID whId = wh.get().getId();
        if (!context.isInScope(whId) || !context.can(Permission.PAYMENT_VIEW, whId)) {
            return 0L;
        }
        return paymentRepository.countPendingByLocationIds(List.of(whId));
    }

    private List<PendingPaymentView> toPendingViews(List<OrderPayment> pending) {
        if (pending.isEmpty()) {
            return List.of();
        }
        int overdueHoursThreshold =
                settingService.get(SettingKey.PAYMENTS_VERIFICATION_OVERDUE_HOURS, Integer.class);
        Instant now = Instant.now(clock);

        List<UUID> orderIds = pending.stream().map(OrderPayment::getOrderId).distinct().toList();
        Map<UUID, Order> orders = new HashMap<>();
        for (Order o : orderRepository.findAllById(orderIds)) {
            orders.put(o.getId(), o);
        }
        Map<UUID, String> usernames = loadUsernames(pending, List.of());

        List<PendingPaymentView> rows = new ArrayList<>(pending.size());
        for (OrderPayment p : pending) {
            Order o = orders.get(p.getOrderId());
            long hours = Math.max(0L, Duration.between(p.getRecordedAt(), now).toHours());
            rows.add(
                    new PendingPaymentView(
                            p.getId(),
                            p.getPaymentNumber(),
                            p.getOrderId(),
                            o != null ? o.getOrderNumber() : "",
                            o != null ? o.getStatus() : null,
                            o != null ? o.getCustomerName() : "",
                            p.getMethod(),
                            p.getAmount(),
                            p.getReference(),
                            p.getSource(),
                            p.getRecordedBy(),
                            usernames.getOrDefault(p.getRecordedBy(), "unknown"),
                            p.getRecordedAt(),
                            hours,
                            hours >= overdueHoursThreshold));
        }
        return rows;
    }

    /** Resolves an order within caller scope using 404 out-of-scope / 403 missing-permission. */
    public Order loadWarehouseOrder(
            AccessContext context, UUID orderId, Permission permission, boolean forUpdate) {
        Order order =
                (forUpdate
                                ? orderRepository.findByIdForUpdate(orderId)
                                : orderRepository.findById(orderId))
                        .orElseThrow(() -> new NotFoundException("Order not found."));
        requireWarehouseLocationAndPermission(context, order.getLocationId(), permission);
        return order;
    }

    /** Enforces 404 when outside WH scope and 403 when in WH scope without permission. */
    public Location requireOnlineWarehouseInScope(AccessContext context, Permission permission) {
        Location warehouse =
                locationRepository
                        .findFirstByOnlineFulfillmentTrueAndActiveTrue()
                        .orElseThrow(
                                () ->
                                        new ConflictException(
                                                ErrorCodes.ONLINE_LOCATION_NOT_SET,
                                                "No active warehouse is configured for online fulfillment."));
        requireWarehouseLocationAndPermission(context, warehouse.getId(), permission);
        return warehouse;
    }

    public static void requireWarehouseLocationAndPermission(
            AccessContext context, UUID locationId, Permission permission) {
        if (context.isOwner()) {
            return;
        }
        context.requireInScope(locationId);
        context.require(permission, locationId);
    }

    private ClaimedAdvanceView buildClaimPrefill(Order order, OrderPaymentPosition position) {
        ClaimedAdvanceMethod method = order.getClaimedAdvanceMethod();
        String ref = trimToNull(order.getClaimedAdvanceReference());
        boolean hasClaim =
                method != null
                        || ref != null
                        || (order.getAdvanceAmount() != null
                                && order.getAdvanceAmount().signum() > 0);
        Money recorded = position.verifiedPaid().plus(position.pendingPaid());
        Money remaining = position.requiredPrepayment().minus(recorded).max(Money.ZERO);
        return new ClaimedAdvanceView(
                hasClaim, method, ref, order.getAdvanceAmount(), remaining.amount());
    }

    private static PaymentSource resolvePaymentSource(
            Order order, OnlinePaymentMethod method, String normalizedRef, Boolean fromClaim) {
        if (Boolean.TRUE.equals(fromClaim)) {
            return PaymentSource.WEBSITE_CLAIM;
        }
        if (order.getClaimedAdvanceMethod() != null
                && order.getClaimedAdvanceMethod().name().equals(method.name())
                && order.getClaimedAdvanceReference() != null
                && order.getClaimedAdvanceReference().trim().equalsIgnoreCase(normalizedRef)) {
            return PaymentSource.WEBSITE_CLAIM;
        }
        return PaymentSource.STAFF_ENTERED;
    }

    private void auditDecision(
            AccessContext context,
            Order order,
            OrderPayment payment,
            PaymentDecisionRecord record,
            HttpServletRequest httpRequest) {
        AuditAction action =
                record.getDecision() == PaymentDecision.VERIFIED
                        ? AuditAction.PAYMENT_VERIFIED
                        : AuditAction.PAYMENT_REJECTED;
        Map<String, Object> auditValues = new LinkedHashMap<>();
        auditValues.put("paymentNumber", payment.getPaymentNumber());
        auditValues.put("orderNumber", order.getOrderNumber());
        auditValues.put("method", payment.getMethod().name());
        auditValues.put("amount", payment.getAmount().toPlainString());
        if (payment.getReference() != null) {
            auditValues.put("reference", payment.getReference());
        }
        auditValues.put("decision", record.getDecision().name());
        auditValues.put("deciderKind", record.getDeciderKind());
        auditService.record(
                new AuditEvent(
                        action,
                        "ORDER_PAYMENT",
                        payment.getId(),
                        order.getLocationId(),
                        context.getUserId(),
                        context.getUsername(),
                        null,
                        auditValues,
                        httpRequest == null ? null : httpRequest.getRemoteAddr(),
                        null));
        log.info("Decided order payment id={} decision={}", payment.getId(), record.getDecision());
    }

    public List<OrderPaymentView> mapPaymentViews(
            List<OrderPayment> payments, Map<UUID, Order> orderCache) {
        if (payments.isEmpty()) {
            return List.of();
        }
        List<UUID> paymentIds = payments.stream().map(OrderPayment::getId).toList();
        Map<UUID, PaymentDecisionRecord> decisions = new HashMap<>();
        for (PaymentDecisionRecord d : decisionRepository.findByPaymentIdIn(paymentIds)) {
            decisions.put(d.getPaymentId(), d);
        }
        Map<UUID, String> usernames = loadUsernames(payments, decisions.values());
        List<OrderPaymentView> views = new ArrayList<>(payments.size());
        for (OrderPayment p : payments) {
            Order o = orderCache.get(p.getOrderId());
            if (o == null) {
                o = orderRepository.findById(p.getOrderId()).orElse(null);
            }
            views.add(toPaymentView(p, o, decisions.get(p.getId()), usernames));
        }
        return views;
    }

    private OrderPaymentView toPaymentView(
            OrderPayment p, Order order, PaymentDecisionRecord d, Map<UUID, String> usernames) {
        PaymentDecisionView decisionView = null;
        if (d != null) {
            decisionView =
                    new PaymentDecisionView(
                            d.getId(),
                            d.getDecision(),
                            d.getDecidedBy(),
                            d.getDecidedBy() == null
                                    ? d.getDeciderKind()
                                    : usernames.getOrDefault(d.getDecidedBy(), "unknown"),
                            d.getDeciderKind(),
                            d.getEvidence(),
                            d.getProviderReference(),
                            d.getDecidedAt());
        }
        return new OrderPaymentView(
                p.getId(),
                p.getPaymentNumber(),
                p.getOrderId(),
                order != null ? order.getOrderNumber() : "",
                p.getMethod(),
                p.getAmount(),
                p.getReference(),
                p.getSource(),
                p.getPayerNote(),
                p.getRecordedBy(),
                usernames.getOrDefault(p.getRecordedBy(), "unknown"),
                p.getRecordedAt(),
                PaymentStatus.derive(d == null ? null : d.getDecision()),
                decisionView);
    }

    private Map<UUID, String> loadUsernames(
            Collection<OrderPayment> payments, Collection<PaymentDecisionRecord> decisions) {
        List<UUID> ids = new ArrayList<>();
        for (OrderPayment p : payments) {
            if (p.getRecordedBy() != null) {
                ids.add(p.getRecordedBy());
            }
        }
        for (PaymentDecisionRecord d : decisions) {
            if (d.getDecidedBy() != null) {
                ids.add(d.getDecidedBy());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (AppUser user : userRepository.findAllById(ids)) {
            names.put(user.getId(), user.getUsername());
        }
        return names;
    }

    private static String formatPaymentNumber(long sequenceValue) {
        return "PAY-%06d".formatted(sequenceValue);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
