package com.merqo.pos;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.catalog.CatalogService;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;
import com.merqo.common.money.Money;
import com.merqo.common.time.BusinessTime;
import com.merqo.inventory.InventoryCommand;
import com.merqo.inventory.InventoryService;
import com.merqo.inventory.domain.MovementType;
import com.merqo.location.LocationRepository;
import com.merqo.pos.domain.ReturnRules;
import com.merqo.settings.SettingKey;
import com.merqo.settings.SettingService;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * Application service for POS returns and refunds.
 *
 * <p>Responsibility: enforce return window and permission rules, lock the parent sale against
 * concurrent returns, calculate drift-free refund amounts via {@link ReturnRules}, refund the
 * original payment method (requiring an open cash session for cash refunds), apply {@code RESTOCK}
 * or {@code INSPECTION} stock dispositions via {@link InventoryService}, enforce idempotency, and
 * write append-only return and audit records.
 *
 * <p>OOP concept: <b>Facade &amp; Delegation</b> — delegates refund math to {@link ReturnRules} and
 * stock changes to {@link InventoryService}.
 */
@Service
public class ReturnService {

    private final SaleRepository saleRepository;
    private final SaleLineRepository saleLineRepository;
    private final SaleReturnRepository returnRepository;
    private final SaleReturnLineRepository returnLineRepository;
    private final CashSessionRepository cashSessionRepository;
    private final CashSessionService cashSessionService;
    private final InventoryService inventoryService;
    private final CatalogService catalogService;
    private final SettingService settingService;
    private final LocationRepository locationRepository;
    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final Clock clock;
    private final TransactionTemplate requiresNewTemplate;

