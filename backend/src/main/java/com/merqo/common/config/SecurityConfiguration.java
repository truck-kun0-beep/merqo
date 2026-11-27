package com.merqo.common.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import com.merqo.access.PermissionResolver;
import com.merqo.user.LoginThrottlePolicy;
import com.merqo.user.PasswordPolicy;

/**
 * The HTTP security rules of Merqo.
 *
 * <p>Responsibility: describe which endpoints are open, how the session is kept and how the CSRF
 * token travels.
 *
 * <p>The rules:
 *
 * <ul>
 *   <li><b>Default deny.</b> Every request needs an authenticated user, except the endpoints on the
 *       public allow list. The list is data in one place, and a test enumerates every mapped
 *       endpoint and proves that nothing else is open.
 *   <li><b>Server side session.</b> The session lives in PostgreSQL and only holds the user id.
 *       There is no token in local storage.
 *   <li><b>CSRF is on.</b> The token travels in a readable cookie and comes back in a header, which
 *       is the pattern a single page application uses. A POST without a valid token is refused with
 *       a problem response.
 *   <li><b>Session fixation protection.</b> A new session is created on every login.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Configuration over code</b> - the rules are data, not branching logic in the
 *       controllers.
 *   <li><b>Interface implementation</b> - this class implements Spring Security's {@link
 *       SecurityFilterChain} extension point.
 *   <li><b>Dependency injection</b> - the pure Java policy objects are registered as beans, so the
 *       services and the tests share the same rules.
 * </ul>
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

    /**
     * Builds the security filter chain.
     *
     * @param http the HTTP security builder
     * @param sessionProperties the session settings
     * @return the filter chain
     * @throws Exception when the chain cannot be built
     */
    @Bean
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
        // The token is read from the header, not from the request parameter, because the
        // application is a single page application that talks JSON.
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

        // The filter sits right behind the CSRF filter. It asks for the token value and writes the
        // XSRF-TOKEN cookie on every answer, so the browser always holds the token of the session
        // it is in. Without it a sign in answered with the session cookie alone and every request
        // that changed something was refused with a 403.
        http.addFilterAfter(
                new com.merqo.common.web.CsrfTokenCookieFilter(csrfTokenRepository),
                org.springframework.security.web.csrf.CsrfFilter.class);

        // The password rule sits behind the authorization filter, so an anonymous caller is already
        // answered with 401 and only a signed in account with a pending password change is
        // affected. It runs after the exception translation filter, so the problem detail it writes
        // reaches the browser unchanged.
        http.addFilterAfter(
                new com.merqo.user.PasswordChangeRequiredFilter(problemResponseWriter),
                org.springframework.security.web.access.ExceptionTranslationFilter.class);

        return http.build();
    }

    /**
     * The password encoder.
     *
     * <p>BCrypt with strength 10, because the free Render instance has a small CPU and a stronger
     * hash would make every login slow.
     *
     * @return the encoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * The password rules.
     *
     * @return the policy
     */
    @Bean
    public PasswordPolicy passwordPolicy() {
        return new PasswordPolicy();
    }

    /**
     * The login throttle rules.
     *
     * @return the policy
     */
    @Bean
    public LoginThrottlePolicy loginThrottlePolicy() {
        return new LoginThrottlePolicy();
    }

    /**
     * The permission resolution rules.
     *
     * @return the resolver
     */
    @Bean
    public PermissionResolver permissionResolver() {
        return new PermissionResolver();
    }

    /**
     * The delegation rules.
     *
     * @return the policy
     */
    @Bean
    public com.merqo.access.DelegationPolicy delegationPolicy() {
        return new com.merqo.access.DelegationPolicy();
    }

    /**
     * The assignment rules.
     *
     * @return the rules
     */
    @Bean
    public com.merqo.access.AssignmentRules assignmentRules() {
        return new com.merqo.access.AssignmentRules();
    }

    /**
     * The starting delegation list of a new manager.
     *
     * @param templateRepository the templates
     * @return the defaults
     */
    @Bean
    public com.merqo.access.DelegationDefaults delegationDefaults(
            com.merqo.access.PermissionTemplateRepository templateRepository) {
        return new com.merqo.access.DelegationDefaults(templateRepository);
    }
}
