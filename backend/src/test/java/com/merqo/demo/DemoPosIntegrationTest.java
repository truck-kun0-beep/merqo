package com.merqo.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.merqo.pos.CashSessionRepository;
import com.merqo.pos.CashSessionStatus;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * Verifies Phase 3 demo data seeding and idempotency when running {@link DemoSeeder} repeatedly.
 */
@SpringBootTest
@ActiveProfiles("test")
class DemoPosIntegrationTest {

    @Autowired private DemoSeeder demoSeeder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AppUserRepository userRepository;
    @Autowired private CashSessionRepository cashSessionRepository;

    @Test
    @DisplayName("demo POS seeder seeds 25 sales, sessions, returns, PINs, and is idempotent")
    void demoPosSeedingIsIdempotent() throws Exception {
        // Initial seeding already ran at Spring startup; verify counts and state.
        Integer seededSales =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM sales WHERE demo_key LIKE 'pos-demo-sale-%'",
                        Integer.class);
        Integer seededSessions =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM cash_sessions WHERE demo_key LIKE 'pos-demo-session-%'",
                        Integer.class);
        Integer seededReturns =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM sale_returns WHERE demo_key LIKE 'pos-demo-return-%'",
                        Integer.class);

        assertEquals(25, seededSales);
        assertEquals(5, seededSessions);
        assertEquals(2, seededReturns);

        AppUser cashier = userRepository.findByDemoKey("cashier").orElseThrow();
        assertTrue(
                cashSessionRepository
                        .findByCashierIdAndStatus(cashier.getId(), CashSessionStatus.OPEN)
                        .isPresent());
        assertNotNull(userRepository.findByDemoKey("owner").orElseThrow().getApprovalPinHash());
        assertNotNull(
                userRepository.findByDemoKey("manager-dm").orElseThrow().getApprovalPinHash());
        assertNotNull(
                userRepository.findByDemoKey("manager-ut").orElseThrow().getApprovalPinHash());

        int totalSalesBefore = count("sales");
        int totalSessionsBefore = count("cash_sessions");
        int totalReturnsBefore = count("sale_returns");
        int totalMovementsBefore = count("stock_movements");

        // Running the full seeder a second time leaves all row counts identical.
        demoSeeder.run(null);

        assertEquals(totalSalesBefore, count("sales"));
        assertEquals(totalSessionsBefore, count("cash_sessions"));
        assertEquals(totalReturnsBefore, count("sale_returns"));
        assertEquals(totalMovementsBefore, count("stock_movements"));
    }

    private int count(String table) {
        Integer c = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return c == null ? 0 : c;
    }
}
