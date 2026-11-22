package com.merqo.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The unit test of the delegation policy.
 *
 * <p>It runs without Spring. The policy answers one question: may this manager hand this permission
 * to this staff member at this location?
 *
 * <p>All four conditions of the access model are proved, and each way the answer can be no is
 * proved as well.
 */
class DelegationPolicyTest {

    private static final UUID ManagerId = UUID.fromString("22222222-0000-4000-8000-000000000001");
    private static final UUID StaffId = UUID.fromString("22222222-0000-4000-8000-000000000002");
    private static final UUID OtherStaffId =
            UUID.fromString("22222222-0000-4000-8000-000000000003");
    private static final UUID Dhanmondi = UUID.fromString("22222222-0000-4000-8000-000000000010");
    private static final UUID Mirpur = UUID.fromString("22222222-0000-4000-8000-000000000011");

    private final DelegationPolicy policy = new DelegationPolicy();

    /**
     * The permissions of a manager who holds the location scoped permissions at Dhanmondi.
     *
     * @return the effective permissions
     */
    private static EffectivePermissions managerHolding() {
        return new EffectivePermissions(
                false,
                Set.of(Permission.PRODUCT_VIEW),
                Map.of(
                        Permission.INVENTORY_VIEW,
                        Set.of(Dhanmondi),
                        Permission.POS_SELL,
                        Set.of(Dhanmondi),
                        Permission.TRANSFER_REQUEST,
                        Set.of(Dhanmondi)));
    }

    /**
     * A request that is allowed by every condition.
     *
     * @return the request
     */
    private static DelegationPolicy.Request allowedRequest() {
        return new DelegationPolicy.Request(
                ManagerId,
                StaffId,
                AccountType.STAFF,
                Permission.INVENTORY_VIEW,
                Dhanmondi,
                managerHolding(),
                Set.of(Dhanmondi),
                Set.of(Permission.INVENTORY_VIEW, Permission.POS_SELL));
    }

    @Test
    @DisplayName("a delegable permission on the list is allowed")
    void aDelegablePermissionOnTheListIsAllowed() {
        DelegationPolicy.Decision decision = policy.check(allowedRequest());

        assertTrue(decision.allowed());
        assertEquals(null, decision.reason());
    }

    @Test
    @DisplayName("an owner only permission is refused")
    void anOwnerOnlyPermissionIsRefused() {
        DelegationPolicy.Decision decision =
                policy.check(
                        new DelegationPolicy.Request(
                                ManagerId,
                                StaffId,
                                AccountType.STAFF,
                                Permission.LOCATION_MANAGE,
                                Dhanmondi,
                                managerHolding(),
                                Set.of(Dhanmondi),
                                Set.of(Permission.LOCATION_MANAGE)));

        assertFalse(decision.allowed());
        assertEquals(DelegationPolicy.Reason.OWNER_ONLY_PERMISSION, decision.reason());
        assertEquals("OWNER_ONLY_PERMISSION", decision.reason().getErrorCode());
    }

    @Test
    @DisplayName("a permission that is not delegable is refused")
    void aPermissionThatIsNotDelegableIsRefused() {
        DelegationPolicy.Decision decision =
                policy.check(
                        new DelegationPolicy.Request(
                                ManagerId,
                                StaffId,
                                AccountType.STAFF,
                                Permission.INVENTORY_ADJUST,
                                Dhanmondi,
                                managerHolding(),
                                Set.of(Dhanmondi),
                                Set.of(Permission.INVENTORY_ADJUST)));

        assertFalse(decision.allowed());
        assertEquals(DelegationPolicy.Reason.NOT_DELEGABLE, decision.reason());
    }

    @Test
    @DisplayName("a permission that is not on the delegation list is refused")
    void aPermissionThatIsNotOnTheListIsRefused() {
        DelegationPolicy.Decision decision =
                policy.check(
                        new DelegationPolicy.Request(
                                ManagerId,
                                StaffId,
                                AccountType.STAFF,
                                Permission.INVENTORY_VIEW,
                                Dhanmondi,
                                managerHolding(),
                                Set.of(Dhanmondi),
                                Set.of()));

        assertFalse(decision.allowed());
        assertEquals(DelegationPolicy.Reason.NOT_IN_DELEGATION_LIST, decision.reason());
    }

