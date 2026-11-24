package com.merqo.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The integration test of the append only rule of the audit log.
 *
 * <p>It runs against the real PostgreSQL database {@code merqo_test}.
 *
 * <p>The audit log has two layers of protection, and this test proves both:
 *
 * <ol>
 *   <li><b>The code.</b> The repository declares no update and no delete method, so no service can
 *       ask for one. The inherited methods of Spring Data are inherited by every repository, so the
 *       test looks at the methods this interface declares itself.
 *   <li><b>The database.</b> A trigger raises an error on any UPDATE or DELETE, so even a bug, a
 *       manual SQL statement or a wrong migration cannot rewrite history.
 * </ol>
 *
 * <p>The database half is the important one, because it holds even against code that is not in this
 * repository.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditAppendOnlyIntegrationTest {

    /** The entity type of the rows this test writes. */
    private static final String ENTITY_TYPE = "append_only_test";

    @Autowired private AuditService auditService;

    @Autowired private AuditLogRepository auditLogRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("the database refuses an UPDATE of an audit row")
    void theDatabaseRefusesAnUpdateOfAnAuditRow() {
        UUID id = writeOneRow();

        DataAccessException failure =
                assertThrows(
                        DataAccessException.class,
                        () ->
                                jdbcTemplate.update(
                                        "update audit_logs set action = 'TAMPERED' where id = ?",
                                        id));

        assertTrue(
                failure.getMessage().contains("append only"),
                "The trigger must say that the audit log is append only, but the message was: "
                        + failure.getMessage());
    }

    @Test
    @DisplayName("the database refuses a DELETE of an audit row")
    void theDatabaseRefusesADeleteOfAnAuditRow() {
        UUID id = writeOneRow();

        DataAccessException failure =
                assertThrows(
                        DataAccessException.class,
                        () -> jdbcTemplate.update("delete from audit_logs where id = ?", id));

        assertTrue(
                failure.getMessage().contains("append only"),
                "The trigger must say that the audit log is append only, but the message was: "
                        + failure.getMessage());
    }

    @Test
    @DisplayName("the row is still there after the refused statements")
    void theRowIsStillThereAfterTheRefusedStatements() {
        UUID id = writeOneRow();
        long before = auditLogRepository.count();

        assertThrows(
                DataAccessException.class,
                () ->
                        jdbcTemplate.update(
                                "update audit_logs set action = 'TAMPERED' where id = ?", id));
        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update("delete from audit_logs where id = ?", id));

        assertEquals(before, auditLogRepository.count(), "The row must still be there");
        assertTrue(
                auditLogRepository.findById(id).isPresent(),
                "The row must not have been changed by the refused statements");
    }

    @Test
    @DisplayName("the repository of the audit log declares no update and no delete method")
    void theRepositoryOfTheAuditLogDeclaresNoUpdateAndNoDeleteMethod() {
        List<String> forbidden = new ArrayList<>();
        for (java.lang.reflect.Method method : AuditLogRepository.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.startsWith("delete") || name.startsWith("remove")) {
                forbidden.add(method.getName());
            }
        }

        assertEquals(
                List.of(),
                forbidden,
                "The audit log is append only, so the repository must not declare a delete method");
    }

    /**
     * Writes one audit row and returns the id of the row itself.
     *
     * <p>The id of the row is read back from the database, because the id of the audited record is
     * a different thing: a row of the audit log carries its own id.
     *
     * @return the id of the new row
     */
    private UUID writeOneRow() {
        UUID entityId = UUID.randomUUID();
        auditService.record(
                new AuditEvent.Builder(AuditAction.SETTING_CHANGED, ENTITY_TYPE)
                        .entityId(entityId)
                        .added("marker", "append-only")
                        .build());

        List<AuditLog> rows =
                auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(
                        ENTITY_TYPE, entityId.toString());
        assertEquals(1, rows.size(), "The test must write exactly one row");
        return rows.get(0).getId();
    }
}
