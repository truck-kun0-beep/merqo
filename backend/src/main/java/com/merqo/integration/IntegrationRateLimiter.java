package com.merqo.integration;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database-backed sliding-window rate limiter enforcing {@value #LIMIT_PER_MINUTE} requests per
 * minute per integration client key, and recording {@code last_used_at} on accepted calls.
 */
@Service
public class IntegrationRateLimiter {

    public static final int LIMIT_PER_MINUTE = 60;

    private final IntegrationRateAttemptRepository attemptRepository;
    private final IntegrationClientRepository clientRepository;
    private final Clock clock;

    public IntegrationRateLimiter(
            IntegrationRateAttemptRepository attemptRepository,
            IntegrationClientRepository clientRepository,
            Clock clock) {
        this.attemptRepository = attemptRepository;
        this.clientRepository = clientRepository;
        this.clock = clock;
    }

    /**
     * Attempts to record one request for {@code clientId} within the current 60-second window.
     *
     * @param clientId authenticated integration client id
     * @return {@code true} if allowed (and {@code last_used_at} updated), {@code false} when
     *     throttled
     */
    @Transactional
    public boolean tryConsumeAndTouch(UUID clientId) {
        Instant now = Instant.now(clock);
        Instant windowStart = now.minusSeconds(60);
        attemptRepository.deleteOlderThan(now.minusSeconds(120));

        long recentCount =
                attemptRepository.countByClientIdAndAttemptedAtGreaterThanEqual(
                        clientId, windowStart);
        if (recentCount >= LIMIT_PER_MINUTE) {
            return false;
        }

        attemptRepository.save(new IntegrationRateAttempt(UUID.randomUUID(), clientId, now));
        clientRepository.touchLastUsedAt(clientId, now);
        return true;
    }
}
