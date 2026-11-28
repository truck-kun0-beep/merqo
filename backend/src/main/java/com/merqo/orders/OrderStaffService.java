package com.merqo.orders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.catalog.Product;
import com.merqo.catalog.ProductRepository;
import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.ProductVariantRepository;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.ForbiddenException;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;
import com.merqo.common.time.BusinessTime;
import com.merqo.common.web.PageRequest;
import com.merqo.common.web.PageResponse;
import com.merqo.inventory.InventoryCommand;
import com.merqo.inventory.InventoryPolicy;
import com.merqo.inventory.InventoryPolicyRepository;
import com.merqo.inventory.InventoryService;
import com.merqo.inventory.StockBalance;
import com.merqo.inventory.domain.MovementType;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.orders.OrderDtos.OrderCallLogView;
import com.merqo.orders.OrderDtos.OrderCancelResponse;
import com.merqo.orders.OrderDtos.OrderCountsView;
import com.merqo.orders.OrderDtos.OrderDetailView;
import com.merqo.orders.OrderDtos.OrderFlagsView;
import com.merqo.orders.OrderDtos.OrderLineView;
import com.merqo.orders.OrderDtos.OrderStatusHistoryView;
import com.merqo.orders.OrderDtos.OrderSummaryView;
import com.merqo.orders.OrderDtos.StockSnapshotView;
import com.merqo.orders.OrderDtos.WarehouseAvailabilityResponse;
import com.merqo.orders.OrderDtos.WarehouseAvailabilityRow;
import com.merqo.orders.PhoneRiskService.PhoneRiskSnapshot;
import com.merqo.orders.domain.CallResult;
import com.merqo.orders.domain.CancelReason;
import com.merqo.orders.domain.DeclineReason;
import com.merqo.orders.domain.OnlineAvailabilityCalculator;
import com.merqo.orders.domain.OrderChannel;
import com.merqo.orders.domain.OrderRuleException;
import com.merqo.orders.domain.OrderStatus;
import com.merqo.orders.domain.OrderTransitions;
import com.merqo.orders.domain.PhoneNumber;
import com.merqo.orders.domain.PhoneRiskPolicy.RiskTier;
import com.merqo.settings.SettingKey;
import com.merqo.settings.SettingService;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * Staff workflows for reviewing, calling, editing, confirming (hard reserving stock), declining,
 * cancelling, and inspecting online/manual orders within strict warehouse scope.
 */
@Service
public class OrderStaffService {

    private final OrderRepository orderRepository;
    private final OrderLineRepository orderLineRepository;
    private final OrderCallLogRepository callLogRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final CustomerService customerService;
    private final PhoneRiskService phoneRiskService;
    private final LocationRepository locationRepository;
    private final InventoryService inventoryService;
    private final InventoryPolicyRepository policyRepository;
    private final OrderSoftHoldReader softHoldReader;
    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final AppUserRepository userRepository;
    private final SettingService settingService;
    private final AuditService auditService;
    private final Clock clock;

