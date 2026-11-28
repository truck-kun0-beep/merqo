package com.merqo.demo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.merqo.access.AccessContext;
import com.merqo.access.PermissionService;
import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.ProductVariantRepository;
import com.merqo.common.time.BusinessTime;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.orders.ManualOrderService;
import com.merqo.orders.ManualOrderService.CreateManualOrderRequest;
import com.merqo.orders.ManualOrderService.ManualOrderLineRequest;
import com.merqo.orders.Order;
import com.merqo.orders.OrderRepository;
import com.merqo.orders.OrderStaffService;
import com.merqo.orders.domain.CancelReason;
import com.merqo.orders.domain.PaymentTerms.ClaimedAdvanceMethod;
import com.merqo.orders.domain.PaymentTerms.PaymentMode;
import com.merqo.payments.CodService;
import com.merqo.payments.CodService.SettlementLineRequest;
import com.merqo.payments.CourierSettlementRepository;
import com.merqo.payments.MockProviderTransaction;
import com.merqo.payments.MockProviderTransactionRepository;
import com.merqo.payments.OrderPaymentService;
import com.merqo.payments.PaymentDtos.OrderPaymentView;
import com.merqo.payments.PaymentDtos.RefundView;
import com.merqo.payments.RefundService;
import com.merqo.payments.domain.OnlinePaymentMethod;
import com.merqo.payments.domain.RefundStatus;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * Seeds Phase 5 payments, verification decisions, refunds, COD collections, courier settlements,
 * and mock provider transactions through real services when {@code DEMO_MODE=true}.
 */
@Component
@ConditionalOnProperty(name = "merqo.demo-mode", havingValue = "true")
public class DemoOrdersPaymentsContributor implements DemoDataContributor {

    private final OrderRepository orderRepository;
    private final ManualOrderService manualOrderService;
    private final OrderStaffService orderStaffService;
    private final OrderPaymentService paymentService;
    private final RefundService refundService;
    private final CodService codService;
    private final CourierSettlementRepository settlementRepository;
    private final MockProviderTransactionRepository mockProviderRepository;
    private final LocationRepository locationRepository;
    private final ProductVariantRepository variantRepository;
    private final AppUserRepository userRepository;
    private final PermissionService permissionService;
    private final Clock clock;

