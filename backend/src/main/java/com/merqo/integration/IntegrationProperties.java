package com.merqo.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for external integration secret encryption.
 *
 * <p>Responsibility: bind {@code merqo.integration.secret-key} (mapped from {@code
 * INTEGRATION_SECRET_KEY}).
 */
@Component
@ConfigurationProperties(prefix = "merqo.integration")
public class IntegrationProperties {

    private String secretKey = "";

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey == null ? "" : secretKey;
    }

    public boolean hasSecretKey() {
        return secretKey != null && !secretKey.isBlank();
    }
}
