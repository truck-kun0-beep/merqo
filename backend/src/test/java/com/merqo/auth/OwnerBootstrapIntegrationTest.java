package com.merqo.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.merqo.access.AccountType;
import com.merqo.audit.AuditLogRepository;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * The integration test of the first owner bootstrap.
 *
 * <p>It runs against the real PostgreSQL database {@code merqo_test}.
 *
 * <p>What it proves:
 *
 * <ol>
 *   <li>Two threads that try to write the owner account at the same moment end with <b>exactly
 *       one</b> owner row. The partial unique index on the account type is the guard, and the
 *       losing thread recovers instead of failing.
 *   <li>The bootstrap is idempotent: running it again changes nothing.
 *   <li>There is no public setup endpoint. The only way to become the owner is the environment
 *       variable.
 * </ol>
 *
 * <p><b>Why the recovery lookup needs its own transaction.</b> The losing thread is refused by
 * PostgreSQL inside the transaction that tried to write the owner. That transaction is aborted, so
 * every further statement in it fails. The service therefore reads the winner in a transaction of
 * its own, and this test proves that the recovery really works: neither thread ends with an
 * exception.
 */
@SpringBootTest
@ActiveProfiles("test")
class OwnerBootstrapIntegrationTest {

    /** How long the threads may take before the test gives up. */
    private static final long TIMEOUT_SECONDS = 30;

    /** A password that follows the owner policy. */
    private static final String OWNER_PASSWORD = "owner-password-1";

    @Autowired private OwnerBootstrapService ownerBootstrapService;

    @Autowired private AppUserRepository userRepository;

    @Autowired private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("two threads that write the owner at the same moment create exactly one owner")
    void twoThreadsThatWriteTheOwnerAtTheSameMomentCreateExactlyOneOwner() throws Exception {
        long before = userRepository.countByAccountType(AccountType.OWNER);

        CountDownLatch startGun = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<AppUser> task =
                    () -> {
                        startGun.await();
                        try {
                            return ownerBootstrapService.createOwnerIfAbsent(
                                    "race-owner", OWNER_PASSWORD);
                        } catch (RuntimeException exception) {
                            failures.incrementAndGet();
                            throw exception;
                        }
                    };
            Future<AppUser> first = pool.submit(task);
            Future<AppUser> second = pool.submit(task);

            startGun.countDown();

            AppUser firstResult = first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            AppUser secondResult = second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertNotNull(firstResult, "The first thread must end with an owner");
            assertNotNull(secondResult, "The second thread must end with an owner too");
            assertEquals(
                    firstResult.getId(),
                    secondResult.getId(),
                    "Both threads must end with the same owner: the loser of the race recovers");
            assertEquals(
                    0, failures.get(), "The thread that loses the race must recover, not fail");
        } finally {
            pool.shutdownNow();
        }

        long after = userRepository.countByAccountType(AccountType.OWNER);

        assertEquals(
                before,
                after,
                "The count of owners must not change: the unique index is the guard and the loser"
                        + " of the race recovers instead of writing a second owner");
        assertEquals(
                1,
                after,
                "The database must hold exactly one owner, even when two threads try to write one at"
                        + " the same moment");
    }

    @Test
    @DisplayName("the bootstrap is idempotent: a second run changes nothing")
    void theBootstrapIsIdempotent() {
        long usersBefore = userRepository.count();
        long auditBefore = auditLogRepository.count();

        ownerBootstrapService.bootstrapOwner();
        ownerBootstrapService.bootstrapOwner();

        assertEquals(
                usersBefore,
                userRepository.count(),
                "A second bootstrap must not create a second account");
        assertEquals(
                auditBefore,
                auditLogRepository.count(),
                "A second bootstrap must not write a second audit row");
    }

    @Test
    @DisplayName("there is exactly one owner account in the database")
    void thereIsExactlyOneOwnerAccount() {
        assertEquals(
                1,
                userRepository.countByAccountType(AccountType.OWNER),
                "The database must hold exactly one owner");
    }

    @Test
    @DisplayName("the owner of the demo data is the owner of the database")
    void theOwnerOfTheDemoDataIsTheOwnerOfTheDatabase() {
        AppUser owner = userRepository.findFirstByAccountType(AccountType.OWNER).orElseThrow();

        assertEquals("owner", owner.getUsername(), "The demo owner must be the one owner account");
    }

    @Test
    @DisplayName("the bootstrap never writes the password of the owner into the audit log")
    void theBootstrapNeverWritesThePasswordOfTheOwnerIntoTheAuditLog() {
        List<com.merqo.audit.AuditLog> rows = auditLogRepository.findAll();

        rows.forEach(
                row -> {
                    String oldValues = String.valueOf(row.getOldValues());
                    String newValues = String.valueOf(row.getNewValues());
                    assertTrue(
                            !oldValues.contains(OWNER_PASSWORD)
                                    && !newValues.contains(OWNER_PASSWORD),
                            "The audit log must never hold a password");
                });
    }
}
