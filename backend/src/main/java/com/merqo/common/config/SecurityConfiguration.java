package com.merqo.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The HTTP security rules for the bootstrap.
 *
 * <p>Responsibility: describe which endpoints are open and how the session is kept, so that Prompt
 * 1 only has to tighten the rules instead of rewriting the chain.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - this class implements Spring Security's {@code
 *       SecurityFilterChain} extension point.
 *   <li><b>Configuration over code</b> - the rules are data, not branching logic scattered through
 *       controllers.
 * </ul>
 *
 * <p>State of the project: authentication is not built yet (Prompt 1). The API is therefore open,
 * but every endpoint is still a first party endpoint under {@code /api}. The frontend uses a server
 * side session cookie, never a token in local storage, so the same origin proxy on Cloudflare Pages
 * keeps working with third party cookies blocked.
 */
@Configuration
public class SecurityConfiguration {

    /**
     * Builds the security filter chain.
     *
     * @param http the HTTP security builder
     * @return the filter chain
     * @throws Exception when the chain cannot be built
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .sessionManagement(
                        session ->
                                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                                        .maximumSessions(-1))
                .headers(
                        headers ->
                                headers.frameOptions(frame -> frame.sameOrigin())
                                        .httpStrictTransportSecurity(hsts -> {}))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout").deleteCookies("SESSION"));
        return http.build();
    }
}
