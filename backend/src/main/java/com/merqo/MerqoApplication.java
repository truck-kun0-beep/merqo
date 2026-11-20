package com.merqo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The entry point of the Merqo backend.
 *
 * <p>Responsibility: start the Spring context and let component scanning find every module.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Composition</b> - the whole application is assembled from small, replaceable parts
 *       (controllers, services, repositories) that Spring wires together.
 * </ul>
 *
 * <p>The packages {@code com.merqo.auth}, {@code com.merqo.locations} and the rest of the business
 * modules are added as they are built in the later prompts.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MerqoApplication {

    /**
     * Starts the application.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(MerqoApplication.class, args);
    }
}
