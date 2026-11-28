package com.merqo.integration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** JPA repository for {@link IntegrationClient}. */
public interface IntegrationClientRepository extends JpaRepository<IntegrationClient, UUID> {

    Optional<IntegrationClient> findByKeyId(String keyId);

    Optional<IntegrationClient> findByDemoKey(String demoKey);

    List<IntegrationClient> findAllByOrderByCreatedAtDesc();

    @Modifying
    @Query("update IntegrationClient c set c.lastUsedAt = :usedAt where c.id = :id")
    void touchLastUsedAt(@Param("id") UUID id, @Param("usedAt") Instant usedAt);
}
