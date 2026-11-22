package com.merqo.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.ForbiddenException;
import com.merqo.common.web.PageRequest;
import com.merqo.common.web.PageResponse;
import com.merqo.user.CurrentUserService;

/**
 * The endpoints of the audit log.
 *
 * <p>Responsibility: translate HTTP into a query on the audit log.
 *
 * <p>The scoping happens in the query: the owner sees everything, everybody else sees only the rows
 * whose location lies inside their own scope.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Thin controller</b> - the controller only maps the request and the response.
 *   <li><b>Query as data</b> - the filters become parameters of one query.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit", description = "The append only audit log")
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;
    private final CurrentUserService currentUserService;

    /**
     * Creates the controller.
     *
     * @param auditLogRepository the audit log
     * @param currentUserService the signed in user
     */
    public AuditLogController(
            AuditLogRepository auditLogRepository, CurrentUserService currentUserService) {
        this.auditLogRepository = auditLogRepository;
        this.currentUserService = currentUserService;
    }

    /**
     * The audit rows the caller may see.
     *
     * @param actorUserId the actor filter
     * @param action the action filter
     * @param entityType the entity type filter
     * @param locationId the location filter
     * @param from the start of the date range
     * @param to the end of the date range
     * @param page the page number
     * @param size the page size
     * @param sort the sort field
     * @param direction the sort direction
     * @return the page of audit rows
     */
    @GetMapping
    @Operation(summary = "The audit rows the caller may see")
    public PageResponse<Map<String, Object>> list(
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        AccessContext context = currentUserService.requireAccessContext();
        // AUDIT_VIEW is a location scoped permission. The caller may read the rows of the
        // locations where they hold it, so the scope comes from the permission and not from the
        // raw assignment list. A manager therefore sees the audit rows of their own branches
        // and the rows without a location, and nothing else.
        Set<UUID> scope = context.visibleLocationIds(Permission.AUDIT_VIEW);
        if (!context.isOwner() && scope.isEmpty()) {
            throw new ForbiddenException(
                    ErrorCodes.FORBIDDEN, "You do not have the permission AUDIT_VIEW.");
        }
        PageRequest request = PageRequest.of(page, size, sort, direction);
        Page<AuditLog> result =
                auditLogRepository.search(
                        actorUserId,
                        action,
                        entityType,
                        locationId,
                        from,
                        to,
                        List.copyOf(scope),
                        context.isOwner(),
                        request.toSpring());
        List<Map<String, Object>> items = new ArrayList<>();
        result.getContent().forEach(row -> items.add(toSummary(row)));
        return PageResponse.of(
                new org.springframework.data.domain.PageImpl<>(
                        items, result.getPageable(), result.getTotalElements()));
    }

    private Map<String, Object> toSummary(AuditLog row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.getId());
        body.put("occurredAt", row.getOccurredAt());
        body.put("action", row.getAction());
        body.put("entityType", row.getEntityType());
        body.put("entityId", row.getEntityId());
        body.put("locationId", row.getLocationId());
        body.put("actorUserId", row.getActorUserId());
        body.put("actorUsername", row.getActorUsername());
        body.put("oldValues", row.getOldValues());
        body.put("newValues", row.getNewValues());
        body.put("ipAddress", row.getIpAddress());
        body.put("correlationId", row.getCorrelationId());
        return body;
    }
}
