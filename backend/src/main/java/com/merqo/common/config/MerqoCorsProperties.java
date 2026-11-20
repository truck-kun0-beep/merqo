package com.merqo.common.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code CORS_ORIGINS} environment variable.
 *
 * <p>Responsibility: read the allowed browser origins from configuration and present them as a
 * clean list.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the raw configuration string never leaves this class; callers only
 *       see a validated list.
 *   <li><b>Immutability</b> - the parsed list is unmodifiable.
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "merqo.cors")
public class MerqoCorsProperties {

    /** Comma separated list of origins that may call the API. */
    private String origins = "";

    /**
     * The configured origins.
     *
     * @return the allowed origins, never {@code null}
     */
    public List<String> allowedOrigins() {
        if (origins == null || origins.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /**
     * Sets the raw origins string.
     *
     * @param origins comma separated list of origins
     */
    public void setOrigins(String origins) {
        this.origins = origins;
    }

    /**
     * The raw origins string.
     *
     * @return the configured value
     */
    public String getOrigins() {
        return origins;
    }
}
