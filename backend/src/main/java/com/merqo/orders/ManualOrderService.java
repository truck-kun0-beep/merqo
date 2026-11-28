package com.merqo.orders;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

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
import com.merqo.catalog.VariantBarcode;
import com.merqo.catalog.VariantBarcodeRepository;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.ValidationException;
import com.merqo.common.money.Money;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.orders.OrderDtos.OrderDetailView;
import com.merqo.orders.domain.IncomingOrderValidator;
import com.merqo.orders.domain.OrderChannel;
import com.merqo.orders.domain.OrderStatus;
import com.merqo.orders.domain.PaymentTerms;
import com.merqo.orders.domain.PaymentTerms.ClaimedAdvanceMethod;
import com.merqo.orders.domain.PaymentTerms.PaymentMode;
import com.merqo.orders.domain.PhoneNumber;

/**
 * Creates manual phone/WhatsApp orders with server-authoritative catalog + {@code ONLINE}
 * promotional pricing and optional immediate stock reservation.
 */
@Service
public class ManualOrderService {

    private final OrderRepository orderRepository;
    private final OrderLineRepository orderLineRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final CustomerService customerService;
    private final LocationRepository locationRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final VariantBarcodeRepository barcodeRepository;
    private final OnlinePricingService onlinePricingService;
    private final OrderStaffService orderStaffService;
    private final AuditService auditService;
    private final Clock clock;

    public ManualOrderService(
            OrderRepository orderRepository,
            OrderLineRepository orderLineRepository,
            OrderStatusHistoryRepository statusHistoryRepository,
            CustomerService customerService,
            LocationRepository locationRepository,
            ProductVariantRepository variantRepository,
            ProductRepository productRepository,
            VariantBarcodeRepository barcodeRepository,
            OnlinePricingService onlinePricingService,
            OrderStaffService orderStaffService,
            AuditService auditService,
            Clock clock) {
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.customerService = customerService;
        this.locationRepository = locationRepository;
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.barcodeRepository = barcodeRepository;
        this.onlinePricingService = onlinePricingService;
        this.orderStaffService = orderStaffService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public OrderDetailView create(
            AccessContext context, CreateManualOrderRequest body, HttpServletRequest httpRequest) {
        Location warehouse =
                locationRepository
                        .findFirstByOnlineFulfillmentTrueAndActiveTrue()
                        .orElseThrow(
                                () ->
                                        new ConflictException(
                                                ErrorCodes.ONLINE_LOCATION_NOT_SET,
                                                "No active warehouse is configured for online"
                                                        + " fulfillment."));

        OrderStaffService.requireWarehouseOrDeny(
                context, warehouse.getId(), Permission.ORDER_CREATE_MANUAL);
        if (body.confirmNow()) {
            context.require(Permission.ORDER_CONFIRM, warehouse.getId());
        }

        if (body.lines().size() > IncomingOrderValidator.MAX_LINES) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "Order cannot exceed " + IncomingOrderValidator.MAX_LINES + " lines.");
        }

        PhoneNumber phone = PhoneNumber.parse(body.phone());
        if (!phone.isValid()) {
            throw new ValidationException(
                    ErrorCodes.INVALID_PHONE,
                    "Phone must be a valid Bangladeshi mobile number (e.g. 01712345678).");
        }

        Money shippingFee = nonNegativeMoney(body.shippingFee(), "shippingFee");
        Money discountTotal = nonNegativeMoney(body.discountTotal(), "discountTotal");

        List<PricedManualLine> resolvedLines = new ArrayList<>();
        Money subtotal = Money.ZERO;
        for (int i = 0; i < body.lines().size(); i++) {
            ManualOrderLineRequest lineReq = body.lines().get(i);
            ProductVariant variant = resolveActiveVariant(lineReq);
            Product product =
                    productRepository
                            .findById(variant.getProductId())
                            .filter(Product::isActive)
                            .orElseThrow(
                                    () ->
                                            new ValidationException(
                                                    ErrorCodes.UNKNOWN_SKU,
                                                    "Product inactive for SKU: "
                                                            + variant.getSku()));
            String barcode =
                    barcodeRepository
                            .findFirstByVariantIdAndKind(
                                    variant.getId(), VariantBarcode.Kind.PRIMARY)
                            .map(VariantBarcode::getBarcode)
                            .orElse(null);

            Money unitPrice = onlinePricingService.effectiveUnitPrice(variant, warehouse.getId());
            Money lineTotal = unitPrice.multipliedBy(BigDecimal.valueOf(lineReq.quantity()));
            subtotal = subtotal.plus(lineTotal);
            resolvedLines.add(
                    new PricedManualLine(
                            i + 1,
                            variant,
                            product.getName(),
                            barcode,
                            lineReq.quantity(),
                            unitPrice,
                            lineTotal));
        }

