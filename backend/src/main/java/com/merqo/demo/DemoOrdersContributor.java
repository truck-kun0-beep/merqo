package com.merqo.demo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.merqo.access.AccessContext;
import com.merqo.access.PermissionService;
import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.ProductVariantRepository;
import com.merqo.catalog.domain.DiscountType;
import com.merqo.catalog.domain.Scope;
import com.merqo.common.error.PromotionConflictException;
import com.merqo.integration.IntegrationClient;
import com.merqo.integration.IntegrationClientRepository;
import com.merqo.integration.SecretCipher;
import com.merqo.inventory.InventoryCommand;
import com.merqo.inventory.InventoryPolicy;
import com.merqo.inventory.InventoryPolicyRepository;
import com.merqo.inventory.InventoryService;
import com.merqo.inventory.StockBalance;
import com.merqo.inventory.domain.MovementType;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.orders.ManualOrderService;
import com.merqo.orders.ManualOrderService.CreateManualOrderRequest;
import com.merqo.orders.ManualOrderService.ManualOrderLineRequest;
import com.merqo.orders.OnlinePricingService;
import com.merqo.orders.Order;
import com.merqo.orders.OrderIntakeService;
import com.merqo.orders.OrderRepository;
import com.merqo.orders.OrderStaffService;
import com.merqo.orders.OrderStatusHistory;
import com.merqo.orders.OrderStatusHistoryRepository;
import com.merqo.orders.domain.CallResult;
import com.merqo.orders.domain.CancelReason;
import com.merqo.orders.domain.DeclineReason;
import com.merqo.orders.domain.OrderStatus;
import com.merqo.orders.domain.PaymentTerms.ClaimedAdvanceMethod;
import com.merqo.orders.domain.PaymentTerms.PaymentMode;
import com.merqo.promotion.PromotionRepository;
import com.merqo.promotion.PromotionService;
import com.merqo.promotion.PromotionService.ConflictAction;
import com.merqo.promotion.PromotionService.PromotionRequest;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * Seeds Phase 4 online integration & order-handling demo data when {@code DEMO_MODE=true}.
 *
 * <p>Idempotently creates:
 *
 * <ul>
 *   <li>Fixed demo webshop API client ({@code mk_demo_webshop}) encrypted via {@link SecretCipher}.
 *   <li>Opening warehouse ({@code WH}) stock and online safety stock policies for demo SKUs.
 *   <li>An active {@code ONLINE} promotion on Blue Tee ({@code TE-100-BLU}).
 *   <li>20 realistic online &amp; manual orders across {@code PENDING_CONFIRMATION}, {@code
 *       FOLLOW_UP}, {@code CONFIRMED}, {@code DECLINED}, {@code CANCELLED}, plus historical {@code
 *       READY_TO_SHIP}, {@code SHIPPED}, {@code DELIVERED}, {@code REFUSED}, {@code RETURNED} rows
 *       demonstrating {@code NEW}, {@code OK}, {@code WATCH}, and {@code HIGH} risk tiers and all
 *       order attention flags.
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "merqo.demo-mode", havingValue = "true")
public class DemoOrdersContributor implements DemoDataContributor {

