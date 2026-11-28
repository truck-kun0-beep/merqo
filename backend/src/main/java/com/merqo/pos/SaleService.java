package com.merqo.pos;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.merqo.access.AccessContext;
import com.merqo.access.LocationType;
import com.merqo.access.Permission;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.catalog.CatalogService;
import com.merqo.catalog.Product;
import com.merqo.catalog.ProductRepository;
import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.ProductVariantRepository;
import com.merqo.catalog.VariantBarcode;
import com.merqo.catalog.VariantBarcodeRepository;
import com.merqo.catalog.domain.DiscountType;
import com.merqo.catalog.domain.PriceCalculator;
import com.merqo.catalog.domain.Scope;
import com.merqo.catalog.domain.TaxPolicy;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.ForbiddenException;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;
import com.merqo.common.money.Money;
import com.merqo.common.time.BusinessTime;
import com.merqo.common.web.PageResponse;
import com.merqo.inventory.InventoryCommand;
import com.merqo.inventory.InventoryService;
import com.merqo.inventory.domain.MovementType;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.payments.domain.PaymentReferenceGuard;
import com.merqo.pos.domain.JsonReceiptRenderer;
import com.merqo.pos.domain.PaymentMethod;
import com.merqo.pos.domain.PaymentRules;
import com.merqo.pos.domain.PosRuleException;
import com.merqo.pos.domain.ReceiptData;
import com.merqo.pos.domain.ReceiptLineItem;
import com.merqo.pos.domain.ReceiptNumber;
import com.merqo.pos.domain.SaleCalculator;
import com.merqo.pos.domain.SaleTotals;
import com.merqo.promotion.Promotion;
import com.merqo.promotion.PromotionService;
import com.merqo.settings.SettingKey;
import com.merqo.settings.SettingService;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * Application service for POS sales, server-side pricing, idempotency, receipts, and history.
 *
 * <p>Responsibility: orchestrate branch-scoped POS sales in a single ACID transaction — resolving
 * catalog prices and POS promotions server-side, checking discount limits and approval tokens,
 * validating payment rules, deducting available branch stock via {@link InventoryService}, writing
 * append-only sale records, rendering receipts, logging duplicate reprints, and serving scoped sale
 * history and lookups.
 *
 * <p>OOP concept: <b>Facade &amp; Delegation</b> — delegates pricing to {@link SaleCalculator},
 * payment checks to {@link PaymentRules}, receipts to {@link JsonReceiptRenderer}, stock changes to
 * {@link InventoryService}, and approvals to {@link ApprovalService}.
 */
@Service
public class SaleService {

    private static final JsonReceiptRenderer JSON_RECEIPT_RENDERER = new JsonReceiptRenderer();

    private final SaleRepository saleRepository;
    private final SaleLineRepository saleLineRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final ReceiptReprintRepository reprintRepository;
    private final CashSessionService cashSessionService;
    private final ApprovalService approvalService;
    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final VariantBarcodeRepository barcodeRepository;
    private final CatalogService catalogService;
    private final PromotionService promotionService;
    private final SettingService settingService;
    private final InventoryService inventoryService;
    private final LocationRepository locationRepository;
    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final PaymentReferenceGuard paymentReferenceGuard;
    private final Clock clock;
    private final TransactionTemplate requiresNewTemplate;

