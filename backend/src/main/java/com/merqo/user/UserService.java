package com.merqo.user;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AccessContext;
import com.merqo.access.AccountType;
import com.merqo.access.DelegationDefaults;
import com.merqo.access.DelegationPolicy;
import com.merqo.access.EffectivePermissions;
import com.merqo.access.LocationAssignment;
import com.merqo.access.LocationAssignmentRepository;
import com.merqo.access.ManagerDelegation;
import com.merqo.access.ManagerDelegationRepository;
import com.merqo.access.Permission;
import com.merqo.access.PermissionResolver;
import com.merqo.access.PermissionService;
import com.merqo.access.PermissionTemplate;
import com.merqo.access.PermissionTemplateRepository;
import com.merqo.access.UserPermissionOverride;
import com.merqo.access.UserPermissionOverrideRepository;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ForbiddenException;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;

/**
 * The rules of the user accounts.
 *
 * <p>Responsibility: create, edit, deactivate and activate accounts, and manage what an account may
 * do: the templates, the overrides, the delegation lists and the assignments.
 *
 * <p>The access rules of this service:
 *
 * <ul>
 *   <li>The owner creates, edits, deactivates and appoints everybody.
 *   <li>A manager may create and edit staff inside their own scope, and may assign templates and
 *       overrides there.
 *   <li>A manager may never grant a permission they do not hold themselves, may never grant a
 *       permission that is not on their delegation list, and may never touch an account outside
 *       their scope.
 *   <li>The owner account may not be changed by anybody else.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Facade</b> - the controller calls one service and does not know the repositories.
 *   <li><b>Delegation</b> - the rules live in {@link PermissionResolver} and {@link
 *       DelegationPolicy}, which are plain Java and unit tested on their own.
 *   <li><b>Single responsibility</b> - the account rules live here, the location rules in the
 *       location service.
 * </ul>
 */
@Service
public class UserService {

