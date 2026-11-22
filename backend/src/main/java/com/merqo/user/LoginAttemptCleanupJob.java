package com.merqo.user;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes the old login attempts.
 *
 * <p>Responsibility: keep the throttle table small. The login attempts are operational data, not
 * business data, so they are deleted after 24 hours.
 *
 * <p>The job is <b>idempotent</b>: running it twice deletes nothing the second time, because the
 * rows that qualify are already gone.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Observer</b> - the scheduled method reacts to the clock.
 *   <li><b>Idempotent operation</b> - the same call twice gives the same end state.
 * </ul>
 */
@Component
public class LoginAttemptCleanupJob {

    /** How long a login attempt is kept. */
    public static final Duration RETENTION = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptCleanupJob.class);

    private final LoginAttemptRepository attemptRepository;
    private final Clock clock;

    /**
     * Creates the job.
     *
     * @param attemptRepository the login attempts
     * @param clock the clock
     */
    public LoginAttemptCleanupJob(LoginAttemptRepository attemptRepository, Clock clock) {
        this.attemptRepository = attemptRepository;
        this.clock = clock;
    }

    /** Deletes the attempts that are older than a day. */
    @Scheduled(cron = "0 17 3 * * *")
    @Transactional
    public void deleteOldAttempts() {
        Instant limit = Instant.now(clock).minus(RETENTION);
        int deleted = attemptRepository.deleteOlderThan(limit);
        if (deleted > 0) {
            log.info("Deleted {} login attempts that were older than 24 hours.", deleted);
        }
    }
}