    public DemoOrdersPaymentsContributor(
            OrderRepository orderRepository,
            ManualOrderService manualOrderService,
            OrderStaffService orderStaffService,
            OrderPaymentService paymentService,
            RefundService refundService,
            CodService codService,
            CourierSettlementRepository settlementRepository,
            MockProviderTransactionRepository mockProviderRepository,
            LocationRepository locationRepository,
            ProductVariantRepository variantRepository,
            AppUserRepository userRepository,
            PermissionService permissionService,
            Clock clock) {
        this.orderRepository = orderRepository;
        this.manualOrderService = manualOrderService;
        this.orderStaffService = orderStaffService;
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.codService = codService;
        this.settlementRepository = settlementRepository;
        this.mockProviderRepository = mockProviderRepository;
        this.locationRepository = locationRepository;
        this.variantRepository = variantRepository;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.clock = clock;
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public void seed() {
        if (settlementRepository.findByDemoKey("demo-stl-02").isPresent()) {
            return;
        }
        Location wh = locationRepository.findByCodeIgnoreCase("WH").orElse(null);
        if (wh == null) {
            return;
        }
        ProductVariant teeBlack = variantRepository.findByDemoKey("var-tee-black").orElse(null);
        ProductVariant hoodieM = variantRepository.findByDemoKey("var-hoodie-m").orElse(null);
        ProductVariant jeans32 = variantRepository.findByDemoKey("var-jeans-32").orElse(null);
        if (teeBlack == null || hoodieM == null || jeans32 == null) {
            return;
        }

        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        AppUser ordersStaff = userRepository.findByDemoKey("orders").orElseThrow();
        AccessContext ownerCtx = permissionService.buildContext(owner);
        AccessContext ordersCtx = permissionService.buildContext(ordersStaff);

        Instant now = Instant.now(clock);
        LocalDate todayDhaka = BusinessTime.today(clock);

        // 1. Cancelled prepaid order with VERIFIED advance and refunds in REQUESTED, APPROVED, PAID
        Order ordCancelledWithRefunds =
                ensureDemoOrder(
                        ownerCtx,
                        "demo-pay-ord-02",
                        "Nadia Rahman",
                        "01755500001",
                        "Dhanmondi 8",
                        teeBlack.getId(),
                        2,
                        PaymentMode.FULL_PREPAID,
                        null,
                        ClaimedAdvanceMethod.BKASH,
                        "BKDM02PAY",
                        false);
        OrderPaymentView p2 =
                paymentService.recordPaymentInternal(
                        ordersCtx,
                        ordCancelledWithRefunds.getId(),
                        "BKASH",
                        ordCancelledWithRefunds.getTotal(),
                        "DMPAY02BK",
                        "Customer confirmed full advance",
                        true,
                        now.minus(48, ChronoUnit.HOURS),
                        "demo-pay-02",
                        null);
        if (p2.decision() == null) {
            paymentService.decidePaymentInternal(
                    ownerCtx,
                    p2.id(),
                    "VERIFIED",
                    ordCancelledWithRefunds.getTotal(),
                    "Matched merchant statement #201",
                    "STAFF_MANUAL",
                    null,
                    now.minus(46, ChronoUnit.HOURS),
                    null);
        }
        if (ordCancelledWithRefunds.getStatus().name().equals("PENDING_CONFIRMATION")) {
            orderStaffService.cancel(
                    ownerCtx,
                    ordCancelledWithRefunds.getId(),
                    CancelReason.CUSTOMER_REQUESTED,
                    "Cancelled after payment for partial refunds",
                    null);
        }
        RefundView r1 =
                refundService.requestRefundInternal(
                        ordersCtx,
                        ordCancelledWithRefunds.getId(),
                        new BigDecimal("200.00"),
                        "ORDER_CANCELLED",
                        "Paid refund part 1",
                        now.minus(24, ChronoUnit.HOURS),
                        "demo-rfd-01",
                        null);
        if (r1.status() == RefundStatus.REQUESTED) {
            refundService.decideInternal(
                    ownerCtx,
                    r1.id(),
                    RefundStatus.APPROVED,
                    "Approved part 1",
                    now.minus(22, ChronoUnit.HOURS),
                    null);
            refundService.payInternal(
                    ordersCtx,
                    r1.id(),
                    "BKASH",
                    "DMRFDPAY01",
                    "Paid via merchant bKash",
                    now.minus(20, ChronoUnit.HOURS),
                    null);
        }
        RefundView r2 =
                refundService.requestRefundInternal(
                        ordersCtx,
                        ordCancelledWithRefunds.getId(),
                        new BigDecimal("200.00"),
                        "ORDER_CANCELLED",
                        "Approved refund awaiting payout",
                        now.minus(6, ChronoUnit.HOURS),
                        "demo-rfd-02",
                        null);
        if (r2.status() == RefundStatus.REQUESTED) {
            refundService.decideInternal(
                    ownerCtx,
                    r2.id(),
                    RefundStatus.APPROVED,
                    "Ready for finance payout",
                    now.minus(4, ChronoUnit.HOURS),
                    null);
        }
        refundService.requestRefundInternal(
                ordersCtx,
                ordCancelledWithRefunds.getId(),
                new BigDecimal("150.00"),
                "ORDER_CANCELLED",
                "Remaining refund request pending approval",
                now.minus(2, ChronoUnit.HOURS),
                "demo-rfd-03",
                null);

        // 2. Cancelled prepaid order with VERIFIED advance and NO open refund request (surfaces in
        // GET /refunds/due)
        Order ordDueRefund =
                ensureDemoOrder(
                        ownerCtx,
                        "demo-pay-ord-03",
                        "Imtiaz Kabir",
                        "01755500002",
                        "Gulshan 1",
                        jeans32.getId(),
                        1,
                        PaymentMode.FULL_PREPAID,
                        null,
                        ClaimedAdvanceMethod.NAGAD,
                        "NGDM03PAY",
                        false);
        OrderPaymentView p3 =
                paymentService.recordPaymentInternal(
                        ordersCtx,
                        ordDueRefund.getId(),
                        "NAGAD",
                        ordDueRefund.getTotal(),
                        "DMPAY03NG",
                        "Nagad transfer verified prior to cancel",
                        true,
                        now.minus(30, ChronoUnit.HOURS),
                        "demo-pay-03",
                        null);
        if (p3.decision() == null) {
            paymentService.decidePaymentInternal(
                    ownerCtx,
                    p3.id(),
                    "VERIFIED",
                    ordDueRefund.getTotal(),
                    "Confirmed Nagad SMS",
                    "STAFF_MANUAL",
                    null,
                    now.minus(28, ChronoUnit.HOURS),
                    null);
        }
        if (ordDueRefund.getStatus().name().equals("PENDING_CONFIRMATION")) {
            orderStaffService.cancel(
                    ownerCtx,
                    ordDueRefund.getId(),
                    CancelReason.STOCK_PROBLEM,
                    "Awaiting refund request creation",
                    null);
        }

        // 3. Overdue WEBSITE_CLAIM pending payment (recorded > 24h ago)
        Order ordClaimedOverdue =
                ensureDemoOrder(
                        ownerCtx,
                        "demo-pay-ord-01",
                        "Shafiqul Alam",
                        "01755500003",
                        "Banani DOHS",
                        hoodieM.getId(),
                        1,
                        PaymentMode.ADVANCE_COD,
                        new BigDecimal("300.00"),
                        ClaimedAdvanceMethod.BKASH,
                        "BKDM01CLM",
                        false);
        paymentService.recordPaymentInternal(
                ordersCtx,
                ordClaimedOverdue.getId(),
                "BKASH",
                new BigDecimal("300.00"),
                "BKDM01CLM",
                "Claimed at checkout awaiting verification (>24h overdue)",
                true,
                now.minus(36, ChronoUnit.HOURS),
                "demo-pay-01",
                null);

        // 4. Additional payments covering all 4 methods & 3 statuses (total 12 payments)
        Order ordNgPend =
                ensureDemoOrder(
                        ownerCtx,
                        "demo-pay-ord-04",
                        "Tania Sultana",
                        "01755500004",
                        "Uttara 4",
                        teeBlack.getId(),
                        1,
                        PaymentMode.FULL_PREPAID,
                        null,
                        null,
                        null,
                        false);
        paymentService.recordPaymentInternal(
                ordersCtx,
                ordNgPend.getId(),
                "NAGAD",
                ordNgPend.getTotal(),
                "DMPAY04NG",
                "Pending Nagad check with mock provider",
                false,
                now.minus(4, ChronoUnit.HOURS),
                "demo-pay-04",
                null);
        ensureMockTx(
                OnlinePaymentMethod.NAGAD, "DMPAY04NG", ordNgPend.getTotal(), "01755500004", now);

        Order ordCardPend =
                ensureDemoOrder(
                        ownerCtx,
                        "demo-pay-ord-05",
                        "Zarif Chowdhury",
                        "01755500005",
                        "Bashundhara",
                        teeBlack.getId(),
                        1,
                        PaymentMode.FULL_PREPAID,
                        null,
                        null,
                        null,
                        false);
        paymentService.recordPaymentInternal(
                ordersCtx,
                ordCardPend.getId(),
                "CARD",
                ordCardPend.getTotal(),
                "4242",
                "POS terminal slip pending check",
                false,
                now.minus(2, ChronoUnit.HOURS),
                "demo-pay-05",
                null);

        Order ordBankPend =
                ensureDemoOrder(
                        ownerCtx,
                        "demo-pay-ord-06",
                        "Farid Uddin",
                        "01755500006",
                        "Motijheel",
                        hoodieM.getId(),
                        1,
                        PaymentMode.ADVANCE_COD,
                        new BigDecimal("400.00"),
                        ClaimedAdvanceMethod.BANK,
                        "IBBL-2026-06",
                        false);
        paymentService.recordPaymentInternal(
                ordersCtx,
                ordBankPend.getId(),
                "BANK",
                new BigDecimal("400.00"),
                "IBBL-2026-06",
                "Bank transfer screenshot",
                true,
                now.minus(1, ChronoUnit.HOURS),
                "demo-pay-06",
                null);
        ensureMockTx(
                OnlinePaymentMethod.BANK,
                "IBBL-2026-06",
                new BigDecimal("400.00"),
                "IBBL-Corporate",
                now);

        Order ordCardVer =
                ensureDemoOrder(
                        ownerCtx,
                        "demo-pay-ord-07",
                        "Rasheda Begum",
                        "01755500007",
                        "Mirpur 12",
                        jeans32.getId(),
                        1,
                        PaymentMode.FULL_PREPAID,
                        null,
                        null,
                        null,
                        false);
        OrderPaymentView p7 =
                paymentService.recordPaymentInternal(
                        ordersCtx,
                        ordCardVer.getId(),
                        "CARD",
                        ordCardVer.getTotal(),
                        "AUTH7711",
                        null,
                        false,
                        now.minus(12, ChronoUnit.HOURS),
                        "demo-pay-07",
                        null);
        if (p7.decision() == null) {
            paymentService.decidePaymentInternal(
                    ownerCtx,
                    p7.id(),
                    "VERIFIED",
                    ordCardVer.getTotal(),
                    "Confirmed via mock provider transaction.",
                    "PROVIDER:mock",
                    "AUTH7711",
                    now.minus(11, ChronoUnit.HOURS),
                    null);
        }

        Order ordBankVer =
                ensureDemoOrder(
                        ownerCtx,
                        "demo-pay-ord-08",
                        "Mahbub Hasan",
                        "01755500008",
                        "Wari",
                        hoodieM.getId(),
                        1,
                        PaymentMode.FULL_PREPAID,
                        null,
                        null,
                        null,
                        false);
        OrderPaymentView p8 =
                paymentService.recordPaymentInternal(
                        ordersCtx,
                        ordBankVer.getId(),
                        "BANK",
                        ordBankVer.getTotal(),
                        "DBBL-2026-08",
                        null,
                        false,
                        now.minus(10, ChronoUnit.HOURS),
                        "demo-pay-08",
                        null);
        if (p8.decision() == null) {
            paymentService.decidePaymentInternal(
                    ownerCtx,
                    p8.id(),
                    "VERIFIED",
                    ordBankVer.getTotal(),
                    "Verified in DBBL corporate statement",
                    "STAFF_MANUAL",
                    null,
                    now.minus(9, ChronoUnit.HOURS),
                    null);
        }

        // Rejected payments + replacement payment on demo-pay-ord-09
        Order ordRejFlow =
                ensureDemoOrder(
                        ownerCtx,
                        "demo-pay-ord-09",
                        "Sadia Tasnim",
                        "01755500009",
                        "Khilgaon",
                        jeans32.getId(),
                        1,
                        PaymentMode.FULL_PREPAID,
                        null,
                        null,
                        null,
                        false);
        OrderPaymentView p9 =
                paymentService.recordPaymentInternal(
                        ordersCtx,
                        ordRejFlow.getId(),
                        "BKASH",
                        ordRejFlow.getTotal(),
                        "DMPAY09REJ",
                        null,
                        false,
                        now.minus(18, ChronoUnit.HOURS),
                        "demo-pay-09",
                        null);
        if (p9.decision() == null) {
            paymentService.decidePaymentInternal(
                    ownerCtx,
                    p9.id(),
                    "REJECTED",
                    null,
                    "Wrong TrxID screenshot uploaded",
                    "STAFF_MANUAL",
                    null,
                    now.minus(17, ChronoUnit.HOURS),
                    null);
        }
        OrderPaymentView p10 =
                paymentService.recordPaymentInternal(
                        ordersCtx,
                        ordRejFlow.getId(),
                        "BKASH",
                        ordRejFlow.getTotal(),
                        "DMPAY10OK",
                        "Customer sent correct TrxID",
                        false,
                        now.minus(15, ChronoUnit.HOURS),
                        "demo-pay-10",
                        null);
        if (p10.decision() == null) {
            paymentService.decidePaymentInternal(
                    ownerCtx,
                    p10.id(),
                    "VERIFIED",
                    ordRejFlow.getTotal(),
                    "Verified replacement payment",
                    "STAFF_MANUAL",
                    null,
                    now.minus(14, ChronoUnit.HOURS),
                    null);
        }

        Order ordNgRej =
                ensureDemoOrder(
                        ownerCtx,
                        "demo-pay-ord-10",
                        "Asif Iqbal",
                        "01755500010",
                        "Mohammadpur",
                        teeBlack.getId(),
                        1,
                        PaymentMode.ADVANCE_COD,
                        new BigDecimal("250.00"),
                        null,
                        null,
                        false);
        OrderPaymentView p11 =
                paymentService.recordPaymentInternal(
                        ordersCtx,
                        ordNgRej.getId(),
                        "NAGAD",
                        new BigDecimal("250.00"),
                        "DMPAY11REJ",
                        null,
                        false,
                        now.minus(14, ChronoUnit.HOURS),
                        "demo-pay-11",
                        null);
        if (p11.decision() == null) {
            paymentService.decidePaymentInternal(
                    ownerCtx,
                    p11.id(),
                    "REJECTED",
                    null,
                    "TrxID not found in Nagad portal",
                    "STAFF_MANUAL",
                    null,
                    now.minus(13, ChronoUnit.HOURS),
                    null);
        }

        Order ordBankRej =
                ensureDemoOrder(
                        ownerCtx,
                        "demo-pay-ord-11",
                        "Monirul Haque",
                        "01755500011",
                        "Tejgaon",
                        hoodieM.getId(),
                        1,
                        PaymentMode.ADVANCE_COD,
                        new BigDecimal("400.00"),
                        null,
                        null,
                        false);
        OrderPaymentView p12 =
                paymentService.recordPaymentInternal(
                        ordersCtx,
                        ordBankRej.getId(),
                        "BANK",
                        new BigDecimal("400.00"),
                        "EBL-2026-12",
                        null,
                        false,
                        now.minus(8, ChronoUnit.HOURS),
                        "demo-pay-12",
                        null);
        if (p12.decision() == null) {
            paymentService.decidePaymentInternal(
                    ownerCtx,
                    p12.id(),
                    "REJECTED",
                    null,
                    "Duplicate deposit slip date",
                    "STAFF_MANUAL",
                    null,
                    now.minus(7, ChronoUnit.HOURS),
                    null);
        }

        // 5. COD collections, >7-day COD gap, exact settlement, and SHORT settlement
        Order codGapOrder =
                ensureDemoOrder(
                        ownerCtx,
                        "demo-pay-cod-01",
                        "Kamal Hossain",
                        "01755500012",
                        "Farmgate",
                        jeans32.getId(),
                        1,
                        PaymentMode.COD,
                        null,
                        null,
                        null,
                        true);
        codService.recordCollectionInternal(
                ownerCtx,
                codGapOrder.getId(),
                "PATHAO",
                codGapOrder.getCodAmount(),
                todayDhaka.minusDays(10),
                "Collected 10 days ago — unsettled (>7d gap)",
                now.minus(10, ChronoUnit.DAYS),
                "demo-cod-01",
                null);

        Order codOpenOrder =
                ensureDemoOrder(
                        ownerCtx,
                        "demo-pay-cod-02",
                        "Nusrat Chowdhury",
                        "01755500013",
                        "Kakrail",
                        teeBlack.getId(),
                        1,
                        PaymentMode.COD,
                        null,
                        null,
                        null,
                        true);
        codService.recordCollectionInternal(
                ownerCtx,
                codOpenOrder.getId(),
                "STEADFAST",
                codOpenOrder.getCodAmount(),
                todayDhaka.minusDays(2),
                "Recent Steadfast collection awaiting settlement",
                now.minus(2, ChronoUnit.DAYS),
                "demo-cod-02",
                null);

        Order codExactSettleOrd =
                ensureDemoOrder(
                        ownerCtx,
                        "demo-pay-cod-03",
                        "Mahmudul Hasan",
                        "01755500014",
                        "Badda",
                        hoodieM.getId(),
                        1,
                        PaymentMode.COD,
                        null,
                        null,
                        null,
                        true);
        codService.recordCollectionInternal(
                ownerCtx,
                codExactSettleOrd.getId(),
                "STEADFAST",
                codExactSettleOrd.getCodAmount(),
                todayDhaka.minusDays(4),
                "Collected for exact demo settlement",
                now.minus(4, ChronoUnit.DAYS),
                "demo-cod-03",
                null);
        BigDecimal exactFee = new BigDecimal("20.00");
        BigDecimal exactReceived = codExactSettleOrd.getCodAmount().subtract(exactFee);
        codService.recordSettlementInternal(
                ownerCtx,
                "STEADFAST",
                "STL-SF-DEMO-01",
                todayDhaka.minusDays(1),
                "BANK",
                exactReceived,
                "Exact Steadfast settlement batch",
                List.of(
                        new SettlementLineRequest(
                                codExactSettleOrd.getId(),
                                null,
                                codExactSettleOrd.getCodAmount(),
                                exactFee)),
                now.minus(1, ChronoUnit.DAYS),
                "demo-stl-01",
                null);

        Order codShortSettleOrd =
                ensureDemoOrder(
                        ownerCtx,
                        "demo-pay-cod-04",
                        "Faisal Ahmed",
                        "01755500015",
                        "Jatrabari",
                        jeans32.getId(),
                        1,
                        PaymentMode.COD,
                        null,
                        null,
                        null,
                        true);
        codService.recordCollectionInternal(
                ownerCtx,
                codShortSettleOrd.getId(),
                "PATHAO",
                codShortSettleOrd.getCodAmount(),
                todayDhaka.minusDays(3),
                "Collected for SHORT demo settlement",
                now.minus(3, ChronoUnit.DAYS),
                "demo-cod-04",
                null);
        BigDecimal shortFee = new BigDecimal("20.00");
        BigDecimal shortReceived =
                codShortSettleOrd
                        .getCodAmount()
                        .subtract(shortFee)
                        .subtract(new BigDecimal("30.00"));
        codService.recordSettlementInternal(
                ownerCtx,
                "PATHAO",
                "STL-PT-DEMO-02",
                todayDhaka,
                "BANK",
                shortReceived,
                "Pathao short remittance — dispute ticket #402",
                List.of(
                        new SettlementLineRequest(
                                codShortSettleOrd.getId(),
                                null,
                                codShortSettleOrd.getCodAmount(),
                                shortFee)),
                now,
                "demo-stl-02",
                null);
    }

    private Order ensureDemoOrder(
            AccessContext ownerCtx,
            String demoKey,
            String name,
            String phone,
            String address,
            UUID variantId,
            int qty,
            PaymentMode mode,
            BigDecimal advanceAmount,
            ClaimedAdvanceMethod claimedMethod,
            String claimedRef,
            boolean confirmNow) {
        return orderRepository
                .findByDemoKey(demoKey)
                .orElseGet(
                        () -> {
                            UUID orderId =
                                    manualOrderService
                                            .create(
                                                    ownerCtx,
                                                    new CreateManualOrderRequest(
                                                            name,
                                                            phone,
                                                            address,
                                                            "Dhaka",
                                                            "Phase 5 demo order",
                                                            List.of(
                                                                    new ManualOrderLineRequest(
                                                                            variantId, null, qty)),
                                                            new BigDecimal("60.00"),
                                                            BigDecimal.ZERO,
                                                            mode,
                                                            advanceAmount,
                                                            claimedMethod,
                                                            claimedRef,
                                                            confirmNow),
                                                    null)
                                            .id();
                            Order saved = orderRepository.findById(orderId).orElseThrow();
                            saved.setDemoKey(demoKey);
                            return orderRepository.saveAndFlush(saved);
                        });
    }

    private void ensureMockTx(
            OnlinePaymentMethod method,
            String reference,
            BigDecimal amount,
            String senderHint,
            Instant createdAt) {
        if (mockProviderRepository.findByMethodAndReference(method, reference).isEmpty()) {
            mockProviderRepository.saveAndFlush(
                    new MockProviderTransaction(
                            UUID.randomUUID(),
                            method,
                            reference,
                            amount,
                            senderHint,
                            null,
                            createdAt));
        }
    }
}
