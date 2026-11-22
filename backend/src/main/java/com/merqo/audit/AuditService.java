package com.merqo.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.common.web.CorrelationIdFilter;

/**
 * Writes the audit rows.
 *
 * <p>Responsibility: turn an {@link AuditEvent} into a row in the audit log. The service is called
 * from the other services in the same transaction as the change it records, so a failed change
 * never leaves an audit row behind.
 *
 * <p>The audit log is append only. The repository has no update and no delete method, and a
 * database trigger raises an error on any UPDATE or DELETE.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - this class is the only place that writes audit rows.
 *   <li><b>Dependency injection</b> - the clock and the repository are injected.
 *   <li><b>Facade</b> - the callers do not have to know how a row is built.
 * </ul>
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param auditLogRepository the audit log
     * @param clock the clock
     */
    public AuditService(AuditLogRepository auditLogRepository, Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    /**
     * Writes one audit row.
     *
     * @param event the event
     */
    @Transactional
    public void record(AuditEvent event) {
        AuditLog row = new AuditLog(UUID.randomUUID(), Instant.now(clock), event);
        auditLogRepository.save(row);
    }

    /**
     * Writes one audit row and fills in the correlation id and the address from the request.
     *
     * @param event the event
     * @param request the current request, may be {@code null}
     */
    @Transactional
    public void record(AuditEvent event, HttpServletRequest request) {
        AuditEvent enriched =
                new AuditEvent(
                        event.action(),
                        event.entityType(),
                        event.entityId(),
                        event.locationId(),
                        event.actorUserId(),
                        event.actorUsername(),
                        event.oldValues(),
                        event.newValues(),
                        event.ipAddress() != null ? event.ipAddress() : clientIp(request),
                        event.correlationId() != null
                                ? event.correlationId()
                                : currentCorrelationId());
        record(enriched);
    }

    /**
     * The audit rows of one record.
     *
     * @param entityType the entity type
     * @param entityId the entity id
     * @return the rows
     */
    @Transactional(readOnly = true)
    public List<AuditLog> forEntity(String entityType, String entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(
                entityType, entityId);
    }

    /**
     * The address the request came from, as the forwarded header the Cloudflare Function sets.
     *
     * <p>The value can be spoofed by anybody who calls the Render URL directly. That is written
     * down in ASSUMPTIONS.md.
     *
     * @param request the current request
     * @return the address, or {@code null}
     */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String currentCorrelationId() {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return correlationId == null ? "unknown" : correlationId;
    }
}
