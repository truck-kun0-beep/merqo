package com.merqo.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.access.AccessContext;
import com.merqo.catalog.domain.DiscountType;
import com.merqo.catalog.domain.Scope;
import com.merqo.user.CurrentUserService;

/**
 * The endpoints of the discounts.
 *
 * <p>Responsibility: translate HTTP into calls on {@link PromotionService}.
 *
 * <p>A promotion is never deleted. It is disabled or ended, and both steps have their own endpoint.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Thin controller</b> - the controller only maps the request and the response.
 *   <li><b>Data transfer object</b> - the request bodies are small records.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/promotions")
@Tag(name = "Promotions", description = "The discounts")
public class PromotionController {

    private final PromotionService promotionService;
    private final CurrentUserService currentUserService;

    /**
     * Creates the controller.
     *
     * @param promotionService the discount rules
     * @param currentUserService the signed in user
     */
    public PromotionController(
            PromotionService promotionService, CurrentUserService currentUserService) {
        this.promotionService = promotionService;
        this.currentUserService = currentUserService;
    }

    /**
     * The promotions the caller may see.
     *
     * @return the promotions
     */
    @GetMapping
    @Operation(summary = "The promotions the caller may see")
    public List<Map<String, Object>> list() {
        AccessContext context = currentUserService.requireAccessContext();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Promotion promotion : promotionService.list(context)) {
            items.add(toSummary(promotion));
        }
        return items;
    }

    /**
     * One promotion with the variants and the locations it covers.
     *
     * @param id the promotion
     * @return the promotion
     */
    @GetMapping("/{id}")
    @Operation(summary = "One promotion with what it covers")
    public Map<String, Object> get(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        Promotion promotion = promotionService.get(context, id);
        Map<String, Object> body = toSummary(promotion);
        body.put("variantIds", new ArrayList<>(promotionService.variantIdsOf(id)));
        return body;
    }

    /**
     * Creates a promotion.
     *
     * @param request the promotion to create
     * @return the new promotion
     */
    @PostMapping
    @Operation(summary = "Create a promotion")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody PromotionBody request) {
        AccessContext context = currentUserService.requireAccessContext();
        Promotion promotion = promotionService.create(context, request.toRequest());
        return ResponseEntity.status(201).body(toSummary(promotion));
    }

    /**
     * Edits a promotion.
     *
     * @param id the promotion
     * @param request the new values
     * @return the changed promotion
     */
    @PutMapping("/{id}")
    @Operation(summary = "Edit a promotion")
    public Map<String, Object> update(
            @PathVariable UUID id, @Valid @RequestBody PromotionBody request) {
        AccessContext context = currentUserService.requireAccessContext();
        Promotion promotion = promotionService.get(context, id);
        return toSummary(promotionService.update(context, promotion, request.toRequest()));
    }

    /**
     * Ends a promotion now.
     *
     * @param id the promotion
     * @param request why it ends
     * @return the ended promotion
     */
    @PostMapping("/{id}/end")
    @Operation(summary = "End a promotion")
    public Map<String, Object> end(
            @PathVariable UUID id, @Valid @RequestBody EndPromotionRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        Promotion promotion = promotionService.get(context, id);
        return toSummary(promotionService.end(context, promotion, request.note()));
    }

    /**
     * Switches a promotion on or off.
     *
     * @param id the promotion
     * @param request the new state
     * @return the changed promotion
     */
    @PostMapping("/{id}/enabled")
    @Operation(summary = "Switch a promotion on or off")
    public Map<String, Object> setEnabled(
            @PathVariable UUID id, @Valid @RequestBody SetEnabledRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        Promotion promotion = promotionService.get(context, id);
        return toSummary(promotionService.setEnabled(context, promotion, request.enabled()));
    }

    private Map<String, Object> toSummary(Promotion promotion) {
        Map<String, Object> body = new LinkedHashMap<>(promotionService.toSummary(promotion));
        body.put("status", promotionService.statusOf(promotion));
        return body;
    }

    /**
     * The body of a promotion request.
     *
     * @param name the display name
     * @param discountType the kind of discount
     * @param value the percentage or the amount
     * @param scope the channel the promotion covers
     * @param allLocations whether the promotion covers every location
     * @param locationIds the locations the promotion covers
     * @param variantIds the variants the promotion covers
     * @param startsAt the moment it starts, may be {@code null}
     * @param endsAt the moment it ends, may be {@code null}
     * @param note the note, may be {@code null}
     * @param conflictAction what to do when a conflict is found
     */
    public record PromotionBody(
            @NotBlank String name,
            @NotNull DiscountType discountType,
            @NotNull @Positive BigDecimal value,
            @NotNull Scope scope,
            boolean allLocations,
            Set<UUID> locationIds,
            Set<UUID> variantIds,
            Instant startsAt,
            Instant endsAt,
            String note,
            PromotionService.ConflictAction conflictAction) {

        /**
         * The service request that carries the same values.
         *
         * @return the request
         */
        public PromotionService.PromotionRequest toRequest() {
            return new PromotionService.PromotionRequest(
                    name,
                    discountType,
                    value,
                    scope,
                    allLocations,
                    new LinkedHashSet<>(locationIds == null ? Set.of() : locationIds),
                    new LinkedHashSet<>(variantIds == null ? Set.of() : variantIds),
                    startsAt,
                    endsAt,
                    note,
                    conflictAction == null
                            ? PromotionService.ConflictAction.REFUSE
                            : conflictAction);
        }
    }

    /**
     * The body of an end promotion request.
     *
     * @param note why it ends
     */
    public record EndPromotionRequest(String note) {}

    /**
     * The body of a set enabled request.
     *
     * @param enabled the new state
     */
    public record SetEnabledRequest(boolean enabled) {}
}
