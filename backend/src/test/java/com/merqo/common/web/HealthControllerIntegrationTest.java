package com.merqo.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration test that runs the real application against the real PostgreSQL database {@code
 * merqo_test}.
 *
 * <p>The brief forbids in-memory database substitutes, because stock locking has to be proven on
 * real PostgreSQL. This test therefore proves that the same database is reachable from the
 * application and that Flyway created the baseline schema.
 *
 * <p>The application context is the real one, with the real servlet filters, so the correlation id
 * behaviour is tested the way it runs in production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("the health endpoint reports the application and the database as up")
    void healthEndpointReportsUp() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("merqo-backend"))
                .andExpect(jsonPath("$.database.status").value("UP"));
    }

    @Test
    @DisplayName("the ping endpoint answers, which is what the uptime monitor calls")
    void pingEndpointAnswers() throws Exception {
        mockMvc.perform(get("/api/v1/health/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pong").value(true))
                .andExpect(jsonPath("$.database.status").value("UP"));
    }

    @Test
    @DisplayName("Flyway created the baseline tables on the test database")
    void flywayCreatedBaselineSchema() {
        Integer tableCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.tables "
                                + "WHERE table_schema = 'public' AND table_name IN "
                                + "('settings', 'locations', 'app_users', 'audit_logs', "
                                + "'spring_session', 'spring_session_attributes', 'flyway_schema_history')",
                        Integer.class);

        assertNotNull(tableCount);
        assertEquals(7, tableCount, "Flyway must create every baseline table");
    }

    @Test
    @DisplayName("the Flyway history records the baseline migration")
    void flywayHistoryContainsBaseline() {
        Integer migrationCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
                        Integer.class);

        assertNotNull(migrationCount);
        assertEquals(1, migrationCount, "The V1 baseline migration must be applied exactly once");
    }

    @Test
    @DisplayName("a request carries a correlation id back to the caller")
    void responseCarriesCorrelationId() throws Exception {
        var response =
                mockMvc.perform(
                                get("/api/v1/health/ping")
                                        .header(
                                                CorrelationIdFilter.CORRELATION_ID_HEADER,
                                                "test-correlation-1"))
                        .andExpect(status().isOk())
                        .andReturn();

        assertEquals(
                "test-correlation-1",
                response.getResponse().getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER));
    }

    @Test
    @DisplayName("a request without a correlation id still gets one back")
    void generatesCorrelationIdWhenMissing() throws Exception {
        var response =
                mockMvc.perform(get("/api/v1/health/ping")).andExpect(status().isOk()).andReturn();

        String correlationId =
                response.getResponse().getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);

        assertNotNull(correlationId);
        assertEquals(false, correlationId.isBlank());
    }
}