    private final AppUserRepository userRepository;
    private final UserTemplateAssignmentRepository userTemplateRepository;
    private final UserPermissionOverrideRepository overrideRepository;
    private final PermissionTemplateRepository templateRepository;
    private final ManagerDelegationRepository delegationRepository;
    private final LocationAssignmentRepository assignmentRepository;
    private final PermissionService permissionService;
    private final PermissionResolver permissionResolver;
    private final DelegationPolicy delegationPolicy;
    private final DelegationDefaults delegationDefaults;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param userRepository the accounts
     * @param userTemplateRepository the template links
     * @param overrideRepository the overrides
     * @param templateRepository the templates
     * @param delegationRepository the delegation lists
     * @param assignmentRepository the assignments
     * @param permissionService the permission loader
     * @param permissionResolver the resolution rules
     * @param delegationPolicy the delegation rules
     * @param passwordPolicy the password rules
     * @param passwordEncoder the BCrypt encoder
     * @param auditService the audit log
     * @param clock the clock
     */
    public UserService(
            AppUserRepository userRepository,
            UserTemplateAssignmentRepository userTemplateRepository,
            UserPermissionOverrideRepository overrideRepository,
            PermissionTemplateRepository templateRepository,
            ManagerDelegationRepository delegationRepository,
            LocationAssignmentRepository assignmentRepository,
            PermissionService permissionService,
            PermissionResolver permissionResolver,
            DelegationPolicy delegationPolicy,
            DelegationDefaults delegationDefaults,
            PasswordPolicy passwordPolicy,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            Clock clock) {
        this.userRepository = userRepository;
        this.userTemplateRepository = userTemplateRepository;
        this.overrideRepository = overrideRepository;
        this.templateRepository = templateRepository;
        this.delegationRepository = delegationRepository;
        this.assignmentRepository = assignmentRepository;
        this.permissionService = permissionService;
        this.permissionResolver = permissionResolver;
        this.delegationPolicy = delegationPolicy;
        this.delegationDefaults = delegationDefaults;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * The accounts the caller may see.
     *
     * @param context the access context of the caller
     * @param search the free text search
     * @param accountType the account type filter
     * @param active the active filter
     * @param locationId the location filter
     * @param pageable the paging
     * @return the page of accounts
     */
    @Transactional(readOnly = true)
    public Page<AppUser> search(
            AccessContext context,
            String search,
            AccountType accountType,
            Boolean active,
            UUID locationId,
            Pageable pageable) {
        Set<UUID> scope = context.getScopeLocationIds();
        if (locationId != null) {
            if (!context.isOwner() && !scope.contains(locationId)) {
                // The location is outside the scope of the caller, so the answer is empty.
                return Page.empty(pageable);
            }
            scope = Set.of(locationId);
        }
        return userRepository.search(
                search, accountType, active, List.copyOf(scope), context.isOwner(), pageable);
    }

    /**
     * One account.
     *
     * @param context the access context of the caller
     * @param userId the account
     * @return the account
     */
    @Transactional(readOnly = true)
    public AppUser get(AccessContext context, UUID userId) {
        AppUser user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        if (!context.isOwner() && !isInScope(context, user)) {
            throw new NotFoundException("The requested record was not found.");
        }
        return user;
    }

    /**
     * Creates an account.
     *
     * @param context the access context of the caller
     * @param username the login name
     * @param email the optional email address
     * @param fullName the display name
     * @param phone the phone number
     * @param accountType the kind of account
     * @param password the password, or {@code null} to let the service generate one
     * @param maxDiscountPercent the discount ceiling
     * @param templateIds the templates the account holds
     * @return the new account and, when a password was generated, that password
     */
    @Transactional
    public CreatedUser create(
            AccessContext context,
            String username,
            String email,
            String fullName,
            String phone,
            AccountType accountType,
            String password,
            BigDecimal maxDiscountPercent,
            List<UUID> templateIds) {
        if (accountType == AccountType.OWNER) {
            throw new ForbiddenException(
                    "OWNER_PROTECTED",
                    "There is exactly one owner account and it cannot be created.");
        }
        if (accountType == AccountType.MANAGER && !context.isOwner()) {
            throw new ForbiddenException(
                    "MANAGER_MANAGE", "Only the owner may create a manager account.");
        }
        if (!context.isOwner()) {
            context.requireGlobal(Permission.USER_CREATE);
        }

        Username normalizedUsername = Username.of(username);
        if (userRepository.existsByUsernameIgnoreCase(normalizedUsername.value())) {
            throw new ConflictException("DUPLICATE_VALUE", "This username is already taken.");
        }
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail != null && userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("DUPLICATE_VALUE", "This email address is already taken.");
        }

        String effectivePassword = password;
        boolean generated = false;
        if (effectivePassword == null || effectivePassword.isBlank()) {
            effectivePassword = generateTemporaryPassword();
            generated = true;
        }
        PasswordPolicy.Result result =
                passwordPolicy.validate(effectivePassword, normalizedUsername.value());
        if (!result.valid()) {
            throw new ValidationException("PASSWORD_POLICY", result.message());
        }

        Instant now = Instant.now(clock);
        AppUser user =
                new AppUser(
                        UUID.randomUUID(),
                        normalizedUsername.value(),
                        normalizedEmail,
                        fullName == null || fullName.isBlank()
                                ? normalizedUsername.value()
                                : fullName.trim(),
                        passwordEncoder.encode(effectivePassword),
                        accountType,
                        now);
        user.setPhone(phone);
        user.setCreatedBy(context.getUserId());
        user.setMustChangePassword(true);
        if (maxDiscountPercent != null) {
            user.setMaxDiscountPercent(maxDiscountPercent);
        }
        userRepository.save(user);

        if (templateIds != null && !templateIds.isEmpty()) {
            replaceTemplates(context, user, templateIds);
        }

        if (accountType == AccountType.MANAGER) {
            applyDefaultDelegations(user);
        }

        auditService.record(
                new AuditEvent.Builder(AuditAction.USER_CREATED, "app_users")
                        .entityId(user.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("username", user.getUsername())
                        .added("accountType", accountType.name())
                        .added("fullName", user.getFullName())
                        .build());

        return new CreatedUser(user, generated ? effectivePassword : null);
    }

    /**
     * Edits the profile of an account.
     *
     * @param context the access context of the caller
     * @param user the account
     * @param fullName the new display name
     * @param phone the new phone number
     * @param email the new email address
     * @param maxDiscountPercent the new discount ceiling
     * @return the changed account
     */
    @Transactional
    public AppUser update(
            AccessContext context,
            AppUser user,
            String fullName,
            String phone,
            String email,
            BigDecimal maxDiscountPercent) {
        requireMayManage(context, user);
        String oldName = user.getFullName();
        String oldPhone = user.getPhone();
        String oldEmail = user.getEmail();
        BigDecimal oldDiscount = user.getMaxDiscountPercent();

        if (fullName != null && !fullName.isBlank()) {
            user.setFullName(fullName.trim());
        }
        if (phone != null) {
            user.setPhone(phone.trim());
        }
        if (email != null) {
            String normalized = normalizeEmail(email);
            if (normalized != null
                    && userRepository.existsByEmailIgnoreCase(normalized)
                    && !normalized.equalsIgnoreCase(oldEmail)) {
                throw new ConflictException(
                        "DUPLICATE_VALUE", "This email address is already taken.");
            }
            user.setEmail(normalized);
        }
        if (maxDiscountPercent != null) {
            if (maxDiscountPercent.signum() < 0
                    || maxDiscountPercent.compareTo(new BigDecimal("100")) > 0) {
                throw new ValidationException(
                        "VALIDATION_FAILED", "The discount must be between 0 and 100 percent.");
            }
            user.setMaxDiscountPercent(maxDiscountPercent);
        }
        user.setUpdatedAt(Instant.now(clock));
        userRepository.save(user);

        auditService.record(
                new AuditEvent.Builder(AuditAction.USER_UPDATED, "app_users")
                        .entityId(user.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("fullName", oldName, user.getFullName())
                        .changed("phone", oldPhone, user.getPhone())
                        .changed("email", oldEmail, user.getEmail())
                        .changed("maxDiscountPercent", oldDiscount, user.getMaxDiscountPercent())
                        .build());
        return user;
    }

    /**
     * Deactivates an account. The row stays; the account may be activated again.
     *
     * @param context the access context of the caller
     * @param user the account
     * @return the deactivated account
     */
    @Transactional
    public AppUser deactivate(AccessContext context, AppUser user) {
        requireMayManage(context, user);
        if (user.getAccountType() == AccountType.OWNER) {
            throw new ForbiddenException(
                    "OWNER_PROTECTED", "The owner account may not be deactivated.");
        }
        user.setActive(false);
        user.setUpdatedAt(Instant.now(clock));
        userRepository.save(user);

        auditService.record(
                new AuditEvent.Builder(AuditAction.USER_DEACTIVATED, "app_users")
                        .entityId(user.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("isActive", true, false)
                        .build());
        return user;
    }

    /**
     * Activates an account again.
     *
     * @param context the access context of the caller
     * @param user the account
     * @return the activated account
     */
    @Transactional
    public AppUser activate(AccessContext context, AppUser user) {
        requireMayManage(context, user);
        if (user.getAccountType() == AccountType.OWNER) {
            throw new ForbiddenException(
                    "OWNER_PROTECTED", "The owner account may not be deactivated.");
        }
        user.setActive(true);
        user.setUpdatedAt(Instant.now(clock));
        userRepository.save(user);

        auditService.record(
                new AuditEvent.Builder(AuditAction.USER_ACTIVATED, "app_users")
                        .entityId(user.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("isActive", false, true)
                        .build());
        return user;
    }

    /**
     * Generates a temporary password for an account and forces a change at the next sign in.
     *
     * @param context the access context of the caller
     * @param user the account
     * @return the temporary password, shown once
     */
    @Transactional
    public String resetPassword(AccessContext context, AppUser user) {
        requireMayManage(context, user);
        String temporaryPassword = generateTemporaryPassword();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(Instant.now(clock));
        userRepository.save(user);

        auditService.record(
                new AuditEvent.Builder(AuditAction.PASSWORD_RESET, "app_users")
                        .entityId(user.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("passwordHash", "[redacted]", "[redacted]")
                        .build());
        return temporaryPassword;
    }

    /**
     * Replaces the templates of an account.
     *
     * @param context the access context of the caller
     * @param user the account
     * @param templateIds the templates the account holds
     * @return the accounts
     */
    @Transactional
    public List<PermissionTemplate> replaceTemplates(
            AccessContext context, AppUser user, List<UUID> templateIds) {
        requireMayManage(context, user);
        List<UUID> oldIds = userTemplateRepository.findTemplateIdsByUserId(user.getId());
        userTemplateRepository.deleteByUserId(user.getId());
        Instant now = Instant.now(clock);
        List<PermissionTemplate> templates = new ArrayList<>();
        for (UUID templateId : templateIds) {
            PermissionTemplate template =
                    templateRepository
                            .findById(templateId)
                            .orElseThrow(
                                    () ->
                                            new NotFoundException(
                                                    "The requested record was not found."));
            templates.add(template);
            userTemplateRepository.save(
                    new UserTemplateAssignment(user.getId(), templateId, context.getUserId(), now));
        }

        auditService.record(
                new AuditEvent.Builder(AuditAction.TEMPLATES_ASSIGNED, "app_users")
                        .entityId(user.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("templateIds", oldIds, templateIds)
                        .build());
        return templates;
    }

    /**
     * Replaces the overrides of an account.
     *
     * @param context the access context of the caller
     * @param user the account
     * @param overrides the new overrides
     * @return the stored overrides
     */
    @Transactional
    public List<UserPermissionOverride> replaceOverrides(
            AccessContext context, AppUser user, List<OverrideRequest> overrides) {
        requireMayManage(context, user);
        List<UserPermissionOverride> old = overrideRepository.findByUserId(user.getId());
        Map<String, Object> oldValues = describeOverrides(old);

        // Every value is checked before anything is written, so a refused request changes nothing.
        List<PermissionOverridePlan> plan = new ArrayList<>();
        for (OverrideRequest request : overrides) {
            Permission permission = request.permission();
            if (permission == null) {
                throw new ValidationException(
                        "VALIDATION_FAILED", "Unknown permission: " + request.permissionCode());
            }
            if (permission.isOwnerOnly()) {
                throw new ForbiddenException(
                        "OWNER_ONLY_PERMISSION",
                        "The permission " + permission.name() + " is owner only.");
            }
            if (!context.isOwner()) {
                requireDelegatable(context, user, permission, request.locationId());
            }
            plan.add(
                    new PermissionOverridePlan(permission, request.effect(), request.locationId()));
        }

        overrideRepository.deleteByUserId(user.getId());
        Instant now = Instant.now(clock);
        List<UserPermissionOverride> stored = new ArrayList<>();
        for (PermissionOverridePlan item : plan) {
            UserPermissionOverride row =
                    new UserPermissionOverride(
                            UUID.randomUUID(),
                            user.getId(),
                            item.permission().name(),
                            item.effect(),
                            item.locationId(),
                            context.getUserId(),
                            now);
            stored.add(overrideRepository.save(row));
        }

        auditService.record(
                new AuditEvent.Builder(AuditAction.OVERRIDES_CHANGED, "app_users")
                        .entityId(user.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("overrides", oldValues, describeOverrides(stored))
                        .build());
        return stored;
    }

    /**
     * Replaces the delegation list of a manager. Only the owner may do this.
     *
     * @param context the access context of the caller
     * @param manager the manager
     * @param permissions the permissions the manager may delegate
     * @return the stored delegations
     */
    @Transactional
    public List<ManagerDelegation> replaceDelegations(
            AccessContext context, AppUser manager, Set<Permission> permissions) {
        if (!context.isOwner()) {
            throw new ForbiddenException(
                    "MANAGER_MANAGE", "Only the owner may change a delegation list.");
        }
        if (manager.getAccountType() != AccountType.MANAGER) {
            throw new ValidationException(
                    "VALIDATION_FAILED", "Only a manager account has a delegation list.");
        }
        List<String> oldCodes =
                delegationRepository.findPermissionCodesByManagerUserId(manager.getId());
        delegationRepository.deleteByManagerUserId(manager.getId());
        Instant now = Instant.now(clock);
        List<ManagerDelegation> stored = new ArrayList<>();
        for (Permission permission : permissions) {
            if (permission.isOwnerOnly()) {
                throw new ForbiddenException(
                        "OWNER_ONLY_PERMISSION",
                        "The permission " + permission.name() + " is owner only.");
            }
            stored.add(
                    delegationRepository.save(
                            new ManagerDelegation(manager.getId(), permission.name(), now)));
        }

        auditService.record(
                new AuditEvent.Builder(AuditAction.DELEGATIONS_CHANGED, "app_users")
                        .entityId(manager.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed(
                                "delegations",
                                oldCodes,
                                permissions.stream().map(Permission::name).sorted().toList())
                        .build());
        return stored;
    }

    /**
     * The explained effective permissions of an account.
     *
     * @param context the access context of the caller
     * @param user the account
     * @return one entry per effective permission
     */
    @Transactional(readOnly = true)
    public List<PermissionResolver.ExplainedPermission> effectivePermissions(
            AccessContext context, AppUser user) {
        if (!context.isOwner() && !isInScope(context, user)) {
            throw new NotFoundException("The requested record was not found.");
        }
        return permissionService.explain(user);
    }

    /**
     * The templates an account holds.
     *
     * @param userId the account
     * @return the templates
     */
    @Transactional(readOnly = true)
    public List<PermissionTemplate> templatesOf(UUID userId) {
        List<UUID> templateIds = userTemplateRepository.findTemplateIdsByUserId(userId);
        List<PermissionTemplate> templates = new ArrayList<>();
        for (UUID templateId : templateIds) {
            templateRepository.findById(templateId).ifPresent(templates::add);
        }
        return templates;
    }

    /**
     * The overrides of an account.
     *
     * @param userId the account
     * @return the overrides
     */
    @Transactional(readOnly = true)
    public List<UserPermissionOverride> overridesOf(UUID userId) {
        return overrideRepository.findByUserId(userId);
    }

    /**
     * The delegation list of a manager.
     *
     * @param managerUserId the manager
     * @return the permissions
     */
    @Transactional(readOnly = true)
    public Set<Permission> delegationsOf(UUID managerUserId) {
        return permissionService.loadDelegationPermissions(managerUserId);
    }

    /**
     * The active assignments of an account.
     *
     * @param userId the account
     * @return the assignments
     */
    @Transactional(readOnly = true)
    public List<LocationAssignment> assignmentsOf(UUID userId) {
        return assignmentRepository.findByUserIdAndEndedAtIsNull(userId);
    }

    /**
     * Whether the target account has an assignment inside the scope of the caller.
     *
     * @param context the access context of the caller
     * @param target the target account
     * @return {@code true} when the target is inside the scope
     */
    @Transactional(readOnly = true)
    public boolean isInScope(AccessContext context, AppUser target) {
        if (context.isOwner()) {
            return true;
        }
        Set<UUID> scope = context.getScopeLocationIds();
        if (scope.isEmpty()) {
            return false;
        }
        return assignmentRepository.findActiveLocationIdsByUserId(target.getId()).stream()
                .anyMatch(scope::contains);
    }

    /**
     * Refuses the request when the caller may not act on the target account.
     *
     * @param context the access context of the caller
     * @param target the target account
     */
    public void requireMayManage(AccessContext context, AppUser target) {
        if (context.isOwner()) {
            return;
        }
        if (target.getAccountType() == AccountType.OWNER
                || target.getAccountType() == AccountType.MANAGER) {
            throw new ForbiddenException("FORBIDDEN", "You may not change this account.");
        }
        if (!isInScope(context, target)) {
            throw new NotFoundException("The requested record was not found.");
        }
    }

    private void requireDelegatable(
            AccessContext context, AppUser target, Permission permission, UUID locationId) {
        EffectivePermissions managerPermissions = context.getPermissions();
        Set<Permission> delegationList =
                permissionService.loadDelegationPermissions(context.getUserId());
        DelegationPolicy.Decision decision =
                delegationPolicy.check(
                        new DelegationPolicy.Request(
                                context.getUserId(),
                                target.getId(),
                                target.getAccountType(),
                                permission,
                                locationId,
                                managerPermissions,
                                context.getScopeLocationIds(),
                                delegationList));
        if (!decision.allowed()) {
            throw new ForbiddenException(
                    decision.reason().getErrorCode(),
                    "You may not delegate the permission " + permission.name() + ".");
        }
    }

    private void applyDefaultDelegations(AppUser manager) {
        Instant now = Instant.now(clock);
        for (Permission permission : defaultDelegablePermissions()) {
            delegationRepository.save(
                    new ManagerDelegation(manager.getId(), permission.name(), now));
        }
    }

    /**
     * The delegable permissions of the three starting templates.
     *
     * <p>When the owner creates a manager, the delegation list starts with the delegable
     * permissions of the cashier, the online order staff and the inventory staff. The owner may
     * then change it.
     *
     * @return the permissions
     */
    public Set<Permission> defaultDelegablePermissions() {
        Set<Permission> permissions = new LinkedHashSet<>();
        for (String code : List.of("CASHIER", "ONLINE_ORDER_STAFF", "INVENTORY_STAFF")) {
            templateRepository
                    .findByCode(code)
                    .ifPresent(
                            template -> {
                                for (String permissionCode :
                                        templateRepository.findPermissionCodes(template.getId())) {
                                    Permission.fromCode(permissionCode)
                                            .filter(Permission::isDelegable)
                                            .ifPresent(permissions::add);
                                }
                            });
        }
        return permissions;
    }

    private Map<String, Object> describeOverrides(List<UserPermissionOverride> overrides) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (UserPermissionOverride override : overrides) {
            values.put(
                    override.getPermission()
                            + "@"
                            + (override.getLocationId() == null ? "*" : override.getLocationId()),
                    override.getEffect().name());
        }
        return values;
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim().toLowerCase(java.util.Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String generateTemporaryPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder builder = new StringBuilder(12);
        for (int index = 0; index < 12; index++) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    /**
     * A new account and, when the service generated the password, that password.
     *
     * @param user the account
     * @param temporaryPassword the generated password, or {@code null}
     */
    public record CreatedUser(AppUser user, String temporaryPassword) {}

    /**
     * One override that the caller wants to store.
     *
     * @param permissionCode the permission code
     * @param permission the permission, or {@code null} when the code is unknown
     * @param effect whether the permission is allowed or denied
     * @param locationId the location, or {@code null} for every location
     */
    public record OverrideRequest(
            String permissionCode,
            Permission permission,
            PermissionResolver.OverrideEffect effect,
            UUID locationId) {

        /**
         * Creates a request.
         *
         * @param permissionCode the permission code
         * @param effect the effect
         * @param locationId the location
         */
        public OverrideRequest(
                String permissionCode, PermissionResolver.OverrideEffect effect, UUID locationId) {
            this(permissionCode, null, effect, locationId);
        }
    }

    private record PermissionOverridePlan(
            Permission permission, PermissionResolver.OverrideEffect effect, UUID locationId) {}
}
