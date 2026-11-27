package com.merqo.pos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.merqo.access.AccountType;
import com.merqo.access.AssignmentType;
import com.merqo.access.LocationAssignment;
import com.merqo.access.Permission;
import com.merqo.access.PermissionResolver;
import com.merqo.access.UserPermissionOverride;
import com.merqo.catalog.domain.DiscountType;
import com.merqo.catalog.domain.Scope;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.promotion.PromotionService;
import com.merqo.user.AppUser;
import com.merqo.user.UserTemplateAssignment;

/**
 * Group 3 integration tests closing discount limit, permission, fixed-ceiling, and cross-cashier /
 * cross-location approval token gaps via HTTP.
 */
class PosGroup3DiscountsIntegrationTest extends PosIntegrationSupport {

    @Test
    @DisplayName(
            "cashier within post-promo limit passes, over-limit requires valid matching token, no POS_DISCOUNT gets 403, fixed >= price fails")
    void discountLimitsPermissionsAndTokenIsolation() throws Exception {
        UUID dmId = locationId("DM");
        UUID utId = locationId("UT");
        ProductFixture fixture = createProductFixture("G3DSC", "1000.00", "400.00");
        addOpeningStock(dmId, fixture.variant().getId(), 20);
        addOpeningStock(utId, fixture.variant().getId(), 20);

        // 20% POS promo at DM and UT -> post-promo unit price = 800.00
        promotionService.create(
                ownerContext(),
                new PromotionService.PromotionRequest(
                        "G3 POS 20%",
                        DiscountType.PERCENT,
                        new BigDecimal("20"),
                        Scope.POS,
                        false,
                        Set.of(dmId, utId),
                        Set.of(fixture.variant().getId()),
                        null,
                        null,
                        null,
                        PromotionService.ConflictAction.REFUSE));

        // Staff account holding MANAGER template at DM with approval PIN 7491
        Cookie mgrDm =
                createAndSignIn(
                        "g3.mgr", AccountType.STAFF, "MANAGER", dmId, new BigDecimal("100"));
        mockMvc.perform(
                        put("/api/v1/auth/approval-pin")
                                .cookie(mgrDm)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"currentPassword\":\"Pass#1234\",\"pin\":\"7491\"}"))
                .andExpect(status().isNoContent());

        // Cashier A assigned to BOTH DM and UT (limit 5% = 40.00 on 800.00)
        String cashierAName = "g3.ca." + shortId();
        Cookie cashierA =
                createAndSignInNamed(cashierAName, "CASHIER", dmId, new BigDecimal("5.00"));
        AppUser cashierAUser = userRepository.findByUsernameIgnoreCase(cashierAName).orElseThrow();
        assignmentRepository.saveAndFlush(
                new LocationAssignment(
                        UUID.randomUUID(),
                        cashierAUser.getId(),
                        utId,
                        AssignmentType.STAFF,
                        null,
                        Instant.now()));
        openSession(cashierA, dmId, "1000.00");

        // Cashier B assigned to DM (limit 5%)
        String cashierBName = "g3.cb." + shortId();
        Cookie cashierB =
                createAndSignInNamed(cashierBName, "CASHIER", dmId, new BigDecimal("5.00"));
        openSession(cashierB, dmId, "1000.00");

