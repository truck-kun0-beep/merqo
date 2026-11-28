package com.merqo.integration;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** JPA repository for {@link IntegrationInbox}. */
public interface IntegrationInboxRepository extends JpaRepository<IntegrationInbox, UUID> {

    Optional<IntegrationInbox> findByClientIdAndExternalOrderId(
            UUID clientId, String externalOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select i from IntegrationInbox i
            where i.clientId = :clientId and i.externalOrderId = :externalOrderId
            """)
    Optional<IntegrationInbox> findForUpdate(
            @Param("clientId") UUID clientId, @Param("externalOrderId") String externalOrderId);
}
