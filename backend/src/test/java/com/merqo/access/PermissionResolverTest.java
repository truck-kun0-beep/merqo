package com.merqo.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.merqo.access.PermissionResolver.OverrideEffect;
import com.merqo.access.PermissionResolver.PermissionOverride;
import com.merqo.access.PermissionResolver.ResolutionInput;

/**
 * The unit test of the permission resolver.
 *
 * <p>It runs without Spring and without a database, because the resolver is a pure function: the
 * same input always gives the same answer.
 *
 * <p>The rules of the access model that this test proves:
 *
 * <ol>
 *   <li>The union of the permissions of every template is the starting point.
 *   <li>An ALLOW override adds a permission.
 *   <li>A DENY override removes a permission, and it beats a template and an allow.
 *   <li>A location limited override only affects that one location.
 *   <li>An owner only permission is never effective for anybody else.
 *   <li>A location scoped permission needs an assignment at that location.
 *   <li>A global permission needs no assignment.
 *   <li>A deactivated user has no permissions at all.
 *   <li>The owner has every permission, everywhere.
 * </ol>
 */
class PermissionResolverTest {

    private static final UUID Dhanmondi = UUID.fromString("11111111-0000-4000-8000-000000000001");
    private static final UUID Mirpur = UUID.fromString("11111111-0000-4000-8000-000000000002");
    private static final UUID Warehouse = UUID.fromString("11111111-0000-4000-8000-000000000003");

    private final PermissionResolver resolver = new PermissionResolver();

    @Nested
    @DisplayName("the owner")
    class Owner {

        @Test
        @DisplayName("has every permission")
        void hasEveryPermission() {
            EffectivePermissions effective =
                    resolver.resolve(
                            new ResolutionInput(true, true, Set.of(), List.of(), Set.of()));

            for (Permission permission : Permission.values()) {
                assertTrue(effective.can(permission), "The owner must hold " + permission);
            }
        }

        @Test
        @DisplayName("has every permission at every location, even without an assignment")
        void hasEveryPermissionAtEveryLocation() {
            EffectivePermissions effective =
                    resolver.resolve(
                            new ResolutionInput(true, true, Set.of(), List.of(), Set.of()));

            assertTrue(effective.can(Permission.INVENTORY_VIEW, Dhanmondi));
            assertTrue(effective.can(Permission.INVENTORY_VIEW, Warehouse));
        }

        @Test
        @DisplayName("is never refused, even by a deny override")
        void isNeverRefused() {
            EffectivePermissions effective =
                    resolver.resolve(
                            new ResolutionInput(
                                    true,
                                    true,
                                    Set.of(),
                                    List.of(
                                            PermissionOverride.of(
                                                    Permission.INVENTORY_VIEW,
                                                    OverrideEffect.DENY)),
                                    Set.of()));

            assertTrue(effective.can(Permission.INVENTORY_VIEW, Dhanmondi));
        }
    }

    @Nested
    @DisplayName("a deactivated account")
    class Deactivated {

        @Test
        @DisplayName("has no permissions at all")
        void hasNoPermissions() {
            EffectivePermissions effective =
                    resolver.resolve(
                            new ResolutionInput(
                                    false,
                                    false,
                                    Set.of(Permission.INVENTORY_VIEW, Permission.PRODUCT_VIEW),
                                    List.of(),
                                    Set.of(Dhanmondi)));

            assertFalse(effective.can(Permission.INVENTORY_VIEW));
            assertFalse(effective.can(Permission.INVENTORY_VIEW, Dhanmondi));
            assertTrue(effective.locationsFor(Permission.INVENTORY_VIEW).isEmpty());
        }
    }

    @Nested
    @DisplayName("the templates")
    class Templates {

        @Test
        @DisplayName("are the union of every template the user holds")
        void areTheUnionOfEveryTemplate() {
            EffectivePermissions effective =
                    resolver.resolve(
                            ResolutionInput.of(
                                    Set.of(Permission.PRODUCT_VIEW, Permission.INVENTORY_VIEW),
                                    Set.of(Dhanmondi)));

            assertTrue(effective.can(Permission.PRODUCT_VIEW));
            assertTrue(effective.can(Permission.INVENTORY_VIEW, Dhanmondi));
        }
    }

    @Nested
    @DisplayName("the overrides")
    class Overrides {