    public ReturnService(
            SaleRepository saleRepository,
            SaleLineRepository saleLineRepository,
            SaleReturnRepository returnRepository,
            SaleReturnLineRepository returnLineRepository,
            CashSessionRepository cashSessionRepository,
            CashSessionService cashSessionService,
            InventoryService inventoryService,
            CatalogService catalogService,
            SettingService settingService,
            LocationRepository locationRepository,
            AppUserRepository userRepository,
            AuditService auditService,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.saleRepository = saleRepository;
        this.saleLineRepository = saleLineRepository;
        this.returnRepository = returnRepository;
        this.returnLineRepository = returnLineRepository;
        this.cashSessionRepository = cashSessionRepository;
        this.cashSessionService = cashSessionService;
        this.inventoryService = inventoryService;
        this.catalogService = catalogService;
        this.settingService = settingService;
        this.locationRepository = locationRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record ReturnLineCommand(UUID saleLineId, int quantity, ReturnDisposition disposition) {}

    public record CreateReturnCommand(
            ReturnReason reason,
            String note,
            List<ReturnLineCommand> lines,
            String idempotencyKey) {}

    public record ReturnCreationOutcome(Map<String, Object> body, boolean created) {}

    /** Processes a POS return against a completed sale. */
    public ReturnCreationOutcome createReturn(
            AccessContext context,
            UUID saleId,
            CreateReturnCommand command,
            String headerIdempotencyKey) {
        return createReturnInternal(
                context, saleId, command, headerIdempotencyKey, Instant.now(clock), null);
    }

    /**
     * Internal return helper with explicit timestamp and demo key for idempotent demo seeding only.
     * Public endpoints never accept client timestamps.
     */
    public ReturnCreationOutcome createReturnForDemo(
            AccessContext context,
            UUID saleId,
            CreateReturnCommand command,
            Instant returnedAt,
            String demoKey) {
        if (demoKey != null) {
            Optional<SaleReturn> existing = returnRepository.findByDemoKey(demoKey);
            if (existing.isPresent()) {
                return new ReturnCreationOutcome(toReturnMap(existing.get()), false);
            }
        }
        return createReturnInternal(
                context,
                saleId,
                command,
                demoKey != null ? demoKey : UUID.randomUUID().toString(),
                returnedAt,
                demoKey);
    }

    private ReturnCreationOutcome createReturnInternal(
            AccessContext context,
            UUID saleId,
            CreateReturnCommand command,
            String headerIdempotencyKey,
            Instant returnedAt,
            String demoKey) {
        if (command == null || command.reason() == null) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "Return reason is required.");
        }
        String trimmedNote = trimToNull(command.note());
        if (command.reason() == ReturnReason.OTHER && trimmedNote == null) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "A note is required when return reason is OTHER.");
        }
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "Return must include at least one line.");
        }

        // Load sale in caller's scope before running idempotency or transaction.
        Set<UUID> visibleLocations =
                context.isOwner()
                        ? new LinkedHashSet<>(locationRepository.findAllIds())
                        : context.getScopeLocationIds();
        if (visibleLocations.isEmpty()) {
            throw new NotFoundException("The requested record was not found.");
        }
        Sale saleCheck =
                saleRepository
                        .findByIdAndLocationIdIn(saleId, visibleLocations)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        context.require(Permission.POS_REFUND, saleCheck.getLocationId());

        String idempotencyKey =
                resolveIdempotencyKey(headerIdempotencyKey, command.idempotencyKey());
        String requestHash = hashRequest(saleId, command);

        Optional<SaleReturn> preExisting =
                returnRepository.findByCreatedByAndIdempotencyKey(
                        context.getUserId(), idempotencyKey);
        if (preExisting.isPresent()) {
            return handleExistingIdempotentReturn(preExisting.get(), requestHash);
        }

        try {
            SaleReturn created =
                    requiresNewTemplate.execute(
                            status ->
                                    executeCreateReturnTx(
                                            context,
                                            saleId,
                                            command,
                                            trimmedNote,
                                            idempotencyKey,
                                            requestHash,
                                            returnedAt,
                                            demoKey));
            return new ReturnCreationOutcome(toReturnMap(created), true);
        } catch (DataIntegrityViolationException exception) {
            if (isConstraint(exception, "ux_sale_returns_idempotency")) {
                SaleReturn winner =
                        returnRepository
                                .findByCreatedByAndIdempotencyKey(
                                        context.getUserId(), idempotencyKey)
                                .orElseThrow(() -> exception);
                return handleExistingIdempotentReturn(winner, requestHash);
            }
            throw exception;
        }
    }

    private SaleReturn executeCreateReturnTx(
            AccessContext context,
            UUID saleId,
            CreateReturnCommand command,
            String trimmedNote,
            String idempotencyKey,
            String requestHash,
            Instant returnedAt,
            String demoKey) {
        // Lock the parent sale row to serialize concurrent return attempts on the same sale.
        Sale sale =
                saleRepository
                        .findByIdForUpdate(saleId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));

        // Return window check (Dhaka calendar days), overridable with POS_DISCOUNT_APPROVE.
        int windowDays = settingService.get(SettingKey.POS_RETURN_WINDOW_DAYS, Integer.class);
        LocalDate saleDay = BusinessTime.toBusinessDate(sale.getCreatedAt());
        LocalDate returnDay = BusinessTime.toBusinessDate(returnedAt);
        long daysSinceSale = ChronoUnit.DAYS.between(saleDay, returnDay);
        if (daysSinceSale > windowDays
                && !context.can(Permission.POS_DISCOUNT_APPROVE, sale.getLocationId())) {
            throw new ConflictException(
                    ErrorCodes.RETURN_WINDOW_EXPIRED,
                    "Sale is outside the "
                            + windowDays
                            + "-day return window and requires override permission.");
        }

        UUID cashSessionId = null;
        if (sale.getPaymentMethod().affectsCashDrawer()) {
            CashSession openSession =
                    cashSessionService.requireOpenSession(
                            context.getUserId(), sale.getLocationId());
            cashSessionId = openSession.getId();
        } else {
            cashSessionId =
                    cashSessionRepository
                            .findByCashierIdAndLocationIdAndStatus(
                                    context.getUserId(),
                                    sale.getLocationId(),
                                    CashSessionStatus.OPEN)
                            .map(CashSession::getId)
                            .orElse(null);
        }

        List<SaleLine> saleLines = saleLineRepository.findBySaleIdOrderByLineIndexAsc(sale.getId());
        Map<UUID, SaleLine> saleLineById = new LinkedHashMap<>();
        for (SaleLine sl : saleLines) {
            saleLineById.put(sl.getId(), sl);
        }

        Set<UUID> seenLineIds = new LinkedHashSet<>();
        List<PreparedReturnLine> prepared = new ArrayList<>(command.lines().size());
        Money totalRefund = Money.ZERO;

        for (ReturnLineCommand lineCmd : command.lines()) {
            if (lineCmd.saleLineId() == null) {
                throw new ValidationException(
                        ErrorCodes.VALIDATION_FAILED, "Each return line requires a saleLineId.");
            }
            if (!seenLineIds.add(lineCmd.saleLineId())) {
                throw new ValidationException(
                        ErrorCodes.VALIDATION_FAILED,
                        "Duplicate saleLineId in return request: " + lineCmd.saleLineId());
            }
            SaleLine saleLine = saleLineById.get(lineCmd.saleLineId());
            if (saleLine == null) {
                throw new NotFoundException("The requested record was not found.");
            }
            ReturnDisposition disposition =
                    lineCmd.disposition() == null
                            ? ReturnDisposition.RESTOCK
                            : lineCmd.disposition();

            List<SaleReturnLine> priorLines =
                    returnLineRepository.findBySaleLineId(saleLine.getId());
            int alreadyReturnedQty = 0;
            Money alreadyRefunded = Money.ZERO;
            for (SaleReturnLine prior : priorLines) {
                alreadyReturnedQty += prior.getQuantity();
                alreadyRefunded = alreadyRefunded.plus(Money.of(prior.getRefundAmount()));
            }

            Money lineRefund =
                    ReturnRules.computeLineRefund(
                            saleLine.getQuantity(),
                            alreadyReturnedQty,
                            lineCmd.quantity(),
                            Money.of(saleLine.getLineTotal()),
                            alreadyRefunded);

            prepared.add(
                    new PreparedReturnLine(saleLine, lineCmd.quantity(), lineRefund, disposition));
            totalRefund = totalRefund.plus(lineRefund);
        }

        UUID returnId = UUID.randomUUID();
        long seq = returnRepository.nextReturnNumberSeq();
        String returnNumber = "RET-%06d".formatted(seq);

        SaleReturn saleReturn =
                new SaleReturn(
                        returnId,
                        returnNumber,
                        sale.getId(),
                        sale.getLocationId(),
                        command.reason(),
                        trimmedNote,
                        sale.getPaymentMethod(),
                        totalRefund.amount(),
                        cashSessionId,
                        idempotencyKey,
                        requestHash,
                        context.getUserId(),
                        returnedAt,
                        demoKey);
        returnRepository.saveAndFlush(saleReturn);

        for (PreparedReturnLine line : prepared) {
            returnLineRepository.save(
                    new SaleReturnLine(
                            UUID.randomUUID(),
                            returnId,
                            line.saleLine().getId(),
                            line.saleLine().getVariantId(),
                            line.quantity(),
                            line.refundAmount().amount(),
                            line.disposition()));
        }

        // Apply stock changes sorted by variantId to avoid lock-order deadlocks.
        List<PreparedReturnLine> sorted = new ArrayList<>(prepared);
        sorted.sort(Comparator.comparing(l -> l.saleLine().getVariantId()));
        for (PreparedReturnLine line : sorted) {
            UUID variantId = line.saleLine().getVariantId();
            BigDecimal cost = catalogService.currentCost(variantId);
            if (line.disposition() == ReturnDisposition.RESTOCK) {
                inventoryService.receive(
                        new InventoryCommand(
                                sale.getLocationId(),
                                variantId,
                                line.quantity(),
                                MovementType.POS_RETURN,
                                "SALE_RETURN",
                                returnId,
                                command.reason().name(),
                                returnNumber,
                                cost,
                                context.getUserId(),
                                null));
            } else {
                inventoryService.receiveToInspection(
                        new InventoryCommand(
                                sale.getLocationId(),
                                variantId,
                                line.quantity(),
                                MovementType.POS_RETURN,
                                "SALE_RETURN",
                                returnId,
                                command.reason().name(),
                                "INSPECTION:" + returnNumber,
                                cost,
                                context.getUserId(),
                                null));
            }
        }

        auditService.record(
                new AuditEvent(
                        AuditAction.POS_RETURN_CREATED,
                        "SALE_RETURN",
                        returnId,
                        sale.getLocationId(),
                        context.getUserId(),
                        context.getUsername(),
                        null,
                        Map.of(
                                "returnNumber",
                                returnNumber,
                                "saleId",
                                sale.getId().toString(),
                                "refundMethod",
                                sale.getPaymentMethod().name(),
                                "refundAmount",
                                totalRefund.amount().toPlainString()),
                        null,
                        null));

        return saleReturn;
    }

    /** Lists all returns for a sale within caller scope. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForSale(AccessContext context, UUID saleId) {
        Set<UUID> visibleLocations =
                context.isOwner()
                        ? new LinkedHashSet<>(locationRepository.findAllIds())
                        : context.getScopeLocationIds();
        if (visibleLocations.isEmpty()) {
            throw new NotFoundException("The requested record was not found.");
        }
        Sale sale =
                saleRepository
                        .findByIdAndLocationIdIn(saleId, visibleLocations)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        boolean canView =
                context.isOwner()
                        || context.can(Permission.POS_REFUND, sale.getLocationId())
                        || context.can(Permission.POS_DISCOUNT_APPROVE, sale.getLocationId())
                        || (context.can(Permission.POS_SELL, sale.getLocationId())
                                && sale.getCashierId().equals(context.getUserId()));
        if (!canView) {
            throw new NotFoundException("The requested record was not found.");
        }
        List<SaleReturn> returns = returnRepository.findBySaleIdOrderByCreatedAtDesc(sale.getId());
        List<Map<String, Object>> result = new ArrayList<>(returns.size());
        for (SaleReturn ret : returns) {
            result.add(toReturnMap(ret));
        }
        return result;
    }

    private Map<String, Object> toReturnMap(SaleReturn ret) {
        String actorUsername =
                userRepository
                        .findById(ret.getCreatedBy())
                        .map(AppUser::getUsername)
                        .orElse("unknown");
        List<SaleReturnLine> lines = returnLineRepository.findBySaleReturnId(ret.getId());
        Map<UUID, SaleLine> saleLinesById = new LinkedHashMap<>();
        for (SaleLine sl : saleLineRepository.findBySaleIdOrderByLineIndexAsc(ret.getSaleId())) {
            saleLinesById.put(sl.getId(), sl);
        }

        List<Map<String, Object>> lineMaps = new ArrayList<>(lines.size());
        for (SaleReturnLine l : lines) {
            SaleLine sl = saleLinesById.get(l.getSaleLineId());
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", l.getId());
            lm.put("saleLineId", l.getSaleLineId());
            lm.put("variantId", l.getVariantId());
            lm.put("sku", sl == null ? null : sl.getSkuSnapshot());
            lm.put("quantity", l.getQuantity());
            lm.put("refundAmount", Money.of(l.getRefundAmount()).amount().toPlainString());
            lm.put("disposition", l.getDisposition().name());
            lineMaps.add(lm);
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", ret.getId());
        map.put("returnNumber", ret.getReturnNumber());
        map.put("saleId", ret.getSaleId());
        map.put("locationId", ret.getLocationId());
        map.put("returnedAt", ret.getCreatedAt().toString());
        map.put("actorUsername", actorUsername);
        map.put("reason", ret.getReason().name());
        map.put("note", ret.getNote());
        map.put("refundMethod", ret.getRefundMethod().name());
        map.put("refundAmount", Money.of(ret.getRefundAmount()).amount().toPlainString());
        map.put("lines", lineMaps);
        return map;
    }

    private ReturnCreationOutcome handleExistingIdempotentReturn(
            SaleReturn existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ConflictException(
                    ErrorCodes.IDEMPOTENCY_CONFLICT,
                    "This Idempotency-Key was already used with a different return payload.");
        }
        return new ReturnCreationOutcome(toReturnMap(existing), false);
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

    private static String hashRequest(UUID saleId, CreateReturnCommand command) {
        StringBuilder sb = new StringBuilder();
        sb.append(saleId).append('|');
        sb.append(command.reason()).append('|');
        sb.append(trimToNull(command.note())).append('|');
        for (ReturnLineCommand line : command.lines()) {
            sb.append(line.saleLineId())
                    .append(':')
                    .append(line.quantity())
                    .append(':')
                    .append(line.disposition())
                    .append(';');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
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

    private record PreparedReturnLine(
            SaleLine saleLine, int quantity, Money refundAmount, ReturnDisposition disposition) {}
}
