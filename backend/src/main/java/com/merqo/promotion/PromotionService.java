package com.merqo.promotion;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.ProductVariantRepository;
import com.merqo.catalog.VariantCostHistory;
import com.merqo.catalog.VariantCostHistoryRepository;
import com.merqo.catalog.domain.DiscountType;
import com.merqo.catalog.domain.PromotionConflictDetector;
import com.merqo.catalog.domain.PromotionResolver;
import com.merqo.catalog.domain.Scope;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.ForbiddenException;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;
import com.merqo.common.money.Money;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;

/**
 * The rules of the discounts.
 *
 * <p>Responsibility: create, change, end and enable the promotions, and pick the promotion that
 * applies to a variant at a place in a channel.
 *
 * <p>The rules that matter:
 *
 * <ol>
 *   <li><b>One promotion at a time.</b> Two promotions that share a variant, a channel, a location
 *       and a period fight each other. The detector finds the fight and the service refuses the
 *       second one with {@code PROMOTION_CONFLICT}.
 *   <li><b>The variants are a snapshot.</b> The bulk selector is expanded when the promotion is
 *       created. A variant that is added later is not covered automatically.
 *   <li><b>Only the owner can cover everything.</b> A promotion that covers every location, or one
 *       that covers the online channel, covers the online fulfilment warehouse too. So in practice
 *       only the owner can create those, and a branch manager may only create a POS promotion for
 *       their own branches.
 *   <li><b>A promotion is never deleted.</b> It is disabled or ended.
 * </ol>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the discount rules live here, not in the controller.
 *   <li><b>Delegation</b> - the conflict rule and the choice of the promotion live in plain Java
 *       classes that are unit tested on their own.
 *   <li><b>Dependency injection</b> - the repositories, the audit log and the clock are injected.
 *   <li><b>Fail fast</b> - every rule that is broken throws, so a broken request writes nothing.
 * </ul>
 */
@Service
public class PromotionService {

    /** What the caller wants to do when a conflict is found. */
    public enum ConflictAction {
        /** The creation is refused and the conflicts are named. */
        REFUSE,
        /** The conflicting promotions are ended now and the new one is created. */
        END_EXISTING
    }

