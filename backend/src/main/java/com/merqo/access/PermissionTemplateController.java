package com.merqo.access;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.user.CurrentUserService;

/**
 * The endpoints of the permission catalogue and the templates.
 *
 * <p>Responsibility: translate HTTP into calls on {@link PermissionTemplateService}.
 *
 * <p>The catalogue is open to every signed in user, because the interface needs the names and the
 * descriptions. Editing a template needs {@code TEMPLATE_MANAGE}, which only the owner holds.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Thin controller</b> - the controller only maps the request and the response.
 *   <li><b>Data transfer object</b> - the request bodies are small records.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Permissions", description = "The permission catalogue and the templates")
public class PermissionTemplateController {

    private final PermissionTemplateService templateService;
    private final CurrentUserService currentUserService;

    /**
     * Creates the controller.
     *
     * @param templateService the template rules
     * @param currentUserService the signed in user
     */
    public PermissionTemplateController(
            PermissionTemplateService templateService, CurrentUserService currentUserService) {
        this.templateService = templateService;
        this.currentUserService = currentUserService;
    }

    /**
     * The permission catalogue, grouped by module.
     *
     * @return the catalogue
     */
    @GetMapping("/permissions")
    @Operation(summary = "The permission catalogue")
    public List<Map<String, Object>> catalogue() {
        currentUserService.requireAccessContext();
        return templateService.catalogue();
    }

    /**
     * The templates.
     *
     * @param includeInactive whether the deactivated templates are returned as well
     * @return the templates
     */
    @GetMapping("/permission-templates")
    @Operation(summary = "The permission templates")
    public List<Map<String, Object>> list(
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        AccessContext context = currentUserService.requireAccessContext();
        context.requireGlobal(Permission.TEMPLATE_MANAGE);
        List<Map<String, Object>> items = new ArrayList<>();
        templateService
                .list(includeInactive)
                .forEach(
                        template -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("id", template.getId());
                            entry.put("code", template.getCode());
                            entry.put("name", template.getName());
                            entry.put("description", template.getDescription());
                            entry.put("isSystem", template.isSystem());
                            entry.put("isActive", template.isActive());
                            entry.put(
                                    "permissions", templateService.permissionsOf(template.getId()));
                            items.add(entry);
                        });
        return items;
    }

    /**
     * One template.
     *
     * @param id the template
     * @return the template
     */
    @GetMapping("/permission-templates/{id}")
    @Operation(summary = "One permission template")
    public Map<String, Object> get(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        context.requireGlobal(Permission.TEMPLATE_MANAGE);
        PermissionTemplate template = templateService.get(id);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", template.getId());
        entry.put("code", template.getCode());
        entry.put("name", template.getName());
        entry.put("description", template.getDescription());
        entry.put("isSystem", template.isSystem());
        entry.put("isActive", template.isActive());
        entry.put("permissions", templateService.permissionsOf(template.getId()));
        return entry;
    }

    /**
     * Creates a template.
     *
     * @param request the template to create
     * @return the new template
     */
    @PostMapping("/permission-templates")
    @Operation(summary = "Create a permission template")
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody CreateTemplateRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        context.requireGlobal(Permission.TEMPLATE_MANAGE);
        PermissionTemplate template =
                templateService.create(
                        request.code(),
                        request.name(),
                        request.description(),
                        request.permissions(),
                        context.getUserId(),
                        context.getUsername());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", template.getId());
        entry.put("code", template.getCode());
        entry.put("name", template.getName());
        entry.put("description", template.getDescription());
        entry.put("isSystem", template.isSystem());
        entry.put("isActive", template.isActive());
        entry.put("permissions", templateService.permissionsOf(template.getId()));
        return ResponseEntity.status(201).body(entry);
    }

    /**
     * Edits a template.
     *
     * @param id the template
     * @param request the new values
     * @return the changed template
     */
    @PutMapping("/permission-templates/{id}")
    @Operation(summary = "Edit a permission template")
    public Map<String, Object> update(
            @PathVariable UUID id, @Valid @RequestBody UpdateTemplateRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        context.requireGlobal(Permission.TEMPLATE_MANAGE);
        PermissionTemplate template = templateService.get(id);
        PermissionTemplate updated =
                templateService.update(
                        template,
                        request.name(),
                        request.description(),
                        request.permissions(),
                        request.active(),
                        context.getUserId(),
                        context.getUsername());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", updated.getId());
        entry.put("code", updated.getCode());
        entry.put("name", updated.getName());
        entry.put("description", updated.getDescription());
        entry.put("isSystem", updated.isSystem());
        entry.put("isActive", updated.isActive());
        entry.put("permissions", templateService.permissionsOf(updated.getId()));
        return entry;
    }

    /**
     * The body of a create template request.
     *
     * @param code the stable code
     * @param name the display name
     * @param description the description
     * @param permissions the permissions of the template
     */
    public record CreateTemplateRequest(
            @NotBlank String code,
            @NotBlank String name,
            String description,
            @NotNull List<String> permissions) {}

    /**
     * The body of an update template request.
     *
     * @param name the display name
     * @param description the description
     * @param permissions the permissions of the template
     * @param active whether the template may be given to a user
     */
    public record UpdateTemplateRequest(
            String name, String description, List<String> permissions, Boolean active) {}
}
