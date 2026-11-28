package com.merqo.integration;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Verifies at startup that {@code INTEGRATION_SECRET_KEY} is configured whenever at least one
 * integration client already exists in the database.
 *
 * <p>Note: in {@code dev} and {@code test} profiles a safe default is supplied; in {@code prod}, a
 * deployment with no integration clients starts cleanly without setting the variable, and fails
 * fast with a clear message if clients exist without a key.
 */
@Component
public class IntegrationKeyStartupCheck implements ApplicationRunner {

    private final IntegrationClientRepository clientRepository;
    private final IntegrationProperties properties;

    public IntegrationKeyStartupCheck(
            IntegrationClientRepository clientRepository, IntegrationProperties properties) {
        this.clientRepository = clientRepository;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.hasSecretKey() && clientRepository.count() > 0) {
            throw new IllegalStateException(
                    "INTEGRATION_SECRET_KEY must be set when integration clients exist.");
        }
    }
}
