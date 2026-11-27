package com.merqo.pos;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence contract for {@link ApprovalToken}.
 *
 * <p>Responsibility: look up approval tokens by their SHA-256 hash, including pessimistic locking
 * during single-use consumption.
 *
 * <p>OOP concept: <b>Repository Abstraction</b> — encapsulates lock-safe token lookup.
 */
public interface ApprovalTokenRepository extends JpaRepository<ApprovalToken, UUID> {

    Optional<ApprovalToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ApprovalToken t where t.tokenHash = :hash")
    Optional<ApprovalToken> findByTokenHashForUpdate(@Param("hash") String hash);
}
