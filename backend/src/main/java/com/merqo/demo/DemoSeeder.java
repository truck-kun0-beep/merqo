package com.merqo.demo;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the demo seeders at startup.
 *
 * <p>Responsibility: run every {@link DemoDataContributor} in order, and only when demo mode is on.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Observer</b> - the runner reacts to the application starting.
 *   <li><b>Polymorphism</b> - the contributors are all the same type, and each one does its own
 *       work.
 *   <li><b>Open/closed principle</b> - a new prompt adds a contributor and touches nothing else.
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "merqo.demo-mode", havingValue = "true")
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final List<DemoDataContributor> contributors;

    /**
     * Creates the runner.
     *
     * @param contributors the contributors, in the order Spring found them
     */
    public DemoSeeder(List<DemoDataContributor> contributors) {
        this.contributors = contributors;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<DemoDataContributor> ordered =
                contributors.stream()
                        .sorted(java.util.Comparator.comparingInt(DemoDataContributor::order))
                        .toList();
        for (DemoDataContributor contributor : ordered) {
            contributor.seed();
        }
        log.info("The demo data is ready. {} contributors ran.", ordered.size());
    }
}
