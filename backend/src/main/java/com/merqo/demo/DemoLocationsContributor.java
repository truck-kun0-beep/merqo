package com.merqo.demo;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.LocationType;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;

/**
 * Seeds the demo locations.
 *
 * <p>Responsibility: write the four demo locations, once. The seed is idempotent: it looks for the
 * location by its code and does nothing when it is already there.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Strategy</b> - the contributor is one implementation of {@link DemoDataContributor}.
 *   <li><b>Idempotency</b> - running the seeder twice gives the same result as running it once.
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "merqo.demo-mode", havingValue = "true")
public class DemoLocationsContributor implements DemoDataContributor {

    /** The demo locations and their codes. */
    public static final List<DemoLocation> LOCATIONS =
            List.of(
                    new DemoLocation("WH", "Main Warehouse", LocationType.WAREHOUSE, true),
                    new DemoLocation("DM", "Dhanmondi", LocationType.BRANCH, false),
                    new DemoLocation("MP", "Mirpur", LocationType.BRANCH, false),
                    new DemoLocation("UT", "Uttara", LocationType.BRANCH, false));

    private final LocationRepository locationRepository;
    private final Clock clock;

    /**
     * Creates the contributor.
     *
     * @param locationRepository the locations
     * @param clock the clock
     */
    public DemoLocationsContributor(LocationRepository locationRepository, Clock clock) {
        this.locationRepository = locationRepository;
        this.clock = clock;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    @Transactional
    public void seed() {
        Instant now = Instant.now(clock);
        for (DemoLocation demo : LOCATIONS) {
            Optional<Location> existing = locationRepository.findByCodeIgnoreCase(demo.code());
            if (existing.isPresent()) {
                continue;
            }
            Location location =
                    new Location(
                            UUID.randomUUID(), demo.code(), demo.name(), demo.locationType(), now);
            location.setOnlineFulfillment(demo.onlineFulfillment());
            locationRepository.save(location);
        }
    }

    /**
     * One demo location.
     *
     * @param code the short code
     * @param name the display name
     * @param locationType the kind of location
     * @param onlineFulfillment whether it fulfils the online orders
     */
    public record DemoLocation(
            String code, String name, LocationType locationType, boolean onlineFulfillment) {}
}
