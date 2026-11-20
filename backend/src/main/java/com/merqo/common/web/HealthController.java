package com.merqo.common.web;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.common.time.BusinessTime;

/**
 * The liveness endpoints used by the frontend, the uptime monitor and Render.
 *
 * <p>Responsibility: answer two questions.
 *
 * <ul>
 *   <li>{@code GET /api/v1/health} - is the application running and can it reach the database? Used
 *       by the home page.
 *   <li>{@code GET /api/v1/health/ping} - a deliberately trivial call. A free monitor hits this
 *       every ten minutes so the free Render web service does not go to sleep.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the controller only reads and reports state, it never
 *       changes it.
 *   <li><b>Composition</b> - the ping endpoint reuses the same database check as the health
 *       endpoint instead of duplicating the query.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health", description = "Liveness and readiness of the Merqo backend")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /**
     * Creates the controller.
     *
     * @param jdbcTemplate used for the trivial database round trip
     * @param clock the clock used to report the current time
     */
    public HealthController(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Reports the health of the application and the database.
     *
     * @return the health report
     */
    @GetMapping
    @Operation(summary = "Full health report including the database check")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "merqo-backend");
        body.put("time", Instant.now(clock).toString());
        body.put("businessTime", BusinessTime.nowInBusinessZone(clock).toString());
        body.put("database", databaseStatus());
        return body;
    }

    /**
     * A trivial endpoint for free uptime monitors.
     *
     * <p>It runs one small query so a successful response also proves the database is reachable.
     *
     * @return the pong payload
     */
    @GetMapping("/ping")
    @Operation(summary = "Trivial ping used by the free uptime monitor")
    public Map<String, Object> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("pong", true);
        body.put("time", Instant.now(clock).toString());
        body.put("database", databaseStatus());
        return body;
    }

    private Map<String, Object> databaseStatus() {
        Map<String, Object> database = new LinkedHashMap<>();
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            database.put("status", "UP");
            database.put("probe", result == null ? 0 : result);
        } catch (RuntimeException exception) {
            database.put("status", "DOWN");
            database.put("reason", exception.getClass().getSimpleName());
        }
        return database;
    }
}
