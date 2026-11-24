package com.merqo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The entry point of the Merqo backend.
 *
 * <p>Responsibility: start the Spring context and let component scanning find every module.
 *
 * <p><b>Why the default Spring Security user is excluded.</b> Without this, Spring Boot creates an
 * in memory account with a random password and writes that password into the log at every start.
 * Merqo has its own accounts in PostgreSQL, so that account would be a second, unknown way in and a
 * secret in the log. The exclusion removes it. The proof is the test {@code
 * noDefaultSpringUserExists}.
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
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
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
