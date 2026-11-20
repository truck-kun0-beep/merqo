package com.merqo.common.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import com.merqo.common.web.CorrelationIdFilter;

/**
 * Configures Cross-Origin Resource Sharing from the environment.
 *
 * <p>Responsibility: allow the browser origins listed in {@code CORS_ORIGINS} to call the API
 * during local development.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Configuration over code</b> - the allowed origins live in an environment variable, so
 *       the same build behaves differently in development and production without a code change.
 * </ul>
 *
 * <p>In production the browser only ever talks to its own origin through the Cloudflare Pages
 * proxy, so CORS is not needed there. The filter stays in place because it costs nothing and keeps
 * direct API access usable for debugging.
 */
@Configuration
public class CorsConfigurationSetup {

    /**
     * The CORS filter built from the configured origins.
     *
     * @param corsOrigins comma separated list of allowed origins
     * @return the CORS filter
     */
    @Bean
    public CorsFilter corsFilter(MerqoCorsProperties corsOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = corsOrigins.allowedOrigins();
        if (!origins.isEmpty()) {
            configuration.setAllowedOrigins(origins);
        }
        configuration.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(List.of(CorrelationIdFilter.CORRELATION_ID_HEADER));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return new CorsFilter(source);
    }
}
