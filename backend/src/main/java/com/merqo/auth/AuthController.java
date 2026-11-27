package com.merqo.auth;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.access.AccessContext;
import com.merqo.access.EffectivePermissions;
import com.merqo.access.LocationAssignmentRepository;
import com.merqo.access.Permission;
import com.merqo.common.config.MerqoModeProperties;
import com.merqo.common.config.MerqoSessionProperties;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.web.CorrelationIdFilter;
import com.merqo.pos.ApprovalService;
import com.merqo.settings.SettingKey;
import com.merqo.settings.SettingService;
import com.merqo.user.AppUser;
import com.merqo.user.AuthService;
import com.merqo.user.CurrentUserService;
import com.merqo.user.SessionSecurityContextRepository;

/**
 * The endpoints of the login, the session and the demo accounts.
 *
 * <p>Responsibility: translate HTTP into calls on the services. The controller holds no business
 * rule; every decision is made in a service or in a plain Java policy class.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Thin controller</b> - the controller only maps the request and the response.
 *   <li><b>Dependency injection</b> - the services are injected.
 *   <li><b>Data transfer object</b> - the request bodies are small records.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Authentication", description = "Login, session and demo accounts")
public class AuthController {

    private final AuthService authService;
    private final CurrentUserService currentUserService;
    private final LocationAssignmentRepository assignmentRepository;
    private final SettingService settingService;
    private final MerqoModeProperties modeProperties;
    private final MerqoSessionProperties sessionProperties;
    private final OwnerBootstrapService ownerBootstrapService;
    private final SessionSecurityContextRepository sessionRepository;
    private final ApprovalService approvalService;

    /**
     * Creates the controller.
     *
     * @param authService the login rules
     * @param currentUserService the signed in user
     * @param assignmentRepository the assignments
     * @param settingService the settings
     * @param modeProperties the mode flags
     * @param sessionProperties the session settings
     * @param ownerBootstrapService the first owner bootstrap
     * @param sessionRepository the session store
     */
    public AuthController(
            AuthService authService,
            CurrentUserService currentUserService,
            LocationAssignmentRepository assignmentRepository,
            SettingService settingService,
            MerqoModeProperties modeProperties,
            MerqoSessionProperties sessionProperties,
            OwnerBootstrapService ownerBootstrapService,
            SessionSecurityContextRepository sessionRepository,
            ApprovalService approvalService) {
        this.authService = authService;
        this.currentUserService = currentUserService;
        this.assignmentRepository = assignmentRepository;
        this.settingService = settingService;
        this.modeProperties = modeProperties;
        this.sessionProperties = sessionProperties;
        this.ownerBootstrapService = ownerBootstrapService;
        this.sessionRepository = sessionRepository;
        this.approvalService = approvalService;
    }

    /**
     * Reports what the login page needs to know.
     *
     * <p>This is the only endpoint that is public and says something about the system.
     *
     * @return the setup state
     */
    @GetMapping("/auth/status")
    @Operation(summary = "Whether the system still needs an owner, and the business name")
    public Map<String, Object> status() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("setupRequired", ownerBootstrapService.setupRequired());
        body.put("demoMode", modeProperties.isDemoMode());
        body.put("businessName", settingService.getString(SettingKey.BUSINESS_NAME));
        return body;
    }

    /**
     * Signs a user in and starts the session.
     *
     * @param request the credentials
     * @param httpRequest the current request
     * @return the signed in user
     */
    @PostMapping("/auth/login")
    @Operation(summary = "Sign in with a username or an email address")
    public Map<String, Object> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        AppUser user =
                authService.login(
                        request.identifier(),
                        request.password(),
                        AuditServiceSupport.clientIp(httpRequest),
                        correlationId());
        return signIn(user, httpRequest);
    }

    /**
     * Ends the session.
     *
     * @param httpRequest the current request
     * @return an empty answer
     */
    @PostMapping("/auth/logout")
    @Operation(summary = "End the session")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * The signed in user with everything the interface needs.
     *
     * @return the user, the scope and the effective permissions
     */
    @GetMapping("/auth/me")
    @Operation(summary = "The signed in user, the assigned locations and the effective permissions")
    public Map<String, Object> me() {
        AppUser user = currentUserService.requireCurrentUser();
        AccessContext context = currentUserService.requireAccessContext();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("id", user.getId());
        body.put("username", user.getUsername());
        body.put("fullName", user.getFullName());
        body.put("email", user.getEmail());
        body.put("accountType", user.getAccountType().name());
        body.put("mustChangePassword", user.isMustChangePassword());
        body.put("isDemo", user.isDemo());
        body.put("employeeCode", user.getEmployeeCode());
        body.put("maxDiscountPercent", user.getMaxDiscountPercent());
        body.put("locations", assignedLocations(user));
        body.put("permissions", permissionList(context.getPermissions()));
        return body;
    }

    /**
     * Changes the password of the signed in user.
     *
     * @param request the current and the new password
     * @param httpRequest the current request
     * @return an empty answer
     */
    @PostMapping("/auth/change-password")
    @Operation(summary = "Change the password of the signed in user")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        AppUser user = currentUserService.requireCurrentUser();
        authService.changeOwnPassword(
                user,
                request.currentPassword(),
                request.newPassword(),
                AuditServiceSupport.clientIp(httpRequest),
                correlationId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Sets or changes the approval PIN of an authorized discount approver.
     *
     * @param request the current password and new PIN
     * @param httpRequest the current request
     * @return an empty answer
     */
    @PutMapping("/auth/approval-pin")
    @Operation(summary = "Set or update the manager approval PIN")
    public ResponseEntity<Void> setApprovalPin(
            @Valid @RequestBody SetApprovalPinRequest request, HttpServletRequest httpRequest) {
        AppUser user = currentUserService.requireCurrentUser();
        AccessContext context = currentUserService.requireAccessContext();
        approvalService.setApprovalPin(
                user,
                context,
                request.currentPassword(),
                request.pin(),
                AuditServiceSupport.clientIp(httpRequest),
                correlationId());
        return ResponseEntity.noContent().build();
    }

    /**
     * The demo accounts, when demo mode is on.
     *
     * @return the demo accounts
     */
    @GetMapping("/demo/accounts")
    @Operation(summary = "The demo accounts, in demo mode only")
    public List<Map<String, Object>> demoAccounts() {
        if (!modeProperties.isDemoMode()) {
            throw new NotFoundException("The requested record was not found.");
        }
        return DemoService.demoAccounts();
    }

    /**
     * Signs a demo account in with one click.
     *
     * @param request the demo key
     * @param httpRequest the current request
     * @return the signed in user
     */
    @PostMapping("/demo/login")
    @Operation(summary = "Sign in as a demo account, in demo mode only")
    public Map<String, Object> demoLogin(
            @Valid @RequestBody DemoLoginRequest request, HttpServletRequest httpRequest) {
        if (!modeProperties.isDemoMode()) {
            throw new NotFoundException("The requested record was not found.");
        }
        AppUser user =
                authService.loginDemoAccount(
                        request.accountKey(),
                        AuditServiceSupport.clientIp(httpRequest),
                        correlationId());
        return signIn(user, httpRequest);
    }

    private Map<String, Object> signIn(AppUser user, HttpServletRequest httpRequest) {
        // A new session on every login. The old id is never reused, so a stolen cookie is useless.
        sessionRepository.startSession(user, httpRequest);
        SecurityContextHolder.clearContext();
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(
                com.merqo.user.SessionSecurityContextRepository.authenticationFor(user));
        SecurityContextHolder.setContext(securityContext);
        return me();
    }

    private List<Map<String, Object>> assignedLocations(AppUser user) {
        List<Map<String, Object>> locations = new java.util.ArrayList<>();
        assignmentRepository.findActiveLocationIdsByUserId(user.getId()).stream()
                .sorted()
                .forEach(
                        locationId -> {
                            Map<String, Object> location = new java.util.LinkedHashMap<>();
                            location.put("id", locationId);
                            locations.add(location);
                        });
        return locations;
    }

    private List<Map<String, Object>> permissionList(EffectivePermissions permissions) {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (Permission permission : Permission.values()) {
            if (!permissions.can(permission) && permissions.locationsFor(permission).isEmpty()) {
                continue;
            }
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("code", permission.name());
            entry.put("module", permission.getModule().name());
            entry.put("description", permission.getDescription());
            entry.put("scope", permission.isLocationScoped() ? "LOCATION" : "ALL");
            entry.put("locationIds", List.copyOf(permissions.locationsFor(permission)));
            list.add(entry);
        }
        return list;
    }

    private String correlationId() {
        String correlationId = org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY);
        return correlationId == null ? "unknown" : correlationId;
    }

    /**
     * The body of a login request.
     *
     * @param identifier the username or the email address
     * @param password the password
     */
    public record LoginRequest(@NotBlank String identifier, @NotBlank String password) {}

    /**
     * The body of a change password request.
     *
     * @param currentPassword the current password
     * @param newPassword the new password
     */
    public record ChangePasswordRequest(
            @NotBlank String currentPassword, @NotBlank String newPassword) {}

    /**
     * The body of an approval PIN update request.
     *
     * @param currentPassword the current login password
     * @param pin the new 4 to 8 digit approval PIN
     */
    public record SetApprovalPinRequest(@NotBlank String currentPassword, @NotBlank String pin) {}

    /**
     * The body of a demo login request.
     *
     * @param accountKey the stable key of the demo account
     */
    public record DemoLoginRequest(@NotBlank String accountKey) {}
}
