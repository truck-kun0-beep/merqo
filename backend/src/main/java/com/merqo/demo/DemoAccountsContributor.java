package com.merqo.demo;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AccountType;
import com.merqo.access.AssignmentType;
import com.merqo.access.DelegationDefaults;
import com.merqo.access.LocationAssignment;
import com.merqo.access.LocationAssignmentRepository;
import com.merqo.access.ManagerDelegation;
import com.merqo.access.ManagerDelegationRepository;
import com.merqo.access.Permission;
import com.merqo.access.PermissionTemplateRepository;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;
import com.merqo.user.UserTemplateAssignment;
import com.merqo.user.UserTemplateAssignmentRepository;

/**
 * Seeds the demo accounts.
 *
 * <p>Responsibility: write the demo accounts, their templates, their assignments and their
 * delegation lists, once. The seed is idempotent: every account is looked up by its stable demo
 * key.
 *
 * <p>The demo accounts get a random hash that nobody knows. They can only sign in through the one
 * click login, and the login endpoint only exists in demo mode.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Strategy</b> - the contributor is one implementation of {@link DemoDataContributor}.
 *   <li><b>Idempotency</b> - the demo key is the stable key of every row.
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "merqo.demo-mode", havingValue = "true")
public class DemoAccountsContributor implements DemoDataContributor {

    /** The password hash of the demo accounts. No one knows the password behind it. */
    private static final String UNUSABLE_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final AppUserRepository userRepository;
    private final LocationRepository locationRepository;
    private final LocationAssignmentRepository assignmentRepository;
    private final UserTemplateAssignmentRepository userTemplateRepository;
    private final ManagerDelegationRepository delegationRepository;
    private final PermissionTemplateRepository templateRepository;
    private final DelegationDefaults delegationDefaults;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /**
     * Creates the contributor.
     *
     * @param userRepository the accounts
     * @param locationRepository the locations
     * @param assignmentRepository the assignments
     * @param userTemplateRepository the template links
     * @param delegationRepository the delegation lists
     * @param templateRepository the permission templates
     * @param passwordEncoder the BCrypt encoder
     * @param clock the clock
     */
    public DemoAccountsContributor(
            AppUserRepository userRepository,
            LocationRepository locationRepository,
            LocationAssignmentRepository assignmentRepository,
            UserTemplateAssignmentRepository userTemplateRepository,
            ManagerDelegationRepository delegationRepository,
            PermissionTemplateRepository templateRepository,
            DelegationDefaults delegationDefaults,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.userRepository = userRepository;
        this.locationRepository = locationRepository;
        this.assignmentRepository = assignmentRepository;
        this.userTemplateRepository = userTemplateRepository;
        this.delegationRepository = delegationRepository;
        this.templateRepository = templateRepository;
        this.delegationDefaults = delegationDefaults;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    @Transactional
    public void seed() {
        Instant now = Instant.now(clock);

        AppUser owner = ensureUser("owner", "owner", "Demo Owner", AccountType.OWNER, now);
        owner.setFullName("Demo Owner");
        userRepository.save(owner);

        AppUser managerDm =
                ensureUser(
                        "manager-dm",
                        "demo.manager.dm",
                        "Demo Manager (two branches)",
                        AccountType.MANAGER,
                        now);
        AppUser managerUt =
                ensureUser(
                        "manager-ut",
                        "demo.manager.ut",
                        "Demo Manager Uttara",
                        AccountType.MANAGER,
                        now);
        AppUser cashier =
                ensureUser("cashier", "demo.cashier", "Demo Cashier", AccountType.STAFF, now);
        AppUser orders =
                ensureUser("orders", "demo.orders", "Demo Online Orders", AccountType.STAFF, now);
        AppUser warehouse =
                ensureUser("warehouse", "demo.warehouse", "Demo Warehouse", AccountType.STAFF, now);

        cashier.setMaxDiscountPercent(new java.math.BigDecimal("5"));
        userRepository.save(cashier);

        assign(managerDm, "DM", AssignmentType.MANAGER, now);
        assign(managerDm, "MP", AssignmentType.MANAGER, now);
        assign(managerUt, "UT", AssignmentType.MANAGER, now);
        assign(cashier, "DM", AssignmentType.STAFF, now);
        assign(orders, "WH", AssignmentType.STAFF, now);
        assign(warehouse, "WH", AssignmentType.STAFF, now);

        template(managerDm, "MANAGER", now);
        template(managerUt, "MANAGER", now);
        template(cashier, "CASHIER", now);
        template(orders, "ONLINE_ORDER_STAFF", now);
        template(warehouse, "WAREHOUSE_STAFF", now);

        seedDelegations(managerDm, now);
        seedDelegations(managerUt, now);
    }

    private AppUser ensureUser(
            String demoKey,
            String username,
            String fullName,
            AccountType accountType,
            Instant now) {
        Optional<AppUser> existing = userRepository.findByDemoKey(demoKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        AppUser user =
                new AppUser(
                        UUID.randomUUID(),
                        username,
                        null,
                        fullName,
                        passwordEncoder.encode(UUID.randomUUID().toString()),
                        accountType,
                        now);
        user.setDemo(true);
        user.setDemoKey(demoKey);
        user.setMustChangePassword(false);
        return userRepository.save(user);
    }

    private void assign(AppUser user, String locationCode, AssignmentType type, Instant now) {
        Optional<Location> location = locationRepository.findByCodeIgnoreCase(locationCode);
        if (location.isEmpty()) {
            return;
        }
        if (assignmentRepository
                .findByUserIdAndLocationIdAndEndedAtIsNull(user.getId(), location.get().getId())
                .isPresent()) {
            return;
        }
        assignmentRepository.save(
                new LocationAssignment(
                        UUID.randomUUID(), user.getId(), location.get().getId(), type, null, now));
    }

    private void template(AppUser user, String templateCode, Instant now) {
        Optional<com.merqo.access.PermissionTemplate> template = findTemplate(templateCode);
        if (template.isEmpty()) {
            return;
        }
        if (!userTemplateRepository
                .findTemplateIdsByUserId(user.getId())
                .contains(template.get().getId())) {
            userTemplateRepository.save(
                    new UserTemplateAssignment(user.getId(), template.get().getId(), null, now));
        }
    }

    private Optional<com.merqo.access.PermissionTemplate> findTemplate(String code) {
        return templateRepository.findByCode(code);
    }

    private void seedDelegations(AppUser manager, Instant now) {
        if (!delegationRepository.findPermissionCodesByManagerUserId(manager.getId()).isEmpty()) {
            return;
        }
        for (Permission permission : delegationDefaults.delegablePermissions()) {
            delegationRepository.save(
                    new ManagerDelegation(manager.getId(), permission.name(), now));
        }
    }
}
