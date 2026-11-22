package com.merqo.access;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns templates, overrides and assignments into one answer: the effective permissions of a user.
 *
 * <p>Responsibility: apply the resolution rules of the access model in exactly one place, so that
 * every service sees the same answer.
 *
 * <p>The rules, in the order they are applied:
 *
 * <ol>
 *   <li>The owner gets every permission everywhere. Nothing is stored for the owner.
 *   <li>Start with the union of the permissions of every active template the user holds.
 *   <li>Add every ALLOW override.
 *   <li>Remove every DENY override. A deny always beats an allow and beats a template.
 *   <li>A permission that is flagged owner only is never effective for a non owner, whatever a
 *       template or an override says.
 *   <li>A location scoped permission is effective at a location only when the user has an active
 *       assignment there. A global permission needs no assignment.
 *   <li>A deactivated user has no effective permissions at all.
 * </ol>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - this class only resolves. It never talks to a database and
 *       never decides what a user is allowed to do at runtime.
 *   <li><b>Pure function</b> - the same input always gives the same answer, which is what makes the
 *       unit tests possible without a Spring context.
 *   <li><b>Strategy</b> - the resolution steps are written as small private methods that each do
 *       one thing.
 *   <li><b>Composition</b> - the class is built from several small input records instead of one
 *       huge parameter list.
 * </ul>
 */
public class PermissionResolver {

    /**
     * Resolves the effective permissions of one user.
     *
     * @param input everything the rules need
     * @return the resolved permissions
     */
    public EffectivePermissions resolve(ResolutionInput input) {
        if (input.owner()) {
            return EffectivePermissions.forOwner();
        }
        if (!input.active()) {
            return EffectivePermissions.none();
        }

        Set<UUID> assignedLocations = input.assignedLocationIds();

        Map<Permission, Set<UUID>> locationPermissions = new EnumMap<>(Permission.class);
        Map<Permission, Set<UUID>> locationDenied = new EnumMap<>(Permission.class);
        Set<Permission> globalPermissions = new LinkedHashSet<>();

        // Step 1 and 2: the union of every template, then every ALLOW override.
        for (Permission permission : input.templatePermissions()) {
            grant(permission, null, assignedLocations, globalPermissions, locationPermissions);
        }
        for (PermissionOverride override : input.overrides()) {
            if (override.effect() == OverrideEffect.ALLOW) {
                grant(
                        override.permission(),
                        override.locationId(),
                        assignedLocations,
                        globalPermissions,
                        locationPermissions);
            }
        }

        // Step 3: every DENY override removes the permission again. A deny always wins.
        for (PermissionOverride override : input.overrides()) {
            if (override.effect() == OverrideEffect.DENY) {
                deny(
                        override.permission(),
                        override.locationId(),
                        globalPermissions,
                        locationPermissions,
                        locationDenied);
            }
        }

        // A location scoped permission without an assignment is useless, so drop it.
        locationPermissions.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        return new EffectivePermissions(
                false, globalPermissions, locationPermissions, locationDenied);
    }

    private void grant(
            Permission permission,
            UUID locationId,
            Set<UUID> assignedLocations,
            Set<Permission> globalPermissions,
            Map<Permission, Set<UUID>> locationPermissions) {
        if (permission.isOwnerOnly()) {
            // An owner only permission is never effective for a non owner.
            return;
        }
        if (permission.isLocationScoped()) {
            Set<UUID> locations =
                    locationPermissions.computeIfAbsent(permission, key -> new LinkedHashSet<>());
            if (locationId == null) {
                locations.addAll(assignedLocations);
            } else if (assignedLocations.contains(locationId)) {
                // A location limited grant only counts where the user is actually assigned.
                locations.add(locationId);
            }
            return;
        }
        if (locationId == null) {
            globalPermissions.add(permission);
            return;
        }
        // A global permission that is only granted at one location.
        locationPermissions
                .computeIfAbsent(permission, key -> new LinkedHashSet<>())
                .add(locationId);
    }

    private void deny(
            Permission permission,
            UUID locationId,
            Set<Permission> globalPermissions,
            Map<Permission, Set<UUID>> locationPermissions,
            Map<Permission, Set<UUID>> locationDenied) {
        if (permission.isLocationScoped()) {
            Set<UUID> locations = locationPermissions.get(permission);
            if (locations != null) {
                if (locationId == null) {
                    locations.clear();
                } else {
                    locations.remove(locationId);
                }
            }
            return;
        }
        if (locationId == null) {
            // A global deny removes the permission everywhere.
            globalPermissions.remove(permission);
            locationPermissions.remove(permission);
            return;
        }
        // A deny at one location keeps the permission everywhere else.
        locationDenied.computeIfAbsent(permission, key -> new LinkedHashSet<>()).add(locationId);
    }

    /**
     * Whether an owner only permission may be stored for a non owner.
     *
     * <p>Called before an override or a template is written, so that the database never holds a
     * permission that could never become effective.
     *
     * @param permission the permission
     * @return {@code true} when the permission may be stored
     */
    public boolean isStorable(Permission permission) {
        return !permission.isOwnerOnly();
    }

    /**
     * Splits a requested set of permissions into the ones that may be stored and the owner only
     * ones that were rejected.
     *
     * @param permissions the requested permissions
     * @param ownerOnlyFound collects the owner only permissions that were rejected
     * @return the permissions that may be stored
     */
    public List<Permission> filterStorable(
            Collection<Permission> permissions, List<Permission> ownerOnlyFound) {
        List<Permission> storable = new ArrayList<>();
        for (Permission permission : permissions) {
            if (permission.isOwnerOnly()) {
                ownerOnlyFound.add(permission);
                continue;
            }
            storable.add(permission);
        }
        return storable;
    }

