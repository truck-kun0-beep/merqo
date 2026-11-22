package com.merqo.location;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AssignmentType;
import com.merqo.access.LocationAssignment;
import com.merqo.access.LocationAssignmentRepository;
import com.merqo.access.LocationType;
import com.merqo.access.Permission;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;

/**
 * The rules of the locations.
 *
 * <p>Responsibility: create, edit, deactivate and activate locations, and decide which location
 * fulfils the online orders.
 *
 * <p>Two rules are worth naming:
 *
 * <ul>
 *   <li>Only a warehouse may be the online fulfilment location, and there is only one of them. The
 *       old flag is cleared in the same transaction, so there is never a moment with two.
 *   <li>A location may only be deactivated when every guard agrees. The guards are found by Spring,
 *       so a later module can add its own veto without touching this class.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Observer and Strategy</b> - the deactivation guards are collected by the framework and
 *       asked one by one.
 *   <li><b>Single responsibility</b> - the location rules live here, not in the controller.
 *   <li><b>Dependency injection</b> - the guards and the repositories are injected.
 * </ul>
 */
@Service
public class LocationService {

    private final LocationRepository locationRepository;
    private final LocationAssignmentRepository assignmentRepository;
    private final List<LocationDeactivationGuard> guards;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param locationRepository the locations
     * @param assignmentRepository the assignments
     * @param guards the deactivation guards
     * @param auditService the audit log
     * @param clock the clock
     */
    public LocationService(
            LocationRepository locationRepository,
            LocationAssignmentRepository assignmentRepository,
            List<LocationDeactivationGuard> guards,
            AuditService auditService,
            Clock clock) {
        this.locationRepository = locationRepository;
        this.assignmentRepository = assignmentRepository;
        this.guards = List.copyOf(guards);
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Every location the caller may see.
     *
     * @param visibleLocationIds the locations the caller may see
     * @param allLocations whether the caller may see every location
     * @return the locations
     */
    @Transactional(readOnly = true)
    public List<Location> list(Set<UUID> visibleLocationIds, boolean allLocations) {
        List<Location> locations = locationRepository.findAllByOrderByCodeAsc();
        if (allLocations) {
            return locations;
        }
        return locations.stream()
                .filter(location -> visibleLocationIds.contains(location.getId()))
                .toList();
    }

    /**
     * One location.
     *
     * @param locationId the location
     * @param visibleLocationIds the locations the caller may see
     * @param allLocations whether the caller may see every location
     * @return the location
     * @throws NotFoundException when the location is outside the scope of the caller
     */
    @Transactional(readOnly = true)
    public Location get(UUID locationId, Set<UUID> visibleLocationIds, boolean allLocations) {
        Location location =
                locationRepository
                        .findById(locationId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        if (!allLocations && !visibleLocationIds.contains(locationId)) {
            throw new NotFoundException("The requested record was not found.");
        }
        return location;
    }

    /**
     * Creates a location.
     *
     * @param code the short code
     * @param name the display name
     * @param type the kind of location
     * @param address the address
     * @param phone the phone number
     * @param actorUserId who created it
     * @param actorUsername the username of that user
     * @return the new location
     */
    @Transactional
    public Location create(
            String code,
            String name,
            LocationType type,
            String address,
            String phone,
            UUID actorUserId,
            String actorUsername) {
        String trimmedCode = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
        if (trimmedCode.isEmpty()) {
            throw new ValidationException("VALIDATION_FAILED", "The location needs a code.");
        }
        if (locationRepository.existsByCodeIgnoreCase(trimmedCode)) {
            throw new ConflictException(
                    "DUPLICATE_VALUE", "A location with this code already exists.");
        }
        Instant now = Instant.now(clock);
        Location location = new Location(UUID.randomUUID(), trimmedCode, name, type, now);
        location.setAddress(address);
        location.setPhone(phone);
        locationRepository.save(location);

        auditService.record(
                new AuditEvent.Builder(AuditAction.LOCATION_CREATED, "locations")
                        .entityId(location.getId())
                        .actor(actorUserId, actorUsername)
                        .added("code", location.getCode())
                        .added("name", location.getName())
                        .added("locationType", location.getLocationType().name())
                        .build());
        return location;
    }

    /**
     * Edits a location.
     *
     * @param location the location
     * @param name the new display name
     * @param address the new address
     * @param phone the new phone number
     * @param actorUserId who changed it
     * @param actorUsername the username of that user
     * @return the changed location
     */
    @Transactional
    public Location update(
            Location location,
            String name,
            String address,
            String phone,
            UUID actorUserId,
            String actorUsername) {
        String oldName = location.getName();
        String oldAddress = location.getAddress();
        String oldPhone = location.getPhone();
        if (name != null && !name.isBlank()) {
            location.setName(name.trim());
        }
        if (address != null) {
            location.setAddress(address.trim());
        }
        if (phone != null) {
            location.setPhone(phone.trim());
        }
        location.setUpdatedAt(Instant.now(clock));
        locationRepository.save(location);

        auditService.record(
                new AuditEvent.Builder(AuditAction.LOCATION_UPDATED, "locations")
                        .entityId(location.getId())
                        .actor(actorUserId, actorUsername)
                        .changed("name", oldName, location.getName())
                        .changed("address", oldAddress, location.getAddress())
                        .changed("phone", oldPhone, location.getPhone())
                        .build());
        return location;
    }

    /**
     * Deactivates a location, when every guard agrees.
     *
     * @param location the location
     * @param actorUserId who deactivated it
     * @param actorUsername the username of that user
     * @return the deactivated location
     */
    @Transactional
    public Location deactivate(Location location, UUID actorUserId, String actorUsername) {
        for (LocationDeactivationGuard guard : guards) {
            LocationDeactivationGuard.GuardResult result = guard.check(location);
            if (!result.allowed()) {
                throw new ConflictException("LOCATION_IN_USE", result.reason());
            }
        }
        location.setActive(false);
        location.setUpdatedAt(Instant.now(clock));
        locationRepository.save(location);

        auditService.record(
                new AuditEvent.Builder(AuditAction.LOCATION_DEACTIVATED, "locations")
                        .entityId(location.getId())
                        .actor(actorUserId, actorUsername)
                        .changed("isActive", true, false)
                        .build());
        return location;
    }

    /**
     * Activates a location again.
     *
     * @param location the location
     * @param actorUserId who activated it
     * @param actorUsername the username of that user
     * @return the activated location
     */
    @Transactional
    public Location activate(Location location, UUID actorUserId, String actorUsername) {
        location.setActive(true);
        location.setUpdatedAt(Instant.now(clock));
        locationRepository.save(location);

        auditService.record(
                new AuditEvent.Builder(AuditAction.LOCATION_ACTIVATED, "locations")
                        .entityId(location.getId())
                        .actor(actorUserId, actorUsername)
                        .changed("isActive", false, true)
                        .build());
        return location;
    }

    /**
     * Makes one active warehouse the online fulfilment location.
     *
     * <p>The previous flag is cleared in the same transaction, so there is never a moment with two
     * online fulfilment locations.
     *
     * @param location the new online fulfilment location
     * @param actorUserId who changed it
     * @param actorUsername the username of that user
     * @return the new online fulfilment location
     */
    @Transactional
    public Location setOnlineFulfillment(
            Location location, UUID actorUserId, String actorUsername) {
        if (!location.isActive()) {
            throw new ValidationException(
                    "VALIDATION_FAILED", "Only an active location may fulfil online orders.");
        }
        if (location.getLocationType() != LocationType.WAREHOUSE) {
            throw new ValidationException(
                    "VALIDATION_FAILED", "Only a warehouse may fulfil online orders.");
        }
        Location previous =
                locationRepository.findFirstByOnlineFulfillmentTrueAndActiveTrue().orElse(null);
        if (previous != null && !previous.getId().equals(location.getId())) {
            previous.setOnlineFulfillment(false);
            previous.setUpdatedAt(Instant.now(clock));
            locationRepository.save(previous);
        }
        location.setOnlineFulfillment(true);
        location.setUpdatedAt(Instant.now(clock));
        locationRepository.save(location);

        auditService.record(
                new AuditEvent.Builder(AuditAction.ONLINE_LOCATION_CHANGED, "locations")
                        .entityId(location.getId())
                        .actor(actorUserId, actorUsername)
                        .changed(
                                "onlineFulfillmentLocation",
                                previous == null ? null : previous.getId().toString(),
                                location.getId().toString())
                        .build());
        return location;
    }

    /**
     * The active assignments of a location.
     *
     * @param locationId the location
     * @return the assignments
     */
    @Transactional(readOnly = true)
    public List<LocationAssignment> assignments(UUID locationId) {
        return assignmentRepository.findByLocationIdAndEndedAtIsNull(locationId);
    }

    /**
     * The permission a caller needs to look at the locations.
     *
     * @return the permission
     */
    public static Permission requiredPermission() {
        return Permission.LOCATION_VIEW;
    }

    /**
     * The kind of assignment of the active manager of a location.
     *
     * @param locationId the location
     * @return the manager assignment, if there is one
     */
    @Transactional(readOnly = true)
    public java.util.Optional<LocationAssignment> activeManager(UUID locationId) {
        return assignmentRepository.findFirstByLocationIdAndAssignmentTypeAndEndedAtIsNull(
                locationId, AssignmentType.MANAGER);
    }
}
