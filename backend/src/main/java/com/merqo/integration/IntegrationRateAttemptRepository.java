package com.merqo.integration;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Database-backed rate-limit store for integration API calls. */
public interface IntegrationRateAttemptRepository
        extends JpaRepository<IntegrationRateAttempt, UUID> {

    long countByClientIdAndAttemptedAtGreaterThanEqual(UUID clientId, Instant since);

    @Modifying
    @Query("delete from IntegrationRateAttempt a where a.attemptedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
