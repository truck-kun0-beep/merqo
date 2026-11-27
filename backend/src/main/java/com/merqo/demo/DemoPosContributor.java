package com.merqo.demo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.merqo.access.AccessContext;
import com.merqo.access.PermissionService;
import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.ProductVariantRepository;
import com.merqo.catalog.domain.DiscountType;
import com.merqo.catalog.domain.Scope;
import com.merqo.common.time.BusinessTime;
import com.merqo.inventory.InventoryCommand;
import com.merqo.inventory.InventoryService;
import com.merqo.inventory.StockBalance;
import com.merqo.inventory.domain.MovementType;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.pos.ApprovalService;
import com.merqo.pos.CashSession;
import com.merqo.pos.CashSessionRepository;
import com.merqo.pos.CashSessionService;
import com.merqo.pos.CashSessionStatus;
import com.merqo.pos.ReturnDisposition;
import com.merqo.pos.ReturnReason;
import com.merqo.pos.ReturnService;
import com.merqo.pos.SaleRepository;
import com.merqo.pos.SaleService;
import com.merqo.promotion.PromotionRepository;
import com.merqo.promotion.PromotionService;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * Seeds Phase 3 POS demo data through real application services when {@code DEMO_MODE=true}.
 *
 * <p>Responsibility: set demo PIN {@code 1234} on approvers, ensure branch opening stock covers
 * seeded sales, seed POS promotions, closed historical cash sessions across all three branches, an
 * open shift session for the demo cashier at Dhanmondi, 25 back-dated sales over the last 14 days
 * across all four payment methods (including promotions, manual discounts, and two manager-approved
 * discounts), and sample returns ({@code RESTOCK} and {@code INSPECTION}).
 *
 * <p>OOP concept: <b>Strategy &amp; Idempotent Seeder</b> — implements {@link DemoDataContributor}
 * and keys every seeded artifact by a stable {@code demoKey} so running twice produces identical
 * row counts.
 */
@Component
@ConditionalOnProperty(name = "merqo.demo-mode", havingValue = "true")
public class DemoPosContributor implements DemoDataContributor {

    private static final String DEMO_PIN = "1234";

    private final AppUserRepository userRepository;
    private final LocationRepository locationRepository;
    private final ProductVariantRepository variantRepository;
    private final PromotionRepository promotionRepository;
    private final CashSessionRepository cashSessionRepository;
    private final SaleRepository saleRepository;
    private final PermissionService permissionService;
    private final InventoryService inventoryService;
    private final PromotionService promotionService;
    private final ApprovalService approvalService;
    private final CashSessionService cashSessionService;
    private final SaleService saleService;
    private final ReturnService returnService;
    private final Clock clock;

