package com.merqo.pos;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence contract for {@link PinAttempt}.
 *
 * <p>Responsibility: query recent PIN attempts for throttling and prune expired operational rows.
 *
 * <p>OOP concept: <b>Repository Abstraction</b> — isolates throttle storage queries.
 */
public interface PinAttemptRepository extends JpaRepository<PinAttempt, UUID> {

    List<PinAttempt> findByUserIdAndAttemptedAtAfterOrderByAttemptedAtDesc(
            UUID userId, Instant after);

    @Modifying
    @Query("delete from PinAttempt p where p.attemptedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
