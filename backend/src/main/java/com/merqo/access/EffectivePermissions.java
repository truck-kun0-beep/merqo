package com.merqo.access;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The answer of the permission resolver: what this user may actually do, and where.
 *
 * <p>Responsibility: hold the resolved permissions of one user in a form that is cheap to query. A
 * service never asks "which template gave me this permission"; it only asks "may I do this here?".
 *
 * <p>The shape of the answer:
 *
 * <ul>
 *   <li>A <b>global</b> permission is either held everywhere or nowhere.
 *   <li>A <b>location</b> permission is held at a set of locations.
 *   <li>A global permission may also be narrowed to a few locations. The narrow grant is kept
 *       separately from the global grant, and a location specific deny is kept separately from
 *       both.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Immutability / value object</b> - a {@code record} with immutable components and
 *       defensive copies of every collection it holds.
 *   <li><b>Encapsulation</b> - the lookup rules live here, so no service has to re-implement them.
 *   <li><b>Null Object</b> - {@link #none()} is an empty answer rather than a null reference.
 * </ul>
 *
 * @param owner whether the user is the owner, which short-circuits every check
 * @param globalPermissions the permissions the user holds at every location
 * @param locationPermissions the permissions the user holds only at some locations
 * @param locationDenied the permissions the user is denied at some locations
 */
public record EffectivePermissions(
        boolean owner,
        Set<Permission> globalPermissions,
        Map<Permission, Set<UUID>> locationPermissions,
        Map<Permission, Set<UUID>> locationDenied) {

    /**
     * Canonical constructor. The collections are copied so that the record can never be changed
     * from outside after it was created.
     *
     * @param owner whether the user is the owner
     * @param globalPermissions the permissions
     * @param locationPermissions the location permissions and their locations
     * @param locationDenied the denied locations per permission
     */
    public EffectivePermissions {
        globalPermissions = Set.copyOf(globalPermissions);
        locationPermissions = copyOfLocations(locationPermissions);
        locationDenied = copyOfLocations(locationDenied);
    }

    /**
     * Creates an answer without any location specific deny.
     *
     * @param owner whether the user is the owner
     * @param globalPermissions the permissions
     * @param locationPermissions the location permissions and their locations
     */
    public EffectivePermissions(
            boolean owner,
            Set<Permission> globalPermissions,
            Map<Permission, Set<UUID>> locationPermissions) {
        this(owner, globalPermissions, locationPermissions, Map.of());
    }

    private static Map<Permission, Set<UUID>> copyOfLocations(Map<Permission, Set<UUID>> source) {
        Map<Permission, Set<UUID>> copy = new LinkedHashMap<>();
        source.forEach((permission, locations) -> copy.put(permission, Set.copyOf(locations)));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * An answer with no permissions at all. Used for a deactivated user and for anonymous callers.
     *
     * @return the empty answer
     */
    public static EffectivePermissions none() {
        return new EffectivePermissions(false, Set.of(), Map.of(), Map.of());
    }

    /**
     * The answer for the owner: every permission, everywhere.
     *
     * @return the owner answer
     */
    public static EffectivePermissions forOwner() {
        Set<Permission> all = new LinkedHashSet<>(java.util.Arrays.asList(Permission.values()));
        return new EffectivePermissions(true, all, Map.of(), Map.of());
    }

    /**
     * Whether the user may do something that is not tied to one location.
     *
     * @param permission the permission
     * @return {@code true} when the permission is held everywhere
     */
    public boolean can(Permission permission) {
        if (owner) {
            return true;
        }
        return globalPermissions.contains(permission);
    }

    /**
     * Whether the user may do something at one location.
     *
     * <p>The order of the checks is the whole rule: a deny at this location beats everything, then
     * a global grant, then a grant that only covers this location.
     *
     * @param permission the permission
     * @param locationId the location
     * @return {@code true} when the permission is held at that location
     */
    public boolean can(Permission permission, UUID locationId) {
        if (owner) {
            return true;
        }
        if (locationId == null) {
            return false;
        }
        Set<UUID> deniedHere = locationDenied.get(permission);
        if (deniedHere != null && deniedHere.contains(locationId)) {
            return false;
        }
        if (globalPermissions.contains(permission)) {
            return true;
        }
        Set<UUID> locations = locationPermissions.get(permission);
        return locations != null && locations.contains(locationId);
    }

    /**
     * The locations at which the user holds a location scoped permission.
     *
     * <p>This is the method every list query uses to scope its search. The answer is empty when the
     * user may not do it anywhere, which makes the query return nothing instead of everything.
     *
     * @param permission the permission
     * @return the location ids, never {@code null}
     */
    public Set<UUID> locationsFor(Permission permission) {
        if (owner) {
            return Set.of();
        }
        Set<UUID> locations = locationPermissions.get(permission);
        if (locations == null) {
            return Set.of();
        }
        Set<UUID> denied = locationDenied.get(permission);
        if (denied == null || denied.isEmpty()) {
            return locations;
        }
        Set<UUID> result = new LinkedHashSet<>(locations);
        result.removeAll(denied);
        return Collections.unmodifiableSet(result);
    }

    /**
     * The location permissions of this user, for the interface.
     *
     * @return an unmodifiable map of permission to locations
     */
    public Map<Permission, Set<UUID>> getLocationPermissions() {
        return locationPermissions;
    }

    /**
     * The global permissions of this user, for the interface.
     *
     * @return an unmodifiable set of permissions
     */
    public Set<Permission> getGlobalPermissions() {
        return globalPermissions;
    }

    /**
     * The permissions that are denied at one specific location.
     *
     * @return an unmodifiable map of permission to denied locations
     */
    public Map<Permission, Set<UUID>> getLocationDenied() {
        return locationDenied;
    }
}
