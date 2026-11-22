package com.merqo.location;

import org.springframework.stereotype.Component;

/**
 * Refuses to deactivate the online fulfilment location.
 *
 * <p>Responsibility: one veto on the deactivation of a location. The owner has to move the online
 * orders to another warehouse first.
 *
 * <p>OOP concept: Strategy. The location service asks every guard the same question and takes the
 * first "no".
 */
@Component
public class OnlineFulfillmentLocationGuard implements LocationDeactivationGuard {

    private final LocationRepository locationRepository;

    /**
     * Creates the guard.
     *
     * @param locationRepository the locations
     */
    public OnlineFulfillmentLocationGuard(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    @Override
    public GuardResult check(Location location) {
        if (!location.isOnlineFulfillment()) {
            return GuardResult.allow();
        }
        if (locationRepository.findFirstByOnlineFulfillmentTrueAndActiveTrue().isPresent()) {
            return GuardResult.refuse(
                    "This location fulfils the online orders. Move the online orders to another"
                            + " warehouse first.");
        }
        return GuardResult.allow();
    }
}
