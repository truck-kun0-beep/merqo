package com.merqo.orders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.catalog.Product;
import com.merqo.catalog.ProductRepository;
import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.ProductVariantRepository;
import com.merqo.catalog.VariantBarcode;
import com.merqo.catalog.VariantBarcodeRepository;
import com.merqo.common.error.BusinessException;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.money.Money;
import com.merqo.integration.IntegrationClient;
import com.merqo.integration.IntegrationInbox;
import com.merqo.integration.IntegrationInbox.Status;
import com.merqo.integration.IntegrationInboxRepository;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.orders.domain.IncomingOrder;
import com.merqo.orders.domain.IncomingOrder.IncomingOrderLine;
import com.merqo.orders.domain.IncomingOrderValidator;
import com.merqo.orders.domain.OrderChannel;
import com.merqo.orders.domain.OrderRuleException;
import com.merqo.orders.domain.OrderStatus;

/**
 * Orchestrates idempotent external website order intake from HMAC-authenticated callers into {@code
 * integration_inbox}, {@code customers}, {@code orders}, {@code order_lines}, and {@code
 * order_status_history}.
 */
@Service
public class OrderIntakeService {

    private final WebsiteOrderSourceResolver sourceResolver;
    private final IntegrationInboxRepository inboxRepository;
    private final OrderRepository orderRepository;
    private final OrderLineRepository orderLineRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final CustomerService customerService;
    private final LocationRepository locationRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final VariantBarcodeRepository barcodeRepository;
    private final OnlinePricingService onlinePricingService;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public OrderIntakeService(
            WebsiteOrderSourceResolver sourceResolver,
            IntegrationInboxRepository inboxRepository,
            OrderRepository orderRepository,
            OrderLineRepository orderLineRepository,
            OrderStatusHistoryRepository statusHistoryRepository,
            CustomerService customerService,
            LocationRepository locationRepository,
            ProductVariantRepository variantRepository,
            ProductRepository productRepository,
            VariantBarcodeRepository barcodeRepository,
            OnlinePricingService onlinePricingService,
            AuditService auditService,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.sourceResolver = sourceResolver;
        this.inboxRepository = inboxRepository;
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.customerService = customerService;
        this.locationRepository = locationRepository;
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.barcodeRepository = barcodeRepository;
        this.onlinePricingService = onlinePricingService;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * Receives a signed external website order payload, guarantees strict idempotency on {@code
     * (client_id, external_order_id)}, validates lines/prices/payment terms, and records durable
     * {@code FAILED} inbox rows on business rejection so corrected retries succeed cleanly.
     */
    public OrderIntakeOutcome receiveOrder(
            IntegrationClient client, String rawBody, HttpServletRequest httpRequest) {
        IncomingOrder incoming = sourceResolver.activeSource().parse(rawBody);
        if (incoming.externalOrderId() == null || incoming.externalOrderId().isBlank()) {
            throw new OrderRuleException(
                    ErrorCodes.VALIDATION_FAILED, 422, "externalOrderId is required.");
        }
        String externalOrderId = incoming.externalOrderId().trim();
        String payloadHash = sha256Hex(rawBody);

        TxResult result =
                transactionTemplate.execute(
                        status ->
                                processInsideTransaction(
                                        client,
                                        incoming,
                                        externalOrderId,
                                        payloadHash,
                                        httpRequest));
        if (result == null) {
            throw new IllegalStateException("Transaction returned null intake result.");
        }
        if (result.failure() != null) {
            throw result.failure();
        }
        return result.outcome();
    }

    @Transactional(readOnly = true)
    public ExternalOrderStatusView getStatusForClient(
            IntegrationClient client, String externalOrderId) {
        if (externalOrderId == null || externalOrderId.isBlank()) {
            throw new NotFoundException("Order not found.");
        }
        Order order =
                orderRepository
                        .findByIntegrationClientIdAndExternalOrderId(
                                client.getId(), externalOrderId.trim())
                        .orElseThrow(() -> new NotFoundException("Order not found."));
        return ExternalOrderStatusView.from(order);
    }

    private TxResult processInsideTransaction(
            IntegrationClient client,
            IncomingOrder incoming,
            String externalOrderId,
            String payloadHash,
            HttpServletRequest httpRequest) {
        Instant now = Instant.now(clock);
        Timestamp ts = Timestamp.from(now);
        jdbcTemplate.update(
                """
                INSERT INTO integration_inbox (
                    id, client_id, external_order_id, payload_hash, status, attempts, received_at
                ) VALUES (?, ?, ?, ?, 'RECEIVED', 1, ?)
                ON CONFLICT (client_id, external_order_id) DO NOTHING
                """,
                UUID.randomUUID(),
                client.getId(),
                externalOrderId,
                payloadHash,
                ts);

        IntegrationInbox inbox =
                inboxRepository
                        .findForUpdate(client.getId(), externalOrderId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Inbox row missing for " + externalOrderId));

        if (inbox.getStatus() == Status.PROCESSED) {
            if (!payloadHash.equals(inbox.getPayloadHash())) {
                return TxResult.fail(
                        new ConflictException(
                                ErrorCodes.IDEMPOTENCY_CONFLICT,
                                "externalOrderId was already processed with a different"
                                        + " payload."));
            }
            Order existing =
                    orderRepository
                            .findById(inbox.getOrderId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Processed inbox references missing order "
                                                            + inbox.getOrderId()));
            return TxResult.ok(new OrderIntakeOutcome(OrderIntakeView.from(existing), true));
        }

        if (inbox.getStatus() == Status.FAILED) {
            inbox.setAttempts(inbox.getAttempts() + 1);
            inbox.setPayloadHash(payloadHash);
            inbox.setStatus(Status.RECEIVED);
            inbox.setErrorCode(null);
            inbox.setErrorMessage(null);
        }

        try {
            OrderIntakeView view =
                    createOrderInternal(client, incoming, externalOrderId, now, httpRequest);
            inbox.setStatus(Status.PROCESSED);
            inbox.setOrderId(view.orderId());
            inbox.setProcessedAt(now);
            inbox.setErrorCode(null);
            inbox.setErrorMessage(null);
            inboxRepository.saveAndFlush(inbox);
            return TxResult.ok(new OrderIntakeOutcome(view, false));
        } catch (OrderRuleException | BusinessException ex) {
            String code =
                    ex instanceof OrderRuleException ore
                            ? ore.getErrorCode()
                            : ((BusinessException) ex).getErrorCode();
            inbox.setStatus(Status.FAILED);
            inbox.setErrorCode(code);
            inbox.setErrorMessage(ex.getMessage());
            inbox.setProcessedAt(now);
            inboxRepository.saveAndFlush(inbox);
            return TxResult.fail(ex);
        }
    }

    private OrderIntakeView createOrderInternal(
            IntegrationClient client,
            IncomingOrder incoming,
            String externalOrderId,
            Instant now,
            HttpServletRequest httpRequest) {
        Location warehouse =
                locationRepository
                        .findFirstByOnlineFulfillmentTrueAndActiveTrue()
                        .orElseThrow(
                                () ->
                                        new ConflictException(
                                                ErrorCodes.ONLINE_LOCATION_NOT_SET,
                                                "No active warehouse is configured for online"
                                                        + " fulfillment."));

        IncomingOrderValidator.validate(incoming);

        List<ResolvedLine> resolvedLines = new ArrayList<>();
        Money subtotal = Money.ZERO;
        boolean priceMismatch = false;

        for (int i = 0; i < incoming.lines().size(); i++) {
            IncomingOrderLine line = incoming.lines().get(i);
            String requestedSku = line.sku().trim();
            ProductVariant variant =
                    variantRepository
                            .findBySkuIgnoreCase(requestedSku)
                            .orElseThrow(
                                    () ->
                                            new OrderRuleException(
                                                    ErrorCodes.UNKNOWN_SKU,
                                                    422,
                                                    "Unknown SKU: " + requestedSku));
            if (!variant.isActive()) {
                throw new OrderRuleException(
                        ErrorCodes.UNKNOWN_SKU, 422, "Inactive SKU: " + variant.getSku());
            }
            Product product =
                    productRepository
                            .findById(variant.getProductId())
                            .orElseThrow(
                                    () ->
                                            new OrderRuleException(
                                                    ErrorCodes.UNKNOWN_SKU,
                                                    422,
                                                    "Missing product for SKU: "
                                                            + variant.getSku()));
            if (!product.isActive()) {
                throw new OrderRuleException(
                        ErrorCodes.UNKNOWN_SKU,
                        422,
                        "Inactive product for SKU: " + variant.getSku());
            }

            String primaryBarcode =
                    barcodeRepository
                            .findFirstByVariantIdAndKind(
                                    variant.getId(), VariantBarcode.Kind.PRIMARY)
                            .map(VariantBarcode::getBarcode)
                            .orElse(null);

            Money catalogOnlinePrice =
                    onlinePricingService.effectiveUnitPrice(variant, warehouse.getId());
            if (!line.unitPrice().equals(catalogOnlinePrice)) {
                priceMismatch = true;
            }
            Money lineTotal = line.unitPrice().multipliedBy(BigDecimal.valueOf(line.quantity()));
            subtotal = subtotal.plus(lineTotal);

            String productNameSnapshot =
                    line.productName() != null ? line.productName() : product.getName();
            resolvedLines.add(
                    new ResolvedLine(
                            i + 1,
                            variant,
                            productNameSnapshot,
                            primaryBarcode,
                            line.quantity(),
                            line.unitPrice(),
                            lineTotal,
                            catalogOnlinePrice));
        }

        UUID customerId = null;
        if (incoming.phone().isValid()) {
            Customer customer =
                    customerService.findOrCreateByPhone(
                            incoming.phone().normalized(), incoming.customerName().trim());
            customerId = customer.getId();
        }

        UUID orderId = UUID.randomUUID();
        String orderNumber = OrderNumber.format(orderRepository.nextOrderSequence());
        Order order =
                new Order(
                        orderId,
                        orderNumber,
                        OrderChannel.ONLINE,
                        OrderStatus.PENDING_CONFIRMATION,
                        warehouse.getId(),
                        customerId,
                        incoming.customerName().trim(),
                        incoming.phone().raw(),
                        incoming.phone().normalized(),
                        incoming.address().trim(),
                        incoming.district(),
                        incoming.note(),
                        externalOrderId,
                        client.getId(),
                        client.getName(),
                        subtotal.amount(),
                        incoming.discountTotal().amount(),
                        incoming.shippingFee().amount(),
                        incoming.total().amount(),
                        incoming.paymentTerms().mode(),
                        incoming.paymentTerms().advanceAmount().amount(),
                        incoming.paymentTerms().codAmount().amount(),
                        incoming.paymentTerms().claimedAdvanceMethod(),
                        incoming.paymentTerms().claimedAdvanceReference(),
                        priceMismatch,
                        null,
                        null,
                        now);
        orderRepository.save(order);

        for (ResolvedLine rl : resolvedLines) {
            orderLineRepository.save(
                    new OrderLine(
                            UUID.randomUUID(),
                            orderId,
                            rl.lineIndex(),
                            rl.variant().getId(),
                            rl.productName(),
                            rl.variant().getSku(),
                            rl.barcode(),
                            rl.variant().getColor(),
                            rl.variant().getSize(),
                            rl.quantity(),
                            rl.unitPrice().amount(),
                            rl.lineTotal().amount(),
                            rl.catalogOnlinePrice().amount()));
        }

        statusHistoryRepository.save(
                new OrderStatusHistory(
                        UUID.randomUUID(),
                        orderId,
                        null,
                        OrderStatus.PENDING_CONFIRMATION,
                        null,
                        "integration:" + client.getName(),
                        "ORDER_RECEIVED",
                        "External order " + externalOrderId,
                        now));

        auditService.record(
                new AuditEvent.Builder(AuditAction.ORDER_RECEIVED, "order")
                        .entityId(orderId)
                        .locationId(warehouse.getId())
                        .actor(null, "integration:" + client.getKeyId())
                        .added("orderNumber", orderNumber)
                        .added("externalOrderId", externalOrderId)
                        .added("channel", OrderChannel.ONLINE.name())
                        .added("status", OrderStatus.PENDING_CONFIRMATION.name())
                        .added("priceMismatch", priceMismatch)
                        .added("total", incoming.total().amount())
                        .build(),
                httpRequest);

        return OrderIntakeView.from(order);
    }

    private static String sha256Hex(String payload) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record ResolvedLine(
            int lineIndex,
            ProductVariant variant,
            String productName,
            String barcode,
            int quantity,
            Money unitPrice,
            Money lineTotal,
            Money catalogOnlinePrice) {}

    private record TxResult(OrderIntakeOutcome outcome, RuntimeException failure) {
        static TxResult ok(OrderIntakeOutcome outcome) {
            return new TxResult(outcome, null);
        }

        static TxResult fail(RuntimeException failure) {
            return new TxResult(null, failure);
        }
    }

    public record OrderIntakeOutcome(OrderIntakeView order, boolean idempotentReplay) {}

    public record OrderIntakeView(
            UUID orderId,
            String orderNumber,
            String externalOrderId,
            OrderStatus status,
            boolean priceMismatch,
            boolean invalidPhone,
            BigDecimal total,
            Instant createdAt) {

        static OrderIntakeView from(Order order) {
            return new OrderIntakeView(
                    order.getId(),
                    order.getOrderNumber(),
                    order.getExternalOrderId(),
                    order.getStatus(),
                    order.isPriceMismatch(),
                    order.getPhoneNormalized() == null,
                    order.getTotal(),
                    order.getCreatedAt());
        }
    }

    /** Public status summary exposed back to the originating external website client only. */
    public record ExternalOrderStatusView(
            UUID orderId,
            String orderNumber,
            String externalOrderId,
            OrderStatus status,
            BigDecimal total,
            Instant createdAt,
            Instant confirmedAt,
            Instant closedAt) {

        static ExternalOrderStatusView from(Order order) {
            return new ExternalOrderStatusView(
                    order.getId(),
                    order.getOrderNumber(),
                    order.getExternalOrderId(),
                    order.getStatus(),
                    order.getTotal(),
                    order.getCreatedAt(),
                    order.getConfirmedAt(),
                    order.getClosedAt());
        }
    }
}
