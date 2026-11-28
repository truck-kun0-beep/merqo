package com.merqo.payments;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only repository for {@link CodCollection}. */
public interface CodCollectionRepository extends JpaRepository<CodCollection, UUID> {

    List<CodCollection> findByOrderIdOrderByCollectedOnAscRecordedAtAsc(UUID orderId);

    List<CodCollection> findAllByOrderByCollectedOnAscRecordedAtAsc();

    Optional<CodCollection> findByDemoKey(String demoKey);
}