    public OrderStaffService(
            OrderRepository orderRepository,
            OrderLineRepository orderLineRepository,
            OrderCallLogRepository callLogRepository,
            OrderStatusHistoryRepository statusHistoryRepository,
            CustomerService customerService,
            PhoneRiskService phoneRiskService,
            LocationRepository locationRepository,
            InventoryService inventoryService,
            InventoryPolicyRepository policyRepository,
            OrderSoftHoldReader softHoldReader,
            ProductVariantRepository variantRepository,
            ProductRepository productRepository,
            AppUserRepository userRepository,
            SettingService settingService,
            AuditService auditService,
            Clock clock) {
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.callLogRepository = callLogRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.customerService = customerService;
        this.phoneRiskService = phoneRiskService;
        this.locationRepository = locationRepository;
        this.inventoryService = inventoryService;
        this.policyRepository = policyRepository;
        this.softHoldReader = softHoldReader;
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.settingService = settingService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryView> listOrders(
            AccessContext context,
            OrderStatus status,
            OrderChannel channel,
            Boolean needsAction,
            Boolean priceMismatch,
            Boolean invalidPhone,
            Boolean cancellationRequested,
            String q,
            LocalDate fromDate,
            LocalDate toDate,
            PageRequest pageRequest) {
        Set<UUID> visibleLocations = requireAnyOrderViewScope(context);
        Instant fromInstant = fromDate == null ? null : BusinessTime.startOfBusinessDay(fromDate);
        Instant toInstant = toDate == null ? null : BusinessTime.endOfBusinessDay(toDate);

        Specification<Order> spec =
                (root, query, cb) -> {
                    List<Predicate> predicates = new ArrayList<>();
                    predicates.add(root.get("locationId").in(visibleLocations));
                    if (status != null) {
                        predicates.add(cb.equal(root.get("status"), status));
                    }
                    if (channel != null) {
                        predicates.add(cb.equal(root.get("channel"), channel));
                    }
                    if (Boolean.TRUE.equals(needsAction)) {
                        predicates.add(
                                root.get("status")
                                        .in(
                                                List.of(
                                                        OrderStatus.PENDING_CONFIRMATION,
                                                        OrderStatus.FOLLOW_UP)));
                    }
                    if (Boolean.TRUE.equals(priceMismatch)) {
                        predicates.add(cb.isTrue(root.get("priceMismatch")));
                    }
                    if (Boolean.TRUE.equals(invalidPhone)) {
                        predicates.add(cb.isNull(root.get("phoneNormalized")));
                    }
                    if (Boolean.TRUE.equals(cancellationRequested)) {
                        predicates.add(cb.isNotNull(root.get("cancellationRequestedAt")));
                    }
                    if (fromInstant != null) {
                        predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromInstant));
                    }
                    if (toInstant != null) {
                        predicates.add(cb.lessThan(root.get("createdAt"), toInstant));
                    }
                    if (q != null && !q.isBlank()) {
                        String pattern = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                        predicates.add(
                                cb.or(
                                        cb.like(cb.lower(root.get("orderNumber")), pattern),
                                        cb.like(
                                                cb.lower(
                                                        cb.coalesce(
                                                                root.get("externalOrderId"), "")),
                                                pattern),
                                        cb.like(cb.lower(root.get("customerName")), pattern),
                                        cb.like(cb.lower(root.get("phoneRaw")), pattern),
                                        cb.like(
                                                cb.lower(
                                                        cb.coalesce(
                                                                root.get("phoneNormalized"), "")),
                                                pattern)));
                    }
                    return cb.and(predicates.toArray(new Predicate[0]));
                };

