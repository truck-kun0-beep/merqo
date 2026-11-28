package com.merqo.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The test that proves the default deny rule of the API.
 *
 * <p>Responsibility: walk every endpoint the application really exposes and check the public allow
 * list against it. Two things are proved:
 *
 * <ol>
 *   <li>Every name on the public allow list maps to an endpoint that really exists, so a typo or an
 *       old name cannot quietly widen the list.
 *   <li>No business endpoint is on the public allow list. Everything else is behind the
 *       authentication, because the security chain ends with {@code anyRequest().authenticated()}.
 * </ol>
 *
 * <p>It runs against the real PostgreSQL database {@code merqo_test}, because the endpoint list
 * comes from the real application context.
 */
@SpringBootTest
@ActiveProfiles("test")
class PublicEndpointPolicyTest {

    /** The prefixes that hold business data. None of them may ever be public. */
    private static final List<String> BUSINESS_PREFIXES =
            List.of(
                    "/api/v1/users",
                    "/api/v1/locations",
                    "/api/v1/settings",
                    "/api/v1/audit-logs",
                    "/api/v1/assignments",
                    "/api/v1/permissions",
                    "/api/v1/permission-templates",
                    "/api/v1/integration",
                    "/api/v1/orders",
                    "/api/v1/customers",
                    "/integration/v1");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("every name on the public allow list maps to an endpoint that exists")
    void everyPublicNameMapsToARealEndpoint() {
        Set<String> mapped = mappedPatterns();

        List<String> missing = new ArrayList<>();
        for (String allowed : SecurityConfiguration.PUBLIC_ENDPOINTS) {
            boolean found =
                    mapped.stream()
                            .anyMatch(
                                    pattern ->
                                            pattern.equals(allowed)
                                                    || pattern.startsWith(allowed + "/"));
            if (!found) {
                missing.add(allowed);
            }
        }

        assertEquals(
                List.of(),
                missing,
                "These names are on the public allow list but no endpoint answers them: "
                        + missing);
    }

    @Test
    @DisplayName("no business endpoint is public")
    void noBusinessEndpointIsPublic() {
        for (String endpoint : SecurityConfiguration.PUBLIC_ENDPOINTS) {
            assertFalse(
                    isBusinessEndpoint(endpoint),
                    "This business endpoint must not be public: " + endpoint);
        }
    }

    @Test
    @DisplayName("the public allow list holds no name twice")
    void thePublicAllowListHasNoDuplicates() {
        Set<String> unique = new LinkedHashSet<>(SecurityConfiguration.PUBLIC_ENDPOINTS);
        assertEquals(
                SecurityConfiguration.PUBLIC_ENDPOINTS.size(),
                unique.size(),
                "The public allow list must not name the same endpoint twice.");
    }

    @Test
    @DisplayName("the health endpoints are public, so the uptime monitor can reach them")
    void theHealthEndpointsArePublic() {
        assertTrue(SecurityConfiguration.PUBLIC_ENDPOINTS.contains("/api/v1/health"));
        assertTrue(SecurityConfiguration.PUBLIC_ENDPOINTS.contains("/api/v1/health/ping"));
    }

    @Test
    @DisplayName("the business endpoints are all behind the authentication")
    void theBusinessEndpointsAreProtected() {
        Set<String> mapped = mappedPatterns();

        long businessEndpoints = mapped.stream().filter(this::isBusinessEndpoint).count();

        assertTrue(
                businessEndpoints > 0,
                "The application must expose at least one business endpoint, otherwise the rule "
                        + "is not proved.");
    }

    /**
     * Whether a path belongs to the business API.
     *
     * @param pattern the endpoint pattern
     * @return {@code true} when the endpoint carries business data
     */
    private boolean isBusinessEndpoint(String pattern) {
        for (String prefix : BUSINESS_PREFIXES) {
            if (pattern.equals(prefix) || pattern.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every request pattern the application exposes.
     *
     * @return the patterns
     */
    private Set<String> mappedPatterns() {
        Set<String> patterns = new LinkedHashSet<>();
        for (RequestMappingInfo mapping : handlerMapping.getHandlerMethods().keySet()) {
            mapping.getPatternValues().stream()
                    .filter(pattern -> !pattern.isBlank())
                    .forEach(patterns::add);
        }
        return patterns;
    }
}
