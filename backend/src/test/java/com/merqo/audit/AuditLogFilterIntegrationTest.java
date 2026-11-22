package com.merqo.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import com.merqo.access.AccountType;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * The integration test of the audit log filters.
 *
 * <p>It runs against the real PostgreSQL database {@code merqo_test}.
 *
 * <p>The filters of the audit log are written with {@code coalesce} instead of the usual {@code
 * :filter is null}, because a parameter that only appears in a null check has no type of its own
 * and PostgreSQL then refuses the query. This test proves that the rewrite kept the meaning:
 *
 * <ol>
 *   <li>No filter at all returns every row, including the rows whose location is empty.
 *   <li>A filter that matches nothing returns nothing, so the filters really narrow the answer.
 *   <li>A filter that matches returns exactly the rows of that value.
 * </ol>
 *
 * <p><b>The test writes the rows it needs itself.</b> It must pass from an empty database and in
 * any order, because the suite may run it before or after the other test classes. The rows are
 * written through the audit service, so the real write path is used, and they carry a marker in the
 * entity type. The marker lets the test look at its own rows only, so the assertions stay true no
 * matter what else is in the table.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditLogFilterIntegrationTest {

    /**
     * The entity type of the rows this test writes.
     *
     * <p>It is a name that no real module uses, so the test never mixes its rows with the rows of
     * the application.
     */
    private static final String ENTITY_TYPE = "audit_filter_test";

    /** The action of the rows this test writes. */
    private static final String ACTION = "LOGIN_SUCCEEDED";

    /**
     * A page size that holds every row of the table.
     *
     * <p>The test database holds far fewer rows than this, so one page always holds the whole
     * answer and the assertions may look at every row.
     */
    private static final int ALL_ROWS = 10_000;

    @Autowired private AuditLogRepository auditLogRepository;

    @Autowired private AuditService auditService;

    @Autowired private AppUserRepository userRepository;

    @Autowired private LocationRepository locationRepository;

    private UUID actorId;

    private UUID locationId;

    @BeforeEach
    void writeTheRowsTheTestNeeds() {
        AppUser actor =
                userRepository
                        .findByDemoKey("owner")
                        .orElseGet(
                                () ->
                                        userRepository.save(
                                                new AppUser(
                                                        UUID.randomUUID(),
                                                        "audit-filter-actor",
                                                        null,
                                                        "Audit filter actor",
                                                        "not-a-real-hash",
                                                        AccountType.STAFF,
                                                        Instant.now())));
        actorId = actor.getId();

        Location location =
                locationRepository
                        .findByCodeIgnoreCase("DM")
                        .orElseGet(
                                () ->
                                        locationRepository.save(
                                                new Location(
                                                        UUID.randomUUID(),
                                                        "AFT",
                                                        "Audit filter location",
                                                        com.merqo.access.LocationType.BRANCH,
                                                        Instant.now())));
        locationId = location.getId();

        // One row with an actor and a location, and one company wide row without a location. The
        // second one proves that a row whose location is empty is not lost when no filter is given.
        auditService.record(
                new AuditEvent.Builder(AuditAction.LOGIN_SUCCEEDED, ENTITY_TYPE)
                        .entityId(actorId)
                        .locationId(locationId)
                        .actor(actorId, actor.getUsername())
                        .added("marker", "with-location")
                        .build());
        auditService.record(
                new AuditEvent.Builder(AuditAction.LOGIN_SUCCEEDED, ENTITY_TYPE)
                        .entityId(actorId)
                        .actor(actorId, actor.getUsername())
                        .added("marker", "without-location")
                        .build());
    }

    /**
     * The rows this test wrote, without the rows of the other test classes.
     *
     * @param actorUserId the actor filter, may be {@code null}
     * @param action the action filter, may be {@code null}
     * @param entityType the entity type filter, may be {@code null}
     * @param locationId the location filter, may be {@code null}
     * @param from the start of the date range, may be {@code null}
     * @param to the end of the date range, may be {@code null}
     * @return the rows of this test
     */
    private Page<AuditLog> searchOwnRows(
            UUID actorUserId,
            String action,
            String entityType,
            UUID locationId,
            Instant from,
            Instant to) {
        return auditLogRepository.search(
                actorUserId,
                action,
                entityType,
                locationId,
                from,
                to,
                List.of(),
                true,
                PageRequest.of(0, ALL_ROWS));
    }

    @Test
    @DisplayName("no filter returns every row")
    void noFilterReturnsEveryRow() {
        long total = auditLogRepository.count();

        Page<AuditLog> page = searchOwnRows(null, null, null, null, null, null);

        assertEquals(total, page.getTotalElements(), "Without a filter every row must be returned");
    }

    @Test
    @DisplayName("no filter keeps the rows whose location is empty")
    void noFilterKeepsTheRowsWithoutALocation() {
        long rowsWithoutALocation =
                auditLogRepository.findAll().stream()
                        .filter(row -> row.getLocationId() == null)
                        .count();

        Page<AuditLog> page = searchOwnRows(null, null, null, null, null, null);

        long returnedWithoutALocation =
                page.getContent().stream().filter(row -> row.getLocationId() == null).count();
        assertEquals(
                rowsWithoutALocation,
                returnedWithoutALocation,
                "A company wide row must not be lost when no filter is given");
    }

    @Test
    @DisplayName("an action that does not exist returns nothing")
    void anActionThatDoesNotExistReturnsNothing() {
        Page<AuditLog> page = searchOwnRows(null, "NO_SUCH_ACTION", null, null, null, null);

        assertEquals(
                0, page.getTotalElements(), "A filter that matches nothing must return nothing");
    }

    @Test
    @DisplayName("an action that exists returns only the rows of that action")
    void anActionThatExistsReturnsOnlyThoseRows() {
        long expected = auditLogRepository.countByAction(ACTION);

        Page<AuditLog> page = searchOwnRows(null, ACTION, null, null, null, null);

        assertEquals(expected, page.getTotalElements());
        assertFalse(page.getContent().isEmpty());
        page.getContent().forEach(row -> assertEquals(ACTION, row.getAction()));
    }

    @Test
    @DisplayName("an entity type filter narrows the answer")
    void anEntityTypeFilterNarrowsTheAnswer() {
        Page<AuditLog> page = searchOwnRows(null, null, "app_users", null, null, null);

        page.getContent().forEach(row -> assertEquals("app_users", row.getEntityType()));
    }

    @Test
    @DisplayName("an actor filter narrows the answer")
    void anActorFilterNarrowsTheAnswer() {
        Page<AuditLog> page = searchOwnRows(actorId, null, null, null, null, null);

        assertFalse(
                page.getContent().isEmpty(), "The actor filter must find the rows of that actor");
        page.getContent().forEach(row -> assertEquals(actorId, row.getActorUserId()));
    }

    @Test
    @DisplayName("a date range narrows the answer")
    void aDateRangeNarrowsTheAnswer() {
        Instant farInTheFuture = Instant.parse("2999-01-01T00:00:00Z");

        Page<AuditLog> page = searchOwnRows(null, null, null, null, farInTheFuture, null);

        assertEquals(
                0, page.getTotalElements(), "A range that starts in the future matches nothing");
    }

    @Test
    @DisplayName("a location filter narrows the answer")
    void aLocationFilterNarrowsTheAnswer() {
        Page<AuditLog> page = searchOwnRows(null, null, null, locationId, null, null);

        page.getContent().forEach(row -> assertEquals(locationId, row.getLocationId()));
        assertTrue(page.getTotalElements() <= auditLogRepository.count());
    }
}
