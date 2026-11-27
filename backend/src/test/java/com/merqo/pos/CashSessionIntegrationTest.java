package com.merqo.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merqo.access.AccountType;
import com.merqo.access.AssignmentType;
import com.merqo.access.LocationAssignment;
import com.merqo.access.LocationAssignmentRepository;
import com.merqo.access.PermissionTemplate;
import com.merqo.access.PermissionTemplateRepository;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.location.LocationRepository;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;
import com.merqo.user.UserTemplateAssignment;
import com.merqo.user.UserTemplateAssignmentRepository;

/** Integration tests for POS cash sessions against real PostgreSQL ({@code merqo_test}). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CashSessionIntegrationTest {

    private static final String SESSION_COOKIE = "MERQO_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private LocationRepository locationRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private LocationAssignmentRepository assignmentRepository;
    @Autowired private PermissionTemplateRepository templateRepository;
    @Autowired private UserTemplateAssignmentRepository userTemplateRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("open, current, summary, close with exact cash, and immutability trigger")
    void openSummaryCloseAndImmutabilityTrigger() throws Exception {
        UUID dmId = locationId("DM");
        String username = "cs.cashier." + UUID.randomUUID().toString().substring(0, 8);
        Cookie session = createAndSignInCashier(username, dmId);

        String openJson =
                mockMvc.perform(
                                post("/api/v1/pos/sessions")
                                        .cookie(session)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"locationId\":\""
                                                        + dmId
                                                        + "\",\"openingCash\":\"5000.00\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("OPEN"))
                        .andExpect(jsonPath("$.openingCash").value("5000.00"))
                        .andExpect(jsonPath("$.expectedCash").value("5000.00"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID cashSessionId = UUID.fromString(MAPPER.readTree(openJson).get("id").asText());

        // Second open session is refused with 409 SESSION_ALREADY_OPEN
        mockMvc.perform(
                        post("/api/v1/pos/sessions")
                                .cookie(session)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + dmId
                                                + "\",\"openingCash\":\"1000.00\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SESSION_ALREADY_OPEN"));

        // Current and summary reflect open session
        mockMvc.perform(
                        get("/api/v1/pos/sessions/current")
                                .param("locationId", dmId.toString())
                                .cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.id").value(cashSessionId.toString()));

        mockMvc.perform(get("/api/v1/pos/sessions/" + cashSessionId + "/summary").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedCash").value("5000.00"));

        // Closing with difference requires a note
        mockMvc.perform(
                        post("/api/v1/pos/sessions/" + cashSessionId + "/close")
                                .cookie(session)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"countedCash\":\"4950.00\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // Closing with difference and a note succeeds
        mockMvc.perform(
                        post("/api/v1/pos/sessions/" + cashSessionId + "/close")
                                .cookie(session)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"countedCash\":\"4950.00\",\"note\":\"50 BDT short\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.variance").value("-50.00"))
                .andExpect(jsonPath("$.closeNote").value("50 BDT short"));

        // Re-closing returns 409 SESSION_NOT_OPEN
        mockMvc.perform(
                        post("/api/v1/pos/sessions/" + cashSessionId + "/close")
                                .cookie(session)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"countedCash\":\"4950.00\",\"note\":\"retry\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SESSION_NOT_OPEN"));

        // Current is now null
        String currentAfter =
                mockMvc.perform(get("/api/v1/pos/sessions/current").cookie(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertTrue(MAPPER.readTree(currentAfter).get("session").isNull());

        // Database trigger blocks UPDATE and DELETE on closed session
        assertThrows(
                Exception.class,
                () ->
                        jdbcTemplate.update(
                                "UPDATE cash_sessions SET status = 'OPEN' WHERE id = ?",
                                cashSessionId));
        assertThrows(
                Exception.class,
                () -> jdbcTemplate.update("DELETE FROM cash_sessions WHERE id = ?", cashSessionId));
    }

    @Test
    @DisplayName("branch scope, manager closing stale session, and warehouse staff refusal")
    void scopeAndRoleRules() throws Exception {
        UUID dmId = locationId("DM");
        UUID utId = locationId("UT");

        String username = "cs.scope." + UUID.randomUUID().toString().substring(0, 8);
        Cookie dmCashier = createAndSignInCashier(username, dmId);

        // Dhanmondi cashier cannot open session at Uttara (404 out of scope)
        mockMvc.perform(
                        post("/api/v1/pos/sessions")
                                .cookie(dmCashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + utId
                                                + "\",\"openingCash\":\"1000.00\"}"))
                .andExpect(status().isNotFound());

        // Warehouse staff cannot open a cash session at WH (403 FORBIDDEN)
        UUID whId = locationId("WH");
        Cookie warehouse = signInDemo("warehouse");
        mockMvc.perform(
                        post("/api/v1/pos/sessions")
                                .cookie(warehouse)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + whId
                                                + "\",\"openingCash\":\"1000.00\"}"))
                .andExpect(status().isForbidden());

        // Dhanmondi cashier opens session at DM; manager-ut gets 404; manager-dm can close it
        String openJson =
                mockMvc.perform(
                                post("/api/v1/pos/sessions")
                                        .cookie(dmCashier)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"locationId\":\""
                                                        + dmId
                                                        + "\",\"openingCash\":\"2000.00\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID sessionId = UUID.fromString(MAPPER.readTree(openJson).get("id").asText());

        Cookie managerUt = signInDemo("manager-ut");
        mockMvc.perform(get("/api/v1/pos/sessions/" + sessionId + "/summary").cookie(managerUt))
                .andExpect(status().isNotFound());

        Cookie managerDm = signInDemo("manager-dm");
        mockMvc.perform(
                        post("/api/v1/pos/sessions/" + sessionId + "/close")
                                .cookie(managerDm)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"countedCash\":\"2000.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    @DisplayName("two simultaneous session opens for the same cashier yield exactly one winner")
    void concurrentSessionOpensYieldOneWinner() throws Exception {
        UUID dmId = locationId("DM");
        String username = "cs.race." + UUID.randomUUID().toString().substring(0, 8);
        Cookie session = createAndSignInCashier(username, dmId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tasks.add(
                    () -> {
                        ready.countDown();
                        start.await();
                        return mockMvc.perform(
                                        post("/api/v1/pos/sessions")
                                                .cookie(session)
                                                .with(CsrfTestSupport.csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"locationId\":\""
                                                                + dmId
                                                                + "\",\"openingCash\":\"3000.00\"}"))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    });
        }

        Future<Integer> f1 = pool.submit(tasks.get(0));
        Future<Integer> f2 = pool.submit(tasks.get(1));
        ready.await();
        start.countDown();
        int s1 = f1.get();
        int s2 = f2.get();
        pool.shutdown();

        assertEquals(List.of(201, 409), List.of(Math.min(s1, s2), Math.max(s1, s2)));

        // Verify only 1 OPEN row exists in DB for this user
        AppUser user = userRepository.findByUsernameIgnoreCase(username).orElseThrow();
        Integer openCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM cash_sessions WHERE cashier_id = ? AND status = 'OPEN'",
                        Integer.class,
                        user.getId());
        assertEquals(1, openCount);
    }

    private UUID locationId(String code) {
        return locationRepository.findByCodeIgnoreCase(code).orElseThrow().getId();
    }

    private Cookie createAndSignInCashier(String username, UUID locationId) throws Exception {
        Instant now = Instant.now();
        String rawPassword = "Cashier#Pass99";
        AppUser user =
                new AppUser(
                        UUID.randomUUID(),
                        username,
                        null,
                        "Test Cashier",
                        passwordEncoder.encode(rawPassword),
                        AccountType.STAFF,
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

        PermissionTemplate template = templateRepository.findByCode("CASHIER").orElseThrow();
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

    private Cookie signInDemo(String accountKey) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/demo/login")
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accountKey\":\"" + accountKey + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertNotNull(cookie);
        return cookie;
    }
}