    @Test
    @DisplayName("a permission the manager does not hold is refused")
    void aPermissionTheManagerDoesNotHoldIsRefused() {
        DelegationPolicy.Decision decision =
                policy.check(
                        new DelegationPolicy.Request(
                                ManagerId,
                                StaffId,
                                AccountType.STAFF,
                                Permission.TRANSFER_RECEIVE,
                                Dhanmondi,
                                managerHolding(),
                                Set.of(Dhanmondi),
                                Set.of(Permission.TRANSFER_RECEIVE)));

        assertFalse(decision.allowed());
        assertEquals(DelegationPolicy.Reason.MANAGER_DOES_NOT_HOLD_PERMISSION, decision.reason());
    }

    @Test
    @DisplayName("a location outside the scope of the manager is refused")
    void aLocationOutsideTheScopeIsRefused() {
        DelegationPolicy.Decision decision =
                policy.check(
                        new DelegationPolicy.Request(
                                ManagerId,
                                StaffId,
                                AccountType.STAFF,
                                Permission.INVENTORY_VIEW,
                                Mirpur,
                                managerHolding(),
                                Set.of(Dhanmondi),
                                Set.of(Permission.INVENTORY_VIEW)));

        assertFalse(decision.allowed());
        assertEquals(DelegationPolicy.Reason.LOCATION_OUTSIDE_MANAGER_SCOPE, decision.reason());
    }

    @Test
    @DisplayName("the owner may never receive a delegation")
    void theOwnerMayNeverReceiveADelegation() {
        DelegationPolicy.Decision decision =
                policy.check(
                        new DelegationPolicy.Request(
                                ManagerId,
                                StaffId,
                                AccountType.OWNER,
                                Permission.INVENTORY_VIEW,
                                Dhanmondi,
                                managerHolding(),
                                Set.of(Dhanmondi),
                                Set.of(Permission.INVENTORY_VIEW)));

        assertFalse(decision.allowed());
        assertEquals(DelegationPolicy.Reason.TARGET_IS_OWNER, decision.reason());
    }

    @Test
    @DisplayName("another manager may never receive a delegation")
    void anotherManagerMayNeverReceiveADelegation() {
        DelegationPolicy.Decision decision =
                policy.check(
                        new DelegationPolicy.Request(
                                ManagerId,
                                StaffId,
                                AccountType.MANAGER,
                                Permission.INVENTORY_VIEW,
                                Dhanmondi,
                                managerHolding(),
                                Set.of(Dhanmondi),
                                Set.of(Permission.INVENTORY_VIEW)));

        assertFalse(decision.allowed());
        assertEquals(DelegationPolicy.Reason.TARGET_IS_MANAGER, decision.reason());
    }

    @Test
    @DisplayName("a manager may never delegate to themselves")
    void aManagerMayNeverDelegateToThemselves() {
        DelegationPolicy.Decision decision =
                policy.check(
                        new DelegationPolicy.Request(
                                ManagerId,
                                ManagerId,
                                AccountType.STAFF,
                                Permission.INVENTORY_VIEW,
                                Dhanmondi,
                                managerHolding(),
                                Set.of(Dhanmondi),
                                Set.of(Permission.INVENTORY_VIEW)));

        assertFalse(decision.allowed());
        assertEquals(DelegationPolicy.Reason.TARGET_IS_THE_MANAGER, decision.reason());
    }

    @Test
    @DisplayName("every refusal carries the same stable error code")
    void everyRefusalCarriesTheSameStableErrorCode() {
        for (DelegationPolicy.Reason reason : DelegationPolicy.Reason.values()) {
            assertFalse(reason.getErrorCode().isBlank(), "A reason must carry an error code");
            assertEquals(reason.getErrorCode(), reason.getErrorCode().toUpperCase());
        }
    }

    @Test
    @DisplayName("the request copies its collections, so a later change cannot rewrite it")
    void theRequestCopiesItsCollections() {
        java.util.Set<UUID> scope = new java.util.LinkedHashSet<>(List.of(Dhanmondi));
        DelegationPolicy.Request request =
                new DelegationPolicy.Request(
                        ManagerId,
                        StaffId,
                        AccountType.STAFF,
                        Permission.INVENTORY_VIEW,
                        Dhanmondi,
                        managerHolding(),
                        scope,
                        Set.of(Permission.INVENTORY_VIEW));

        scope.add(Mirpur);

        assertFalse(request.managerScope().contains(Mirpur));
    }
}
