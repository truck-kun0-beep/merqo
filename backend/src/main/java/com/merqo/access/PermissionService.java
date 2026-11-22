package com.merqo.access;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.user.AppUser;
import com.merqo.user.UserTemplateAssignmentRepository;

/**
 * Loads the effective permissions of a user and builds the {@link AccessContext} of a request.
 *
 * <p>Responsibility: read the templates, the overrides and the assignments of one user, hand them
 * to the {@link PermissionResolver} and keep the result in one place.
 *
 * <p><b>Nothing is cached between requests.</b> The session stores only the user id, and the
 * templates, the overrides and the assignments are loaded again on every request. That is what
 * makes a change take effect on the very next request: ending an assignment or changing a template
 * is visible at once.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Dependency injection</b> - the repositories are injected, so the service can be tested
 *       with test doubles.
 *   <li><b>Delegation</b> - the service does not implement the resolution rules itself; it asks the
 *       resolver, which is plain Java.
 *   <li><b>Single responsibility</b> - loading permissions and building the context is all this
 *       class does.
 * </ul>
 */
@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private final UserTemplateAssignmentRepository userTemplateRepository;
    private final PermissionTemplateRepository templateRepository;
    private final UserPermissionOverrideRepository overrideRepository;
    private final LocationAssignmentRepository assignmentRepository;
    private final ManagerDelegationRepository delegationRepository;
    private final PermissionResolver resolver;

    /**
     * Creates the service.
     *
     * @param userTemplateRepository the template links
     * @param templateRepository the templates
     * @param overrideRepository the overrides
     * @param assignmentRepository the assignments
     * @param delegationRepository the delegation lists
     * @param resolver the resolver that applies the rules
     */
    public PermissionService(
            UserTemplateAssignmentRepository userTemplateRepository,
            PermissionTemplateRepository templateRepository,
            UserPermissionOverrideRepository overrideRepository,
            LocationAssignmentRepository assignmentRepository,
            ManagerDelegationRepository delegationRepository,
            PermissionResolver resolver) {
        this.userTemplateRepository = userTemplateRepository;
        this.templateRepository = templateRepository;
        this.overrideRepository = overrideRepository;
        this.assignmentRepository = assignmentRepository;
        this.delegationRepository = delegationRepository;
        this.resolver = resolver;
    }

    /**
     * Builds the access context of one user.
     *
     * @param user the signed in user
     * @return the access context
     */
    @Transactional(readOnly = true)
    public AccessContext buildContext(AppUser user) {
        Set<UUID> assignedLocations =
                new LinkedHashSet<>(
                        assignmentRepository.findActiveLocationIdsByUserId(user.getId()));
        Set<Permission> templatePermissions = loadTemplatePermissions(user.getId());
        List<PermissionResolver.PermissionOverride> overrides = loadOverrides(user.getId());

        PermissionResolver.ResolutionInput input =
                new PermissionResolver.ResolutionInput(
                        user.getAccountType() == AccountType.OWNER,
                        user.isActive(),
                        templatePermissions,
                        overrides,
                        assignedLocations);

        EffectivePermissions effective = resolver.resolve(input);
        return new AccessContext(
                user.getId(),
                user.getUsername(),
                user.getAccountType(),
                effective,
                assignedLocations,
                user.isMustChangePassword());
    }

    /**
     * The union of the permissions of every active template the user holds.
     *
     * <p>A permission code that this build does not know is logged once and ignored, so an old
     * database never crashes the application.
     *
     * @param userId the user
     * @return the permissions
     */
    @Transactional(readOnly = true)
    public Set<Permission> loadTemplatePermissions(UUID userId) {
        List<UUID> templateIds = userTemplateRepository.findTemplateIdsByUserId(userId);
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        Set<String> reportedUnknown = new HashSet<>();
        for (UUID templateId : templateIds) {
            for (String code : templateRepository.findPermissionCodes(templateId)) {
                Optional<Permission> permission = Permission.fromCode(code);
                if (permission.isPresent()) {
                    permissions.add(permission.get());
                } else if (reportedUnknown.add(code)) {
                    log.warn(
                            "Ignoring the unknown permission code {} of template {}",
                            code,
                            templateId);
                }
            }
        }
        return permissions;
    }

    /**
     * The overrides of one user.
     *
     * @param userId the user
     * @return the overrides
     */
    @Transactional(readOnly = true)
    public List<PermissionResolver.PermissionOverride> loadOverrides(UUID userId) {
        List<PermissionResolver.PermissionOverride> overrides = new ArrayList<>();
        for (UserPermissionOverride stored : overrideRepository.findByUserId(userId)) {
            Optional<Permission> permission = Permission.fromCode(stored.getPermission());
            if (permission.isEmpty()) {
                log.warn(
                        "Ignoring the unknown permission code {} of the overrides of user {}",
                        stored.getPermission(),
                        userId);
                continue;
            }
            overrides.add(
                    new PermissionResolver.PermissionOverride(
                            permission.get(), stored.getEffect(), stored.getLocationId()));
        }
        return overrides;
    }

    /**
     * The explained effective permissions of one user, for the "why" view.
     *
     * @param user the user
     * @return one entry per effective permission
     */
    @Transactional(readOnly = true)
    public List<PermissionResolver.ExplainedPermission> explain(AppUser user) {
        Set<UUID> assignedLocations =
                new LinkedHashSet<>(
                        assignmentRepository.findActiveLocationIdsByUserId(user.getId()));
        PermissionResolver.ResolutionInput input =
                new PermissionResolver.ResolutionInput(
                        user.getAccountType() == AccountType.OWNER,
                        user.isActive(),
                        loadTemplatePermissions(user.getId()),
                        loadOverrides(user.getId()),
                        assignedLocations);
        return resolver.explain(input);
    }

    /**
     * The permissions a manager may delegate, taken from the delegation list of that manager.
     *
     * @param managerUserId the manager
     * @return the permission codes
     */
    @Transactional(readOnly = true)
    public Set<String> loadDelegationCodes(UUID managerUserId) {
        return new LinkedHashSet<>(
                delegationRepository.findPermissionCodesByManagerUserId(managerUserId));
    }

    /**
     * The delegation list of a manager as permissions.
     *
     * <p>An unknown code in the list is ignored, so a stale list cannot break the login.
     *
     * @param managerUserId the manager
     * @return the permissions
     */
    @Transactional(readOnly = true)
    public Set<Permission> loadDelegationPermissions(UUID managerUserId) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String code : loadDelegationCodes(managerUserId)) {
            Optional<Permission> permission = Permission.fromCode(code);
            if (permission.isPresent()) {
                permissions.add(permission.get());
            } else {
                log.warn("Ignoring the unknown permission code {} in a delegation list", code);
            }
        }
        return permissions;
    }
}
