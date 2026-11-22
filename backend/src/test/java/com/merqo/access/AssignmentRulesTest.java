package com.merqo.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The unit test of the assignment rules.
 *
 * <p>It runs without Spring. The rules answer one question: may this combination of account type,
 * assignment type and location type exist?
 */
class AssignmentRulesTest {

    private static final UUID UserId = UUID.fromString("33333333-0000-4000-8000-000000000001");
    private static final UUID OtherUserId = UUID.fromString("33333333-0000-4000-8000-000000000002");
    private static final UUID LocationId = UUID.fromString("33333333-0000-4000-8000-000000000010");

    private final AssignmentRules rules = new AssignmentRules();

    /**
     * A request with the given facts.
     *
     * @param accountType the kind of account
     * @param assignmentType the kind of assignment
     * @param locationType the kind of location
     * @param existingManager the current active manager, or {@code null}
     * @param replaceExisting whether the current manager may be replaced
     * @return the request
     */
    private static AssignmentRules.Request request(
            AccountType accountType,
            AssignmentType assignmentType,
            LocationType locationType,
            UUID existingManager,
            boolean replaceExisting) {
        return new AssignmentRules.Request(
                UserId,
                accountType,
                LocationId,
                locationType,
                assignmentType,
                existingManager,
                replaceExisting);
    }

    @Test
    @DisplayName("a manager may be appointed at a branch")
    void aManagerMayBeAppointedAtABranch() {
        AssignmentRules.Decision decision =
                rules.check(
                        request(
                                AccountType.MANAGER,
                                AssignmentType.MANAGER,
                                LocationType.BRANCH,
                                null,
                                false));

        assertTrue(decision.allowed());
    }

    @Test
    @DisplayName("a staff account may never be appointed as a manager")
    void aStaffAccountMayNeverBeAppointedAsAManager() {
        AssignmentRules.Decision decision =
                rules.check(
                        request(
                                AccountType.STAFF,
                                AssignmentType.MANAGER,
                                LocationType.BRANCH,
                                null,
                                false));

        assertFalse(decision.allowed());
        assertEquals(AssignmentRules.Reason.ACCOUNT_TYPE_CANNOT_BE_MANAGER, decision.reason());
    }

    @Test
    @DisplayName("a warehouse accepts only staff assignments")
    void aWarehouseAcceptsOnlyStaffAssignments() {
        AssignmentRules.Decision decision =
                rules.check(
                        request(
                                AccountType.MANAGER,
                                AssignmentType.MANAGER,
                                LocationType.WAREHOUSE,
                                null,
                                false));

        assertFalse(decision.allowed());
        assertEquals(AssignmentRules.Reason.WAREHOUSE_ACCEPTS_STAFF_ONLY, decision.reason());
    }

    @Test
    @DisplayName("a staff assignment at a warehouse is allowed")
    void aStaffAssignmentAtAWarehouseIsAllowed() {
        AssignmentRules.Decision decision =
                rules.check(
                        request(
                                AccountType.STAFF,
                                AssignmentType.STAFF,
                                LocationType.WAREHOUSE,
                                null,
                                false));

        assertTrue(decision.allowed());
    }

    @Test
    @DisplayName("a location that already has a manager is refused")
    void aLocationThatAlreadyHasAManagerIsRefused() {
        AssignmentRules.Decision decision =
                rules.check(
                        request(
                                AccountType.MANAGER,
                                AssignmentType.MANAGER,
                                LocationType.BRANCH,
                                OtherUserId,
                                false));

        assertFalse(decision.allowed());
        assertEquals(AssignmentRules.Reason.PRIMARY_MANAGER_EXISTS, decision.reason());
        assertEquals("PRIMARY_MANAGER_EXISTS", decision.reason().getErrorCode());
    }

    @Test
    @DisplayName("the manager may be replaced when the caller asks for it")
    void theManagerMayBeReplacedWhenTheCallerAsksForIt() {
        AssignmentRules.Decision decision =
                rules.check(
                        request(
                                AccountType.MANAGER,
                                AssignmentType.MANAGER,
                                LocationType.BRANCH,
                                OtherUserId,
                                true));

        assertTrue(decision.allowed());
    }

    @Test
    @DisplayName("the same manager may not be appointed twice")
    void theSameManagerMayNotBeAppointedTwice() {
        AssignmentRules.Decision decision =
                rules.check(
                        request(
                                AccountType.MANAGER,
                                AssignmentType.MANAGER,
                                LocationType.BRANCH,
                                UserId,
                                true));

        assertFalse(decision.allowed());
        assertEquals(AssignmentRules.Reason.ALREADY_THE_ACTIVE_MANAGER, decision.reason());
    }

    @Test
    @DisplayName("an unknown account type is refused")
    void anUnknownAccountTypeIsRefused() {
        AssignmentRules.Decision decision =
                rules.check(request(null, AssignmentType.STAFF, LocationType.BRANCH, null, false));

        assertFalse(decision.allowed());
        assertEquals(AssignmentRules.Reason.UNKNOWN_ACCOUNT_TYPE, decision.reason());
    }

    @Test
    @DisplayName("an unknown location type is refused")
    void anUnknownLocationTypeIsRefused() {
        AssignmentRules.Decision decision =
                rules.check(request(AccountType.STAFF, AssignmentType.STAFF, null, null, false));

        assertFalse(decision.allowed());
        assertEquals(AssignmentRules.Reason.UNKNOWN_LOCATION_TYPE, decision.reason());
    }

    @Test
    @DisplayName("the scope tells whether a location is inside it")
    void theScopeTellsWhetherALocationIsInsideIt() {
        AssignmentRules.Scope scope = new AssignmentRules.Scope(java.util.Set.of(LocationId));

        assertTrue(scope.contains(LocationId));
        assertFalse(scope.contains(UUID.randomUUID()));
    }
}
