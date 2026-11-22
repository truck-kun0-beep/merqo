package com.merqo.access;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;

/**
 * The rules of the permission templates.
 *
 * <p>Responsibility: list, create and edit the named sets of permissions.
 *
 * <p>Two rules:
 *
 * <ul>
 *   <li>An owner only permission may never be stored in a template, because it could never become
 *       effective for anybody else.
 *   <li>Editing a template changes what its holders get on their next request, because nothing is
 *       cached between requests.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the template rules live here.
 *   <li><b>Delegation</b> - the "may this permission be stored" rule is asked of the {@link
 *       PermissionResolver}, which is plain Java.
 *   <li><b>Dependency injection</b> - the repositories are injected.
 * </ul>
 */
@Service
public class PermissionTemplateService {

    private final PermissionTemplateRepository templateRepository;
    private final TemplatePermissionRepository templatePermissionRepository;
    private final PermissionResolver permissionResolver;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param templateRepository the templates
     * @param templatePermissionRepository the permissions of a template
     * @param permissionResolver the resolution rules
     * @param auditService the audit log
     * @param clock the clock
     */
    public PermissionTemplateService(
            PermissionTemplateRepository templateRepository,
            TemplatePermissionRepository templatePermissionRepository,
            PermissionResolver permissionResolver,
            AuditService auditService,
            Clock clock) {
        this.templateRepository = templateRepository;
        this.templatePermissionRepository = templatePermissionRepository;
        this.permissionResolver = permissionResolver;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Every template.
     *
     * @param includeInactive whether the deactivated templates are returned as well
     * @return the templates
     */
    @Transactional(readOnly = true)
    public List<PermissionTemplate> list(boolean includeInactive) {
        return includeInactive
                ? templateRepository.findAllByOrderByCodeAsc()
                : templateRepository.findByActiveTrueOrderByCodeAsc();
    }

    /**
     * One template with its permissions.
     *
     * @param templateId the template
     * @return the template
     */
    @Transactional(readOnly = true)
    public PermissionTemplate get(UUID templateId) {
        return templateRepository
                .findById(templateId)
                .orElseThrow(() -> new NotFoundException("The requested record was not found."));
    }

    /**
     * The permission codes of one template.
     *
     * @param templateId the template
     * @return the codes
     */
    @Transactional(readOnly = true)
    public List<String> permissionsOf(UUID templateId) {
        return templateRepository.findPermissionCodes(templateId);
    }

    /**
     * Creates a template.
     *
     * @param code the stable code
     * @param name the display name
     * @param description the description
     * @param permissions the permissions of the template
     * @param actorUserId who created it
     * @param actorUsername the username of that user
     * @return the new template
     */
    @Transactional
    public PermissionTemplate create(
            String code,
            String name,
            String description,
            List<String> permissions,
            UUID actorUserId,
            String actorUsername) {
        String trimmedCode =
                code == null
                        ? ""
                        : code.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
        if (trimmedCode.isEmpty()) {
            throw new ValidationException("VALIDATION_FAILED", "The template needs a code.");
        }
        if (templateRepository.findByCode(trimmedCode).isPresent()) {
            throw new ConflictException(
                    "DUPLICATE_VALUE", "A template with this code already exists.");
        }
        Set<Permission> resolved = resolvePermissions(permissions);
        Instant now = Instant.now(clock);
        PermissionTemplate template =
                new PermissionTemplate(
                        UUID.randomUUID(), trimmedCode, name, description, false, now);
        templateRepository.save(template);
        writePermissions(template.getId(), resolved, now);

        auditService.record(
                new AuditEvent.Builder(AuditAction.TEMPLATE_CREATED, "permission_templates")
                        .entityId(template.getId())
                        .actor(actorUserId, actorUsername)
                        .added("code", template.getCode())
                        .added("name", template.getName())
                        .added("permissions", resolved.stream().map(Permission::name).toList())
                        .build());
        return template;
    }

    /**
     * Edits a template.
     *
     * @param template the template
     * @param name the new display name
     * @param description the new description
     * @param permissions the new permissions
     * @param active whether the template may be given to a user
     * @param actorUserId who changed it
     * @param actorUsername the username of that user
     * @return the changed template
     */
    @Transactional
    public PermissionTemplate update(
            PermissionTemplate template,
            String name,
            String description,
            List<String> permissions,
            Boolean active,
            UUID actorUserId,
            String actorUsername) {
        List<String> oldPermissions = templateRepository.findPermissionCodes(template.getId());
        String oldName = template.getName();
        String oldDescription = template.getDescription();
        boolean oldActive = template.isActive();

        if (name != null && !name.isBlank()) {
            template.setName(name.trim());
        }
        if (description != null) {
            template.setDescription(description.trim());
        }
        if (active != null) {
            template.setActive(active);
        }
        template.setUpdatedAt(Instant.now(clock));
        templateRepository.save(template);

        List<String> newPermissions = oldPermissions;
        if (permissions != null) {
            Set<Permission> resolved = resolvePermissions(permissions);
            writePermissions(template.getId(), resolved, Instant.now(clock));
            newPermissions = resolved.stream().map(Permission::name).sorted().toList();
        }

        auditService.record(
                new AuditEvent.Builder(AuditAction.TEMPLATE_UPDATED, "permission_templates")
                        .entityId(template.getId())
                        .actor(actorUserId, actorUsername)
                        .changed("name", oldName, template.getName())
                        .changed("description", oldDescription, template.getDescription())
                        .changed("isActive", oldActive, template.isActive())
                        .changed(
                                "permissions",
                                oldPermissions.stream().sorted().toList(),
                                newPermissions)
                        .build());
        return template;
    }

    /**
     * The permission catalogue, grouped by module.
     *
     * @return the catalogue
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> catalogue() {
        List<Map<String, Object>> modules = new ArrayList<>();
        for (Permission.Module module : Permission.Module.values()) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("code", module.name());
            entry.put("label", module.getLabel());
            List<Map<String, Object>> permissions = new ArrayList<>();
            for (Permission permission : Permission.inModule(module)) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("code", permission.name());
                item.put("description", permission.getDescription());
                item.put("scope", permission.getScopeKind().name());
                item.put("ownerOnly", permission.isOwnerOnly());
                item.put("delegable", permission.isDelegable());
                permissions.add(item);
            }
            entry.put("permissions", permissions);
            modules.add(entry);
        }
        return modules;
    }

    private Set<Permission> resolvePermissions(List<String> permissions) {
        Set<Permission> resolved = new LinkedHashSet<>();
        List<Permission> ownerOnly = new ArrayList<>();
        if (permissions != null) {
            for (String code : permissions) {
                Optional<Permission> permission = Permission.fromCode(code);
                if (permission.isEmpty()) {
                    throw new ValidationException(
                            "VALIDATION_FAILED", "Unknown permission: " + code);
                }
                resolved.add(permission.get());
            }
        }
        permissionResolver.filterStorable(resolved, ownerOnly);
        if (!ownerOnly.isEmpty()) {
            throw new ValidationException(
                    "OWNER_ONLY_PERMISSION",
                    "The owner only permission "
                            + ownerOnly.get(0).name()
                            + " may not be put in a template.");
        }
        return resolved;
    }

    private void writePermissions(UUID templateId, Set<Permission> permissions, Instant now) {
        templatePermissionRepository.deleteAll(
                templatePermissionRepository.findAll().stream()
                        .filter(row -> row.getTemplateId().equals(templateId))
                        .toList());
        for (Permission permission : permissions) {
            templatePermissionRepository.save(
                    new TemplatePermission(templateId, permission.name(), now));
        }
    }
}