    /**
     * Everything the resolution rules need to know about one user.
     *
     * <p>OOP concept: a small immutable input record keeps the method signature readable and makes
     * the resolver easy to call from a test.
     *
     * @param owner whether the account is the owner
     * @param active whether the account is active
     * @param templatePermissions the union of the permissions of every active template
     * @param overrides the ALLOW and DENY overrides of the user
     * @param assignedLocationIds the locations the user currently has an active assignment at
     */
    public record ResolutionInput(
            boolean owner,
            boolean active,
            Set<Permission> templatePermissions,
            List<PermissionOverride> overrides,
            Set<UUID> assignedLocationIds) {

        /**
         * Canonical constructor with defensive copies.
         *
         * @param owner whether the account is the owner
         * @param active whether the account is active
         * @param templatePermissions the template permissions
         * @param overrides the overrides
         * @param assignedLocationIds the assigned locations
         */
        public ResolutionInput {
            templatePermissions = Set.copyOf(templatePermissions);
            overrides = List.copyOf(overrides);
            assignedLocationIds = Set.copyOf(assignedLocationIds);
        }

        /**
         * A convenient input for a user with templates and assignments but no overrides.
         *
         * @param templatePermissions the template permissions
         * @param assignedLocationIds the assigned locations
         * @return the input
         */
        public static ResolutionInput of(
                Set<Permission> templatePermissions, Set<UUID> assignedLocationIds) {
            return new ResolutionInput(
                    false, true, templatePermissions, List.of(), assignedLocationIds);
        }
    }

    /**
     * One ALLOW or DENY override of one permission.
     *
     * @param permission the permission
     * @param effect whether the permission is allowed or denied
     * @param locationId the location the override is limited to, or {@code null} for every location
     */
    public record PermissionOverride(
            Permission permission, OverrideEffect effect, UUID locationId) {

        /**
         * Creates an override.
         *
         * @param permission the permission
         * @param effect the effect
         * @param locationId the location, or {@code null}
         */
        public PermissionOverride {
            if (permission == null) {
                throw new IllegalArgumentException("An override needs a permission");
            }
            if (effect == null) {
                throw new IllegalArgumentException("An override needs an effect");
            }
        }

        /**
         * An override that applies everywhere.
         *
         * @param permission the permission
         * @param effect the effect
         * @return the override
         */
        public static PermissionOverride of(Permission permission, OverrideEffect effect) {
            return new PermissionOverride(permission, effect, null);
        }

        /**
         * An override that applies at one location only.
         *
         * @param permission the permission
         * @param effect the effect
         * @param locationId the location
         * @return the override
         */
        public static PermissionOverride at(
                Permission permission, OverrideEffect effect, UUID locationId) {
            return new PermissionOverride(permission, effect, locationId);
        }
    }

    /** Whether an override allows or denies. */
    public enum OverrideEffect {
        /** The permission is added. */
        ALLOW,
        /** The permission is removed. A deny always beats an allow. */
        DENY
    }

    /**
     * A read-only view of the resolved permissions with the reason for each one.
     *
     * <p>Used by the "why does this person have this permission" view.
     *
     * @param permission the permission
     * @param scope the location ids the permission applies to, empty for "everywhere"
     * @param reasons why the permission is effective
     */
    public record ExplainedPermission(
            Permission permission, List<UUID> scope, List<String> reasons) {

        /**
         * Creates an explained permission.
         *
         * @param permission the permission
         * @param scope the scope
         * @param reasons the reasons
         */
        public ExplainedPermission {
            scope = List.copyOf(scope);
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * Explains every effective permission, so the interface can show why it is there.
     *
     * @param input the same input the resolver uses
     * @return one entry per effective permission
     */
    public List<ExplainedPermission> explain(ResolutionInput input) {
        EffectivePermissions effective = resolve(input);
        Map<Permission, List<String>> grantedBy = new LinkedHashMap<>();
        Map<Permission, List<String>> deniedBy = new LinkedHashMap<>();

        for (Permission permission : input.templatePermissions()) {
            grantedBy.computeIfAbsent(permission, key -> new ArrayList<>()).add("from a template");
        }
        for (PermissionOverride override : input.overrides()) {
            if (override.effect() == OverrideEffect.ALLOW) {
                grantedBy
                        .computeIfAbsent(override.permission(), key -> new ArrayList<>())
                        .add("from an allow override");
            } else {
                deniedBy.computeIfAbsent(override.permission(), key -> new ArrayList<>())
                        .add("denied by an override");
            }
        }

        List<ExplainedPermission> explained = new ArrayList<>();
        for (Permission permission : Permission.values()) {
            List<String> reasons = grantedBy.get(permission);
            if (reasons == null) {
                continue;
            }
            if (input.owner()) {
                explained.add(
                        new ExplainedPermission(permission, List.of(), List.of("owner account")));
                continue;
            }
            if (permission.isOwnerOnly()) {
                continue;
            }
            if (permission.isLocationScoped()) {
                Set<UUID> locations = effective.locationsFor(permission);
                if (locations.isEmpty()) {
                    continue;
                }
                List<String> allReasons = new ArrayList<>(reasons);
                allReasons.addAll(deniedBy.getOrDefault(permission, List.of()));
                explained.add(
                        new ExplainedPermission(permission, List.copyOf(locations), allReasons));
                continue;
            }
            if (!effective.can(permission)) {
                continue;
            }
            List<String> allReasons = new ArrayList<>(reasons);
            allReasons.addAll(deniedBy.getOrDefault(permission, List.of()));
            explained.add(new ExplainedPermission(permission, List.of(), allReasons));
        }
        return explained;
    }
}