        if (discountTotal.compareTo(subtotal) > 0) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "discountTotal cannot exceed order subtotal.");
        }
        Money total = subtotal.minus(discountTotal).plus(shippingFee);

        PaymentTerms paymentTerms = resolvePaymentTerms(body, total);
        paymentTerms.validateAgainst(total);

        String trimmedName = body.customerName().trim();
        Customer customer = customerService.findOrCreateByPhone(phone.normalized(), trimmedName);

        Instant now = Instant.now(clock);
        UUID orderId = UUID.randomUUID();
        String orderNumber = OrderNumber.format(orderRepository.nextOrderSequence());
        Order order =
                new Order(
                        orderId,
                        orderNumber,
                        OrderChannel.MANUAL,
                        OrderStatus.PENDING_CONFIRMATION,
                        warehouse.getId(),
                        customer.getId(),
                        trimmedName,
                        phone.raw(),
                        phone.normalized(),
                        body.address().trim(),
                        trimToNull(body.district()),
                        trimToNull(body.note()),
                        null,
                        null,
                        "Manual (" + context.getUsername() + ")",
                        subtotal.amount(),
                        discountTotal.amount(),
                        shippingFee.amount(),
                        total.amount(),
                        paymentTerms.mode(),
                        paymentTerms.advanceAmount().amount(),
                        paymentTerms.codAmount().amount(),
                        paymentTerms.claimedAdvanceMethod(),
                        trimToNull(paymentTerms.claimedAdvanceReference()),
                        false,
                        context.getUserId(),
                        null,
                        now);
        orderRepository.save(order);

        for (PricedManualLine rl : resolvedLines) {
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
                            rl.unitPrice().amount()));
        }

        statusHistoryRepository.save(
                new OrderStatusHistory(
                        UUID.randomUUID(),
                        orderId,
                        null,
                        OrderStatus.PENDING_CONFIRMATION,
                        context.getUserId(),
                        "user:" + context.getUsername(),
                        "MANUAL_ORDER_CREATED",
                        trimToNull(body.note()),
                        now));

        auditService.record(
                new AuditEvent.Builder(AuditAction.MANUAL_ORDER_CREATED, "order")
                        .entityId(orderId)
                        .locationId(warehouse.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("orderNumber", orderNumber)
                        .added("channel", OrderChannel.MANUAL.name())
                        .added("total", total.amount())
                        .added("confirmNow", body.confirmNow())
                        .build(),
                httpRequest);

        if (body.confirmNow()) {
            return orderStaffService.confirm(context, orderId, body.note(), httpRequest);
        }
        return orderStaffService.buildDetailView(context, order);
    }

    private ProductVariant resolveActiveVariant(ManualOrderLineRequest lineReq) {
        ProductVariant variant = null;
        if (lineReq.variantId() != null) {
            variant = variantRepository.findById(lineReq.variantId()).orElse(null);
        } else if (lineReq.sku() != null && !lineReq.sku().isBlank()) {
            variant = variantRepository.findBySkuIgnoreCase(lineReq.sku().trim()).orElse(null);
        }
        if (variant == null || !variant.isActive()) {
            throw new ValidationException(
                    ErrorCodes.UNKNOWN_SKU, "Unknown or inactive product variant.");
        }
        return variant;
    }

    private static Money nonNegativeMoney(BigDecimal raw, String field) {
        if (raw == null) {
            return Money.ZERO;
        }
        if (raw.signum() < 0) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, field + " must not be negative.");
        }
        return Money.of(raw);
    }

    private static PaymentTerms resolvePaymentTerms(CreateManualOrderRequest body, Money total) {
        PaymentMode mode = body.paymentMode();
        Money advance;
        Money cod;
        switch (mode) {
            case COD -> {
                advance = Money.ZERO;
                cod = total;
            }
            case FULL_PREPAID -> {
                advance = total;
                cod = Money.ZERO;
            }
            case ADVANCE_COD -> {
                advance = nonNegativeMoney(body.advanceAmount(), "advanceAmount");
                if (advance.isZero() || advance.compareTo(total) >= 0) {
                    throw new ValidationException(
                            ErrorCodes.VALIDATION_FAILED,
                            "ADVANCE_COD requires 0 < advanceAmount < total.");
                }
                cod = total.minus(advance);
            }
            default -> throw new IllegalStateException("Unexpected mode: " + mode);
        }
        return new PaymentTerms(
                mode,
                advance,
                cod,
                body.claimedAdvanceMethod(),
                trimToNull(body.claimedAdvanceReference()));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record PricedManualLine(
            int lineIndex,
            ProductVariant variant,
            String productName,
            String barcode,
            int quantity,
            Money unitPrice,
            Money lineTotal) {}

    public record ManualOrderLineRequest(UUID variantId, String sku, @Positive int quantity) {}

    public record CreateManualOrderRequest(
            @NotBlank String customerName,
            @NotBlank String phone,
            @NotBlank String address,
            String district,
            String note,
            @NotEmpty List<@Valid ManualOrderLineRequest> lines,
            BigDecimal shippingFee,
            BigDecimal discountTotal,
            @NotNull PaymentMode paymentMode,
            BigDecimal advanceAmount,
            ClaimedAdvanceMethod claimedAdvanceMethod,
            String claimedAdvanceReference,
            boolean confirmNow) {}
}
