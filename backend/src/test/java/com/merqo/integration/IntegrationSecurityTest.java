package com.merqo.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditLog;
import com.merqo.audit.AuditLogRepository;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.orders.domain.RequestSignature;

/**
 * Integration and unit security tests for {@code /api/v1/integration/clients} and {@code
 * /integration/v1/**} HMAC signature authentication, rate limiting, and encryption safeguards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntegrationSecurityTest {

    private static final String SESSION_COOKIE = "MERQO_SESSION";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private IntegrationClientRepository clientRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private SecretCipher secretCipher;

    private final java.util.List<UUID> createdClientIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanupCreatedClients() {
        for (UUID id : createdClientIds) {
            clientRepository.findById(id).ifPresent(clientRepository::delete);
        }
        createdClientIds.clear();
    }

    @Test
    @DisplayName(
            "owner creates client returning secret once; non-owner receives 403; list omits secrets")
    void clientLifecyclePermissionsAndSecretRedaction() throws Exception {
        Cookie managerCookie = signIn("manager-dm");
        mockMvc.perform(
                        post("/api/v1/integration/clients")
                                .cookie(managerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Blocked Manager Store\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.FORBIDDEN));

        mockMvc.perform(get("/api/v1/integration/clients").cookie(managerCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.FORBIDDEN));

        Cookie ownerCookie = signIn("owner");
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/integration/clients")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"Merqo Webshop Test\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode createdJson =
                objectMapper.readTree(
                        created.getResponse().getContentAsString(StandardCharsets.UTF_8));
        UUID clientId = UUID.fromString(createdJson.get("client").get("id").asString());
        createdClientIds.add(clientId);

        String keyId = createdJson.get("client").get("keyId").asString();
        String secret = createdJson.get("secret").asString();
        assertTrue(keyId.startsWith("mk_"));
        assertTrue(secret.startsWith("ms_"));

        IntegrationClient stored = clientRepository.findById(clientId).orElseThrow();
        assertNotEquals(secret, stored.getSecretEncrypted());
        assertEquals(secret, secretCipher.decrypt(stored.getSecretEncrypted()));

        MvcResult listResult =
                mockMvc.perform(get("/api/v1/integration/clients").cookie(ownerCookie))
                        .andExpect(status().isOk())
                        .andReturn();
        String listBody = listResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(listBody.contains(secret));
        assertFalse(listBody.contains(stored.getSecretEncrypted()));
        JsonNode listArray = objectMapper.readTree(listBody);
        for (JsonNode entry : listArray) {
            assertFalse(entry.has("secret"));
            assertFalse(entry.has("secretEncrypted"));
        }

        // Verify audit trail captured creation without leaking any secret
        AuditLog audit =
                auditLogRepository
                        .findByEntityTypeAndEntityIdOrderByOccurredAtAsc(
                                "integration_client", clientId.toString())
                        .stream()
                        .filter(
                                a ->
                                        AuditAction.INTEGRATION_CLIENT_CREATED
                                                .name()
                                                .equals(a.getAction()))
                        .findFirst()
                        .orElseThrow();
        assertFalse(String.valueOf(audit.getNewValues()).contains(secret));
    }

    @Test
    @DisplayName(
            "signed integration request succeeds, invalid/expired/future/disabled fail accurately")
    void signatureVerificationReplayAndDisableRules() throws Exception {
        Cookie ownerCookie = signIn("owner");
        CreatedClient client = createClient(ownerCookie, "Signature Check Client");

        long now = Instant.now().getEpochSecond();

        // 1. Valid request succeeds and updates lastUsedAt
        String validSig = RequestSignature.computeHex(client.secret(), now, "");
        mockMvc.perform(
                        get("/integration/v1/stock?sku=NO-SUCH-SKU")
                                .header(IntegrationAuthFilter.HEADER_KEY, client.keyId())
                                .header(IntegrationAuthFilter.HEADER_TIMESTAMP, String.valueOf(now))
                                .header(IntegrationAuthFilter.HEADER_SIGNATURE, validSig))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sku").value("NO-SUCH-SKU"))
                .andExpect(jsonPath("$.items[0].found").value(false));

        IntegrationClient afterValid = clientRepository.findById(client.id()).orElseThrow();
        assertNotNull(afterValid.getLastUsedAt());

        // 2. Slight future drift within 300s window (+60s) is accepted
        long driftTimestamp = now + 60;
        String driftSig = RequestSignature.computeHex(client.secret(), driftTimestamp, "");
        mockMvc.perform(
                        get("/integration/v1/stock")
                                .header(IntegrationAuthFilter.HEADER_KEY, client.keyId())
                                .header(
                                        IntegrationAuthFilter.HEADER_TIMESTAMP,
                                        String.valueOf(driftTimestamp))
                                .header(IntegrationAuthFilter.HEADER_SIGNATURE, driftSig))
                .andExpect(status().isOk());

        // 3. Bad signature -> 401 INVALID_SIGNATURE
        mockMvc.perform(
                        get("/integration/v1/stock")
                                .header(IntegrationAuthFilter.HEADER_KEY, client.keyId())
                                .header(IntegrationAuthFilter.HEADER_TIMESTAMP, String.valueOf(now))
                                .header(IntegrationAuthFilter.HEADER_SIGNATURE, "0".repeat(64)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_SIGNATURE));

        // 4. Past expired timestamp -> 401 SIGNATURE_EXPIRED
        long expiredPast = now - 400;
        mockMvc.perform(
                        get("/integration/v1/stock")
                                .header(IntegrationAuthFilter.HEADER_KEY, client.keyId())
                                .header(
                                        IntegrationAuthFilter.HEADER_TIMESTAMP,
                                        String.valueOf(expiredPast))
                                .header(
                                        IntegrationAuthFilter.HEADER_SIGNATURE,
                                        RequestSignature.computeHex(
                                                client.secret(), expiredPast, "")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.SIGNATURE_EXPIRED));

        // 5. Far future expired timestamp -> 401 SIGNATURE_EXPIRED
        long expiredFuture = now + 400;
        mockMvc.perform(
                        get("/integration/v1/stock")
                                .header(IntegrationAuthFilter.HEADER_KEY, client.keyId())
                                .header(
                                        IntegrationAuthFilter.HEADER_TIMESTAMP,
                                        String.valueOf(expiredFuture))
                                .header(
                                        IntegrationAuthFilter.HEADER_SIGNATURE,
                                        RequestSignature.computeHex(
                                                client.secret(), expiredFuture, "")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.SIGNATURE_EXPIRED));

        // 6. Disable client -> 403 INTEGRATION_DISABLED; re-enable -> 200 OK
        mockMvc.perform(
                        post("/api/v1/integration/clients/" + client.id() + "/disable")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(
                        get("/integration/v1/stock")
                                .header(IntegrationAuthFilter.HEADER_KEY, client.keyId())
                                .header(IntegrationAuthFilter.HEADER_TIMESTAMP, String.valueOf(now))
                                .header(IntegrationAuthFilter.HEADER_SIGNATURE, validSig))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INTEGRATION_DISABLED));

        mockMvc.perform(
                        post("/api/v1/integration/clients/" + client.id() + "/enable")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        // 7. Rotate secret -> old secret fails 401, new secret succeeds 200
        MvcResult rotated =
                mockMvc.perform(
                                post("/api/v1/integration/clients/" + client.id() + "/rotate")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf()))
                        .andExpect(status().isOk())
                        .andReturn();
        String newSecret =
                objectMapper
                        .readTree(rotated.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .get("secret")
                        .asString();
        assertNotEquals(client.secret(), newSecret);

        mockMvc.perform(
                        get("/integration/v1/stock")
                                .header(IntegrationAuthFilter.HEADER_KEY, client.keyId())
                                .header(IntegrationAuthFilter.HEADER_TIMESTAMP, String.valueOf(now))
                                .header(IntegrationAuthFilter.HEADER_SIGNATURE, validSig))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_SIGNATURE));

        mockMvc.perform(
                        get("/integration/v1/stock")
                                .header(IntegrationAuthFilter.HEADER_KEY, client.keyId())
                                .header(IntegrationAuthFilter.HEADER_TIMESTAMP, String.valueOf(now))
                                .header(
                                        IntegrationAuthFilter.HEADER_SIGNATURE,
                                        RequestSignature.computeHex(newSecret, now, "")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName(
            "auth boundaries keep session cookies off /integration/v1 and API keys off /api/v1")
    void authSurfaceIsolationAndRateLimiting() throws Exception {
        Cookie ownerCookie = signIn("owner");
        CreatedClient client = createClient(ownerCookie, "Surface & Rate Client");
        long now = Instant.now().getEpochSecond();
        String validSig = RequestSignature.computeHex(client.secret(), now, "");

        // Session cookie sent to /integration/v1/** must be rejected
        mockMvc.perform(
                        get("/integration/v1/stock")
                                .cookie(ownerCookie)
                                .header(IntegrationAuthFilter.HEADER_KEY, client.keyId())
                                .header(IntegrationAuthFilter.HEADER_TIMESTAMP, String.valueOf(now))
                                .header(IntegrationAuthFilter.HEADER_SIGNATURE, validSig))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNAUTHENTICATED));

        // API key headers sent to /api/v1/** must be rejected even with a valid cookie
        mockMvc.perform(
                        get("/api/v1/settings")
                                .cookie(ownerCookie)
                                .header(IntegrationAuthFilter.HEADER_KEY, client.keyId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNAUTHENTICATED));

        // 60 req/min succeed; 61st returns 429 RATE_LIMITED
        for (int i = 0; i < IntegrationRateLimiter.LIMIT_PER_MINUTE; i++) {
            mockMvc.perform(
                            get("/integration/v1/stock")
                                    .header(IntegrationAuthFilter.HEADER_KEY, client.keyId())
                                    .header(
                                            IntegrationAuthFilter.HEADER_TIMESTAMP,
                                            String.valueOf(now))
                                    .header(IntegrationAuthFilter.HEADER_SIGNATURE, validSig))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(
                        get("/integration/v1/stock")
                                .header(IntegrationAuthFilter.HEADER_KEY, client.keyId())
                                .header(IntegrationAuthFilter.HEADER_TIMESTAMP, String.valueOf(now))
                                .header(IntegrationAuthFilter.HEADER_SIGNATURE, validSig))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RATE_LIMITED));
    }

    @Test
    @DisplayName(
            "startup check fails cleanly when clients exist but INTEGRATION_SECRET_KEY is unset")
    void startupCheckFailsWithoutEncryptionKeyWhenClientsExist() {
        IntegrationClientRepository repo = mock(IntegrationClientRepository.class);
        when(repo.count()).thenReturn(1L);
        IntegrationProperties emptyProps = new IntegrationProperties();

        IntegrationKeyStartupCheck check = new IntegrationKeyStartupCheck(repo, emptyProps);
        assertThrows(
                IllegalStateException.class, () -> check.run(new DefaultApplicationArguments()));

        when(repo.count()).thenReturn(0L);
        check.run(new DefaultApplicationArguments());
    }

    private CreatedClient createClient(Cookie ownerCookie, String name) throws Exception {
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/integration/clients")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"" + name + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        JsonNode root =
                objectMapper.readTree(
                        created.getResponse().getContentAsString(StandardCharsets.UTF_8));
        UUID id = UUID.fromString(root.get("client").get("id").asString());
        createdClientIds.add(id);
        return new CreatedClient(
                id, root.get("client").get("keyId").asString(), root.get("secret").asString());
    }

    private Cookie signIn(String accountKey) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/demo/login")
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accountKey\":\"" + accountKey + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        return result.getResponse().getCookie(SESSION_COOKIE);
    }

    private record CreatedClient(UUID id, String keyId, String secret) {}
}