        @Test
        @DisplayName("an allow adds a permission that no template holds")
        void anAllowAddsAPermission() {
            EffectivePermissions effective =
                    resolver.resolve(
                            new ResolutionInput(
                                    false,
                                    true,
                                    Set.of(),
                                    List.of(
                                            PermissionOverride.of(
                                                    Permission.INVENTORY_VIEW,
                                                    OverrideEffect.ALLOW)),
                                    Set.of(Dhanmondi)));

            assertTrue(effective.can(Permission.INVENTORY_VIEW, Dhanmondi));
        }

        @Test
        @DisplayName("a deny beats a template")
        void aDenyBeatsATemplate() {
            EffectivePermissions effective =
                    resolver.resolve(
                            new ResolutionInput(
                                    false,
                                    true,
                                    Set.of(Permission.INVENTORY_VIEW),
                                    List.of(
                                            PermissionOverride.of(
                                                    Permission.INVENTORY_VIEW,
                                                    OverrideEffect.DENY)),
                                    Set.of(Dhanmondi)));

            assertFalse(effective.can(Permission.INVENTORY_VIEW, Dhanmondi));
        }

        @Test
        @DisplayName("a deny beats an allow")
        void aDenyBeatsAnAllow() {
            EffectivePermissions effective =
                    resolver.resolve(
                            new ResolutionInput(
                                    false,
                                    true,
                                    Set.of(),
                                    List.of(
                                            PermissionOverride.of(
                                                    Permission.INVENTORY_VIEW,
                                                    OverrideEffect.ALLOW),
                                            PermissionOverride.of(
                                                    Permission.INVENTORY_VIEW,
                                                    OverrideEffect.DENY)),
                                    Set.of(Dhanmondi)));

            assertFalse(effective.can(Permission.INVENTORY_VIEW, Dhanmondi));
        }

        @Test
        @DisplayName("a location limited deny only affects that one location")
        void aLocationLimitedDenyOnlyAffectsThatLocation() {
            EffectivePermissions effective =
                    resolver.resolve(
                            new ResolutionInput(
                                    false,
                                    true,
                                    Set.of(Permission.INVENTORY_VIEW),
                                    List.of(
                                            PermissionOverride.at(
                                                    Permission.INVENTORY_VIEW,
                                                    OverrideEffect.DENY,
                                                    Dhanmondi)),
                                    Set.of(Dhanmondi, Mirpur)));

            assertFalse(effective.can(Permission.INVENTORY_VIEW, Dhanmondi));
            assertTrue(effective.can(Permission.INVENTORY_VIEW, Mirpur));
        }

        @Test
        @DisplayName("a location limited allow only counts where the user is assigned")
        void aLocationLimitedAllowOnlyCountsWhereTheUserIsAssigned() {
            EffectivePermissions effective =
                    resolver.resolve(
                            new ResolutionInput(
                                    false,
                                    true,
                                    Set.of(),
                                    List.of(
                                            PermissionOverride.at(
                                                    Permission.INVENTORY_VIEW,
                                                    OverrideEffect.ALLOW,
                                                    Mirpur)),
                                    Set.of(Dhanmondi)));

            assertFalse(effective.can(Permission.INVENTORY_VIEW, Mirpur));
            assertTrue(effective.locationsFor(Permission.INVENTORY_VIEW).isEmpty());
        }
    }

    @Nested
    @DisplayName("the owner only permissions")
    class OwnerOnly {

        @Test
        @DisplayName("are never effective for a non owner, even from a template")
        void areNeverEffectiveForANonOwner() {
            EffectivePermissions effective =
                    resolver.resolve(
                            ResolutionInput.of(
                                    Set.of(Permission.LOCATION_MANAGE), Set.of(Dhanmondi)));

            assertFalse(effective.can(Permission.LOCATION_MANAGE));
        }

        @Test
        @DisplayName("are never effective for a non owner, even from an allow override")
        void areNeverEffectiveEvenFromAnAllowOverride() {
            EffectivePermissions effective =
                    resolver.resolve(
                            new ResolutionInput(
                                    false,
                                    true,
                                    Set.of(),
                                    List.of(
                                            PermissionOverride.of(
                                                    Permission.LOCATION_MANAGE,
                                                    OverrideEffect.ALLOW)),
                                    Set.of(Dhanmondi)));

            assertFalse(effective.can(Permission.LOCATION_MANAGE));
        }