    private final PromotionRepository promotionRepository;
    private final PromotionVariantRepository variantLinkRepository;
    private final PromotionLocationRepository locationLinkRepository;
    private final ProductVariantRepository productVariantRepository;
    private final VariantCostHistoryRepository costHistoryRepository;
    private final LocationRepository locationRepository;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param promotionRepository the promotions
     * @param variantLinkRepository the variants a promotion covers
     * @param locationLinkRepository the locations a promotion covers
     * @param productVariantRepository the variants
     * @param costHistoryRepository the cost history
     * @param locationRepository the locations
     * @param auditService the audit log
     * @param clock the clock
     */
    public PromotionService(
            PromotionRepository promotionRepository,
            PromotionVariantRepository variantLinkRepository,
            PromotionLocationRepository locationLinkRepository,
            ProductVariantRepository productVariantRepository,
            VariantCostHistoryRepository costHistoryRepository,
            LocationRepository locationRepository,
            AuditService auditService,
            Clock clock) {
        this.promotionRepository = promotionRepository;
        this.variantLinkRepository = variantLinkRepository;
        this.locationLinkRepository = locationLinkRepository;
        this.productVariantRepository = productVariantRepository;
        this.costHistoryRepository = costHistoryRepository;
        this.locationRepository = locationRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ---- Reads --------------------------------------------------------------

    /**
     * The promotions the caller may see.
     *
     * @param context the caller
     * @return the promotions
     */
    @Transactional(readOnly = true)
    public List<Promotion> list(AccessContext context) {
        Set<UUID> visible = context.visibleLocationIds(Permission.DISCOUNT_MANAGE);
        List<Promotion> promotions = promotionRepository.findAllByOrderByCreatedAtDesc();
        List<Promotion> result = new ArrayList<>();
        for (Promotion promotion : promotions) {
            if (maySee(context, promotion)) {
                result.add(promotion);
            }
        }
        return result;
    }

    /**
     * One promotion, when the caller may see it.
     *
     * @param context the caller
     * @param promotionId the promotion
     * @return the promotion
     */
    @Transactional(readOnly = true)
    public Promotion get(AccessContext context, UUID promotionId) {
        Promotion promotion =
                promotionRepository
                        .findById(promotionId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        if (!maySee(context, promotion)) {
            throw new NotFoundException("The requested record was not found.");
        }
        return promotion;
    }

    /**
     * The variants a promotion covers.
     *
     * @param promotionId the promotion
     * @return the variant ids
     */
    @Transactional(readOnly = true)
    public Set<UUID> variantIdsOf(UUID promotionId) {
        return new LinkedHashSet<>(variantLinkRepository.findVariantIdsByPromotionId(promotionId));
    }

    /**
     * The locations a promotion covers.
     *
     * @param promotionId the promotion
     * @return the location ids
     */
    @Transactional(readOnly = true)
    public Set<UUID> locationIdsOf(UUID promotionId) {
        return new LinkedHashSet<>(
                locationLinkRepository.findLocationIdsByPromotionId(promotionId));
    }

    /**
     * The promotion that applies to a variant in a channel at a place, right now.
     *
     * @param variantId the variant
     * @param scope the channel
     * @param locationId the location of the purchase
     * @return the promotion, or {@code null} when none applies
     */
    @Transactional(readOnly = true)
    public Optional<Promotion> resolve(UUID variantId, Scope scope, UUID locationId) {
        Instant now = Instant.now(clock);
        List<com.merqo.catalog.domain.Promotion> candidates = new ArrayList<>();
        for (Promotion promotion : promotionRepository.findByVariantId(variantId)) {
            if (!coversLocation(promotion, locationId)) {
                continue;
            }
            candidates.add(promotion.toDomain());
        }
        com.merqo.catalog.domain.Promotion chosen =
                PromotionResolver.resolve(candidates, scope, locationId, now);
        if (chosen == null) {
            return Optional.empty();
        }
        return promotionRepository.findById(chosen.getId());
    }

    /**
     * The answer of one promotion, as the interface shows it.
     *
     * @param promotion the promotion
     * @return the map
     */
    public Map<String, Object> toSummary(Promotion promotion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", promotion.getId());
        body.put("name", promotion.getName());
        body.put("discountType", promotion.getDiscountType().name());
        body.put("value", promotion.getValue());
        body.put("scope", promotion.getScope().name());
        body.put("allLocations", promotion.isAllLocations());
        body.put("startsAt", promotion.getStartsAt());
        body.put("endsAt", promotion.getEndsAt());
        body.put("isEnabled", promotion.isEnabled());
        body.put("status", statusOf(promotion));
        body.put("note", promotion.getNote());
        body.put("variantIds", new ArrayList<>(variantIdsOf(promotion.getId())));
        body.put("locationIds", new ArrayList<>(locationIdsOf(promotion.getId())));
        return body;
    }

    /**
     * The state of a promotion as the interface shows it.
     *
     * @param promotion the promotion
     * @return the state: ACTIVE, SCHEDULED, EXPIRED or DISABLED
     */
    public String statusOf(Promotion promotion) {
        Instant now = Instant.now(clock);
        if (!promotion.isEnabled()) {
            return "DISABLED";
        }
        if (promotion.getStartsAt() != null && now.isBefore(promotion.getStartsAt())) {
            return "SCHEDULED";
        }
        if (promotion.getEndsAt() != null && !now.isBefore(promotion.getEndsAt())) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    // ---- Writing ------------------------------------------------------------

    /**
     * Creates a promotion.
     *
     * @param context the caller
     * @param request what to create
     * @return the new promotion
     */
    @Transactional
    public Promotion create(AccessContext context, PromotionRequest request) {
        validate(request);
        Set<UUID> variants = new LinkedHashSet<>(request.variantIds());
        Set<UUID> locations = new LinkedHashSet<>(request.locationIds());

        requirePermissionEverywhere(context, request, locations);

        Instant now = Instant.now(clock);
        Promotion promotion =
                new Promotion(
                        UUID.randomUUID(),
                        request.name().trim(),
                        request.discountType(),
                        request.value(),
                        request.scope(),
                        request.allLocations(),
                        request.startsAt(),
                        request.endsAt(),
                        true,
                        request.note(),
                        context.getUserId(),
                        now);
        promotionRepository.save(promotion);

        for (UUID variantId : variants) {
            variantLinkRepository.save(new PromotionVariant(promotion.getId(), variantId));
        }
        for (UUID locationId : locations) {
            locationLinkRepository.save(new PromotionLocation(promotion.getId(), locationId));
        }

        List<PromotionConflictDetector.Conflict> conflicts =
                detectConflicts(promotion, variants, locations);
        if (!conflicts.isEmpty()) {
            if (request.conflictAction() == ConflictAction.END_EXISTING) {
                for (PromotionConflictDetector.Conflict conflict : conflicts) {
                    com.merqo.catalog.domain.Promotion other = conflict.second();
                    Promotion stored =
                            promotionRepository
                                    .findById(other.getId())
                                    .orElseThrow(
                                            () ->
                                                    new NotFoundException(
                                                            "The requested record was not found."));
                    if (stored.getEndsAt() == null || stored.getEndsAt().isAfter(now)) {
                        stored.setEndsAt(now);
                        stored.setUpdatedAt(now);
                        promotionRepository.save(stored);
                    }
                }
            } else {
                throw new ConflictException(
                        ErrorCodes.PROMOTION_CONFLICT,
                        "This promotion fights "
                                + conflicts.size()
                                + " promotion(s) that are already there.");
            }
        }

        auditService.record(
                new AuditEvent.Builder(AuditAction.PROMOTION_CREATED, "promotions")
                        .entityId(promotion.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("name", promotion.getName())
                        .added("discountType", promotion.getDiscountType().name())
                        .added("value", promotion.getValue())
                        .added("scope", promotion.getScope().name())
                        .added("allLocations", promotion.isAllLocations())
                        .added("variantCount", variants.size())
                        .added("locationCount", locations.size())
                        .build());
        return promotion;
    }

    /**
     * Edits a promotion.
     *
     * @param context the caller
     * @param promotion the promotion
     * @param request the new values
     * @return the changed promotion
     */
    @Transactional
    public Promotion update(AccessContext context, Promotion promotion, PromotionRequest request) {
        Set<UUID> locations = new LinkedHashSet<>(request.locationIds());
        requirePermissionEverywhere(context, request, locations);

        String oldName = promotion.getName();
        String oldDiscountType = promotion.getDiscountType().name();
        BigDecimal oldValue = promotion.getValue();
        String oldScope = promotion.getScope().name();
        promotion.setName(request.name().trim());
        promotion.setDiscountType(request.discountType());
        promotion.setValue(request.value());
        promotion.setScope(request.scope());
        promotion.setAllLocations(request.allLocations());
        promotion.setStartsAt(request.startsAt());
        promotion.setEndsAt(request.endsAt());
        promotion.setNote(request.note());
        promotion.setUpdatedAt(Instant.now(clock));
        promotionRepository.save(promotion);

        auditService.record(
                new AuditEvent.Builder(AuditAction.PROMOTION_UPDATED, "promotions")
                        .entityId(promotion.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("name", oldName, promotion.getName())
                        .changed(
                                "discountType", oldDiscountType, promotion.getDiscountType().name())
                        .changed("value", oldValue, promotion.getValue())
                        .changed("scope", oldScope, promotion.getScope().name())
                        .build());
        return promotion;
    }

    /**
     * Ends a promotion now.
     *
     * @param context the caller
     * @param promotion the promotion
     * @param note why it ends, may be {@code null}
     * @return the ended promotion
     */
    @Transactional
    public Promotion end(AccessContext context, Promotion promotion, String note) {
        Instant now = Instant.now(clock);
        String oldEndsAt = promotion.getEndsAt() == null ? null : promotion.getEndsAt().toString();
        promotion.setEndsAt(now);
        promotion.setUpdatedAt(now);
        promotionRepository.save(promotion);

        auditService.record(
                new AuditEvent.Builder(AuditAction.PROMOTION_ENDED, "promotions")
                        .entityId(promotion.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("endsAt", oldEndsAt, now.toString())
                        .added("note", note)
                        .build());
        return promotion;
    }

    /**
     * Switches a promotion on or off.
     *
     * @param context the caller
     * @param promotion the promotion
     * @param enabled the new state
     * @return the changed promotion
     */
    @Transactional
    public Promotion setEnabled(AccessContext context, Promotion promotion, boolean enabled) {
        promotion.setEnabled(enabled);
        promotion.setUpdatedAt(Instant.now(clock));
        promotionRepository.save(promotion);

        auditService.record(
                new AuditEvent.Builder(
                                enabled
                                        ? AuditAction.PROMOTION_ENABLED
                                        : AuditAction.PROMOTION_DISABLED,
                                "promotions")
                        .entityId(promotion.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("isEnabled", !enabled, enabled)
                        .build());
        return promotion;
    }

    /**
     * The conflicts of a promotion with the promotions that are already there.
     *
     * @param promotion the promotion
     * @param variants the variants it covers
     * @param locations the locations it covers
     * @return the conflicts
     */
    @Transactional(readOnly = true)
    public List<PromotionConflictDetector.Conflict> detectConflicts(
            Promotion promotion, Set<UUID> variants, Set<UUID> locations) {
        List<PromotionConflictDetector.OtherPromotion> others = new ArrayList<>();
        for (Promotion other : promotionRepository.findAllByOrderByCreatedAtDesc()) {
            if (other.getId().equals(promotion.getId())) {
                continue;
            }
            if (other.getEndsAt() != null && !other.getEndsAt().isAfter(Instant.now(clock))) {
                // An ended promotion fights nobody.
                continue;
            }
            others.add(
                    new PromotionConflictDetector.OtherPromotion(
                            other.toDomain(),
                            new LinkedHashSet<>(
                                    variantLinkRepository.findVariantIdsByPromotionId(
                                            other.getId())),
                            new LinkedHashSet<>(
                                    locationLinkRepository.findLocationIdsByPromotionId(
                                            other.getId()))));
        }
        return PromotionConflictDetector.detectAll(
                promotion.toDomain(), variants, locations, others);
    }

    // ---- Helpers ------------------------------------------------------------

    private void validate(PromotionRequest request) {
        if (request.name() == null || request.name().trim().isEmpty()) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "The promotion needs a name.");
        }
        if (request.discountType() == null || request.scope() == null) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "The promotion needs a kind of discount and a scope.");
        }
        if (!request.discountType().accepts(request.value())) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "A percentage is above zero and at most one hundred, and an amount is never negative.");
        }
        if (request.startsAt() != null
                && request.endsAt() != null
                && !request.endsAt().isAfter(request.startsAt())) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "The end of the promotion is after its start.");
        }
        if (request.variantIds().isEmpty()) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "A promotion needs at least one variant.");
        }
        if (!request.allLocations() && request.locationIds().isEmpty()) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "A promotion needs at least one location.");
        }
        requireFixedDiscountFits(request);
    }

    /**
     * Refuses a fixed discount that is at or above the price of one of the chosen variants.
     *
     * @param request the request
     */
    private void requireFixedDiscountFits(PromotionRequest request) {
        if (request.discountType() != DiscountType.FIXED) {
            return;
        }
        List<String> offenders = new ArrayList<>();
        for (UUID variantId : request.variantIds()) {
            ProductVariant variant = productVariantRepository.findById(variantId).orElse(null);
            if (variant == null) {
                continue;
            }
            if (request.value().compareTo(variant.getSellingPrice()) >= 0) {
                offenders.add(variant.getSku());
            }
        }
        if (!offenders.isEmpty()) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "A fixed discount of "
                            + request.value()
                            + " is at or above the price of these variants: "
                            + String.join(", ", offenders)
                            + ".");
        }
    }

    /**
     * Refuses a caller that may not manage a discount everywhere the promotion reaches.
     *
     * @param context the caller
     * @param request the request
     * @param locations the locations the promotion covers
     */
    private void requirePermissionEverywhere(
            AccessContext context, PromotionRequest request, Set<UUID> locations) {
        if (context.can(Permission.DISCOUNT_MANAGE)) {
            return;
        }
        if (request.allLocations() || request.scope() != Scope.POS) {
            // A promotion that covers every place, or the online channel, reaches the online
            // fulfilment warehouse as well. Only the owner may do that.
            throw new ForbiddenException(
                    ErrorCodes.FORBIDDEN,
                    "Only the owner may create a promotion for every location or for the online orders.");
        }
        for (UUID locationId : locations) {
            if (!context.can(Permission.DISCOUNT_MANAGE, locationId)) {
                throw new ForbiddenException(
                        ErrorCodes.FORBIDDEN,
                        "You do not have the permission DISCOUNT_MANAGE at this location.");
            }
        }
    }

    private boolean maySee(AccessContext context, Promotion promotion) {
        if (context.can(Permission.DISCOUNT_MANAGE)) {
            return true;
        }
        Set<UUID> visible = context.visibleLocationIds(Permission.DISCOUNT_MANAGE);
        if (visible.isEmpty()) {
            return false;
        }
        if (promotion.isAllLocations()) {
            return true;
        }
        for (UUID locationId :
                locationLinkRepository.findLocationIdsByPromotionId(promotion.getId())) {
            if (visible.contains(locationId)) {
                return true;
            }
        }
        return false;
    }

    private boolean coversLocation(Promotion promotion, UUID locationId) {
        if (promotion.isAllLocations()) {
            return true;
        }
        return locationLinkRepository
                .findLocationIdsByPromotionId(promotion.getId())
                .contains(locationId);
    }

    /**
     * The cost of a variant, used to flag a discounted price that is under the cost.
     *
     * @param variantId the variant
     * @return the cost, or {@code null} when the variant never had one
     */
    @Transactional(readOnly = true)
    public BigDecimal costOf(UUID variantId) {
        return costHistoryRepository
                .findLatest(variantId)
                .map(VariantCostHistory::getValue)
                .orElse(null);
    }

    /**
     * The price of a variant, as a {@link Money} value.
     *
     * @param variant the variant
     * @return the price
     */
    public static Money priceOf(ProductVariant variant) {
        return Money.of(variant.getSellingPrice());
    }

    /**
     * The location a promotion covers, checked against the caller.
     *
     * @param context the caller
     * @param locationId the location
     * @return the location
     */
    @Transactional(readOnly = true)
    public Location requireLocation(AccessContext context, UUID locationId) {
        Location location =
                locationRepository
                        .findById(locationId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        if (!context.isInScope(locationId)) {
            throw new NotFoundException("The requested record was not found.");
        }
        return location;
    }

    /**
     * What the caller wants to create.
     *
     * @param name the display name
     * @param discountType the kind of discount
     * @param value the percentage or the amount
     * @param scope the channel
     * @param allLocations whether the promotion covers every location
     * @param locationIds the locations the promotion covers
     * @param variantIds the variants the promotion covers
     * @param startsAt the moment it starts, may be {@code null}
     * @param endsAt the moment it ends, may be {@code null}
     * @param note the note, may be {@code null}
     * @param conflictAction what to do when a conflict is found
     */
    public record PromotionRequest(
            String name,
            DiscountType discountType,
            BigDecimal value,
            Scope scope,
            boolean allLocations,
            Set<UUID> locationIds,
            Set<UUID> variantIds,
            Instant startsAt,
            Instant endsAt,
            String note,
            ConflictAction conflictAction) {}
}
