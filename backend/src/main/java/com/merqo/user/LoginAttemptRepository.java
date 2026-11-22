package com.merqo.user;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The database access for the login attempts.
 *
 * <p>Responsibility: read and write the {@code login_attempts} table. This is operational data for
 * the throttle, never business data, so it is deleted after 24 hours.
 *
 * <p>OOP concept: the interface is the contract the throttle service depends on.
 */
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, UUID> {

    /**
     * Finds the attempts of one identifier since a moment.
     *
     * @param identifier the login name or email
     * @param since the start of the window
     * @return the attempts
     */
    @Query(
            "select a from LoginAttempt a "
                    + "where a.identifier = :identifier and a.attemptedAt >= :since")
    List<LoginAttempt> findByIdentifierSince(
            @Param("identifier") String identifier, @Param("since") Instant since);

    /**
     * Finds the attempts the throttle has to look at.
     *
     * <p>The throttle needs the failures of the account that was typed <b>and</b> the failures of
     * the address the request came from. The two sets overlap, because most attempts carry both.
     * The query therefore uses {@code distinct}, so an attempt that matches both is only returned
     * once. Without it the same failure would be counted twice and the account would be locked
     * after three attempts instead of five.
     *
     * @param identifier the login name or the email address
     * @param ipAddress the address, or an empty string when it is not known
     * @param since the start of the window
     * @return the attempts
     */
    @Query(
            "select distinct a from LoginAttempt a "
                    + "where a.attemptedAt >= :since "
                    + "and (a.identifier = :identifier or a.ipAddress = :ipAddress)")
    List<LoginAttempt> findForThrottleSince(
            @Param("identifier") String identifier,
            @Param("ipAddress") String ipAddress,
            @Param("since") Instant since);

    /**
     * Counts the attempts from one address since a moment.
     *
     * @param ipAddress the address
     * @param since the start of the window
     * @return the number of attempts
     */
    @Query(
            "select count(a) from LoginAttempt a "
                    + "where a.ipAddress = :ipAddress and a.attemptedAt >= :since")
    long countByIpAddressSince(@Param("ipAddress") String ipAddress, @Param("since") Instant since);

    /**
     * Counts the attempts of one identifier since a moment.
     *
     * @param identifier the identifier
     * @param since the start of the window
     * @return the number of attempts
     */
    @Query(
            "select count(a) from LoginAttempt a "
                    + "where a.identifier = :identifier and a.attemptedAt >= :since")
    long countByIdentifierSince(
            @Param("identifier") String identifier, @Param("since") Instant since);

    /**
     * Deletes every attempt older than a moment.
     *
     * <p>The scheduled job calls this once a day. Running it twice changes nothing, so it is
     * idempotent.
     *
     * @param moment the moment
     * @return the number of deleted attempts
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from LoginAttempt a where a.attemptedAt < :moment")
    int deleteOlderThan(@Param("moment") Instant moment);
}
