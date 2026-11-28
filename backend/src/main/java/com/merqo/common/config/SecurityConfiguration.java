package com.merqo.common.config;

import java.time.Clock;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import com.merqo.access.PermissionResolver;
import com.merqo.integration.IntegrationAuthFilter;
import com.merqo.integration.IntegrationClientRepository;
import com.merqo.integration.IntegrationRateLimiter;
import com.merqo.integration.IntegrationRejectionFilter;
import com.merqo.integration.SecretCipher;
import com.merqo.settings.SettingService;
import com.merqo.user.LoginThrottlePolicy;
import com.merqo.user.PasswordPolicy;

/**
 * The HTTP security rules of Merqo.
 *
 * <p>Responsibility: describe which endpoints are open, how the session is kept and how the CSRF
 * token travels, and isolate stateless HMAC-signed {@code /integration/v1/**} webhook traffic into
 * its own security filter chain.
 */
@Configuration
public class SecurityConfiguration {

    /** The endpoints that may be called without signing in. */
    public static final List<String> PUBLIC_ENDPOINTS =
            List.of(
                    "/api/v1/auth/status",
                    "/api/v1/auth/login",
                    "/api/v1/demo/accounts",
                    "/api/v1/demo/login",
                    "/api/v1/health",
                    "/api/v1/health/ping");

    /** Integration endpoints authenticated by HMAC key signature rather than browser sessions. */
    public static final List<String> INTEGRATION_ENDPOINTS =
            List.of(
                    "/integration/v1/orders",
                    "/integration/v1/orders/{externalOrderId}",
                    "/integration/v1/stock");

    /**
     * Stateless HMAC-signed filter chain for external website integration endpoints.
     *
     * @param http HTTP security builder
     * @param clientRepository integration clients
     * @param secretCipher AES-GCM cipher for client secrets
     * @param rateLimiter DB-backed per-minute rate limiter
     * @param settingService business settings lookup
     * @param sessionProperties cookie names (to reject session cookies here)
     * @param problemAuthenticationEntryPoint RFC 7807 entry point
     * @param problemAccessDeniedHandler RFC 7807 access denied handler
     * @param problemResponseWriter shared RFC 7807 writer
     * @param clock time source
     * @return the integration filter chain
     * @throws Exception when the chain cannot be built
     */
    @Bean
    @Order(1)
    public SecurityFilterChain integrationSecurityFilterChain(
            HttpSecurity http,
            IntegrationClientRepository clientRepository,
            SecretCipher secretCipher,
            IntegrationRateLimiter rateLimiter,
            SettingService settingService,
            MerqoSessionProperties sessionProperties,
            com.merqo.common.web.ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint,
            com.merqo.common.web.ProblemAccessDeniedHandler problemAccessDeniedHandler,
            com.merqo.common.web.ProblemResponseWriter problemResponseWriter,
            Clock clock)
            throws Exception {
        IntegrationAuthFilter integrationAuthFilter =
                new IntegrationAuthFilter(
                        clientRepository,
                        secretCipher,
                        rateLimiter,
                        settingService,
                        sessionProperties,
                        problemResponseWriter,
                        clock);

        http.securityMatcher("/integration/v1/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        handling ->
                                handling.authenticationEntryPoint(problemAuthenticationEntryPoint)
                                        .accessDeniedHandler(problemAccessDeniedHandler))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .headers(
                        headers ->
                                headers.frameOptions(frame -> frame.sameOrigin())
                                        .httpStrictTransportSecurity(hsts -> {}))
                .logout(logout -> logout.disable())
                .anonymous(Customizer.withDefaults())
                .addFilterBefore(integrationAuthFilter, AnonymousAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Builds the session-based security filter chain for {@code /api/v1/**}.
     *
     * @param http the HTTP security builder
     * @param sessionProperties the session settings
     * @return the filter chain
     * @throws Exception when the chain cannot be built
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            MerqoSessionProperties sessionProperties,
            com.merqo.user.SessionSecurityContextRepository sessionContextRepository,
            com.merqo.common.web.ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint,
            com.merqo.common.web.ProblemAccessDeniedHandler problemAccessDeniedHandler,
            com.merqo.common.web.ProblemResponseWriter problemResponseWriter)
            throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository =
                CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookiePath("/");
        CsrfTokenRequestAttributeHandler csrfRequestHandler =
                new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http.csrf(
                        csrf ->
                                csrf.csrfTokenRepository(csrfTokenRepository)
                                        .csrfTokenRequestHandler(csrfRequestHandler)
                                        .requireCsrfProtectionMatcher(
                                                request ->
                                                        !request.getMethod().equals("GET")
                                                                && !request.getMethod()
                                                                        .equals("HEAD")
                                                                && !request.getMethod()
                                                                        .equals("OPTIONS")
                                                                && !request.getMethod()
                                                                        .equals("TRACE")))
                .cors(cors -> {})
                .securityContext(
                        context -> context.securityContextRepository(sessionContextRepository))
                .exceptionHandling(
                        handling ->
                                handling.authenticationEntryPoint(problemAuthenticationEntryPoint)
                                        .accessDeniedHandler(problemAccessDeniedHandler))
                .authorizeHttpRequests(
                        requests ->
                                requests.requestMatchers(PUBLIC_ENDPOINTS.toArray(new String[0]))
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .sessionManagement(
                        session ->
                                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .headers(
                        headers ->
                                headers.frameOptions(frame -> frame.sameOrigin())
                                        .httpStrictTransportSecurity(hsts -> {}))
                .logout(logout -> logout.disable())
                .anonymous(Customizer.withDefaults());

        // Reject integration API headers on staff session endpoints before CSRF/auth handling.
        http.addFilterBefore(
                new IntegrationRejectionFilter(problemResponseWriter),
                org.springframework.security.web.csrf.CsrfFilter.class);

        http.addFilterAfter(
                new com.merqo.common.web.CsrfTokenCookieFilter(csrfTokenRepository),
                org.springframework.security.web.csrf.CsrfFilter.class);

        http.addFilterAfter(
                new com.merqo.user.PasswordChangeRequiredFilter(problemResponseWriter),
                org.springframework.security.web.access.ExceptionTranslationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public PasswordPolicy passwordPolicy() {
        return new PasswordPolicy();
    }

    @Bean
    public LoginThrottlePolicy loginThrottlePolicy() {
        return new LoginThrottlePolicy();
    }

    @Bean
    public PermissionResolver permissionResolver() {
        return new PermissionResolver();
    }

    @Bean
    public com.merqo.access.DelegationPolicy delegationPolicy() {
        return new com.merqo.access.DelegationPolicy();
    }

    @Bean
    public com.merqo.access.AssignmentRules assignmentRules() {
        return new com.merqo.access.AssignmentRules();
    }

    @Bean
    public com.merqo.access.DelegationDefaults delegationDefaults(
            com.merqo.access.PermissionTemplateRepository templateRepository) {
        return new com.merqo.access.DelegationDefaults(templateRepository);
    }
}
