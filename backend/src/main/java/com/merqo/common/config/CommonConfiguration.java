package com.merqo.common.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the shared infrastructure beans of the common module.
 *
 * <p>Responsibility: provide the clock that every time calculation uses, so tests can replace it
 * with a fixed clock.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Dependency injection</b> - services ask for a {@link Clock} instead of calling {@code
 *       Instant.now()} directly, which is what makes the Asia/Dhaka day boundary tests possible.
 *   <li><b>Open/closed principle</b> - the production behaviour can be swapped for a test double
 *       without changing any calling code.
 * </ul>
 */
@Configuration
public class CommonConfiguration {

    /**
     * The system clock, in UTC.
     *
     * @return the clock bean
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