    private final IntegrationClientRepository clientRepository;
    private final SecretCipher secretCipher;
    private final LocationRepository locationRepository;
    private final ProductVariantRepository variantRepository;
    private final InventoryService inventoryService;
    private final InventoryPolicyRepository policyRepository;
    private final PromotionRepository promotionRepository;
    private final PromotionService promotionService;
    private final OnlinePricingService onlinePricingService;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final OrderIntakeService orderIntakeService;
    private final OrderStaffService orderStaffService;
    private final ManualOrderService manualOrderService;
    private final AppUserRepository userRepository;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public DemoOrdersContributor(
            IntegrationClientRepository clientRepository,
            SecretCipher secretCipher,
            LocationRepository locationRepository,
            ProductVariantRepository variantRepository,
            InventoryService inventoryService,
            InventoryPolicyRepository policyRepository,
            PromotionRepository promotionRepository,
            PromotionService promotionService,
            OnlinePricingService onlinePricingService,
            OrderRepository orderRepository,
            OrderStatusHistoryRepository statusHistoryRepository,
            OrderIntakeService orderIntakeService,
            OrderStaffService orderStaffService,
            ManualOrderService manualOrderService,
            AppUserRepository userRepository,
            PermissionService permissionService,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            Clock clock) {
        this.clientRepository = clientRepository;
        this.secretCipher = secretCipher;
        this.locationRepository = locationRepository;
        this.variantRepository = variantRepository;
        this.inventoryService = inventoryService;
        this.policyRepository = policyRepository;
        this.promotionRepository = promotionRepository;
        this.promotionService = promotionService;
        this.onlinePricingService = onlinePricingService;
        this.orderRepository = orderRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.orderIntakeService = orderIntakeService;
        this.orderStaffService = orderStaffService;
        this.manualOrderService = manualOrderService;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public void seed() {
        Instant now = Instant.now(clock);
        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        AppUser ordersStaff = userRepository.findByDemoKey("orders").orElseThrow();
        Location wh = locationRepository.findByCodeIgnoreCase("WH").orElse(null);
        if (wh == null) {
            return;
        }

        IntegrationClient webshop = ensureDemoClient(owner.getId(), now);

        if (orderRepository.findByDemoKey("demo-ord-20").isPresent()) {
            return;
        }

        AccessContext ownerCtx = permissionService.buildContext(owner);
        AccessContext ordersCtx = permissionService.buildContext(ordersStaff);

        List<ProductVariant> demoVariants = variantRepository.findByDemoKeyNotNull();
        if (demoVariants.isEmpty()) {
            return;
        }

        // 1. Ensure WH stock & safety buffers exist for online fulfillment
        for (ProductVariant v : demoVariants) {
            StockBalance bal = inventoryService.balanceOf(wh.getId(), v.getId());
            int avail = bal == null ? 0 : bal.getAvailable();
            if (avail < 30) {
                inventoryService.receive(
                        new InventoryCommand(
                                wh.getId(),
                                v.getId(),
                                40 - avail,
                                MovementType.OPENING_BALANCE,
                                null,
                                null,
                                "Phase 4 demo warehouse stock",
                                null,
                                null,
                                owner.getId(),
                                null));
            }
            if (policyRepository.findByLocationIdAndVariantId(wh.getId(), v.getId()).isEmpty()) {
                policyRepository.save(
                        new InventoryPolicy(
                                UUID.randomUUID(), wh.getId(), v.getId(), 5, 40, 2, now));
            }
        }

        ProductVariant teeBlack =
                variantRepository.findByDemoKey("var-tee-black").orElse(demoVariants.getFirst());
        ProductVariant teeWhite =
                variantRepository.findByDemoKey("var-tee-white").orElse(demoVariants.getFirst());
        ProductVariant teeBlue =
                variantRepository.findByDemoKey("var-tee-blue").orElse(demoVariants.getFirst());
        ProductVariant hoodieM =
                variantRepository.findByDemoKey("var-hoodie-m").orElse(demoVariants.getFirst());
        ProductVariant jeans32 =
                variantRepository.findByDemoKey("var-jeans-32").orElse(demoVariants.getFirst());
        ProductVariant sneaker42 =
                variantRepository.findByDemoKey("var-sneaker-42").orElse(demoVariants.getFirst());

        // 2. Seed an ONLINE promotion idempotently
        boolean hasOnlinePromo =
                promotionRepository.findAll().stream()
                        .anyMatch(p -> "Online 10% Blue Tee (Demo)".equals(p.getName()));
        if (!hasOnlinePromo) {
            try {
                promotionService.create(
                        ownerCtx,
                        new PromotionRequest(
                                "Online 10% Blue Tee (Demo)",
                                DiscountType.PERCENT,
                                new BigDecimal("10"),
                                Scope.ONLINE,
                                true,
                                Set.of(),
                                Set.of(teeBlue.getId()),
                                now.minus(2, ChronoUnit.DAYS),
                                now.plus(30, ChronoUnit.DAYS),
                                "Demo online campaign",
                                ConflictAction.REFUSE));
            } catch (PromotionConflictException ignored) {
                // Skip safely if user already created an overlapping online promo on this SKU
            }
        }

        // ---- Group A: OK customer (01711100001) - 2 historical DELIVERED + 1 pending ----------
        seedHistoricalOrder(
                webshop,
                wh,
                teeBlack,
                1,
                "demo-ord-01",
                "Rafiqul Islam",
                "01711100001",
                "House 12, Road 4, Dhanmondi",
                "Dhaka",
                "COD",
                null,
                null,
                OrderStatus.DELIVERED,
                now.minus(10, ChronoUnit.DAYS),
                owner);
        seedHistoricalOrder(
                webshop,
                wh,
                jeans32,
                1,
                "demo-ord-02",
                "Rafiqul Islam",
                "01711100001",
                "House 12, Road 4, Dhanmondi",
                "Dhaka",
                "FULL_PREPAID",
                "BKASH",
                "BK-HIST-02",
                OrderStatus.DELIVERED,
                now.minus(5, ChronoUnit.DAYS),
                owner);
        seedOnlineOrder(
                webshop,
                wh,
                teeBlue,
                2,
                null,
                "demo-ord-03",
                "Rafiqul Islam",
                "01711100001",
                "House 12, Road 4, Dhanmondi",
                "Dhaka",
                "ADVANCE_COD",
                "200.00",
                "BKASH",
                "BK-DM-03",
                now.minus(2, ChronoUnit.HOURS));

        // ---- Group B: WATCH customer (01811100002) - 1 DECLINED + 1 CANCELLED + 1 FOLLOW_UP ---
        UUID ord04 =
                seedOnlineOrder(
                        webshop,
                        wh,
                        hoodieM,
                        1,
                        null,
                        "demo-ord-04",
                        "Farhana Sultana",
                        "01811100002",
                        "Sector 11, Uttara",
                        "Dhaka",
                        "COD",
                        "0.00",
                        null,
                        null,
                        now.minus(6, ChronoUnit.DAYS));
        if (ord04 != null) {
            orderStaffService.decline(
                    ordersCtx,
                    ord04,
                    DeclineReason.CUSTOMER_UNREACHABLE,
                    "3 attempts unanswered",
                    null);
        }
        UUID ord05 =
                seedOnlineOrder(
                        webshop,
                        wh,
                        sneaker42,
                        1,
                        null,
                        "demo-ord-05",
                        "Farhana Sultana",
                        "01811100002",
                        "Sector 11, Uttara",
                        "Dhaka",
                        "COD",
                        "0.00",
                        null,
                        null,
                        now.minus(3, ChronoUnit.DAYS));
        if (ord05 != null) {
            orderStaffService.cancel(
                    ownerCtx,
                    ord05,
                    CancelReason.CUSTOMER_REQUESTED,
                    "Changed mind before dispatch",
                    null);
        }
        UUID ord06 =
                seedOnlineOrder(
                        webshop,
                        wh,
                        teeWhite,
                        2,
                        null,
                        "demo-ord-06",
                        "Farhana Sultana",
                        "01811100002",
                        "Sector 11, Uttara",
                        "Dhaka",
                        "COD",
                        "0.00",
                        null,
                        null,
                        now.minus(4, ChronoUnit.HOURS));
        if (ord06 != null) {
            orderStaffService.logCall(
                    ordersCtx,
                    ord06,
                    CallResult.CALL_BACK_REQUESTED,
                    "Customer asked to call back after 6 PM",
                    now.plus(3, ChronoUnit.HOURS));
        }

        // ---- Group C: HIGH risk customer (01911100003) - 2 REFUSED + 1 PENDING_CONFIRMATION ---
        seedHistoricalOrder(
                webshop,
                wh,
                sneaker42,
                1,
                "demo-ord-07",
                "Tariq Mahmud",
                "01911100003",
                "Mirpur 10",
                "Dhaka",
                "COD",
                null,
                null,
                OrderStatus.REFUSED,
                now.minus(9, ChronoUnit.DAYS),
                owner);
        seedHistoricalOrder(
                webshop,
                wh,
                hoodieM,
                1,
                "demo-ord-08",
                "Tariq Mahmud",
                "01911100003",
                "Mirpur 10",
                "Dhaka",
                "COD",
                null,
                null,
                OrderStatus.REFUSED,
                now.minus(4, ChronoUnit.DAYS),
                owner);
        seedOnlineOrder(
                webshop,
                wh,
                jeans32,
                1,
                null,
                "demo-ord-09",
                "Tariq Mahmud",
                "01911100003",
                "Mirpur 10",
                "Dhaka",
                "COD",
                "0.00",
                null,
                null,
                now.minus(1, ChronoUnit.HOURS));

        // ---- Group D: Manual phone/WhatsApp orders -------------------------------------------
        seedManualOrder(
                ordersCtx,
                "demo-ord-10",
                "Nusrat Jahan",
                "01611100004",
                "Banani Road 11",
                "Dhaka",
                teeBlack.getId(),
                2,
                PaymentMode.FULL_PREPAID,
                null,
                ClaimedAdvanceMethod.BKASH,
                "WA-BK-10",
                true,
                "Confirmed on WhatsApp");
        seedManualOrder(
                ordersCtx,
                "demo-ord-11",
                "Sajjad Hossain",
                "01711100011",
                "Agrabad C/A",
                "Chattogram",
                hoodieM.getId(),
                1,
                PaymentMode.ADVANCE_COD,
                new BigDecimal("500.00"),
                ClaimedAdvanceMethod.NAGAD,
                "WA-NG-11",
                false,
                "Awaiting final address confirmation");

        // ---- Group E: Flags & additional workflow states --------------------------------------
        // 12. priceMismatch = true (website charged 650 instead of 750)
        seedOnlineOrder(
                webshop,
                wh,
                teeBlack,
                1,
                new BigDecimal("650.00"),
                "demo-ord-12",
                "Maliha Chowdhury",
                "01722200012",
                "Gulshan 2",
                "Dhaka",
                "COD",
                "0.00",
                null,
                null,
                now.minus(90, ChronoUnit.MINUTES));

        // 13. invalidPhone = true
        seedOnlineOrder(
                webshop,
                wh,
                teeWhite,
                1,
                null,
                "demo-ord-13",
                "Kamrul Hasan",
                "0171-BAD-99",
                "Bashundhara R/A",
                "Dhaka",
                "COD",
                "0.00",
                null,
                null,
                now.minus(45, ChronoUnit.MINUTES));

        // 14. CONFIRMED with cancellationRequested = true flag
        UUID ord14 =
                seedOnlineOrder(
                        webshop,
                        wh,
                        jeans32,
                        1,
                        null,
                        "demo-ord-14",
                        "Tahmid Anwar",
                        "01722200014",
                        "Lalmatia Block C",
                        "Dhaka",
                        "ADVANCE_COD",
                        "300.00",
                        "NAGAD",
                        "NG-14",
                        now.minus(8, ChronoUnit.HOURS));
        if (ord14 != null) {
            orderStaffService.confirm(ordersCtx, ord14, "Confirmed at noon", null);
            orderStaffService.requestCancellation(
                    ordersCtx, ord14, "Customer messaged asking to switch size/cancel", null);
        }

        // 15-17. Historical RETURNED, SHIPPED, READY_TO_SHIP
        seedHistoricalOrder(
                webshop,
                wh,
                teeWhite,
                1,
                "demo-ord-15",
                "Sadia Afrin",
                "01722200015",
                "Sylhet Zindabazar",
                "Sylhet",
                "COD",
                null,
                null,
                OrderStatus.RETURNED,
                now.minus(7, ChronoUnit.DAYS),
                owner);
        seedHistoricalOrder(
                webshop,
                wh,
                sneaker42,
                1,
                "demo-ord-16",
                "Arif Rahman",
                "01722200016",
                "Khulna Sonadanga",
                "Khulna",
                "COD",
                null,
                null,
                OrderStatus.SHIPPED,
                now.minus(2, ChronoUnit.DAYS),
                owner);
        seedHistoricalOrder(
                webshop,
                wh,
                teeBlue,
                1,
                "demo-ord-17",
                "Mehedi Hasan",
                "01722200017",
                "Rajshahi Shaheb Bazar",
                "Rajshahi",
                "COD",
                null,
                null,
                OrderStatus.READY_TO_SHIP,
                now.minus(1, ChronoUnit.DAYS),
                owner);

        // 18. Clean CONFIRMED order
        UUID ord18 =
                seedOnlineOrder(
                        webshop,
                        wh,
                        hoodieM,
                        2,
                        null,
                        "demo-ord-18",
                        "Tanvir Ahmed",
                        "01722200018",
                        "Baily Road",
                        "Dhaka",
                        "COD",
                        "0.00",
                        null,
                        null,
                        now.minus(5, ChronoUnit.HOURS));
        if (ord18 != null) {
            orderStaffService.confirm(ordersCtx, ord18, "Customer confirmed 2x hoodie", null);
        }

        // 19. FOLLOW_UP with NO_ANSWER log
        UUID ord19 =
                seedOnlineOrder(
                        webshop,
                        wh,
                        teeBlack,
                        1,
                        null,
                        "demo-ord-19",
                        "Nabil Islam",
                        "01722200019",
                        "Mohammadpur Tajmahal Rd",
                        "Dhaka",
                        "COD",
                        "0.00",
                        null,
                        null,
                        now.minus(3, ChronoUnit.HOURS));
        if (ord19 != null) {
            orderStaffService.logCall(
                    ordersCtx,
                    ord19,
                    CallResult.NO_ANSWER,
                    "Switched off",
                    now.plus(2, ChronoUnit.HOURS));
        }

        // 20. Fresh PENDING_CONFIRMATION order
        seedOnlineOrder(
                webshop,
                wh,
                sneaker42,
                1,
                null,
                "demo-ord-20",
                "Sumaiya Yasmin",
                "01722200020",
                "Baridhara DOHS",
                "Dhaka",
                "FULL_PREPAID",
                "0.00",
                "CARD",
                "CARD-DEMO-20",
                now.minus(15, ChronoUnit.MINUTES));
    }

    private IntegrationClient ensureDemoClient(UUID ownerUserId, Instant now) {
        return clientRepository
                .findByDemoKey(DemoSimulatorController.DEMO_CLIENT_KEY)
                .orElseGet(
                        () ->
                                clientRepository.save(
                                        new IntegrationClient(
                                                UUID.randomUUID(),
                                                "Demo Webshop",
                                                DemoSimulatorController.DEMO_CLIENT_KEY_ID,
                                                secretCipher.encrypt(
                                                        DemoSimulatorController.DEMO_CLIENT_SECRET),
                                                true,
                                                ownerUserId,
                                                now,
                                                DemoSimulatorController.DEMO_CLIENT_KEY)));
    }

    private UUID seedOnlineOrder(
            IntegrationClient client,
            Location wh,
            ProductVariant variant,
            int qty,
            BigDecimal priceOverride,
            String demoKey,
            String name,
            String phone,
            String address,
            String district,
            String paymentMode,
            String advanceStr,
            String claimedMethod,
            String claimedRef,
            Instant backdateCreatedAt) {
        if (orderRepository.findByDemoKey(demoKey).isPresent()) {
            return null;
        }
        BigDecimal unitPrice =
                priceOverride != null
                        ? priceOverride
                        : onlinePricingService.effectiveUnitPrice(variant, wh.getId()).amount();
        BigDecimal shipping = new BigDecimal("60.00");
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(qty)).add(shipping);
        BigDecimal advance =
                "FULL_PREPAID".equals(paymentMode) ? total : new BigDecimal(advanceStr);
        BigDecimal cod = total.subtract(advance);

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("externalOrderId", "DEMO-" + demoKey.toUpperCase());
        payload.put("customerName", name);
        payload.put("phone", phone);
        payload.put("address", address);
        payload.put("district", district);
        payload.put(
                "lines",
                List.of(
                        Map.of(
                                "sku", variant.getSku(),
                                "quantity", qty,
                                "unitPrice", unitPrice.toPlainString())));
        payload.put("shippingFee", shipping.toPlainString());
        payload.put("discountTotal", "0.00");
        payload.put("total", total.toPlainString());
        payload.put("paymentMode", paymentMode);
        payload.put("advanceAmount", advance.toPlainString());
        payload.put("codAmount", cod.toPlainString());
        if (claimedMethod != null) {
            payload.put("claimedAdvanceMethod", claimedMethod);
        }
        if (claimedRef != null) {
            payload.put("claimedAdvanceReference", claimedRef);
        }

        String raw = objectMapper.writeValueAsString(payload);
        UUID orderId = orderIntakeService.receiveOrder(client, raw, null).order().orderId();
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setDemoKey(demoKey);
        orderRepository.save(order);
        if (backdateCreatedAt != null) {
            jdbcTemplate.update(
                    "UPDATE orders SET created_at = ? WHERE id = ?",
                    Timestamp.from(backdateCreatedAt),
                    orderId);
        }
        return orderId;
    }

