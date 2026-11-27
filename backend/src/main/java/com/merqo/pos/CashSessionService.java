package com.merqo.pos;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.dao.DataIntegrityViolationException;
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
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;
import com.merqo.common.money.Money;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.pos.domain.CashSessionReconciler;
import com.merqo.pos.domain.PaymentMethod;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * Application service managing POS cash drawer sessions.
 *
 * <p>Responsibility: enforce location scope and permissions, open at most one {@code OPEN} session
 * per cashier (race-safe via database partial unique index), compute live expected figures from
 * recorded sales and refunds, and close sessions with server-side reconciliation and audit logging.
 *
 * <p>OOP concept: <b>Facade &amp; Service Boundary</b> — coordinates repositories, {@link
 * CashSessionReconciler}, and {@link AuditService} behind clean transaction boundaries.
 */
@Service
public class CashSessionService {

    private final CashSessionRepository sessionRepository;
    private final LocationRepository locationRepository;
    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final Clock clock;
    private final TransactionTemplate requiresNewTemplate;

    @PersistenceContext private EntityManager entityManager;

    public CashSessionService(
            CashSessionRepository sessionRepository,
            LocationRepository locationRepository,
            AppUserRepository userRepository,
            AuditService auditService,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.locationRepository = locationRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Opens a new cash session for the current cashier at the requested branch.
     *
     * @param context caller access context
     * @param locationId branch location id
     * @param openingCash opening cash drawer float
     * @return summary of the newly opened session
     */
    public Map<String, Object> open(
            AccessContext context, UUID locationId, BigDecimal openingCash) {
        return openInternal(context, locationId, openingCash, Instant.now(clock), null);
    }

    /**
     * Internal open helper allowing explicit timestamps and demo keys for idempotent demo seeding.
     * Public endpoints never accept client timestamps.
     */
    public Map<String, Object> openForDemo(
            AccessContext context,
            UUID locationId,
            BigDecimal openingCash,
            Instant openedAt,
            String demoKey) {
        if (demoKey != null) {
            Optional<CashSession> existing = sessionRepository.findByDemoKey(demoKey);
            if (existing.isPresent()) {
                return toSummary(existing.get());
            }
        }
        return openInternal(context, locationId, openingCash, openedAt, demoKey);
    }

    private Map<String, Object> openInternal(
            AccessContext context,
            UUID locationId,
            BigDecimal openingCash,
            Instant openedAt,
            String demoKey) {
        if (locationId == null) {
            throw new ValidationException(ErrorCodes.VALIDATION_FAILED, "Location is required.");
        }
        context.requireInScope(locationId);
        context.require(Permission.POS_CASH_SESSION, locationId);

        Location location =
                locationRepository
                        .findById(locationId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        if (!location.isActive() || location.getLocationType() != LocationType.BRANCH) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "Cash sessions can only be opened at an active branch.");
        }
        if (openingCash == null || openingCash.signum() < 0) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "Opening cash must not be negative.");
        }
        Money openingMoney = Money.of(openingCash);

        try {
            CashSession saved =
                    requiresNewTemplate.execute(
                            status -> {
                                if (sessionRepository
                                        .findByCashierIdAndStatus(
                                                context.getUserId(), CashSessionStatus.OPEN)
                                        .isPresent()) {
                                    throw new ConflictException(
                                            ErrorCodes.SESSION_ALREADY_OPEN,
                                            "You already have an open cash session.");
                                }
                                long seq = sessionRepository.nextSessionNumberSeq();
                                String sessionNumber = "CS-%06d".formatted(seq);
                                CashSession session =
                                        new CashSession(
                                                UUID.randomUUID(),
                                                sessionNumber,
                                                locationId,
                                                context.getUserId(),
                                                openingMoney.amount(),
                                                openedAt);
                                if (demoKey != null) {
                                    session.setDemoKey(demoKey);
                                }
                                CashSession persisted = sessionRepository.saveAndFlush(session);
                                auditService.record(
                                        new AuditEvent(
                                                AuditAction.CASH_SESSION_OPENED,
                                                "CASH_SESSION",
                                                persisted.getId(),
                                                locationId,
                                                context.getUserId(),
                                                context.getUsername(),
                                                null,
                                                Map.of(
                                                        "sessionNumber",
                                                        sessionNumber,
                                                        "openingCash",
                                                        openingMoney.amount().toPlainString()),
                                                null,
                                                null));
                                return persisted;
                            });
            return toSummary(saved);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException(
                    ErrorCodes.SESSION_ALREADY_OPEN, "You already have an open cash session.");
        }
    }

    /**
     * Returns the caller's currently open cash session (optionally filtered by branch), or {@code
     * null} inside {@code {"session": ...}}.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> current(AccessContext context, UUID locationId) {
        Optional<CashSession> found;
        if (locationId != null) {
            context.requireInScope(locationId);
            if (!context.can(Permission.POS_CASH_SESSION, locationId)
                    && !context.can(Permission.POS_SELL, locationId)) {
                throw new com.merqo.common.error.ForbiddenException(
                        ErrorCodes.FORBIDDEN, "You do not have POS permission at this location.");
            }
            found =
                    sessionRepository.findByCashierIdAndLocationIdAndStatus(
                            context.getUserId(), locationId, CashSessionStatus.OPEN);
        } else {
            boolean hasAnyPos =
                    !context.visibleLocationIds(Permission.POS_CASH_SESSION).isEmpty()
                            || !context.visibleLocationIds(Permission.POS_SELL).isEmpty();
            if (!context.isOwner() && !hasAnyPos) {
                throw new com.merqo.common.error.ForbiddenException(
                        ErrorCodes.FORBIDDEN, "You do not have POS session permission.");
            }
            found =
                    sessionRepository.findByCashierIdAndStatus(
                            context.getUserId(), CashSessionStatus.OPEN);
        }
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("session", found.map(this::toSummary).orElse(null));
        return wrapper;
    }

    /** Returns live expected totals (or saved reconciliation if closed) for a cash session. */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(AccessContext context, UUID sessionId) {
        CashSession session = loadInScope(context, sessionId);
        return toSummary(session);
    }

    /**
     * Closes an open cash session with server-computed expected totals and variance checks.
     *
     * @param context caller access context
     * @param sessionId session to close
     * @param countedCash physical cash counted (required)
     * @param countedBkash optional counted bKash (defaults to server expected if omitted)
     * @param countedNagad optional counted Nagad (defaults to server expected if omitted)
     * @param countedCard optional counted Card (defaults to server expected if omitted)
     * @param note closing note (mandatory when any variance is non-zero)
     * @return closed session summary
     */
    @Transactional
    public Map<String, Object> close(
            AccessContext context,
            UUID sessionId,
            BigDecimal countedCash,
            BigDecimal countedBkash,
            BigDecimal countedNagad,
            BigDecimal countedCard,
            String note) {
        return closeInternal(
                context,
                sessionId,
                countedCash,
                countedBkash,
                countedNagad,
                countedCard,
                note,
                Instant.now(clock));
    }

    /** Internal close helper with explicit timestamp for demo seeding only. */
    @Transactional
    public Map<String, Object> closeForDemo(
            AccessContext context,
            UUID sessionId,
            BigDecimal countedCash,
            BigDecimal countedBkash,
            BigDecimal countedNagad,
            BigDecimal countedCard,
            String note,
            Instant closedAt) {
        return closeInternal(
                context,
                sessionId,
                countedCash,
                countedBkash,
                countedNagad,
                countedCard,
                note,
                closedAt);
    }

    private Map<String, Object> closeInternal(
            AccessContext context,
            UUID sessionId,
            BigDecimal countedCash,
            BigDecimal countedBkash,
            BigDecimal countedNagad,
            BigDecimal countedCard,
            String note,
            Instant closedAt) {
        CashSession session = loadInScope(context, sessionId);

        if (session.getCashierId().equals(context.getUserId())) {
            context.require(Permission.POS_CASH_SESSION, session.getLocationId());
        } else {
            context.require(Permission.POS_DISCOUNT_APPROVE, session.getLocationId());
        }

        if (session.getStatus() != CashSessionStatus.OPEN) {
            throw new ConflictException(
                    ErrorCodes.SESSION_NOT_OPEN, "Cash session is already closed.");
        }
        if (countedCash == null || countedCash.signum() < 0) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "Counted cash is required and must be >= 0.");
        }

        SessionActivity activity = loadActivity(session.getId());
        CashSessionReconciler.ExpectedTotals expected =
                CashSessionReconciler.expected(
                        Money.of(session.getOpeningCash()), activity.sales(), activity.refunds());

        EnumMap<PaymentMethod, Money> countedMap = new EnumMap<>(PaymentMethod.class);
        countedMap.put(PaymentMethod.CASH, Money.of(countedCash));
        countedMap.put(
                PaymentMethod.BKASH,
                countedBkash != null ? Money.of(countedBkash) : expected.get(PaymentMethod.BKASH));
        countedMap.put(
                PaymentMethod.NAGAD,
                countedNagad != null ? Money.of(countedNagad) : expected.get(PaymentMethod.NAGAD));
        countedMap.put(
                PaymentMethod.CARD,
                countedCard != null ? Money.of(countedCard) : expected.get(PaymentMethod.CARD));

        CashSessionReconciler.ReconciliationResult result =
                CashSessionReconciler.reconcile(expected, countedMap);

        String trimmedNote = note == null ? null : note.trim();
        if (trimmedNote != null && trimmedNote.isEmpty()) {
            trimmedNote = null;
        }
        if (result.anyDifferenceNonZero() && trimmedNote == null) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "A closing note is required when any counted amount differs from expected.");
        }

        CashSessionReconciler.MethodVariance cashV = result.byMethod().get(PaymentMethod.CASH);
        CashSessionReconciler.MethodVariance bkashV = result.byMethod().get(PaymentMethod.BKASH);
        CashSessionReconciler.MethodVariance nagadV = result.byMethod().get(PaymentMethod.NAGAD);
        CashSessionReconciler.MethodVariance cardV = result.byMethod().get(PaymentMethod.CARD);

        session.close(
                context.getUserId(),
                closedAt,
                cashV.counted().amount(),
                bkashV.counted().amount(),
                nagadV.counted().amount(),
                cardV.counted().amount(),
                cashV.expected().amount(),
                bkashV.expected().amount(),
                nagadV.expected().amount(),
                cardV.expected().amount(),
                cashV.difference().amount(),
                bkashV.difference().amount(),
                nagadV.difference().amount(),
                cardV.difference().amount(),
                trimmedNote);
        CashSession saved = sessionRepository.saveAndFlush(session);

        Map<String, Object> auditValues = new LinkedHashMap<>();
        auditValues.put("sessionNumber", saved.getSessionNumber());
        auditValues.put("expectedCash", cashV.expected().amount().toPlainString());
        auditValues.put("countedCash", cashV.counted().amount().toPlainString());
        auditValues.put("differenceCash", cashV.difference().amount().toPlainString());
        auditValues.put("hasVariance", result.anyDifferenceNonZero());
        if (trimmedNote != null) {
            auditValues.put("note", trimmedNote);
        }
        auditService.record(
                new AuditEvent(
                        AuditAction.CASH_SESSION_CLOSED,
                        "CASH_SESSION",
                        saved.getId(),
                        saved.getLocationId(),
                        context.getUserId(),
                        context.getUsername(),
                        Map.of("status", "OPEN"),
                        auditValues,
                        null,
                        null));

        return toSummary(saved, activity, expected);
    }

    /**
     * Returns the cashier's open session at the given branch, or throws 409 POS_SESSION_REQUIRED.
     */
    @Transactional(readOnly = true)
    public CashSession requireOpenSession(UUID cashierId, UUID locationId) {
        return sessionRepository
                .findByCashierIdAndLocationIdAndStatus(
                        cashierId, locationId, CashSessionStatus.OPEN)
                .orElseThrow(
                        () ->
                                new ConflictException(
                                        ErrorCodes.POS_SESSION_REQUIRED,
                                        "An open cash session is required at this branch before selling or refunding cash."));
    }

    private CashSession loadInScope(AccessContext context, UUID sessionId) {
        Set<UUID> visibleLocations =
                context.isOwner()
                        ? Set.copyOf(locationRepository.findAllIds())
                        : context.getScopeLocationIds();
        if (visibleLocations.isEmpty()) {
            throw new NotFoundException("The requested record was not found.");
        }
        CashSession session =
                sessionRepository
                        .findByIdAndLocationIdIn(sessionId, visibleLocations)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        boolean isOwnerOrOwnSession =
                context.isOwner() || session.getCashierId().equals(context.getUserId());
        if (!isOwnerOrOwnSession
                && !context.can(Permission.POS_DISCOUNT_APPROVE, session.getLocationId())) {
            throw new NotFoundException("The requested record was not found.");
        }
        if (!context.can(Permission.POS_CASH_SESSION, session.getLocationId())
                && !context.can(Permission.POS_SELL, session.getLocationId())
                && !context.can(Permission.POS_DISCOUNT_APPROVE, session.getLocationId())) {
            throw new com.merqo.common.error.ForbiddenException(
                    ErrorCodes.FORBIDDEN,
                    "You do not have the permission POS_CASH_SESSION at this location.");
        }
        return session;
    }

    private Map<String, Object> toSummary(CashSession session) {
        SessionActivity activity = loadActivity(session.getId());
        CashSessionReconciler.ExpectedTotals expected =
                CashSessionReconciler.expected(
                        Money.of(session.getOpeningCash()), activity.sales(), activity.refunds());
        return toSummary(session, activity, expected);
    }

    private Map<String, Object> toSummary(
            CashSession session,
            SessionActivity activity,
            CashSessionReconciler.ExpectedTotals liveExpected) {
        String cashierUsername =
                userRepository
                        .findById(session.getCashierId())
                        .map(AppUser::getUsername)
                        .orElse("unknown");

        boolean closed = session.getStatus() == CashSessionStatus.CLOSED;
        Money expectedCash =
                closed && session.getExpectedCash() != null
                        ? Money.of(session.getExpectedCash())
                        : liveExpected.get(PaymentMethod.CASH);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", session.getId());
        map.put("sessionNumber", session.getSessionNumber());
        map.put("locationId", session.getLocationId());
        map.put("cashierId", session.getCashierId());
        map.put("cashierUsername", cashierUsername);
        map.put("openedAt", session.getOpenedAt().toString());
        map.put(
                "closedAt",
                session.getClosedAt() == null ? null : session.getClosedAt().toString());
        map.put("status", session.getStatus().name());
        map.put("openingCash", Money.of(session.getOpeningCash()).amount().toPlainString());
        map.put("expectedCash", expectedCash.amount().toPlainString());
        map.put(
                "countedCash",
                session.getCountedCash() == null
                        ? null
                        : Money.of(session.getCountedCash()).amount().toPlainString());
        map.put(
                "variance",
                session.getDifferenceCash() == null
                        ? null
                        : Money.of(session.getDifferenceCash()).amount().toPlainString());
        map.put("closeNote", session.getCloseNote());
        map.put("saleCount", activity.saleCount());
        map.put("refundTotal", activity.refundTotal().amount().toPlainString());

        List<Map<String, Object>> byMethod = new ArrayList<>();
        for (PaymentMethod method : PaymentMethod.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("method", method.name());
            if (closed) {
                BigDecimal exp =
                        switch (method) {
                            case CASH -> session.getExpectedCash();
                            case BKASH -> session.getExpectedBkash();
                            case NAGAD -> session.getExpectedNagad();
                            case CARD -> session.getExpectedCard();
                        };
                BigDecimal cnt =
                        switch (method) {
                            case CASH -> session.getCountedCash();
                            case BKASH -> session.getCountedBkash();
                            case NAGAD -> session.getCountedNagad();
                            case CARD -> session.getCountedCard();
                        };
                BigDecimal diff =
                        switch (method) {
                            case CASH -> session.getDifferenceCash();
                            case BKASH -> session.getDifferenceBkash();
                            case NAGAD -> session.getDifferenceNagad();
                            case CARD -> session.getDifferenceCard();
                        };
                entry.put(
                        "expected",
                        (exp == null ? liveExpected.get(method) : Money.of(exp))
                                .amount()
                                .toPlainString());
                entry.put("counted", cnt == null ? null : Money.of(cnt).amount().toPlainString());
                entry.put(
                        "difference",
                        diff == null ? null : Money.of(diff).amount().toPlainString());
            } else {
                entry.put("expected", liveExpected.get(method).amount().toPlainString());
                entry.put("counted", null);
                entry.put("difference", null);
            }
            byMethod.add(entry);
        }
        map.put("byMethod", byMethod);
        return map;
    }

    private SessionActivity loadActivity(UUID sessionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> saleRows =
                entityManager
                        .createNativeQuery(
                                "SELECT payment_method, total FROM sales WHERE cash_session_id = :sid")
                        .setParameter("sid", sessionId)
                        .getResultList();

        List<CashSessionReconciler.MethodAmount> sales = new ArrayList<>(saleRows.size());
        for (Object[] row : saleRows) {
            PaymentMethod method = PaymentMethod.valueOf(row[0].toString());
            Money amount = Money.of((BigDecimal) row[1]);
            sales.add(new CashSessionReconciler.MethodAmount(method, amount));
        }

        @SuppressWarnings("unchecked")
        List<Object[]> refundRows =
                entityManager
                        .createNativeQuery(
                                "SELECT refund_method, refund_amount FROM sale_returns WHERE cash_session_id = :sid")
                        .setParameter("sid", sessionId)
                        .getResultList();

        List<CashSessionReconciler.MethodAmount> refunds = new ArrayList<>(refundRows.size());
        Money refundTotal = Money.ZERO;
        for (Object[] row : refundRows) {
            PaymentMethod method = PaymentMethod.valueOf(row[0].toString());
            Money amount = Money.of((BigDecimal) row[1]);
            refunds.add(new CashSessionReconciler.MethodAmount(method, amount));
            refundTotal = refundTotal.plus(amount);
        }

        return new SessionActivity(saleRows.size(), refundTotal, sales, refunds);
    }

    private record SessionActivity(
            int saleCount,
            Money refundTotal,
            List<CashSessionReconciler.MethodAmount> sales,
            List<CashSessionReconciler.MethodAmount> refunds) {}
}
