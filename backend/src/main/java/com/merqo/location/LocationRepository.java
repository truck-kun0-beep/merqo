package com.merqo.location;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * The database access for locations.
 *
 * <p>Responsibility: read and write the {@code locations} table.
 *
 * <p>OOP concept: the interface is the contract the service depends on.
 */
public interface LocationRepository extends JpaRepository<Location, UUID> {

    /**
     * Finds a location by its short code.
     *
     * @param code the code
     * @return the location, if it exists
     */
    Optional<Location> findByCodeIgnoreCase(String code);

    /**
     * Whether a location with this code exists.
     *
     * @param code the code
     * @return {@code true} when it exists
     */
    boolean existsByCodeIgnoreCase(String code);

    /**
     * Finds every location, active or not.
     *
     * @return the locations
     */
    List<Location> findAllByOrderByCodeAsc();

    /**
     * Finds every active location.
     *
     * @return the active locations
     */
    List<Location> findByActiveTrueOrderByCodeAsc();

    /**
     * The id of every location of the business.
     *
     * <p>The owner holds every permission everywhere, so the lists of the owner are scoped with
     * this answer instead of with the assignments of the owner, which are none.
     *
     * @return the location ids
     */
    @Query("select l.id from Location l")
    List<UUID> findAllIds();

    /**
     * Finds the active online fulfilment location.
     *
     * @return the location, if there is one
     */
    Optional<Location> findFirstByOnlineFulfillmentTrueAndActiveTrue();
}