    public SaleService(
            SaleRepository saleRepository,
            SaleLineRepository saleLineRepository,
            SalePaymentRepository salePaymentRepository,
            ReceiptReprintRepository reprintRepository,
            CashSessionService cashSessionService,
            ApprovalService approvalService,
            ProductVariantRepository variantRepository,
            ProductRepository productRepository,
            VariantBarcodeRepository barcodeRepository,
            CatalogService catalogService,
            PromotionService promotionService,
            SettingService settingService,
            InventoryService inventoryService,
            LocationRepository locationRepository,
            AppUserRepository userRepository,
            AuditService auditService,
            PaymentReferenceGuard paymentReferenceGuard,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.saleRepository = saleRepository;
        this.saleLineRepository = saleLineRepository;
        this.salePaymentRepository = salePaymentRepository;
        this.reprintRepository = reprintRepository;
        this.cashSessionService = cashSessionService;
        this.approvalService = approvalService;
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.barcodeRepository = barcodeRepository;
        this.catalogService = catalogService;
        this.promotionService = promotionService;
        this.settingService = settingService;
        this.inventoryService = inventoryService;
        this.locationRepository = locationRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.paymentReferenceGuard = paymentReferenceGuard;
        this.clock = clock;
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record SaleLineCommand(
            UUID variantId,
            int quantity,
            DiscountType manualDiscountType,
            BigDecimal manualDiscountValue) {}

    public record PaymentCommand(String method, BigDecimal tendered, String reference) {}

    public record CreateSaleCommand(
            UUID locationId,
            List<SaleLineCommand> lines,
            DiscountType billDiscountType,
            BigDecimal billDiscountValue,
            String approvalToken,
            PaymentCommand payment,
            String idempotencyKey,
            String note) {}

    public record SaleCreationOutcome(Map<String, Object> body, boolean created) {}

    /** Completes a POS sale or returns the idempotent replay for the same cashier + key. */
    public SaleCreationOutcome createSale(
            AccessContext context, CreateSaleCommand command, String headerIdempotencyKey) {
        return createSaleInternal(
                context, command, headerIdempotencyKey, Instant.now(clock), null, null);
    }

    /**
     * Internal sale completion helper with an explicit timestamp and demo key for idempotent demo
     * seeding only. Public API endpoints never accept a client timestamp.
     */
    public SaleCreationOutcome createSaleForDemo(
            AccessContext context, CreateSaleCommand command, Instant saleInstant, String demoKey) {
        if (demoKey != null) {
            Optional<Sale> existing = saleRepository.findByDemoKey(demoKey);
            if (existing.isPresent()) {
                return new SaleCreationOutcome(toSaleDetail(context, existing.get()), false);
            }
        }
        return createSaleInternal(
                context,
                command,
                demoKey != null ? demoKey : UUID.randomUUID().toString(),
                saleInstant,
                demoKey,
                Clock.fixed(saleInstant, BusinessTime.BUSINESS_ZONE));
    }

    private SaleCreationOutcome createSaleInternal(
            AccessContext context,
            CreateSaleCommand command,
            String headerIdempotencyKey,
            Instant saleInstant,
            String demoKey,
            Clock overrideClock) {
        if (command == null || command.locationId() == null) {
            throw new ValidationException(ErrorCodes.VALIDATION_FAILED, "Location is required.");
        }
        UUID locationId = command.locationId();
        context.requireInScope(locationId);
        context.require(Permission.POS_SELL, locationId);

        Location location =
                locationRepository
                        .findById(locationId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        if (!location.isActive() || location.getLocationType() != LocationType.BRANCH) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "POS sales are only allowed at active branches.");
        }

        String idempotencyKey =
                resolveIdempotencyKey(headerIdempotencyKey, command.idempotencyKey());
        String requestHash = hashRequest(command);

        Optional<Sale> preExisting =
                saleRepository.findByCashierIdAndIdempotencyKey(
                        context.getUserId(), idempotencyKey);
        if (preExisting.isPresent()) {
            return handleExistingIdempotentSale(context, preExisting.get(), requestHash);
        }

        try {
            Sale createdSale =
                    requiresNewTemplate.execute(
                            status ->
                                    executeCreateSaleTx(
                                            context,
                                            command,
                                            location,
                                            idempotencyKey,
                                            requestHash,
                                            saleInstant,
                                            demoKey,
                                            overrideClock));
            return new SaleCreationOutcome(toSaleDetail(context, createdSale), true);
        } catch (DataIntegrityViolationException exception) {
            if (isConstraint(exception, "ux_sales_idempotency")) {
                Sale winner =
                        saleRepository
                                .findByCashierIdAndIdempotencyKey(
                                        context.getUserId(), idempotencyKey)
                                .orElseThrow(() -> exception);
                return handleExistingIdempotentSale(context, winner, requestHash);
            }
            if (isConstraint(exception, "ux_sale_payments_trx_id")) {
                throw new ConflictException(
                        ErrorCodes.DUPLICATE_REFERENCE,
                        "This payment TrxID has already been used.");
            }
            throw exception;
        }
    }

