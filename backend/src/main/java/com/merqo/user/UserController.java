package com.merqo.user;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

import com.merqo.access.AccessContext;
import com.merqo.access.AccountType;
import com.merqo.access.LocationAssignment;
import com.merqo.access.ManagerDelegation;
import com.merqo.access.Permission;
import com.merqo.access.PermissionResolver;
import com.merqo.access.PermissionTemplate;
import com.merqo.access.UserPermissionOverride;
import com.merqo.common.web.PageRequest;
import com.merqo.common.web.PageResponse;

/**
 * The endpoints of the user accounts.
 *
 * <p>Responsibility: translate HTTP into calls on {@link UserService}. The controller holds no
 * business rule.
 *
 * <p>The scoping is not done here either: the service asks the {@link AccessContext} what the
 * caller may see, and the repository filters by location in the query.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Thin controller</b> - the controller only maps the request and the response.
 *   <li><b>Data transfer object</b> - the request bodies are small records.
 *   <li><b>Dependency injection</b> - the services are injected.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Accounts, templates, overrides and assignments")
public class UserController {

    private final UserService userService;
    private final CurrentUserService currentUserService;

    /**
     * Creates the controller.
     *
     * @param userService the account rules
     * @param currentUserService the signed in user
     */
    public UserController(UserService userService, CurrentUserService currentUserService) {
        this.userService = userService;
        this.currentUserService = currentUserService;
    }

    /**
     * The accounts the caller may see.
     *
     * @param q the free text search
     * @param accountType the account type filter
     * @param active the active filter
     * @param locationId the location filter
     * @param page the page number
     * @param size the page size
     * @param sort the sort field
     * @param direction the sort direction
     * @return the page of accounts
     */
    @GetMapping
    @Operation(summary = "The accounts the caller may see")
    public PageResponse<Map<String, Object>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) AccountType accountType,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        AccessContext context = currentUserService.requireAccessContext();
        PageRequest request = PageRequest.of(page, size, sort, direction);
        var result =
                userService.search(context, q, accountType, active, locationId, request.toSpring());
        List<Map<String, Object>> items = new ArrayList<>();
        result.getContent().forEach(user -> items.add(toSummary(context, user)));
        return new PageResponse<>(
                items,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }

    /**
     * Creates an account.
     *
     * @param request the account to create
     * @return the new account and, when the service generated a password, that password
     */
    @PostMapping("/create")
    @Operation(summary = "Create an account")
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody CreateUserRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        UserService.CreatedUser created =
                userService.create(
                        context,
                        request.username(),
                        request.email(),
                        request.fullName(),
                        request.phone(),
                        request.accountType(),
                        request.password(),
                        request.maxDiscountPercent(),
                        request.templateIds());
        Map<String, Object> body = toSummary(context, created.user());
        if (created.temporaryPassword() != null) {
            body.put("temporaryPassword", created.temporaryPassword());
        }
        return ResponseEntity.status(201).body(body);
    }

    /**
     * One account.
     *
     * @param id the account
     * @return the account
     */
    @GetMapping("/{id}")
    @Operation(summary = "One account")
    public Map<String, Object> get(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        AppUser user = userService.get(context, id);
        Map<String, Object> body = toSummary(context, user);
        body.put("templates", templateNames(userService.templatesOf(user.getId())));
        body.put("overrides", overrideList(userService.overridesOf(user.getId())));
        body.put("locations", assignmentList(userService.assignmentsOf(user.getId())));
        body.put(
                "delegations",
                context.isOwner()
                        ? userService.delegationsOf(user.getId()).stream()
                                .map(Permission::name)
                                .sorted()
                                .toList()
                        : List.of());
        return body;
    }

    /**
     * Edits the profile of an account.
     *
     * @param id the account
     * @param request the new profile
     * @return the changed account
     */
    @PutMapping("/{id}")
    @Operation(summary = "Edit the profile of an account")
    public Map<String, Object> update(
            @PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        AppUser user = userService.get(context, id);
        AppUser updated =
                userService.update(
                        context,
                        user,
                        request.fullName(),
                        request.phone(),
                        request.email(),
                        request.maxDiscountPercent());
        return toSummary(context, updated);
    }

    /**
     * Deactivates an account.
     *
     * @param id the account
     * @return the deactivated account
     */
    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate an account")
    public Map<String, Object> deactivate(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        AppUser user = userService.get(context, id);
        return toSummary(context, userService.deactivate(context, user));
    }

    /**
     * Activates an account again.
     *
     * @param id the account
     * @return the activated account
     */
    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate an account again")
    public Map<String, Object> activate(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        AppUser user = userService.get(context, id);
        return toSummary(context, userService.activate(context, user));
    }

    /**
     * Generates a temporary password for an account.
     *
     * @param id the account
     * @return the temporary password, shown once
     */
    @PostMapping("/{id}/reset-password")
    @Operation(summary = "Generate a temporary password")
    public Map<String, Object> resetPassword(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        AppUser user = userService.get(context, id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("temporaryPassword", userService.resetPassword(context, user));
        body.put("mustChangePassword", true);
        return body;
    }

    /**
     * Replaces the templates of an account.
     *
     * @param id the account
     * @param request the templates
     * @return the templates of the account
     */
    @PutMapping("/{id}/templates")
    @Operation(summary = "Replace the templates of an account")
    public List<Map<String, Object>> replaceTemplates(
            @PathVariable UUID id, @Valid @RequestBody ReplaceTemplatesRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        AppUser user = userService.get(context, id);
        List<PermissionTemplate> templates =
                userService.replaceTemplates(context, user, request.templateIds());
        return templateNames(templates);
    }

    /**
     * Replaces the overrides of an account.
     *
     * @param id the account
     * @param request the overrides
     * @return the stored overrides
     */
    @PutMapping("/{id}/overrides")
    @Operation(summary = "Replace the overrides of an account")
    public List<Map<String, Object>> replaceOverrides(
            @PathVariable UUID id, @Valid @RequestBody ReplaceOverridesRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        AppUser user = userService.get(context, id);
        List<UserService.OverrideRequest> overrides = new ArrayList<>();
        for (OverrideRequest requestItem : request.overrides()) {
            overrides.add(
                    new UserService.OverrideRequest(
                            requestItem.permission(),
                            Permission.fromCode(requestItem.permission()).orElse(null),
                            requestItem.effect(),
                            requestItem.locationId()));
        }
        return overrideList(userService.replaceOverrides(context, user, overrides));
    }

    /**
     * The effective permissions of an account, with the reason for each one.
     *
     * @param id the account
     * @return the explained permissions
     */
    @GetMapping("/{id}/effective-permissions")
    @Operation(summary = "The effective permissions of an account, with the reason for each")
    public List<Map<String, Object>> effectivePermissions(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        AppUser user = userService.get(context, id);
        List<Map<String, Object>> list = new ArrayList<>();
        userService
                .effectivePermissions(context, user)
                .forEach(
                        explained -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("code", explained.permission().name());
                            entry.put("module", explained.permission().getModule().name());
                            entry.put("description", explained.permission().getDescription());
                            entry.put("scope", explained.scope().isEmpty() ? "ALL" : "LOCATION");
                            entry.put("locationIds", explained.scope());
                            entry.put("reasons", explained.reasons());
                            list.add(entry);
                        });
        return list;
    }

    /**
     * Replaces the delegation list of a manager. Only the owner may do this.
     *
     * @param id the manager
     * @param request the permissions the manager may delegate
     * @return the delegation list
     */
    @PutMapping("/{id}/delegations")
    @Operation(summary = "Replace the delegation list of a manager")
    public List<Map<String, Object>> replaceDelegations(
            @PathVariable UUID id, @Valid @RequestBody ReplaceDelegationsRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        AppUser manager = userService.get(context, id);
        Set<Permission> permissions = new java.util.LinkedHashSet<>();
        for (String code : request.permissions()) {
            permissions.add(
                    Permission.fromCode(code)
                            .orElseThrow(
                                    () ->
                                            new com.merqo.common.error.ValidationException(
                                                    "VALIDATION_FAILED",
                                                    "Unknown permission: " + code)));
        }
        List<ManagerDelegation> stored =
                userService.replaceDelegations(context, manager, permissions);
        List<Map<String, Object>> list = new ArrayList<>();
        stored.forEach(
                delegation -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("permission", delegation.getPermission());
                    list.add(entry);
                });
        return list;
    }

    /**
     * The assignments of an account.
     *
     * @param id the account
     * @return the assignments
     */
    @GetMapping("/{id}/assignments")
    @Operation(summary = "The assignments of an account")
    public List<Map<String, Object>> assignments(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        AppUser user = userService.get(context, id);
        return assignmentList(userService.assignmentsOf(user.getId()));
    }

    private Map<String, Object> toSummary(AccessContext context, AppUser user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.getId());
        body.put("username", user.getUsername());
        body.put("fullName", user.getFullName());
        body.put("email", user.getEmail());
        body.put("phone", user.getPhone());
        body.put("accountType", user.getAccountType().name());
        body.put("isActive", user.isActive());
        body.put("mustChangePassword", user.isMustChangePassword());
        body.put("employeeCode", user.getEmployeeCode());
        body.put("maxDiscountPercent", user.getMaxDiscountPercent());
        body.put("isDemo", user.isDemo());
        body.put("lastLoginAt", user.getLastLoginAt());
        body.put("createdAt", user.getCreatedAt());
        return body;
    }

    private List<Map<String, Object>> templateNames(List<PermissionTemplate> templates) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PermissionTemplate template : templates) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", template.getId());
            entry.put("code", template.getCode());
            entry.put("name", template.getName());
            entry.put("description", template.getDescription());
            entry.put("isActive", template.isActive());
            list.add(entry);
        }
        return list;
    }

    private List<Map<String, Object>> overrideList(List<UserPermissionOverride> overrides) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (UserPermissionOverride override : overrides) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", override.getId());
            entry.put("permission", override.getPermission());
            entry.put("effect", override.getEffect().name());
            entry.put("locationId", override.getLocationId());
            list.add(entry);
        }
        return list;
    }

    private List<Map<String, Object>> assignmentList(List<LocationAssignment> assignments) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (LocationAssignment assignment : assignments) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", assignment.getId());
            entry.put("locationId", assignment.getLocationId());
            entry.put("assignmentType", assignment.getAssignmentType().name());
            entry.put("startedAt", assignment.getStartedAt());
            entry.put("endedAt", assignment.getEndedAt());
            entry.put("isActive", assignment.isActive());
            list.add(entry);
        }
        return list;
    }

    /**
     * The body of a create account request.
     *
     * @param username the login name
     * @param email the optional email address
     * @param fullName the display name
     * @param phone the phone number
     * @param accountType the kind of account
     * @param password the password, or empty to let the service generate one
     * @param maxDiscountPercent the discount ceiling
     * @param templateIds the templates the account holds
     */
    public record CreateUserRequest(
            @NotBlank String username,
            String email,
            String fullName,
            String phone,
            @NotNull AccountType accountType,
            String password,
            BigDecimal maxDiscountPercent,
            List<UUID> templateIds) {}

    /**
     * The body of an update account request.
     *
     * @param fullName the display name
     * @param phone the phone number
     * @param email the email address
     * @param maxDiscountPercent the discount ceiling
     */
    public record UpdateUserRequest(
            String fullName, String phone, String email, BigDecimal maxDiscountPercent) {}

    /**
     * The body of a replace templates request.
     *
     * @param templateIds the templates
     */
    public record ReplaceTemplatesRequest(@NotNull List<UUID> templateIds) {}

    /**
     * The body of a replace overrides request.
     *
     * @param overrides the overrides
     */
    public record ReplaceOverridesRequest(@NotNull List<OverrideRequest> overrides) {}

    /**
     * One override in a request.
     *
     * @param permission the permission code
     * @param effect whether the permission is allowed or denied
     * @param locationId the location, or {@code null} for every location
     */
    public record OverrideRequest(
            @NotBlank String permission,
            @NotNull PermissionResolver.OverrideEffect effect,
            UUID locationId) {}

    /**
     * The body of a replace delegations request.
     *
     * @param permissions the permissions the manager may delegate
     */
    public record ReplaceDelegationsRequest(@NotNull List<String> permissions) {}
}