        Pageable pageable = pageRequest.toSpring();
        Page<Order> page = orderRepository.findAll(spec, pageable);
        return PageResponse.of(page.map(this::toSummaryView));
    }

    @Transactional(readOnly = true)
    public OrderCountsView counts(AccessContext context) {
        Set<UUID> visibleLocations = requireAnyOrderViewScope(context);
        long pending =
                countInLocationsAndStatus(visibleLocations, OrderStatus.PENDING_CONFIRMATION);
        long followUp = countInLocationsAndStatus(visibleLocations, OrderStatus.FOLLOW_UP);
        long confirmed = countInLocationsAndStatus(visibleLocations, OrderStatus.CONFIRMED);
        return new OrderCountsView(pending, followUp, pending + followUp, confirmed);
    }

    @Transactional(readOnly = true)
    public OrderDetailView getDetail(AccessContext context, UUID orderId) {
        Order order = loadScopedOrder(context, orderId, Permission.ORDER_VIEW, false);
        return buildDetailView(context, order);
    }

    @Transactional
    public OrderDetailView logCall(
            AccessContext context,
            UUID orderId,
            CallResult result,
            String note,
            Instant followUpAt) {
        if (result == null) {
            throw new ValidationException(ErrorCodes.VALIDATION_FAILED, "Call result is required.");
        }
        String cleanNote = note == null ? null : note.trim();
        if (result.requiresNote() && (cleanNote == null || cleanNote.isEmpty())) {
            throw new ValidationException(
                    ErrorCodes.CALL_NOTE_REQUIRED,
                    "A note is required when the customer does not answer normally.");
        }

        Order order = loadScopedOrder(context, orderId, Permission.ORDER_CALL, true);
        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION
                && order.getStatus() != OrderStatus.FOLLOW_UP) {
            throw new OrderRuleException(
                    ErrorCodes.INVALID_TRANSITION,
                    409,
                    "Calls can only be logged while an order awaits confirmation.");
        }

        Instant now = Instant.now(clock);
        Instant effectiveFollowUp = null;
        if (result.expectsFollowUp()) {
            if (followUpAt != null) {
                effectiveFollowUp = followUpAt;
            } else {
                int defaultMinutes =
                        settingService.get(
                                SettingKey.ORDERS_FOLLOW_UP_DEFAULT_MINUTES, Integer.class);
                effectiveFollowUp = now.plus(defaultMinutes, ChronoUnit.MINUTES);
            }
            order.setFollowUpAt(effectiveFollowUp);
            if (order.getStatus() == OrderStatus.PENDING_CONFIRMATION) {
                OrderTransitions.requireTransition(
                        OrderStatus.PENDING_CONFIRMATION, OrderStatus.FOLLOW_UP);
                order.setStatus(OrderStatus.FOLLOW_UP);
                statusHistoryRepository.save(
                        new OrderStatusHistory(
                                UUID.randomUUID(),
                                order.getId(),
                                OrderStatus.PENDING_CONFIRMATION,
                                OrderStatus.FOLLOW_UP,
                                context.getUserId(),
                                "user:" + context.getUsername(),
                                result.name(),
                                cleanNote,
                                now));
            }
            orderRepository.save(order);
        }

        int attemptNumber = callLogRepository.countByOrderId(order.getId()) + 1;
        callLogRepository.save(
                new OrderCallLog(
                        UUID.randomUUID(),
                        order.getId(),
                        attemptNumber,
                        result,
                        cleanNote,
                        effectiveFollowUp,
                        context.getUserId(),
                        now));

        return buildDetailView(context, order);
    }

    @Transactional
    public OrderDetailView editCustomer(
            AccessContext context,
            UUID orderId,
            String customerName,
            String phoneInput,
            String address,
            String district,
            String note,
            HttpServletRequest request) {
        Order order = loadScopedOrder(context, orderId, Permission.ORDER_CALL, true);
        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION
                && order.getStatus() != OrderStatus.FOLLOW_UP) {
            throw new OrderRuleException(
                    ErrorCodes.INVALID_TRANSITION,
                    409,
                    "Customer details can only be edited before order confirmation.");
        }

        String trimmedName = customerName == null ? "" : customerName.trim();
        String trimmedAddress = address == null ? "" : address.trim();
        if (trimmedName.isEmpty() || trimmedAddress.isEmpty()) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "Customer name and address are required.");
        }

        PhoneNumber parsedPhone = PhoneNumber.parse(phoneInput);
        if (!parsedPhone.isValid()) {
            throw new ValidationException(
                    ErrorCodes.INVALID_PHONE,
                    "Phone must be a valid Bangladeshi mobile number (e.g. 01712345678).");
        }

        Customer customer =
                customerService.findOrCreateByPhone(parsedPhone.normalized(), trimmedName);

        String oldName = order.getCustomerName();
        String oldPhone = order.getPhoneRaw();
        String oldAddress = order.getAddress();

        order.setCustomerId(customer.getId());
        order.setCustomerName(trimmedName);
        order.setPhoneRaw(parsedPhone.raw());
        order.setPhoneNormalized(parsedPhone.normalized());
        order.setAddress(trimmedAddress);
        order.setDistrict(district == null || district.isBlank() ? null : district.trim());
        if (note != null) {
            order.setNote(note.isBlank() ? null : note.trim());
        }
        orderRepository.save(order);

        auditService.record(
                new AuditEvent.Builder(AuditAction.ORDER_CUSTOMER_EDITED, "order")
                        .entityId(order.getId())
                        .locationId(order.getLocationId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("orderNumber", order.getOrderNumber())
                        .changed("customerName", oldName, trimmedName)
                        .changed("phoneRaw", oldPhone, parsedPhone.raw())
                        .changed("address", oldAddress, trimmedAddress)
                        .build(),
                request);

        return buildDetailView(context, order);
    }

    @Transactional
    public OrderDetailView confirm(
            AccessContext context, UUID orderId, String note, HttpServletRequest request) {
        Order order = loadScopedOrder(context, orderId, Permission.ORDER_CONFIRM, true);
        OrderStatus previous = order.getStatus();
        OrderTransitions.requireTransition(previous, OrderStatus.CONFIRMED);

        if (order.getPhoneNormalized() == null || order.getPhoneNormalized().isBlank()) {
            throw new ValidationException(
                    ErrorCodes.INVALID_PHONE,
                    "A valid normalized phone number is required before confirming this order.");
        }

        List<OrderLine> lines =
                new ArrayList<>(
                        orderLineRepository.findByOrderIdOrderByLineIndexAsc(order.getId()));
        // Deterministic ordering avoids balance row lock-order deadlocks when concurrent orders
        // reserve overlapping multi-line baskets.
        lines.sort(Comparator.comparing(OrderLine::getVariantId));

        for (OrderLine line : lines) {
            inventoryService.reserve(
                    new InventoryCommand(
                            order.getLocationId(),
                            line.getVariantId(),
                            -line.getQuantity(),
                            MovementType.ADJUSTMENT_OUT,
                            "ORDER",
                            order.getId(),
                            "ORDER_CONFIRMED",
                            order.getOrderNumber(),
                            null,
                            context.getUserId(),
                            null));
        }

        Instant now = Instant.now(clock);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setConfirmedAt(now);
        orderRepository.save(order);

        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        statusHistoryRepository.save(
                new OrderStatusHistory(
                        UUID.randomUUID(),
                        order.getId(),
                        previous,
                        OrderStatus.CONFIRMED,
                        context.getUserId(),
                        "user:" + context.getUsername(),
                        "ORDER_CONFIRMED",
                        cleanNote,
                        now));

        auditService.record(
                new AuditEvent.Builder(AuditAction.ORDER_CONFIRMED, "order")
                        .entityId(order.getId())
                        .locationId(order.getLocationId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("orderNumber", order.getOrderNumber())
                        .changed("status", previous.name(), OrderStatus.CONFIRMED.name())
                        .added("total", order.getTotal())
                        .build(),
                request);

        return buildDetailView(context, order);
    }

    @Transactional
    public OrderDetailView decline(
            AccessContext context,
            UUID orderId,
            DeclineReason reason,
            String note,
            HttpServletRequest request) {
        if (reason == null) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "Decline reason is required.");
        }
        String cleanNote = note == null ? null : note.trim();
        if (reason.requiresNote() && (cleanNote == null || cleanNote.isEmpty())) {
            throw new ValidationException(
                    ErrorCodes.REASON_REQUIRED,
                    "Please explain the decline reason when choosing OTHER.");
        }

        Order order = loadScopedOrder(context, orderId, Permission.ORDER_DECLINE, true);
        OrderStatus previous = order.getStatus();
        OrderTransitions.requireTransition(previous, OrderStatus.DECLINED);

        Instant now = Instant.now(clock);
        order.setStatus(OrderStatus.DECLINED);
        order.setClosedAt(now);
        orderRepository.save(order);

        statusHistoryRepository.save(
                new OrderStatusHistory(
                        UUID.randomUUID(),
                        order.getId(),
                        previous,
                        OrderStatus.DECLINED,
                        context.getUserId(),
                        "user:" + context.getUsername(),
                        reason.name(),
                        cleanNote,
                        now));

        auditService.record(
                new AuditEvent.Builder(AuditAction.ORDER_DECLINED, "order")
                        .entityId(order.getId())
                        .locationId(order.getLocationId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("orderNumber", order.getOrderNumber())
                        .changed("status", previous.name(), OrderStatus.DECLINED.name())
                        .added("reason", reason.name())
                        .build(),
                request);

        return buildDetailView(context, order);
    }

    @Transactional
    public OrderCancelResponse cancel(
            AccessContext context,
            UUID orderId,
            CancelReason reason,
            String note,
            HttpServletRequest request) {
        if (reason == null) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "Cancel reason is required.");
        }
        String cleanNote = note == null ? null : note.trim();
        if (reason.requiresNote() && (cleanNote == null || cleanNote.isEmpty())) {
            throw new ValidationException(
                    ErrorCodes.REASON_REQUIRED,
                    "Please explain the cancellation reason when choosing OTHER.");
        }

        Order order = loadScopedOrder(context, orderId, Permission.ORDER_CANCEL, true);
        OrderStatus previous = order.getStatus();
        OrderTransitions.requireTransition(previous, OrderStatus.CANCELLED);

        boolean releasedReservation = previous == OrderStatus.CONFIRMED;
        if (releasedReservation) {
            List<OrderLine> lines =
                    new ArrayList<>(
                            orderLineRepository.findByOrderIdOrderByLineIndexAsc(order.getId()));
            lines.sort(Comparator.comparing(OrderLine::getVariantId));
            for (OrderLine line : lines) {
                inventoryService.release(
                        new InventoryCommand(
                                order.getLocationId(),
                                line.getVariantId(),
                                line.getQuantity(),
                                MovementType.ADJUSTMENT_IN,
                                "ORDER",
                                order.getId(),
                                "ORDER_CANCELLED",
                                order.getOrderNumber(),
                                null,
                                context.getUserId(),
                                null));
            }
        }

        Instant now = Instant.now(clock);
        order.setStatus(OrderStatus.CANCELLED);
        order.setClosedAt(now);
        orderRepository.save(order);

        statusHistoryRepository.save(
                new OrderStatusHistory(
                        UUID.randomUUID(),
                        order.getId(),
                        previous,
                        OrderStatus.CANCELLED,
                        context.getUserId(),
                        "user:" + context.getUsername(),
                        reason.name(),
                        cleanNote,
                        now));

        boolean advanceClaimed = order.getAdvanceAmount().signum() > 0;
        auditService.record(
                new AuditEvent.Builder(AuditAction.ORDER_CANCELLED, "order")
                        .entityId(order.getId())
                        .locationId(order.getLocationId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("orderNumber", order.getOrderNumber())
                        .changed("status", previous.name(), OrderStatus.CANCELLED.name())
                        .added("reason", reason.name())
                        .added("releasedReservation", releasedReservation)
                        .added("customerContacted", true)
                        .added("advanceClaimed", advanceClaimed)
                        .build(),
                request);

        return new OrderCancelResponse(
                buildDetailView(context, order),
                releasedReservation,
                true,
                advanceClaimed,
                order.getAdvanceAmount());
    }

    @Transactional
    public OrderDetailView requestCancellation(
            AccessContext context, UUID orderId, String note, HttpServletRequest request) {
        String cleanNote = note == null ? "" : note.trim();
        if (cleanNote.isEmpty()) {
            throw new ValidationException(
                    ErrorCodes.REASON_REQUIRED,
                    "A note explaining the cancellation request is required.");
        }

        Order order = loadScopedOrder(context, orderId, Permission.ORDER_CALL, true);
        if (order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.DECLINED) {
            throw new OrderRuleException(
                    ErrorCodes.INVALID_TRANSITION,
                    409,
                    "Closed orders cannot be flagged for cancellation.");
        }

        Instant now = Instant.now(clock);
        order.setCancellationRequestedAt(now);
        order.setCancellationRequestNote(cleanNote);
        orderRepository.save(order);

        auditService.record(
                new AuditEvent.Builder(AuditAction.ORDER_CANCELLATION_REQUESTED, "order")
                        .entityId(order.getId())
                        .locationId(order.getLocationId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("orderNumber", order.getOrderNumber())
                        .added("note", cleanNote)
                        .build(),
                request);

        return buildDetailView(context, order);
    }

    @Transactional(readOnly = true)
    public WarehouseAvailabilityResponse warehouseAvailability(
            AccessContext context, List<String> skus, String q) {
        Location warehouse =
                locationRepository
                        .findFirstByOnlineFulfillmentTrueAndActiveTrue()
                        .orElseThrow(
                                () ->
                                        new ConflictException(
                                                ErrorCodes.ONLINE_LOCATION_NOT_SET,
                                                "No active warehouse is configured for online"
                                                        + " fulfillment."));

        requireWarehouseOrDeny(context, warehouse.getId(), Permission.ORDER_VIEW);
        requireWarehouseOrDeny(context, warehouse.getId(), Permission.INVENTORY_VIEW);

        List<ProductVariant> variants = resolveAvailabilityVariants(skus, q);
        List<WarehouseAvailabilityRow> rows = new ArrayList<>();
        Map<UUID, Product> productCache = new HashMap<>();

        for (ProductVariant variant : variants) {
            Product product =
                    productCache.computeIfAbsent(
                            variant.getProductId(),
                            pid -> productRepository.findById(pid).orElse(null));
            String productName = product != null ? product.getName() : variant.getSku();
            StockSnapshotView snapshot = snapshotFor(warehouse.getId(), variant.getId());
            rows.add(
                    new WarehouseAvailabilityRow(
                            variant.getId(),
                            variant.getSku(),
                            productName,
                            variant.getColor(),
                            variant.getSize(),
                            snapshot.onHand(),
                            snapshot.reserved(),
                            snapshot.softHolds(),
                            snapshot.safetyStock(),
                            snapshot.available()));
        }

        return new WarehouseAvailabilityResponse(warehouse.getId(), warehouse.getCode(), rows);
    }

    private List<ProductVariant> resolveAvailabilityVariants(List<String> skus, String q) {
        if (skus != null && !skus.isEmpty()) {
            Set<String> seen = new LinkedHashSet<>();
            List<ProductVariant> result = new ArrayList<>();
            for (String rawSku : skus) {
                if (rawSku == null || rawSku.isBlank()) {
                    continue;
                }
                String trimmed = rawSku.trim();
                if (!seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                variantRepository
                        .findBySkuIgnoreCase(trimmed)
                        .filter(ProductVariant::isActive)
                        .ifPresent(result::add);
            }
            return result;
        }
        return variantRepository
                .search(
                        q == null ? "" : q.trim(),
                        true,
                        org.springframework.data.domain.PageRequest.of(
                                0, 50, Sort.by(Sort.Direction.ASC, "sku")))
                .getContent();
    }

    private Set<UUID> requireAnyOrderViewScope(AccessContext context) {
        Set<UUID> allIds = Set.copyOf(locationRepository.findAllIds());
        Set<UUID> visible = context.visibleLocationIds(Permission.ORDER_VIEW, allIds);
        if (visible.isEmpty()) {
            throw new ForbiddenException(
                    ErrorCodes.FORBIDDEN, "You do not have permission to view orders.");
        }
        return visible;
    }

    private Order loadScopedOrder(
            AccessContext context, UUID orderId, Permission requiredPermission, boolean forUpdate) {
        Order order =
                (forUpdate
                                ? orderRepository.findByIdForUpdate(orderId)
                                : orderRepository.findById(orderId))
                        .orElseThrow(() -> new NotFoundException("Order not found."));

        requireWarehouseOrDeny(context, order.getLocationId(), requiredPermission);
        return order;
    }

    /**
     * Enforces branch isolation (404) for managers outside WH scope before permission checks, while
     * denying unpermitted staff (e.g. cashiers) with 403.
     */
    static void requireWarehouseOrDeny(
            AccessContext context, UUID locationId, Permission permission) {
        if (context.isOwner()) {
            return;
        }
        if (context.getAccountType() == com.merqo.access.AccountType.MANAGER) {
            context.requireInScope(locationId);
            context.require(permission, locationId);
            return;
        }
        if (context.visibleLocationIds(permission).isEmpty()) {
            throw new ForbiddenException(
                    ErrorCodes.FORBIDDEN,
                    "You do not have the permission " + permission.name() + ".");
        }
        context.requireInScope(locationId);
        context.require(permission, locationId);
    }

    private long countInLocationsAndStatus(Set<UUID> locations, OrderStatus status) {
        return orderRepository.count(
                (root, query, cb) ->
                        cb.and(
                                root.get("locationId").in(locations),
                                cb.equal(root.get("status"), status)));
    }

    private OrderSummaryView toSummaryView(Order order) {
        int callAttempts = callLogRepository.countByOrderId(order.getId());
        PhoneRiskSnapshot risk =
                phoneRiskService.evaluate(order.getPhoneNormalized(), order.getId());
        OrderFlagsView flags =
                new OrderFlagsView(
                        order.isPriceMismatch(),
                        order.getPhoneNormalized() == null,
                        risk.riskTier() == RiskTier.HIGH,
                        order.getCancellationRequestedAt() != null);
        return new OrderSummaryView(
                order.getId(),
                order.getOrderNumber(),
                order.getChannel(),
                order.getStatus(),
                order.getLocationId(),
                order.getCustomerName(),
                order.getPhoneRaw(),
                order.getPhoneNormalized(),
                order.getDistrict(),
                order.getSourceLabel(),
                order.getExternalOrderId(),
                order.getSubtotal(),
                order.getDiscountTotal(),
                order.getShippingFee(),
                order.getTotal(),
                order.getPaymentMode(),
                order.getAdvanceAmount(),
                order.getCodAmount(),
                flags,
                risk.riskTier(),
                callAttempts,
                order.getFollowUpAt(),
                order.getCancellationRequestedAt(),
                order.getCreatedAt(),
                order.getConfirmedAt(),
                order.getClosedAt());
    }

    OrderDetailView buildDetailView(AccessContext context, Order order) {
        boolean includeStock = context.can(Permission.INVENTORY_VIEW, order.getLocationId());
        List<OrderLine> rawLines =
                orderLineRepository.findByOrderIdOrderByLineIndexAsc(order.getId());
        List<OrderLineView> lines = new ArrayList<>();
        for (OrderLine line : rawLines) {
            StockSnapshotView availability =
                    includeStock ? snapshotFor(order.getLocationId(), line.getVariantId()) : null;
            lines.add(
                    new OrderLineView(
                            line.getId(),
                            line.getLineIndex(),
                            line.getVariantId(),
                            line.getProductNameSnapshot(),
                            line.getSkuSnapshot(),
                            line.getBarcodeSnapshot(),
                            line.getColorSnapshot(),
                            line.getSizeSnapshot(),
                            line.getQuantity(),
                            line.getUnitPrice(),
                            line.getLineTotal(),
                            line.getCatalogOnlinePrice(),
                            availability));
        }

        List<OrderCallLog> rawCalls =
                callLogRepository.findByOrderIdOrderByAttemptNumberAsc(order.getId());
        Map<UUID, String> usernames = new HashMap<>();
        List<OrderCallLogView> callLogs = new ArrayList<>();
        for (OrderCallLog c : rawCalls) {
            String uname =
                    usernames.computeIfAbsent(
                            c.getCalledBy(),
                            uid ->
                                    userRepository
                                            .findById(uid)
                                            .map(AppUser::getUsername)
                                            .orElse("unknown"));
            callLogs.add(
                    new OrderCallLogView(
                            c.getId(),
                            c.getAttemptNumber(),
                            c.getResult(),
                            c.getNote(),
                            c.getFollowUpAt(),
                            c.getCalledBy(),
                            uname,
                            c.getCalledAt()));
        }

        List<OrderStatusHistoryView> history =
                statusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(order.getId()).stream()
                        .map(
                                h ->
                                        new OrderStatusHistoryView(
                                                h.getId(),
                                                h.getFromStatus(),
                                                h.getToStatus(),
                                                h.getChangedBy(),
                                                h.getActorLabel(),
                                                h.getReason(),
                                                h.getNote(),
                                                h.getCreatedAt()))
                        .toList();

        PhoneRiskSnapshot risk =
                phoneRiskService.evaluate(order.getPhoneNormalized(), order.getId());
        OrderFlagsView flags =
                new OrderFlagsView(
                        order.isPriceMismatch(),
                        order.getPhoneNormalized() == null,
                        risk.riskTier() == RiskTier.HIGH,
                        order.getCancellationRequestedAt() != null);

        return new OrderDetailView(
                order.getId(),
                order.getOrderNumber(),
                order.getChannel(),
                order.getStatus(),
                order.getLocationId(),
                order.getCustomerId(),
                order.getCustomerName(),
                order.getPhoneRaw(),
                order.getPhoneNormalized(),
                order.getAddress(),
                order.getDistrict(),
                order.getNote(),
                order.getSourceLabel(),
                order.getExternalOrderId(),
                order.getSubtotal(),
                order.getDiscountTotal(),
                order.getShippingFee(),
                order.getTotal(),
                order.getPaymentMode(),
                order.getAdvanceAmount(),
                order.getCodAmount(),
                order.getClaimedAdvanceMethod(),
                order.getClaimedAdvanceReference(),
                flags,
                risk.history(),
                risk.riskTier(),
                order.getFollowUpAt(),
                order.getCancellationRequestedAt(),
                order.getCancellationRequestNote(),
                order.getCreatedAt(),
                order.getConfirmedAt(),
                order.getClosedAt(),
                lines,
                callLogs,
                history);
    }

    private StockSnapshotView snapshotFor(UUID warehouseId, UUID variantId) {
        StockBalance balance = inventoryService.balanceOf(warehouseId, variantId);
        int onHand = balance == null ? 0 : balance.getOnHand();
        int reserved = balance == null ? 0 : balance.getReserved();
        int safety =
                policyRepository
                        .findByLocationIdAndVariantId(warehouseId, variantId)
                        .map(InventoryPolicy::getOnlineSafetyStock)
                        .orElse(0);
        int softHolds = softHoldReader.softHoldForVariant(warehouseId, variantId);
        int available = OnlineAvailabilityCalculator.available(onHand, reserved, softHolds, safety);
        return new StockSnapshotView(onHand, reserved, softHolds, safety, available);
    }
}