    private Sale executeCreateSaleTx(
            AccessContext context,
            CreateSaleCommand command,
            Location location,
            String idempotencyKey,
            String requestHash,
            Instant saleInstant,
            String demoKey,
            Clock overrideClock) {
        UUID locationId = location.getId();
        CashSession session =
                cashSessionService.requireOpenSession(context.getUserId(), locationId);

        if (command.lines() == null || command.lines().isEmpty()) {
            throw new PosRuleException(
                    ErrorCodes.EMPTY_CART, 422, "Sale must contain at least one item.");
        }
        if (command.payment() == null) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "Payment details are required.");
        }

        BigDecimal globalTaxRate =
                settingService.get(SettingKey.TAX_RATE_PERCENT, BigDecimal.class);
        boolean taxInclusive = settingService.get(SettingKey.TAX_INCLUSIVE, Boolean.class);

        List<ResolvedLine> resolvedLines = new ArrayList<>(command.lines().size());
        List<SaleCalculator.SaleLineInput> calcInputs = new ArrayList<>(command.lines().size());

        for (SaleLineCommand lineCmd : command.lines()) {
            if (lineCmd.variantId() == null) {
                throw new ValidationException(
                        ErrorCodes.VALIDATION_FAILED, "Each line requires a variantId.");
            }
            ProductVariant variant =
                    variantRepository
                            .findById(lineCmd.variantId())
                            .orElseThrow(
                                    () ->
                                            new NotFoundException(
                                                    "The requested record was not found."));
            Product product =
                    productRepository
                            .findById(variant.getProductId())
                            .orElseThrow(
                                    () ->
                                            new NotFoundException(
                                                    "The requested record was not found."));
            if (!variant.isActive() || !product.isActive()) {
                throw new ValidationException(
                        ErrorCodes.VALIDATION_FAILED,
                        "Variant " + variant.getSku() + " is inactive and cannot be sold.");
            }

            Money listPrice = Money.of(variant.getSellingPrice());
            Promotion promo =
                    promotionService
                            .resolveAt(variant.getId(), Scope.POS, locationId, saleInstant)
                            .orElse(null);
            Money promoDiscountPerUnit = discountOf(promo, listPrice);

            BigDecimal cost = catalogService.currentCost(variant.getId());
            Money costMoney = cost == null ? null : Money.of(cost);
            BigDecimal taxRate =
                    TaxPolicy.effectiveRate(product.getTaxRatePercent(), globalTaxRate);
            String primaryBarcode =
                    barcodeRepository
                            .findFirstByVariantIdAndKind(
                                    variant.getId(), VariantBarcode.Kind.PRIMARY)
                            .map(VariantBarcode::getBarcode)
                            .orElse(null);

            resolvedLines.add(
                    new ResolvedLine(lineCmd, variant, product, primaryBarcode, promo, cost));
            calcInputs.add(
                    new SaleCalculator.SaleLineInput(
                            variant.getId(),
                            listPrice,
                            promoDiscountPerUnit,
                            lineCmd.quantity(),
                            lineCmd.manualDiscountType(),
                            lineCmd.manualDiscountValue(),
                            taxRate,
                            taxInclusive,
                            costMoney));
        }

        SaleTotals totals =
                SaleCalculator.calculate(
                        calcInputs, command.billDiscountType(), command.billDiscountValue());

        UUID discountApprovedBy = null;
        UUID approvalTokenId = null;
        boolean hasAnyManualDiscount = !totals.manualDiscount().isZero();
        if (hasAnyManualDiscount) {
            boolean hasDiscountPerm = context.can(Permission.POS_DISCOUNT, locationId);
            boolean hasApprovePerm = context.can(Permission.POS_DISCOUNT_APPROVE, locationId);
            if (!hasDiscountPerm && !hasApprovePerm) {
                throw new ForbiddenException(
                        ErrorCodes.FORBIDDEN,
                        "You do not have the permission POS_DISCOUNT at this location.");
            }
            if (!hasApprovePerm) {
                AppUser cashier =
                        userRepository
                                .findById(context.getUserId())
                                .orElseThrow(
                                        () ->
                                                new NotFoundException(
                                                        "The requested record was not found."));
                BigDecimal limit =
                        cashier.getMaxDiscountPercent() == null
                                ? BigDecimal.ZERO
                                : cashier.getMaxDiscountPercent();
                if (totals.maxEffectiveManualDiscountPercent().compareTo(limit) > 0) {
                    if (command.approvalToken() == null || command.approvalToken().isBlank()) {
                        throw new PosRuleException(
                                ErrorCodes.DISCOUNT_LIMIT_EXCEEDED,
                                422,
                                "Manual discount exceeds your personal limit of "
                                        + limit
                                        + "% and requires manager approval.");
                    }
                    ApprovalToken consumed =
                            approvalService.consumeToken(
                                    command.approvalToken(),
                                    context.getUserId(),
                                    locationId,
                                    totals.maxEffectiveManualDiscountPercent(),
                                    overrideClock);
                    discountApprovedBy = consumed.getApproverId();
                    approvalTokenId = consumed.getId();
                }
            }
        }

        PaymentMethod method = PaymentMethod.fromCode(command.payment().method());
        Money tendered =
                command.payment().tendered() == null
                        ? null
                        : Money.of(command.payment().tendered());
        PaymentRules.ValidatedPayment validatedPayment =
                PaymentRules.validate(
                        method, totals.total(), tendered, command.payment().reference());
        paymentReferenceGuard.requireUniqueTrxId(
                validatedPayment.method().name(), validatedPayment.normalizedReference());

        UUID saleId = UUID.randomUUID();
        long receiptSeq = saleRepository.nextReceiptNumberSeq();
        String receiptNumber = ReceiptNumber.format(saleInstant, receiptSeq);

        // Aggregate and sort by variantId before locking inventory rows to prevent deadlocks.
        Map<UUID, Integer> qtyByVariant = new LinkedHashMap<>();
        Map<UUID, ResolvedLine> lineByVariant = new LinkedHashMap<>();
        for (ResolvedLine rl : resolvedLines) {
            qtyByVariant.merge(rl.variant().getId(), rl.command().quantity(), Integer::sum);
            lineByVariant.putIfAbsent(rl.variant().getId(), rl);
        }
        List<UUID> sortedVariants = new ArrayList<>(qtyByVariant.keySet());
        sortedVariants.sort(Comparator.naturalOrder());

        for (UUID variantId : sortedVariants) {
            ResolvedLine rl = lineByVariant.get(variantId);
            int qty = qtyByVariant.get(variantId);
            try {
                inventoryService.remove(
                        new InventoryCommand(
                                locationId,
                                variantId,
                                -qty,
                                MovementType.POS_SALE,
                                "SALE",
                                saleId,
                                null,
                                receiptNumber,
                                rl.unitCost(),
                                context.getUserId(),
                                null));
            } catch (ConflictException ex) {
                throw new ConflictException(
                        ErrorCodes.INSUFFICIENT_STOCK,
                        "Insufficient stock for SKU "
                                + rl.variant().getSku()
                                + ": "
                                + ex.getMessage());
            }
        }

        Sale sale =
                new Sale(
                        saleId,
                        receiptNumber,
                        locationId,
                        session.getId(),
                        context.getUserId(),
                        totals.subtotal().amount(),
                        totals.promotionDiscount().amount(),
                        totals.manualDiscount().amount(),
                        command.billDiscountType(),
                        command.billDiscountValue() == null
                                ? null
                                : Money.of(command.billDiscountValue()).amount(),
                        totals.tax().amount(),
                        totals.total().amount(),
                        validatedPayment.method(),
                        discountApprovedBy,
                        approvalTokenId,
                        idempotencyKey,
                        requestHash,
                        trimToNull(command.note()),
                        demoKey,
                        saleInstant);
        saleRepository.saveAndFlush(sale);

        for (int i = 0; i < resolvedLines.size(); i++) {
            ResolvedLine rl = resolvedLines.get(i);
            SaleTotals.ComputedLine cl = totals.lines().get(i);
            UUID appliedBy = cl.totalManualDiscount().isZero() ? null : context.getUserId();
            saleLineRepository.save(
                    new SaleLine(
                            UUID.randomUUID(),
                            saleId,
                            i + 1,
                            rl.variant().getId(),
                            rl.product().getName(),
                            rl.variant().getSku(),
                            rl.primaryBarcode(),
                            rl.variant().getColor(),
                            rl.variant().getSize(),
                            rl.command().quantity(),
                            cl.pricing().listPrice().amount(),
                            rl.promotion() == null ? null : rl.promotion().getId(),
                            rl.promotion() == null ? null : rl.promotion().getName(),
                            cl.pricing().promotionDiscount().amount(),
                            rl.command().manualDiscountType(),
                            rl.command().manualDiscountValue(),
                            cl.totalManualDiscount().amount(),
                            cl.billDiscountAllocated().amount(),
                            appliedBy,
                            cl.taxRate(),
                            cl.taxInclusive(),
                            cl.tax().amount(),
                            cl.netUnitPrice().amount(),
                            cl.lineSubtotal().amount(),
                            cl.lineTotal().amount(),
                            rl.unitCost(),
                            cl.belowCost()));
        }

        salePaymentRepository.saveAndFlush(
                new SalePayment(
                        UUID.randomUUID(),
                        saleId,
                        validatedPayment.method(),
                        validatedPayment.amount().amount(),
                        validatedPayment.tendered().amount(),
                        validatedPayment.changeGiven().amount(),
                        validatedPayment.normalizedReference(),
                        saleInstant));

        Map<String, Object> auditValues = new LinkedHashMap<>();
        auditValues.put("receiptNumber", receiptNumber);
        auditValues.put("total", totals.total().amount().toPlainString());
        auditValues.put("paymentMethod", validatedPayment.method().name());
        auditValues.put("belowCost", totals.anyBelowCost());
        auditService.record(
                new AuditEvent(
                        AuditAction.POS_SALE_COMPLETED,
                        "SALE",
                        saleId,
                        locationId,
                        context.getUserId(),
                        context.getUsername(),
                        null,
                        auditValues,
                        null,
                        null));

        return sale;
    }

    /** Returns the original receipt JSON without logging a reprint row. */
    @Transactional(readOnly = true)
    public Map<String, Object> getReceipt(AccessContext context, UUID saleId) {
        Sale sale = loadSaleInScope(context, saleId);
        return JSON_RECEIPT_RENDERER.render(buildReceiptData(sale, false));
    }

    /**
     * Logs a duplicate receipt reprint, audits it, and returns receipt JSON with {@code
     * isDuplicate: true}.
     */
    @Transactional
    public Map<String, Object> reprintReceipt(AccessContext context, UUID saleId) {
        Sale sale = loadSaleInScope(context, saleId);
        context.require(Permission.POS_REPRINT, sale.getLocationId());

        Instant now = Instant.now(clock);
        ReceiptReprint reprint =
                new ReceiptReprint(UUID.randomUUID(), sale.getId(), context.getUserId(), now);
        reprintRepository.saveAndFlush(reprint);
        long reprintCount = reprintRepository.countBySaleId(sale.getId());

        auditService.record(
                new AuditEvent(
                        AuditAction.RECEIPT_REPRINTED,
                        "SALE",
                        sale.getId(),
                        sale.getLocationId(),
                        context.getUserId(),
                        context.getUsername(),
                        null,
                        Map.of(
                                "receiptNumber",
                                sale.getReceiptNumber(),
                                "reprintCount",
                                reprintCount),
                        null,
                        null));

        Map<String, Object> rendered =
                new LinkedHashMap<>(JSON_RECEIPT_RENDERER.render(buildReceiptData(sale, true)));
        rendered.put("reprintCount", reprintCount);
        return rendered;
    }

    /**
     * Lists sales history filtered by location, Dhaka-day range, and receipt search, respecting
     * scope.
     */
    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> listSales(
            AccessContext context,
            UUID locationId,
            LocalDate fromDate,
            LocalDate toDate,
            String q,
            Pageable pageable) {
        Set<UUID> visibleLocations = visibleLocationsForHistory(context);
        if (locationId != null) {
            if (!visibleLocations.contains(locationId)) {
                throw new NotFoundException("The requested record was not found.");
            }
            visibleLocations = Set.of(locationId);
        }
        if (visibleLocations.isEmpty()) {
            return PageResponse.of(Page.empty(pageable));
        }

        boolean onlyCashier =
                !context.isOwner()
                        && visibleLocations.stream()
                                .noneMatch(
                                        loc ->
                                                context.can(Permission.POS_REFUND, loc)
                                                        || context.can(
                                                                Permission.POS_DISCOUNT_APPROVE,
                                                                loc));

        Instant fromInstant =
                fromDate == null ? Instant.EPOCH : BusinessTime.startOfBusinessDay(fromDate);
        Instant toInstant = toDate == null ? Instant.EPOCH : BusinessTime.endOfBusinessDay(toDate);

        Page<Sale> page =
                saleRepository.search(
                        visibleLocations,
                        onlyCashier,
                        context.getUserId(),
                        fromDate != null,
                        fromInstant,
                        toDate != null,
                        toInstant,
                        trimToNull(q),
                        pageable);

        List<Map<String, Object>> items = new ArrayList<>(page.getContent().size());
        for (Sale sale : page.getContent()) {
            items.add(toSaleSummary(sale));
        }
        return PageResponse.of(new PageImpl<>(items, page.getPageable(), page.getTotalElements()));
    }

    /** Fetches a single sale by UUID with owner-aware branch scope checks. */
    @Transactional(readOnly = true)
    public Map<String, Object> getSale(AccessContext context, UUID saleId) {
        Sale sale = loadSaleInScope(context, saleId);
        return toSaleDetail(context, sale);
    }

    /**
     * Looks up a single sale by exact receipt number (normalized to upper case) in caller scope.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> lookupByReceipt(AccessContext context, String receiptNumber) {
        String normalized = ReceiptNumber.normalize(receiptNumber);
        if (normalized.isEmpty()) {
            throw new NotFoundException("The requested record was not found.");
        }
        Set<UUID> visibleLocations = visibleLocationsForHistory(context);
        if (visibleLocations.isEmpty()) {
            throw new NotFoundException("The requested record was not found.");
        }
        Sale sale =
                saleRepository
                        .findByReceiptNumberIgnoreCaseAndLocationIdIn(normalized, visibleLocations)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        enforceOwnerAwareRead(context, sale);
        return toSaleDetail(context, sale);
    }

    /** Loads a sale if it lies inside caller scope and passes owner-aware cashier visibility. */
    public Sale loadSaleInScope(AccessContext context, UUID saleId) {
        Set<UUID> visibleLocations = visibleLocationsForHistory(context);
        if (visibleLocations.isEmpty()) {
            throw new NotFoundException("The requested record was not found.");
        }
        Sale sale =
                saleRepository
                        .findByIdAndLocationIdIn(saleId, visibleLocations)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        enforceOwnerAwareRead(context, sale);
        return sale;
    }

    private void enforceOwnerAwareRead(AccessContext context, Sale sale) {
        UUID locId = sale.getLocationId();
        boolean hasAnyPosReadPerm =
                context.isOwner()
                        || context.can(Permission.POS_SELL, locId)
                        || context.can(Permission.POS_REPRINT, locId)
                        || context.can(Permission.POS_REFUND, locId)
                        || context.can(Permission.POS_DISCOUNT_APPROVE, locId);
        if (!hasAnyPosReadPerm) {
            throw new ForbiddenException(
                    ErrorCodes.FORBIDDEN,
                    "You do not have permission to view POS sales at this location.");
        }
        boolean canViewOthers =
                context.isOwner()
                        || context.can(Permission.POS_REFUND, locId)
                        || context.can(Permission.POS_DISCOUNT_APPROVE, locId);
        if (!canViewOthers && !sale.getCashierId().equals(context.getUserId())) {
            throw new NotFoundException("The requested record was not found.");
        }
    }

    private Set<UUID> visibleLocationsForHistory(AccessContext context) {
        if (context.isOwner()) {
            return new LinkedHashSet<>(locationRepository.findAllIds());
        }
        Set<UUID> allowed = new LinkedHashSet<>();
        for (UUID locId : context.getScopeLocationIds()) {
            if (context.can(Permission.POS_SELL, locId)
                    || context.can(Permission.POS_REPRINT, locId)
                    || context.can(Permission.POS_REFUND, locId)
                    || context.can(Permission.POS_DISCOUNT_APPROVE, locId)) {
                allowed.add(locId);
            }
        }
        return allowed;
    }

    private Map<String, Object> toSaleSummary(Sale sale) {
        Location location = locationRepository.findById(sale.getLocationId()).orElse(null);
        String cashierUsername =
                userRepository
                        .findById(sale.getCashierId())
                        .map(AppUser::getUsername)
                        .orElse("unknown");
        List<SaleLine> lines = saleLineRepository.findBySaleIdOrderByLineIndexAsc(sale.getId());
        int itemCount = lines.stream().mapToInt(SaleLine::getQuantity).sum();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", sale.getId());
        map.put("receiptNumber", sale.getReceiptNumber());
        map.put("locationId", sale.getLocationId());
        map.put("locationName", location == null ? "" : location.getName());
        map.put("cashierUsername", cashierUsername);
        map.put("createdAt", sale.getCreatedAt().toString());
        map.put("itemCount", itemCount);
        map.put("total", Money.of(sale.getTotal()).amount().toPlainString());
        map.put("paymentMethod", sale.getPaymentMethod().name());
        return map;
    }

    public Map<String, Object> toSaleDetail(AccessContext context, Sale sale) {
        boolean canViewCost = context.can(Permission.COST_VIEW, sale.getLocationId());
        Location location = locationRepository.findById(sale.getLocationId()).orElse(null);
        String cashierUsername =
                userRepository
                        .findById(sale.getCashierId())
                        .map(AppUser::getUsername)
                        .orElse("unknown");
        List<SaleLine> lines = saleLineRepository.findBySaleIdOrderByLineIndexAsc(sale.getId());
        SalePayment payment =
                salePaymentRepository
                        .findBySaleId(sale.getId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Missing payment row for sale " + sale.getId()));

        List<Map<String, Object>> lineMaps = new ArrayList<>(lines.size());
        List<Map<String, Object>> belowCostWarnings = new ArrayList<>();
        for (SaleLine line : lines) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", line.getId());
            lm.put("lineIndex", line.getLineIndex());
            lm.put("variantId", line.getVariantId());
            lm.put("productName", line.getProductNameSnapshot());
            lm.put("sku", line.getSkuSnapshot());
            lm.put("barcode", line.getBarcodeSnapshot());
            lm.put("color", line.getColorSnapshot());
            lm.put("size", line.getSizeSnapshot());
            lm.put("quantity", line.getQuantity());
            lm.put("listPrice", Money.of(line.getListPrice()).amount().toPlainString());
            lm.put("promotionName", line.getPromotionNameSnapshot());
            lm.put(
                    "promotionDiscount",
                    Money.of(line.getPromotionDiscount()).amount().toPlainString());
            lm.put("manualDiscount", Money.of(line.getManualDiscount()).amount().toPlainString());
            lm.put(
                    "billDiscountAllocated",
                    Money.of(line.getBillDiscountAllocated()).amount().toPlainString());
            lm.put("netUnitPrice", Money.of(line.getNetUnitPrice()).amount().toPlainString());
            lm.put("taxRatePercent", line.getTaxRatePercent().toPlainString());
            lm.put("taxInclusive", line.isTaxInclusive());
            lm.put("taxAmount", Money.of(line.getTaxAmount()).amount().toPlainString());
            lm.put("lineSubtotal", Money.of(line.getLineSubtotal()).amount().toPlainString());
            lm.put("lineTotal", Money.of(line.getLineTotal()).amount().toPlainString());
            if (canViewCost) {
                lm.put(
                        "unitCost",
                        line.getUnitCostSnapshot() == null
                                ? null
                                : Money.of(line.getUnitCostSnapshot()).amount().toPlainString());
                lm.put("belowCost", line.isBelowCost());
                if (line.isBelowCost()) {
                    Map<String, Object> warning = new LinkedHashMap<>();
                    warning.put("variantId", line.getVariantId());
                    warning.put("sku", line.getSkuSnapshot());
                    warning.put(
                            "netUnitPrice",
                            Money.of(line.getNetUnitPrice()).amount().toPlainString());
                    warning.put(
                            "costPrice",
                            line.getUnitCostSnapshot() == null
                                    ? null
                                    : Money.of(line.getUnitCostSnapshot())
                                            .amount()
                                            .toPlainString());
                    belowCostWarnings.add(warning);
                }
            }
            lineMaps.add(lm);
        }

        Map<String, Object> paymentMap = new LinkedHashMap<>();
        paymentMap.put("method", payment.getMethod().name());
        paymentMap.put("amount", Money.of(payment.getAmount()).amount().toPlainString());
        paymentMap.put(
                "tendered",
                payment.getTendered() == null
                        ? null
                        : Money.of(payment.getTendered()).amount().toPlainString());
        paymentMap.put("changeGiven", Money.of(payment.getChangeGiven()).amount().toPlainString());
        paymentMap.put("reference", payment.getReference());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", sale.getId());
        detail.put("receiptNumber", sale.getReceiptNumber());
        detail.put("locationId", sale.getLocationId());
        detail.put("locationName", location == null ? "" : location.getName());
        detail.put("cashSessionId", sale.getCashSessionId());
        detail.put("cashierId", sale.getCashierId());
        detail.put("cashierUsername", cashierUsername);
        detail.put("createdAt", sale.getCreatedAt().toString());
        detail.put("subtotal", Money.of(sale.getSubtotal()).amount().toPlainString());
        detail.put(
                "promotionDiscount",
                Money.of(sale.getPromotionDiscount()).amount().toPlainString());
        detail.put("manualDiscount", Money.of(sale.getManualDiscount()).amount().toPlainString());
        detail.put("taxTotal", Money.of(sale.getTaxTotal()).amount().toPlainString());
        detail.put("total", Money.of(sale.getTotal()).amount().toPlainString());
        detail.put("paymentMethod", sale.getPaymentMethod().name());
        detail.put("changeGiven", Money.of(payment.getChangeGiven()).amount().toPlainString());
        detail.put("payment", paymentMap);
        detail.put("lines", lineMaps);
        detail.put("note", sale.getNote());
        detail.put(
                "receipt",
                JSON_RECEIPT_RENDERER.render(
                        buildReceiptData(sale, lines, payment, location, cashierUsername, false)));
        if (canViewCost) {
            detail.put("belowCostWarnings", belowCostWarnings);
        }
        return detail;
    }

    private ReceiptData buildReceiptData(Sale sale, boolean isDuplicate) {
        Location location = locationRepository.findById(sale.getLocationId()).orElse(null);
        String cashierName =
                userRepository
                        .findById(sale.getCashierId())
                        .map(AppUser::getFullName)
                        .orElse("Staff");
        List<SaleLine> lines = saleLineRepository.findBySaleIdOrderByLineIndexAsc(sale.getId());
        SalePayment payment =
                salePaymentRepository
                        .findBySaleId(sale.getId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Missing payment row for sale " + sale.getId()));
        return buildReceiptData(sale, lines, payment, location, cashierName, isDuplicate);
    }

    private ReceiptData buildReceiptData(
            Sale sale,
            List<SaleLine> lines,
            SalePayment payment,
            Location location,
            String cashierName,
            boolean isDuplicate) {
        String businessName = settingService.getString(SettingKey.BUSINESS_NAME);
        String footerText = trimToNull(settingService.getString(SettingKey.RECEIPT_FOOTER));
        List<ReceiptLineItem> receiptLines = new ArrayList<>(lines.size());
        for (SaleLine line : lines) {
            String variantLabel =
                    formatVariantLabel(line.getColorSnapshot(), line.getSizeSnapshot());
            Money linePromoTotal =
                    Money.of(line.getPromotionDiscount())
                            .multipliedBy(BigDecimal.valueOf(line.getQuantity()));
            Money lineTotalDiscount = linePromoTotal.plus(Money.of(line.getManualDiscount()));
            receiptLines.add(
                    new ReceiptLineItem(
                            line.getProductNameSnapshot(),
                            variantLabel,
                            line.getQuantity(),
                            Money.of(line.getListPrice()),
                            lineTotalDiscount,
                            Money.of(line.getLineTotal())));
        }
        Money totalDiscount =
                Money.of(sale.getPromotionDiscount()).plus(Money.of(sale.getManualDiscount()));
        return new ReceiptData(
                businessName,
                location == null ? "" : location.getName(),
                location == null ? null : location.getAddress(),
                location == null ? null : location.getPhone(),
                sale.getReceiptNumber(),
                sale.getCreatedAt(),
                cashierName,
                receiptLines,
                Money.of(sale.getSubtotal()),
                totalDiscount,
                Money.of(sale.getTaxTotal()),
                Money.of(sale.getTotal()),
                payment.getMethod(),
                payment.getReference(),
                footerText,
                isDuplicate);
    }

    private SaleCreationOutcome handleExistingIdempotentSale(
            AccessContext context, Sale existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ConflictException(
                    ErrorCodes.IDEMPOTENCY_CONFLICT,
                    "This Idempotency-Key was already used with a different request payload.");
        }
        return new SaleCreationOutcome(toSaleDetail(context, existing), false);
    }

    private static String resolveIdempotencyKey(String headerKey, String bodyKey) {
        String h = trimToNull(headerKey);
        if (h != null) {
            return h;
        }
        String b = trimToNull(bodyKey);
        if (b != null) {
            return b;
        }
        return UUID.randomUUID().toString();
    }

    private static String hashRequest(CreateSaleCommand command) {
        StringBuilder sb = new StringBuilder();
        sb.append(command.locationId()).append('|');
        sb.append(command.billDiscountType()).append('|');
        sb.append(normDecimal(command.billDiscountValue())).append('|');
        sb.append(trimToNull(command.approvalToken())).append('|');
        sb.append(trimToNull(command.note())).append('|');
        if (command.payment() != null) {
            sb.append(trimToNull(command.payment().method())).append('|');
            sb.append(normDecimal(command.payment().tendered())).append('|');
            sb.append(trimToNull(command.payment().reference())).append('|');
        }
        if (command.lines() != null) {
            for (SaleLineCommand line : command.lines()) {
                sb.append(line.variantId())
                        .append(':')
                        .append(line.quantity())
                        .append(':')
                        .append(line.manualDiscountType())
                        .append(':')
                        .append(normDecimal(line.manualDiscountValue()))
                        .append(';');
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String normDecimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static Money discountOf(Promotion promotion, Money listPrice) {
        if (promotion == null) {
            return null;
        }
        if (promotion.getDiscountType() == DiscountType.PERCENT) {
            return PriceCalculator.percentDiscount(listPrice, promotion.getValue());
        }
        return PriceCalculator.fixedDiscount(listPrice, Money.of(promotion.getValue()));
    }

    private static String formatVariantLabel(String color, String size) {
        if (color != null && !color.isBlank() && size != null && !size.isBlank()) {
            return color.trim() + " / " + size.trim();
        }
        if (color != null && !color.isBlank()) {
            return color.trim();
        }
        if (size != null && !size.isBlank()) {
            return size.trim();
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isConstraint(Throwable throwable, String constraintName) {
        Throwable cur = throwable;
        while (cur != null) {
            if (cur.getMessage() != null && cur.getMessage().contains(constraintName)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private record ResolvedLine(
            SaleLineCommand command,
            ProductVariant variant,
            Product product,
            String primaryBarcode,
            Promotion promotion,
            BigDecimal unitCost) {}
}
