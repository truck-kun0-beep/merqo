package com.merqo.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.merqo.integration.IntegrationClientRepository;
import com.merqo.location.LocationRepository;
import com.merqo.orders.Order;
import com.merqo.orders.OrderRepository;
import com.merqo.orders.domain.OrderStatus;

/** Verifies idempotency and coverage of {@link DemoOrdersContributor} in demo mode. */
@SpringBootTest
@ActiveProfiles("test")
class DemoOrdersIntegrationTest {

    @Autowired private DemoOrdersContributor contributor;
    @Autowired private OrderRepository orderRepository;
    @Autowired private IntegrationClientRepository clientRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName(
            "demo orders contributor seeds 20 orders idempotently across all states and preserves balance invariants")
    void seedsIdempotentlyAndPreservesReservedInvariants() {
        assertTrue(
                clientRepository
                        .findByDemoKey(DemoSimulatorController.DEMO_CLIENT_KEY)
                        .isPresent());
        for (int i = 1; i <= 20; i++) {
            String key = String.format("demo-ord-%02d", i);
            Order found = orderRepository.findByDemoKey(key).orElse(null);
            assertNotNull(found, "Missing demo order: " + key);
        }

        // Verify re-running contributor produces zero additional demo orders
        long countBefore = orderRepository.count();
        contributor.seed();
        assertEquals(countBefore, orderRepository.count());

        // Verify CONFIRMED, FOLLOW_UP, DECLINED, CANCELLED, DELIVERED, REFUSED, RETURNED all exist
        assertTrue(
                orderRepository.findByDemoKey("demo-ord-01").orElseThrow().getStatus()
                        == OrderStatus.DELIVERED);
        assertTrue(
                orderRepository.findByDemoKey("demo-ord-04").orElseThrow().getStatus()
                        == OrderStatus.DECLINED);
        assertTrue(
                orderRepository.findByDemoKey("demo-ord-05").orElseThrow().getStatus()
                        == OrderStatus.CANCELLED);
        assertTrue(
                orderRepository.findByDemoKey("demo-ord-06").orElseThrow().getStatus()
                        == OrderStatus.FOLLOW_UP);
        assertTrue(
                orderRepository.findByDemoKey("demo-ord-07").orElseThrow().getStatus()
                        == OrderStatus.REFUSED);
        assertTrue(
                orderRepository.findByDemoKey("demo-ord-15").orElseThrow().getStatus()
                        == OrderStatus.RETURNED);
        assertTrue(orderRepository.findByDemoKey("demo-ord-12").orElseThrow().isPriceMismatch());
        assertTrue(
                orderRepository
                                .findByDemoKey("demo-ord-14")
                                .orElseThrow()
                                .getCancellationRequestedAt()
                        != null);

        UUID whId = locationRepository.findByCodeIgnoreCase("WH").orElseThrow().getId();
        Integer balanceReserved =
                jdbcTemplate.queryForObject(
                        "SELECT coalesce(sum(reserved), 0) FROM inventory_balances WHERE"
                                + " location_id = ?",
                        Integer.class,
                        whId);
        Integer confirmedSum =
                jdbcTemplate.queryForObject(
                        """
                        SELECT coalesce(sum(ol.quantity), 0)
                        FROM order_lines ol
                        JOIN orders o ON o.id = ol.order_id
                        WHERE o.location_id = ? AND o.status = 'CONFIRMED'
                        """,
                        Integer.class,
                        whId);
        assertEquals(confirmedSum, balanceReserved);
    }
}