        // 1. Within 5% of post-promo (40.00 on 800.00) passes with no approval token
        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(cashierA)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1, "manualDiscountType": "FIXED", "manualDiscountValue": "40.00"}],
                                          "payment": {"method": "CASH", "tendered": "800.00"}
                                        }
                                        """
                                                .formatted(dmId, fixture.variant().getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.manualDiscount").value("40.00"))
                .andExpect(jsonPath("$.total").value("760.00"));

        // 2. Cashier A gets token at DM (15%), but Cashier B tries to use it at DM ->
        // APPROVAL_INVALID
        String tokenJson =
                mockMvc.perform(
                                post("/api/v1/pos/approvals")
                                        .cookie(cashierA)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"locationId\":\""
                                                        + dmId
                                                        + "\",\"pin\":\"7491\",\"maxDiscountPercent\":15}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String dmTokenForA = MAPPER.readTree(tokenJson).get("approvalToken").asText();

        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(cashierB)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1, "manualDiscountType": "PERCENT", "manualDiscountValue": "10"}],
                                          "approvalToken": "%s",
                                          "payment": {"method": "CASH", "tendered": "1000.00"}
                                        }
                                        """
                                                .formatted(
                                                        dmId,
                                                        fixture.variant().getId(),
                                                        dmTokenForA)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_INVALID"));

        // 3. Same cashier tries using DM token on a sale at UT -> 422 APPROVAL_INVALID
        // Re-open session for Cashier A at UT first (close DM session, open UT session)
        UUID aDmSession =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM cash_sessions WHERE cashier_id = ? AND status = 'OPEN'",
                        UUID.class,
                        cashierAUser.getId());
        mockMvc.perform(
                        post("/api/v1/pos/sessions/" + aDmSession + "/close")
                                .cookie(cashierA)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"countedCash\":\"1760.00\"}"))
                .andExpect(status().isOk());
        openSession(cashierA, utId, "500.00");

        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(cashierA)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1, "manualDiscountType": "PERCENT", "manualDiscountValue": "10"}],
                                          "approvalToken": "%s",
                                          "payment": {"method": "CASH", "tendered": "1000.00"}
                                        }
                                        """
                                                .formatted(
                                                        utId,
                                                        fixture.variant().getId(),
                                                        dmTokenForA)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_INVALID"));

        // 4. Cashier without POS_DISCOUNT cannot apply even 1% line discount or 1.00 bill discount
        String noDiscName = "g3.nod." + shortId();
        Cookie noDiscCashier =
                createAndSignInNamed(noDiscName, "CASHIER", dmId, new BigDecimal("5.00"));
        AppUser noDiscUser = userRepository.findByUsernameIgnoreCase(noDiscName).orElseThrow();
        overrideRepository.saveAndFlush(
                new UserPermissionOverride(
                        UUID.randomUUID(),
                        noDiscUser.getId(),
                        Permission.POS_DISCOUNT.name(),
                        PermissionResolver.OverrideEffect.DENY,
                        dmId,
                        demoOwner().getId(),
                        Instant.now()));
        openSession(noDiscCashier, dmId, "500.00");

        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(noDiscCashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1, "manualDiscountType": "PERCENT", "manualDiscountValue": "1"}],
                                          "payment": {"method": "CASH", "tendered": "1000.00"}
                                        }
                                        """
                                                .formatted(dmId, fixture.variant().getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(noDiscCashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1}],
                                          "billDiscountType": "FIXED",
                                          "billDiscountValue": "1.00",
                                          "payment": {"method": "CASH", "tendered": "1000.00"}
                                        }
                                        """
                                                .formatted(dmId, fixture.variant().getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        // 5. Fixed discount at or above post-promo line price (800.00) is rejected even for manager
        openSession(mgrDm, dmId, "500.00");
        for (String fixedVal : new String[] {"800.00", "900.00"}) {
            mockMvc.perform(
                            post("/api/v1/pos/sales")
                                    .cookie(mgrDm)
                                    .with(CsrfTestSupport.csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {
                                              "locationId": "%s",
                                              "lines": [{"variantId": "%s", "quantity": 1, "manualDiscountType": "FIXED", "manualDiscountValue": "%s"}],
                                              "payment": {"method": "CASH", "tendered": "100.00"}
                                            }
                                            """
                                                    .formatted(
                                                            dmId,
                                                            fixture.variant().getId(),
                                                            fixedVal)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
    }

    private Cookie createAndSignInNamed(
            String exactUsername, String templateCode, UUID locationId, BigDecimal limit)
            throws Exception {
        Instant now = Instant.now();
        AppUser user =
                new AppUser(
                        UUID.randomUUID(),
                        exactUsername,
                        null,
                        exactUsername,
                        passwordEncoder.encode("Pass#1234"),
                        AccountType.STAFF,
                        now);
        user.setMaxDiscountPercent(limit);
        userRepository.saveAndFlush(user);
        assignmentRepository.saveAndFlush(
                new LocationAssignment(
                        UUID.randomUUID(),
                        user.getId(),
                        locationId,
                        AssignmentType.STAFF,
                        null,
                        now));
        userTemplateRepository.saveAndFlush(
                new UserTemplateAssignment(
                        user.getId(),
                        templateRepository.findByCode(templateCode).orElseThrow().getId(),
                        null,
                        now));
        return mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"identifier\":\""
                                                + exactUsername
                                                + "\",\"password\":\"Pass#1234\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie(SESSION_COOKIE);
    }
}