    private void seedManualOrder(
            AccessContext ctx,
            String demoKey,
            String name,
            String phone,
            String address,
            String district,
            UUID variantId,
            int qty,
            PaymentMode mode,
            BigDecimal advanceAmount,
            ClaimedAdvanceMethod method,
            String ref,
            boolean confirmNow,
            String note) {
        if (orderRepository.findByDemoKey(demoKey).isPresent()) {
            return;
        }
        UUID orderId =
                manualOrderService
                        .create(
                                ctx,
                                new CreateManualOrderRequest(
                                        name,
                                        phone,
                                        address,
                                        district,
                                        note,
                                        List.of(new ManualOrderLineRequest(variantId, null, qty)),
                                        new BigDecimal("60.00"),
                                        BigDecimal.ZERO,
                                        mode,
                                        advanceAmount,
                                        method,
                                        ref,
                                        confirmNow),
                                null)
                        .id();
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setDemoKey(demoKey);
        orderRepository.save(order);
    }

    private void seedHistoricalOrder(
            IntegrationClient client,
            Location wh,
            ProductVariant variant,
            int qty,
            String demoKey,
            String name,
            String phone,
            String address,
            String district,
            String paymentMode,
            String claimedMethod,
            String claimedRef,
            OrderStatus targetStatus,
            Instant backdatedAt,
            AppUser actor) {
        UUID orderId =
                seedOnlineOrder(
                        client,
                        wh,
                        variant,
                        qty,
                        null,
                        demoKey,
                        name,
                        phone,
                        address,
                        district,
                        paymentMode,
                        "0.00",
                        claimedMethod,
                        claimedRef,
                        backdatedAt);
        if (orderId == null) {
            return;
        }
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(targetStatus);
        if (targetStatus == OrderStatus.DELIVERED
                || targetStatus == OrderStatus.REFUSED
                || targetStatus == OrderStatus.RETURNED) {
            order.setConfirmedAt(backdatedAt.plus(1, ChronoUnit.HOURS));
            order.setClosedAt(backdatedAt.plus(2, ChronoUnit.DAYS));
        } else {
            order.setConfirmedAt(backdatedAt.plus(1, ChronoUnit.HOURS));
        }
        orderRepository.save(order);
        statusHistoryRepository.save(
                new OrderStatusHistory(
                        UUID.randomUUID(),
                        orderId,
                        OrderStatus.PENDING_CONFIRMATION,
                        targetStatus,
                        actor.getId(),
                        "demo-seeder",
                        "DEMO_HISTORY",
                        "Historical status for demo dataset",
                        backdatedAt.plus(1, ChronoUnit.HOURS)));
    }
}