        @Test
        @DisplayName("may not be stored for a non owner")
        void mayNotBeStoredForANonOwner() {
            assertFalse(resolver.isStorable(Permission.LOCATION_MANAGE));
            assertFalse(resolver.isStorable(Permission.MANAGER_MANAGE));
            assertTrue(resolver.isStorable(Permission.INVENTORY_VIEW));
        }

        @Test
        @DisplayName("are reported when they are filtered out")
        void areReportedWhenTheyAreFilteredOut() {
            java.util.List<Permission> rejected = new java.util.ArrayList<>();

            java.util.List<Permission> stored =
                    resolver.filterStorable(
                            List.of(Permission.LOCATION_MANAGE, Permission.INVENTORY_VIEW),
                            rejected);

            assertEquals(List.of(Permission.INVENTORY_VIEW), stored);
            assertEquals(List.of(Permission.LOCATION_MANAGE), rejected);
        }
    }

    @Nested
    @DisplayName("the scope")
    class Scope {

        @Test
        @DisplayName("a location permission needs an assignment at that location")
        void aLocationPermissionNeedsAnAssignment() {
            EffectivePermissions effective =
                    resolver.resolve(
                            ResolutionInput.of(
                                    Set.of(Permission.INVENTORY_VIEW), Set.of(Dhanmondi)));

            assertTrue(effective.can(Permission.INVENTORY_VIEW, Dhanmondi));
            assertFalse(effective.can(Permission.INVENTORY_VIEW, Mirpur));
            assertFalse(effective.can(Permission.INVENTORY_VIEW, Warehouse));
        }

        @Test
        @DisplayName("a global permission needs no assignment")
        void aGlobalPermissionNeedsNoAssignment() {
            EffectivePermissions effective =
                    resolver.resolve(ResolutionInput.of(Set.of(Permission.PRODUCT_VIEW), Set.of()));

            assertTrue(effective.can(Permission.PRODUCT_VIEW));
        }

        @Test
        @DisplayName("the visible locations are the assigned ones")
        void theVisibleLocationsAreTheAssignedOnes() {
            EffectivePermissions effective =
                    resolver.resolve(
                            ResolutionInput.of(
                                    Set.of(Permission.INVENTORY_VIEW), Set.of(Dhanmondi, Mirpur)));

            assertEquals(
                    Set.of(Dhanmondi, Mirpur), effective.locationsFor(Permission.INVENTORY_VIEW));
        }

        @Test
        @DisplayName("a location without an assignment is dropped from the answer")
        void aLocationWithoutAnAssignmentIsDropped() {
            EffectivePermissions effective =
                    resolver.resolve(
                            ResolutionInput.of(Set.of(Permission.INVENTORY_VIEW), Set.of()));

            assertTrue(effective.locationsFor(Permission.INVENTORY_VIEW).isEmpty());
            assertFalse(effective.can(Permission.INVENTORY_VIEW, Dhanmondi));
        }
    }

    @Nested
    @DisplayName("the explanation")
    class Explanation {

        @Test
        @DisplayName("names the template a permission came from")
        void namesTheTemplate() {
            var explained =
                    resolver.explain(
                            ResolutionInput.of(
                                    Set.of(Permission.INVENTORY_VIEW), Set.of(Dhanmondi)));

            var entry =
                    explained.stream()
                            .filter(item -> item.permission() == Permission.INVENTORY_VIEW)
                            .findFirst()
                            .orElseThrow();

            assertEquals(List.of(Dhanmondi), entry.scope());
            assertTrue(entry.reasons().contains("from a template"));
        }

        @Test
        @DisplayName("names the override that denied a permission")
        void namesTheDenial() {
            var explained =
                    resolver.explain(
                            new ResolutionInput(
                                    false,
                                    true,
                                    Set.of(Permission.INVENTORY_VIEW),
                                    List.of(
                                            PermissionOverride.at(
                                                    Permission.INVENTORY_VIEW,
                                                    OverrideEffect.DENY,
                                                    Dhanmondi)),
                                    Set.of(Dhanmondi, Mirpur)));

            var entry =
                    explained.stream()
                            .filter(item -> item.permission() == Permission.INVENTORY_VIEW)
                            .findFirst()
                            .orElseThrow();

            assertTrue(entry.reasons().contains("denied by an override"));
        }
    }
}