    public DemoPosContributor(
            AppUserRepository userRepository,
            LocationRepository locationRepository,
            ProductVariantRepository variantRepository,
            PromotionRepository promotionRepository,
            CashSessionRepository cashSessionRepository,
            SaleRepository saleRepository,
            PermissionService permissionService,
            InventoryService inventoryService,
            PromotionService promotionService,
            ApprovalService approvalService,
            CashSessionService cashSessionService,
            SaleService saleService,
            ReturnService returnService,
            Clock clock) {
        this.userRepository = userRepository;
        this.locationRepository = locationRepository;
        this.variantRepository = variantRepository;
        this.promotionRepository = promotionRepository;
        this.cashSessionRepository = cashSessionRepository;
        this.saleRepository = saleRepository;
        this.permissionService = permissionService;
        this.inventoryService = inventoryService;
        this.promotionService = promotionService;
        this.approvalService = approvalService;
        this.cashSessionService = cashSessionService;
        this.saleService = saleService;
        this.returnService = returnService;
        this.clock = clock;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public void seed() {
        Instant now = Instant.now(clock);

        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        AppUser managerDm = userRepository.findByDemoKey("manager-dm").orElseThrow();
        AppUser managerUt = userRepository.findByDemoKey("manager-ut").orElseThrow();
        AppUser cashier = userRepository.findByDemoKey("cashier").orElseThrow();

        // 1. Demo PIN 1234 on approvers (idempotent, demo mode only).
        for (AppUser approver : List.of(owner, managerDm, managerUt)) {
            if (approver.getApprovalPinHash() == null) {
                approvalService.setApprovalPinForDemo(approver, DEMO_PIN);
            }
        }

        // Fast idempotency check: if all 25 demo sales and open cashier session already exist,
        // stop.
        if (saleRepository.findByDemoKey("pos-demo-sale-25").isPresent()
                && cashSessionRepository.findByDemoKey("pos-demo-session-dm-open").isPresent()) {
            return;
        }

        Location dm = locationRepository.findByCodeIgnoreCase("DM").orElseThrow();
        Location mp = locationRepository.findByCodeIgnoreCase("MP").orElseThrow();
        Location ut = locationRepository.findByCodeIgnoreCase("UT").orElseThrow();

        AccessContext ownerCtx = permissionService.buildContext(owner);
        AccessContext mgrDmCtx = permissionService.buildContext(managerDm);
        AccessContext mgrUtCtx = permissionService.buildContext(managerUt);
        AccessContext cashierCtx = permissionService.buildContext(cashier);

        List<ProductVariant> demoVariants = variantRepository.findByDemoKeyNotNull();
        // 2. Ensure opening branch stock exists so demo sales never hit insufficient stock.
        for (Location branch : List.of(dm, mp, ut)) {
            for (ProductVariant v : demoVariants) {
                StockBalance balance = inventoryService.balanceOf(branch.getId(), v.getId());
                int available = balance == null ? 0 : balance.getAvailable();
                if (available < 40) {
                    inventoryService.receive(
                            new InventoryCommand(
                                    branch.getId(),
                                    v.getId(),
                                    40 - available,
                                    MovementType.OPENING_BALANCE,
                                    "DEMO_SEED",
                                    null,
                                    null,
                                    "Phase 3 demo opening stock",
                                    null,
                                    owner.getId(),
                                    null));
                }
            }
        }

        ProductVariant teeBlack = variantRepository.findByDemoKey("var-tee-black").orElseThrow();
        ProductVariant teeWhite = variantRepository.findByDemoKey("var-tee-white").orElseThrow();
        ProductVariant hoodieM = variantRepository.findByDemoKey("var-hoodie-m").orElseThrow();
        ProductVariant jeans32 = variantRepository.findByDemoKey("var-jeans-32").orElseThrow();
        ProductVariant sneaker40 = variantRepository.findByDemoKey("var-sneaker-40").orElseThrow();
        ProductVariant necklaceS = variantRepository.findByDemoKey("var-necklace-s").orElseThrow();

        // 3. POS promotions (idempotent by demoKey).
        seedPromo(
                ownerCtx,
                "pos-demo-promo-tee",
                "POS Tee 10% Off",
                DiscountType.PERCENT,
                new BigDecimal("10"),
                Set.of(dm.getId(), mp.getId(), ut.getId()),
                Set.of(teeBlack.getId(), teeWhite.getId()));
        seedPromo(
                ownerCtx,
                "pos-demo-promo-sneaker",
                "POS Sneaker 200 Off",
                DiscountType.FIXED,
                new BigDecimal("200"),
                Set.of(dm.getId(), mp.getId()),
                Set.of(sneaker40.getId()));

        // 4. Historical closed sessions + 25 back-dated sales over the last 14 days.
        // Batch A: Dhanmondi past session 1 (manager-dm, days -13 to -11, 5 sales + 1 RESTOCK
        // return)
        Instant dmPast1Open = now.minus(13, ChronoUnit.DAYS);
        Map<String, Object> dmPast1 =
                cashSessionService.openForDemo(
                        mgrDmCtx,
                        dm.getId(),
                        new BigDecimal("5000.00"),
                        dmPast1Open,
                        "pos-demo-session-dm-past-1");
        UUID dmPast1Id = UUID.fromString(dmPast1.get("id").toString());

        Map<String, Object> sale01 =
                seedSale(
                        mgrDmCtx,
                        dm.getId(),
                        teeBlack.getId(),
                        2,
                        null,
                        null,
                        null,
                        "CASH",
                        new BigDecimal("2000.00"),
                        null,
                        now.minus(13, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS),
                        "pos-demo-sale-01");
        seedSale(
                mgrDmCtx,
                dm.getId(),
                hoodieM.getId(),
                1,
                DiscountType.PERCENT,
                new BigDecimal("5"),
                null,
                "BKASH",
                null,
                "BKDEMO0002",
                now.minus(12, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS),
                "pos-demo-sale-02");
        seedSale(
                mgrDmCtx,
                dm.getId(),
                jeans32.getId(),
                1,
                null,
                null,
                null,
                "NAGAD",
                null,
                "NGDEMO0003",
                now.minus(12, ChronoUnit.DAYS).plus(5, ChronoUnit.HOURS),
                "pos-demo-sale-03");
        seedSale(
                mgrDmCtx,
                dm.getId(),
                sneaker40.getId(),
                1,
                null,
                null,
                null,
                "CARD",
                null,
                "4242",
                now.minus(11, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS),
                "pos-demo-sale-04");
        seedSale(
                mgrDmCtx,
                dm.getId(),
                teeWhite.getId(),
                2,
                null,
                null,
                null,
                "CASH",
                new BigDecimal("1500.00"),
                null,
                now.minus(11, ChronoUnit.DAYS).plus(4, ChronoUnit.HOURS),
                "pos-demo-sale-05");

        // Return 1 (RESTOCK on sale01 while dmPast1 is still open so cash refund links to it)
        seedReturn(
                mgrDmCtx,
                sale01,
                1,
                ReturnReason.WRONG_SIZE,
                ReturnDisposition.RESTOCK,
                now.minus(11, ChronoUnit.DAYS).plus(5, ChronoUnit.HOURS),
                "pos-demo-return-01");

        closeDemoSessionIfOpen(
                mgrDmCtx, dmPast1Id, now.minus(11, ChronoUnit.DAYS).plus(8, ChronoUnit.HOURS));

        // Batch B: Mirpur past session (manager-dm, days -10 to -8, 6 sales + 1 INSPECTION return)
        Map<String, Object> mpPast1 =
                cashSessionService.openForDemo(
                        mgrDmCtx,
                        mp.getId(),
                        new BigDecimal("4000.00"),
                        now.minus(10, ChronoUnit.DAYS),
                        "pos-demo-session-mp-past-1");
        UUID mpPast1Id = UUID.fromString(mpPast1.get("id").toString());

        seedSale(
                mgrDmCtx,
                mp.getId(),
                teeBlack.getId(),
                1,
                null,
                null,
                null,
                "CASH",
                new BigDecimal("1000.00"),
                null,
                now.minus(10, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS),
                "pos-demo-sale-06");
        Map<String, Object> sale07 =
                seedSale(
                        mgrDmCtx,
                        mp.getId(),
                        jeans32.getId(),
                        2,
                        null,
                        null,
                        null,
                        "BKASH",
                        null,
                        "BKDEMO0007",
                        now.minus(9, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS),
                        "pos-demo-sale-07");
        seedSale(
                mgrDmCtx,
                mp.getId(),
                sneaker40.getId(),
                1,
                null,
                null,
                null,
                "NAGAD",
                null,
                "NGDEMO0008",
                now.minus(9, ChronoUnit.DAYS).plus(5, ChronoUnit.HOURS),
                "pos-demo-sale-08");
        seedSale(
                mgrDmCtx,
                mp.getId(),
                necklaceS.getId(),
                1,
                DiscountType.PERCENT,
                new BigDecimal("10"),
                null,
                "CARD",
                null,
                "AUTH0009",
                now.minus(8, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS),
                "pos-demo-sale-09");
        seedSale(
                mgrDmCtx,
                mp.getId(),
                hoodieM.getId(),
                1,
                null,
                null,
                null,
                "CASH",
                new BigDecimal("2500.00"),
                null,
                now.minus(8, ChronoUnit.DAYS).plus(4, ChronoUnit.HOURS),
                "pos-demo-sale-10");
        seedSale(
                mgrDmCtx,
                mp.getId(),
                teeWhite.getId(),
                3,
                null,
                null,
                null,
                "BKASH",
                null,
                "BKDEMO0011",
                now.minus(8, ChronoUnit.DAYS).plus(6, ChronoUnit.HOURS),
                "pos-demo-sale-11");

        // Return 2 (INSPECTION on sale07)
        seedReturn(
                mgrDmCtx,
                sale07,
                1,
                ReturnReason.DEFECTIVE,
                ReturnDisposition.INSPECTION,
                now.minus(8, ChronoUnit.DAYS).plus(7, ChronoUnit.HOURS),
                "pos-demo-return-02");

        closeDemoSessionIfOpen(
                mgrDmCtx, mpPast1Id, now.minus(8, ChronoUnit.DAYS).plus(9, ChronoUnit.HOURS));

        // Batch C: Uttara past session (manager-ut, days -7 to -5, 6 sales)
        Map<String, Object> utPast1 =
                cashSessionService.openForDemo(
                        mgrUtCtx,
                        ut.getId(),
                        new BigDecimal("3500.00"),
                        now.minus(7, ChronoUnit.DAYS),
                        "pos-demo-session-ut-past-1");
        UUID utPast1Id = UUID.fromString(utPast1.get("id").toString());

        seedSale(
                mgrUtCtx,
                ut.getId(),
                teeBlack.getId(),
                2,
                null,
                null,
                null,
                "CASH",
                new BigDecimal("1500.00"),
                null,
                now.minus(7, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS),
                "pos-demo-sale-12");
        seedSale(
                mgrUtCtx,
                ut.getId(),
                hoodieM.getId(),
                1,
                null,
                null,
                null,
                "BKASH",
                null,
                "BKDEMO0013",
                now.minus(6, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS),
                "pos-demo-sale-13");
        seedSale(
                mgrUtCtx,
                ut.getId(),
                jeans32.getId(),
                1,
                null,
                null,
                null,
                "NAGAD",
                null,
                "NGDEMO0014",
                now.minus(6, ChronoUnit.DAYS).plus(4, ChronoUnit.HOURS),
                "pos-demo-sale-14");
        seedSale(
                mgrUtCtx,
                ut.getId(),
                sneaker40.getId(),
                1,
                null,
                null,
                null,
                "CARD",
                null,
                "7788",
                now.minus(5, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS),
                "pos-demo-sale-15");
        seedSale(
                mgrUtCtx,
                ut.getId(),
                necklaceS.getId(),
                1,
                DiscountType.PERCENT,
                new BigDecimal("5"),
                null,
                "CASH",
                new BigDecimal("5000.00"),
                null,
                now.minus(5, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS),
                "pos-demo-sale-16");
        seedSale(
                mgrUtCtx,
                ut.getId(),
                teeWhite.getId(),
                1,
                null,
                null,
                null,
                "NAGAD",
                null,
                "NGDEMO0017",
                now.minus(5, ChronoUnit.DAYS).plus(5, ChronoUnit.HOURS),
                "pos-demo-sale-17");

        closeDemoSessionIfOpen(
                mgrUtCtx, utPast1Id, now.minus(5, ChronoUnit.DAYS).plus(8, ChronoUnit.HOURS));

        // Batch D: Dhanmondi past session for demo cashier (days -4 to -2, 5 sales incl. 1 approved
        // discount)
        Map<String, Object> dmPastCashier =
                cashSessionService.openForDemo(
                        cashierCtx,
                        dm.getId(),
                        new BigDecimal("3000.00"),
                        now.minus(4, ChronoUnit.DAYS),
                        "pos-demo-session-dm-past-cashier");
        UUID dmPastCashierId = UUID.fromString(dmPastCashier.get("id").toString());

        seedSale(
                cashierCtx,
                dm.getId(),
                teeBlack.getId(),
                1,
                DiscountType.PERCENT,
                new BigDecimal("5"),
                null,
                "CASH",
                new BigDecimal("1000.00"),
                null,
                now.minus(4, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS),
                "pos-demo-sale-18");
        Instant approved1Time = now.minus(3, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS);
        String token1 =
                saleRepository.findByDemoKey("pos-demo-sale-19").isPresent()
                        ? null
                        : issueDemoToken(
                                cashierCtx, dm.getId(), new BigDecimal("20"), approved1Time);
        seedSale(
                cashierCtx,
                dm.getId(),
                hoodieM.getId(),
                1,
                DiscountType.PERCENT,
                new BigDecimal("15"),
                token1,
                "CARD",
                null,
                "9012",
                approved1Time,
                "pos-demo-sale-19");
        seedSale(
                cashierCtx,
                dm.getId(),
                jeans32.getId(),
                1,
                null,
                null,
                null,
                "BKASH",
                null,
                "BKDEMO0020",
                now.minus(3, ChronoUnit.DAYS).plus(5, ChronoUnit.HOURS),
                "pos-demo-sale-20");
        seedSale(
                cashierCtx,
                dm.getId(),
                sneaker40.getId(),
                1,
                null,
                null,
                null,
                "NAGAD",
                null,
                "NGDEMO0021",
                now.minus(2, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS),
                "pos-demo-sale-21");
        seedSale(
                cashierCtx,
                dm.getId(),
                teeWhite.getId(),
                2,
                null,
                null,
                null,
                "CASH",
                new BigDecimal("2000.00"),
                null,
                now.minus(2, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS),
                "pos-demo-sale-22");

        closeDemoSessionIfOpen(
                cashierCtx,
                dmPastCashierId,
                now.minus(2, ChronoUnit.DAYS).plus(7, ChronoUnit.HOURS));

        // Batch E: Open session for demo cashier at Dhanmondi + 3 recent sales (incl. 2nd approved
        // discount)
        cashSessionService.openForDemo(
                cashierCtx,
                dm.getId(),
                new BigDecimal("5000.00"),
                now.minus(3, ChronoUnit.HOURS),
                "pos-demo-session-dm-open");

        seedSale(
                cashierCtx,
                dm.getId(),
                teeBlack.getId(),
                1,
                null,
                null,
                null,
                "CASH",
                new BigDecimal("1000.00"),
                null,
                now.minus(2, ChronoUnit.HOURS),
                "pos-demo-sale-23");
        Instant approved2Time = now.minus(1, ChronoUnit.HOURS);
        String token2 =
                saleRepository.findByDemoKey("pos-demo-sale-24").isPresent()
                        ? null
                        : issueDemoToken(
                                cashierCtx, dm.getId(), new BigDecimal("20"), approved2Time);
        seedSale(
                cashierCtx,
                dm.getId(),
                necklaceS.getId(),
                1,
                DiscountType.PERCENT,
                new BigDecimal("15"),
                token2,
                "BKASH",
                null,
                "BKDEMO0024",
                approved2Time,
                "pos-demo-sale-24");
        seedSale(
                cashierCtx,
                dm.getId(),
                jeans32.getId(),
                1,
                DiscountType.PERCENT,
                new BigDecimal("5"),
                null,
                "CARD",
                null,
                "3344",
                now.minus(30, ChronoUnit.MINUTES),
                "pos-demo-sale-25");
    }

    private void seedPromo(
            AccessContext ownerCtx,
            String demoKey,
            String name,
            DiscountType type,
            BigDecimal value,
            Set<UUID> locations,
            Set<UUID> variants) {
        if (promotionRepository.findByDemoKey(demoKey).isPresent()) {
            return;
        }
        com.merqo.promotion.Promotion created =
                promotionService.create(
                        ownerCtx,
                        new PromotionService.PromotionRequest(
                                name,
                                type,
                                value,
                                Scope.POS,
                                false,
                                locations,
                                variants,
                                null,
                                null,
                                "Phase 3 demo promotion",
                                PromotionService.ConflictAction.END_EXISTING));
        created.setDemoKey(demoKey);
        promotionRepository.save(created);
    }

    private String issueDemoToken(
            AccessContext cashierCtx, UUID locationId, BigDecimal ceiling, Instant at) {
        Clock fixed = Clock.fixed(at, BusinessTime.BUSINESS_ZONE);
        return approvalService
                .issueApprovalToken(cashierCtx, locationId, DEMO_PIN, ceiling, fixed)
                .get("approvalToken")
                .toString();
    }

    private Map<String, Object> seedSale(
            AccessContext actorCtx,
            UUID locationId,
            UUID variantId,
            int qty,
            DiscountType manualType,
            BigDecimal manualValue,
            String approvalToken,
            String method,
            BigDecimal tendered,
            String reference,
            Instant saleInstant,
            String demoKey) {
        return saleService
                .createSaleForDemo(
                        actorCtx,
                        new SaleService.CreateSaleCommand(
                                locationId,
                                List.of(
                                        new SaleService.SaleLineCommand(
                                                variantId, qty, manualType, manualValue)),
                                null,
                                null,
                                approvalToken,
                                new SaleService.PaymentCommand(method, tendered, reference),
                                demoKey,
                                null),
                        saleInstant,
                        demoKey)
                .body();
    }

    private void seedReturn(
            AccessContext actorCtx,
            Map<String, Object> saleBody,
            int qty,
            ReturnReason reason,
            ReturnDisposition disposition,
            Instant returnedAt,
            String demoKey) {
        UUID saleId = UUID.fromString(saleBody.get("id").toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) saleBody.get("lines");
        UUID saleLineId = UUID.fromString(lines.get(0).get("id").toString());
        returnService.createReturnForDemo(
                actorCtx,
                saleId,
                new ReturnService.CreateReturnCommand(
                        reason,
                        "Demo return",
                        List.of(new ReturnService.ReturnLineCommand(saleLineId, qty, disposition)),
                        demoKey),
                returnedAt,
                demoKey);
    }

    private void closeDemoSessionIfOpen(AccessContext actorCtx, UUID sessionId, Instant closedAt) {
        CashSession session = cashSessionRepository.findById(sessionId).orElseThrow();
        if (session.getStatus() == CashSessionStatus.CLOSED) {
            return;
        }
        Map<String, Object> summary = cashSessionService.summary(actorCtx, sessionId);
        BigDecimal expectedCash = new BigDecimal(summary.get("expectedCash").toString());
        cashSessionService.closeForDemo(
                actorCtx, sessionId, expectedCash, null, null, null, null, closedAt);
    }
}
