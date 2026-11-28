package com.merqo.payments;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.orders.Order;
import com.merqo.orders.OrderRepository;
import com.merqo.orders.domain.OrderStatus;
import com.merqo.payments.PaymentDtos.RefundDueRowView;
import com.merqo.payments.PaymentDtos.RefundEventView;
import com.merqo.payments.PaymentDtos.RefundView;
import com.merqo.payments.domain.CardNumberGuard;
import com.merqo.payments.domain.OrderPaymentPosition;
import com.merqo.payments.domain.RefundApprovalPolicy;
import com.merqo.payments.domain.RefundPayoutMethod;
import com.merqo.payments.domain.RefundPayoutMethod.ValidatedPayout;
import com.merqo.payments.domain.RefundRules;
import com.merqo.payments.domain.RefundRules.RefundReason;
import com.merqo.payments.domain.RefundStatus;
import com.merqo.payments.domain.RefundStatus.RefundEventType;
import com.merqo.payments.domain.RefundTransitions;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * Application service governing online/manual order refund requests, four-eyes approvals, and
 * immutable payout completions.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundRequestRepository refundRequestRepository;
    private final RefundEventRepository refundEventRepository;
    private final OrderRepository orderRepository;
    private final OrderPaymentPositionService positionService;
    private final LocationRepository locationRepository;
    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final Clock clock;

    public RefundService(
            RefundRequestRepository refundRequestRepository,
            RefundEventRepository refundEventRepository,
            OrderRepository orderRepository,
            OrderPaymentPositionService positionService,
            LocationRepository locationRepository,
            AppUserRepository userRepository,
            AuditService auditService,
            Clock clock) {
        this.refundRequestRepository = refundRequestRepository;
        this.refundEventRepository = refundEventRepository;
        this.orderRepository = orderRepository;
        this.positionService = positionService;
        this.locationRepository = locationRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** Creates an append-only refund request against a verified paid order. */
    @Transactional
    public RefundView requestRefund(
            AccessContext context,
            UUID orderId,
            BigDecimal amount,
            String rawReason,
            String note,
            HttpServletRequest httpRequest) {
        return requestRefundInternal(
                context, orderId, amount, rawReason, note, Instant.now(clock), null, httpRequest);
    }

    /** Internal helper allowing back-dated idempotent demo seeding via real validation. */
    @Transactional
    public RefundView requestRefundInternal(
            AccessContext context,
            UUID orderId,
            BigDecimal rawAmount,
            String rawReason,
            String note,
            Instant requestedAt,
            String demoKey,
            HttpServletRequest httpRequest) {
        if (demoKey != null) {
            Optional<RefundRequest> existing = refundRequestRepository.findByDemoKey(demoKey);
            if (existing.isPresent()) {
                return mapViews(List.of(existing.get())).getFirst();
            }
        }

        Order order =
                orderRepository
                        .findByIdForUpdate(orderId)
                        .orElseThrow(() -> new NotFoundException("Order not found."));
        OrderPaymentService.requireWarehouseLocationAndPermission(
                context, order.getLocationId(), Permission.PAYMENT_RECORD);

        if (rawAmount == null) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "Refund amount is required.");
        }
        Money amount = Money.of(rawAmount);
        RefundReason reason = RefundReason.fromString(rawReason);
        String cleanNote = trimToNull(note);

        OrderPaymentPosition position = positionService.computePosition(order);
        RefundRules.validateRequest(position, amount, reason, cleanNote);

        UUID refundId = UUID.randomUUID();
        String refundNumber = formatRefundNumber(refundRequestRepository.nextRefundSequence());
        RefundRequest saved =
                refundRequestRepository.saveAndFlush(
                        new RefundRequest(
                                refundId,
                                refundNumber,
                                order.getId(),
                                amount.amount(),
                                reason,
                                cleanNote,
                                context.getUserId(),
                                requestedAt,
                                demoKey));

        Map<String, Object> auditValues = new LinkedHashMap<>();
        auditValues.put("refundNumber", refundNumber);
        auditValues.put("orderNumber", order.getOrderNumber());
        auditValues.put("amount", amount.amount().toPlainString());
        auditValues.put("reason", reason.name());
        auditService.record(
                new AuditEvent(
                        AuditAction.REFUND_REQUESTED,
                        "REFUND_REQUEST",
                        refundId,
                        order.getLocationId(),
                        context.getUserId(),
                        context.getUsername(),
                        null,
                        auditValues,
                        httpRequest == null ? null : httpRequest.getRemoteAddr(),
                        null));
        log.info("Requested refund id={} orderId={}", refundId, order.getId());

        return mapViews(List.of(saved)).getFirst();
    }

    /** Approves a pending refund request respecting {@link RefundApprovalPolicy}. */
    @Transactional
    public RefundView approve(
            AccessContext context, UUID refundId, String note, HttpServletRequest httpRequest) {
        return decideInternal(
                context, refundId, RefundStatus.APPROVED, note, Instant.now(clock), httpRequest);
    }

    /** Rejects a refund request with a required explanatory note. */
    @Transactional
    public RefundView reject(
            AccessContext context, UUID refundId, String note, HttpServletRequest httpRequest) {
        return decideInternal(
                context, refundId, RefundStatus.REJECTED, note, Instant.now(clock), httpRequest);
    }

    /** Internal helper shared across live endpoints and idempotent demo seeding. */
    @Transactional
    public RefundView decideInternal(
            AccessContext context,
            UUID refundId,
            RefundStatus targetStatus,
            String note,
            Instant decidedAt,
            HttpServletRequest httpRequest) {
        RefundRequest req =
                refundRequestRepository
                        .findByIdForUpdate(refundId)
                        .orElseThrow(() -> new NotFoundException("Refund request not found."));
        Order order =
                orderRepository
                        .findById(req.getOrderId())
                        .orElseThrow(() -> new NotFoundException("Order not found."));

        OrderPaymentService.requireWarehouseLocationAndPermission(
                context, order.getLocationId(), Permission.REFUND_APPROVE);

        List<RefundEvent> events =
                refundEventRepository.findByRefundIdOrderByCreatedAtAsc(refundId);
        RefundStatus current =
                RefundStatus.derive(events.stream().map(RefundEvent::getEventType).toList());
        RefundTransitions.requireTransition(current, targetStatus);

        RefundApprovalPolicy.assertCanDecide(
                context.getUserId(),
                context.isOwner(),
                context.can(Permission.REFUND_APPROVE, order.getLocationId()),
                req.getRequestedBy());

        CardNumberGuard.rejectCardNumberInText(note);
        String cleanNote = trimToNull(note);
        if (targetStatus == RefundStatus.REJECTED && cleanNote == null) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "A note is required when rejecting a refund.");
        }

        RefundEventType eventType =
                targetStatus == RefundStatus.APPROVED
                        ? RefundEventType.APPROVED
                        : RefundEventType.REJECTED;
        try {
            refundEventRepository.saveAndFlush(
                    new RefundEvent(
                            UUID.randomUUID(),
                            refundId,
                            eventType,
                            context.getUserId(),
                            cleanNote,
                            null,
                            null,
                            decidedAt));
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(
                    ErrorCodes.INVALID_TRANSITION,
                    "Refund cannot move from " + current + " to " + targetStatus + ".");
        }

        AuditAction action =
                targetStatus == RefundStatus.APPROVED
                        ? AuditAction.REFUND_APPROVED
                        : AuditAction.REFUND_REJECTED;
        Map<String, Object> auditValues = new LinkedHashMap<>();
        auditValues.put("refundNumber", req.getRefundNumber());
        auditValues.put("orderNumber", order.getOrderNumber());
        auditValues.put("amount", req.getAmount().toPlainString());
        auditValues.put("status", targetStatus.name());
        auditService.record(
                new AuditEvent(
                        action,
                        "REFUND_REQUEST",
                        refundId,
                        order.getLocationId(),
                        context.getUserId(),
                        context.getUsername(),
                        null,
                        auditValues,
                        httpRequest == null ? null : httpRequest.getRemoteAddr(),
                        null));
        log.info("Decided refund id={} targetStatus={}", refundId, targetStatus);

        return mapViews(List.of(req)).getFirst();
    }

    /** Completes payout for an {@code APPROVED} refund request. */
    @Transactional
    public RefundView pay(
            AccessContext context,
            UUID refundId,
            String rawPayoutMethod,
            String rawPayoutReference,
            String note,
            HttpServletRequest httpRequest) {
        return payInternal(
                context,
                refundId,
                rawPayoutMethod,
                rawPayoutReference,
                note,
                Instant.now(clock),
                httpRequest);
    }

    /** Internal helper supporting back-dated demo payout creation. */
    @Transactional
    public RefundView payInternal(
            AccessContext context,
            UUID refundId,
            String rawPayoutMethod,
            String rawPayoutReference,
            String note,
            Instant paidAt,
            HttpServletRequest httpRequest) {
        RefundRequest req =
                refundRequestRepository
                        .findByIdForUpdate(refundId)
                        .orElseThrow(() -> new NotFoundException("Refund request not found."));
        Order order =
                orderRepository
                        .findByIdForUpdate(req.getOrderId())
                        .orElseThrow(() -> new NotFoundException("Order not found."));

        OrderPaymentService.requireWarehouseLocationAndPermission(
                context, order.getLocationId(), Permission.PAYMENT_RECORD);

        List<RefundEvent> events =
                refundEventRepository.findByRefundIdOrderByCreatedAtAsc(refundId);
        RefundStatus current =
                RefundStatus.derive(events.stream().map(RefundEvent::getEventType).toList());
        RefundTransitions.requireTransition(current, RefundStatus.PAID);

        ValidatedPayout payout =
                RefundPayoutMethod.fromString(rawPayoutMethod)
                        .validateAndNormalize(rawPayoutReference, note);
        if (payout.normalizedReference() != null
                && refundEventRepository.existsByPayoutMethodAndPayoutReference(
                        payout.method(), payout.normalizedReference())) {
            throw new ConflictException(
                    ErrorCodes.DUPLICATE_REFERENCE,
                    "This payout reference has already been used for another refund.");
        }

        try {
            refundEventRepository.saveAndFlush(
                    new RefundEvent(
                            UUID.randomUUID(),
                            refundId,
                            RefundEventType.PAID,
                            context.getUserId(),
                            payout.note(),
                            payout.method(),
                            payout.normalizedReference(),
                            paidAt));
        } catch (DataIntegrityViolationException ex) {
            if (isConstraint(ex, "ux_refund_events_payout_ref")) {
                throw new ConflictException(
                        ErrorCodes.DUPLICATE_REFERENCE,
                        "This payout reference has already been used for another refund.");
            }
            throw new ConflictException(
                    ErrorCodes.INVALID_TRANSITION, "Refund payout already completed.");
        }

        Map<String, Object> auditValues = new LinkedHashMap<>();
        auditValues.put("refundNumber", req.getRefundNumber());
        auditValues.put("orderNumber", order.getOrderNumber());
        auditValues.put("amount", req.getAmount().toPlainString());
        auditValues.put("payoutMethod", payout.method().name());
        if (payout.normalizedReference() != null) {
            auditValues.put("payoutReference", payout.normalizedReference());
        }
        auditService.record(
                new AuditEvent(
                        AuditAction.REFUND_PAID,
                        "REFUND_REQUEST",
                        refundId,
                        order.getLocationId(),
                        context.getUserId(),
                        context.getUsername(),
                        null,
                        auditValues,
                        httpRequest == null ? null : httpRequest.getRemoteAddr(),
                        null));
        log.info("Completed refund payout id={}", refundId);

        return mapViews(List.of(req)).getFirst();
    }

    /** Lists scoped refund requests with optional status and order number filtering. */
    @Transactional(readOnly = true)
    public List<RefundView> listRefunds(
            AccessContext context, RefundStatus filterStatus, String orderNumberFilter) {
        Location warehouse = requireWarehouseView(context);
        List<RefundRequest> scoped =
                refundRequestRepository.findScopedNewestFirst(List.of(warehouse.getId()));
        List<RefundView> mapped = mapViews(scoped);
        String needle =
                orderNumberFilter == null || orderNumberFilter.isBlank()
                        ? null
                        : orderNumberFilter.trim().toLowerCase(Locale.ROOT);
        List<RefundView> result = new ArrayList<>();
        for (RefundView v : mapped) {
            if (filterStatus != null && v.status() != filterStatus) {
                continue;
            }
            if (needle != null
                    && !v.orderNumber().toLowerCase(Locale.ROOT).contains(needle)
                    && !v.refundNumber().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            result.add(v);
        }
        return result;
    }

    /**
     * Lists closed ({@code CANCELLED} or {@code DECLINED}) warehouse orders with {@code refundable
     * > 0} and no currently open refund request.
     */
    @Transactional(readOnly = true)
    public List<RefundDueRowView> listDueRefunds(AccessContext context) {
        Location warehouse = requireWarehouseView(context);
        List<Order> candidates =
                orderRepository.findAll(
                        (root, query, cb) ->
                                cb.and(
                                        cb.equal(root.get("locationId"), warehouse.getId()),
                                        root.get("status")
                                                .in(
                                                        List.of(
                                                                OrderStatus.CANCELLED,
                                                                OrderStatus.DECLINED))));
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<RefundDueRowView> due = new ArrayList<>();
        for (Order order : candidates) {
            OrderPaymentPosition pos = positionService.computePosition(order);
            if (pos.refundable().compareTo(Money.ZERO) <= 0) {
                continue;
            }
            List<RefundView> existing = listForOrder(order.getId());
            boolean hasOpenRequest =
                    existing.stream().anyMatch(r -> r.status().reservesRefundableBalance());
            if (hasOpenRequest) {
                continue;
            }
            due.add(
                    new RefundDueRowView(
                            order.getId(),
                            order.getOrderNumber(),
                            order.getStatus(),
                            order.getCustomerName(),
                            pos.verifiedPaid().amount(),
                            pos.refundedPaid().amount(),
                            pos.refundable().amount(),
                            order.getClosedAt()));
        }
        return due;
    }

    /** Lists refund views for one order (called internally by {@link OrderPaymentService}). */
    public List<RefundView> listForOrder(UUID orderId) {
        return mapViews(refundRequestRepository.findByOrderIdOrderByRequestedAtAsc(orderId));
    }

    private Location requireWarehouseView(AccessContext context) {
        Location warehouse =
                locationRepository
                        .findFirstByOnlineFulfillmentTrueAndActiveTrue()
                        .orElseThrow(
                                () ->
                                        new ConflictException(
                                                ErrorCodes.ONLINE_LOCATION_NOT_SET,
                                                "No active warehouse is configured for online fulfillment."));
        OrderPaymentService.requireWarehouseLocationAndPermission(
                context, warehouse.getId(), Permission.PAYMENT_VIEW);
        return warehouse;
    }

    private List<RefundView> mapViews(List<RefundRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        List<UUID> refundIds = requests.stream().map(RefundRequest::getId).toList();
        List<UUID> orderIds = requests.stream().map(RefundRequest::getOrderId).distinct().toList();

        Map<UUID, Order> orderMap = new HashMap<>();
        for (Order o : orderRepository.findAllById(orderIds)) {
            orderMap.put(o.getId(), o);
        }

        Map<UUID, List<RefundEvent>> eventsByRefund = new HashMap<>();
        List<RefundEvent> allEvents =
                refundEventRepository.findByRefundIdInOrderByCreatedAtAsc(refundIds);
        for (RefundEvent e : allEvents) {
            eventsByRefund.computeIfAbsent(e.getRefundId(), k -> new ArrayList<>()).add(e);
        }

        Map<UUID, String> usernames = loadUsernames(requests, allEvents);

        List<RefundView> views = new ArrayList<>(requests.size());
        for (RefundRequest r : requests) {
            List<RefundEvent> evs = eventsByRefund.getOrDefault(r.getId(), List.of());
            RefundStatus status =
                    RefundStatus.derive(evs.stream().map(RefundEvent::getEventType).toList());
            Order order = orderMap.get(r.getOrderId());
            List<RefundEventView> eventViews =
                    evs.stream()
                            .map(
                                    e ->
                                            new RefundEventView(
                                                    e.getId(),
                                                    e.getEventType(),
                                                    e.getActorId(),
                                                    usernames.getOrDefault(
                                                            e.getActorId(), "unknown"),
                                                    e.getNote(),
                                                    e.getPayoutMethod(),
                                                    e.getPayoutReference(),
                                                    e.getCreatedAt()))
                            .toList();
            views.add(
                    new RefundView(
                            r.getId(),
                            r.getRefundNumber(),
                            r.getOrderId(),
                            order != null ? order.getOrderNumber() : "",
                            order != null ? order.getStatus() : null,
                            r.getAmount(),
                            r.getReason(),
                            r.getNote(),
                            r.getRequestedBy(),
                            usernames.getOrDefault(r.getRequestedBy(), "unknown"),
                            r.getRequestedAt(),
                            status,
                            eventViews));
        }
        return views;
    }

    private Map<UUID, String> loadUsernames(
            Collection<RefundRequest> requests, Collection<RefundEvent> events) {
        List<UUID> ids = new ArrayList<>();
        for (RefundRequest r : requests) {
            ids.add(r.getRequestedBy());
        }
        for (RefundEvent e : events) {
            ids.add(e.getActorId());
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (AppUser u : userRepository.findAllById(ids)) {
            names.put(u.getId(), u.getUsername());
        }
        return names;
    }

    private static String formatRefundNumber(long sequenceValue) {
        return "RFD-%06d".formatted(sequenceValue);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isConstraint(Throwable throwable, String name) {
        Throwable cur = throwable;
        while (cur != null) {
            if (cur.getMessage() != null && cur.getMessage().contains(name)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
