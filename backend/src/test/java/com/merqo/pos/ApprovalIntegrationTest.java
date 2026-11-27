package com.merqo.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merqo.access.AccountType;
import com.merqo.access.AssignmentType;
import com.merqo.access.LocationAssignment;
import com.merqo.access.LocationAssignmentRepository;
import com.merqo.access.PermissionTemplate;
import com.merqo.access.PermissionTemplateRepository;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.location.LocationRepository;
import com.merqo.pos.domain.ApprovalTokenRules;
import com.merqo.pos.domain.PosRuleException;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;
import com.merqo.user.UserTemplateAssignment;
import com.merqo.user.UserTemplateAssignmentRepository;

/**
 * Integration tests for approval PIN management and single-use approval tokens against PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalIntegrationTest {

    private static final String SESSION_COOKIE = "MERQO_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private ApprovalService approvalService;
    @Autowired private ApprovalTokenRepository tokenRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private LocationAssignmentRepository assignmentRepository;
    @Autowired private PermissionTemplateRepository templateRepository;
    @Autowired private UserTemplateAssignmentRepository userTemplateRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName(
            "set approval PIN enforces permission, password, policy, and audits without leaking PIN")
    void setApprovalPinRulesAndAudit() throws Exception {
        UUID dmId = locationId("DM");
        String cashierName = "ap.cash." + shortUuid();
        String managerName = "ap.mgr." + shortUuid();
        Cookie cashierCookie =
                createAndSignIn(cashierName, "Pass#1234", AccountType.STAFF, "CASHIER", dmId);
        Cookie managerCookie =
                createAndSignIn(managerName, "Pass#1234", AccountType.MANAGER, "MANAGER", dmId);

        // Cashier lacks POS_DISCOUNT_APPROVE -> 403
        mockMvc.perform(
                        put("/api/v1/auth/approval-pin")
                                .cookie(cashierCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"currentPassword\":\"Pass#1234\",\"pin\":\"4829\"}"))
                .andExpect(status().isForbidden());

        // Manager with wrong password -> 401
        mockMvc.perform(
                        put("/api/v1/auth/approval-pin")
                                .cookie(managerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"currentPassword\":\"Wrong#1\",\"pin\":\"4829\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));

        // Manager with weak sequence PIN -> 422 PIN_POLICY
        mockMvc.perform(
                        put("/api/v1/auth/approval-pin")
                                .cookie(managerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"currentPassword\":\"Pass#1234\",\"pin\":\"1234\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("PIN_POLICY"));

        // Valid strong PIN -> 204 + audit
        mockMvc.perform(
                        put("/api/v1/auth/approval-pin")
                                .cookie(managerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"currentPassword\":\"Pass#1234\",\"pin\":\"4829\"}"))
                .andExpect(status().isNoContent());

        AppUser mgr = userRepository.findByUsernameIgnoreCase(managerName).orElseThrow();
        Integer auditCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE action = 'APPROVAL_PIN_CHANGED' AND entity_id = ?",
                        Integer.class,
                        mgr.getId().toString());
        assertEquals(1, auditCount);
    }

    @Test
    @DisplayName(
            "approval token hashes storage, single-use, expiry, wrong location/cashier, and lockout")
    void approvalTokenLifecycleAndLockout() throws Exception {
        UUID dmId = locationId("DM");
        UUID utId = locationId("UT");
        String cashierName = "ap.c2." + shortUuid();
        String managerName = "ap.m2." + shortUuid();
        Cookie cashierCookie =
                createAndSignIn(cashierName, "Pass#1234", AccountType.STAFF, "CASHIER", dmId);
        Cookie managerCookie =
                createAndSignIn(managerName, "Pass#1234", AccountType.MANAGER, "MANAGER", dmId);
        AppUser cashierUser = userRepository.findByUsernameIgnoreCase(cashierName).orElseThrow();

        // Set manager PIN to 5927
        mockMvc.perform(
                        put("/api/v1/auth/approval-pin")
                                .cookie(managerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"currentPassword\":\"Pass#1234\",\"pin\":\"5927\"}"))
                .andExpect(status().isNoContent());

        // Cashier requests approval for out-of-scope Uttara -> 404
        mockMvc.perform(
                        post("/api/v1/pos/approvals")
                                .cookie(cashierCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + utId
                                                + "\",\"pin\":\"5927\",\"maxDiscountPercent\":20}"))
                .andExpect(status().isNotFound());

        // Cashier gets valid approval token at DM
        String tokenJson =
                mockMvc.perform(
                                post("/api/v1/pos/approvals")
                                        .cookie(cashierCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"locationId\":\""
                                                        + dmId
                                                        + "\",\"pin\":\"5927\",\"maxDiscountPercent\":20}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode root = MAPPER.readTree(tokenJson);
        String rawToken = root.get("approvalToken").asText();
        assertNotNull(rawToken);
        assertFalse(root.has("approverId"));
        assertFalse(root.has("approverUsername"));
        assertFalse(root.has("approver"));

        // DB stores SHA-256 hash, never raw token
        String expectedHash = ApprovalService.sha256Hex(rawToken);
        assertNotEquals(rawToken, expectedHash);
        ApprovalToken stored = tokenRepository.findByTokenHash(expectedHash).orElseThrow();
        assertEquals(cashierUser.getId(), stored.getCashierId());

        // Wrong location fails
        PosRuleException wrongLoc =
                assertThrows(
                        PosRuleException.class,
                        () ->
                                approvalService.consumeToken(
                                        rawToken,
                                        cashierUser.getId(),
                                        utId,
                                        new BigDecimal("15.00"),
                                        null));
        assertEquals("APPROVAL_INVALID", wrongLoc.getErrorCode());

        // Wrong cashier fails
        PosRuleException wrongCashier =
                assertThrows(
                        PosRuleException.class,
                        () ->
                                approvalService.consumeToken(
                                        rawToken,
                                        UUID.randomUUID(),
                                        dmId,
                                        new BigDecimal("15.00"),
                                        null));
        assertEquals("APPROVAL_INVALID", wrongCashier.getErrorCode());

        // Expired clock fails
        Clock expiredClock = Clock.fixed(stored.getExpiresAt().plusSeconds(1), ZoneOffset.UTC);
        PosRuleException expired =
                assertThrows(
                        PosRuleException.class,
                        () ->
                                approvalService.consumeToken(
                                        rawToken,
                                        cashierUser.getId(),
                                        dmId,
                                        new BigDecimal("15.00"),
                                        expiredClock));
        assertEquals("APPROVAL_INVALID", expired.getErrorCode());

        // Valid consume succeeds once, second consume fails (single-use)
        Clock validClock =
                Clock.fixed(
                        stored.getExpiresAt().minus(ApprovalTokenRules.TOKEN_TTL).plusSeconds(10),
                        ZoneOffset.UTC);
        ApprovalToken consumed =
                approvalService.consumeToken(
                        rawToken, cashierUser.getId(), dmId, new BigDecimal("20.00"), validClock);
        assertNotNull(consumed.getUsedAt());

        PosRuleException reused =
                assertThrows(
                        PosRuleException.class,
                        () ->
                                approvalService.consumeToken(
                                        rawToken,
                                        cashierUser.getId(),
                                        dmId,
                                        new BigDecimal("10.00"),
                                        validClock));
        assertEquals("APPROVAL_INVALID", reused.getErrorCode());

        // 5 wrong PIN attempts -> 422 generic + audited; 6th attempt -> 423 PIN_LOCKED
        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(
                            post("/api/v1/pos/approvals")
                                    .cookie(cashierCookie)
                                    .with(CsrfTestSupport.csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"locationId\":\""
                                                    + dmId
                                                    + "\",\"pin\":\"9999\",\"maxDiscountPercent\":20}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errorCode").value("APPROVAL_INVALID"))
                    .andExpect(jsonPath("$.detail").value("Invalid approval PIN."));
        }

        // 6th attempt even with the RIGHT PIN is locked!
        mockMvc.perform(
                        post("/api/v1/pos/approvals")
                                .cookie(cashierCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + dmId
                                                + "\",\"pin\":\"5927\",\"maxDiscountPercent\":20}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.errorCode").value("PIN_LOCKED"));

        Integer failedAudits =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE action = 'POS_APPROVAL_FAILED' AND actor_user_id = ?",
                        Integer.class,
                        cashierUser.getId());
        assertTrue(failedAudits >= 5);
    }

    private UUID locationId(String code) {
        return locationRepository.findByCodeIgnoreCase(code).orElseThrow().getId();
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Cookie createAndSignIn(
            String username,
            String rawPassword,
            AccountType accountType,
            String templateCode,
            UUID locationId)
            throws Exception {
        Instant now = Instant.now();
        AppUser user =
                new AppUser(
                        UUID.randomUUID(),
                        username,
                        null,
                        username,
                        passwordEncoder.encode(rawPassword),
                        accountType,
                        now);
        userRepository.saveAndFlush(user);

        assignmentRepository.saveAndFlush(
                new LocationAssignment(
                        UUID.randomUUID(),
                        user.getId(),
                        locationId,
                        AssignmentType.STAFF,
                        null,
                        now));

        PermissionTemplate template = templateRepository.findByCode(templateCode).orElseThrow();
        userTemplateRepository.saveAndFlush(
                new UserTemplateAssignment(user.getId(), template.getId(), null, now));

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"identifier\":\""
                                                        + username
                                                        + "\",\"password\":\""
                                                        + rawPassword
                                                        + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertNotNull(cookie);
        return cookie;
    }
}
