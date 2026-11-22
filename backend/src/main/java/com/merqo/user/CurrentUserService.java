package com.merqo.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AccessContext;
import com.merqo.access.PermissionService;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.ForbiddenException;
import com.merqo.common.error.UnauthenticatedException;

/**
 * Finds the signed in user of the current request.
 *
 * <p>Responsibility: turn the security context into an {@link AppUser} and into an {@link
 * AccessContext}.
 *
 * <p>The session stores only the user id. The user, the templates, the overrides and the
 * assignments are loaded again on every request, so deactivating a user or changing a permission
 * takes effect on the very next request.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Facade</b> - the controllers ask for the access context and never touch Spring Security.
 *   <li><b>Null Object</b> - an anonymous caller gives an empty context instead of {@code null}.
 * </ul>
 */
@Service
public class CurrentUserService {

    /** The name of the session attribute that holds the user id. */
    public static final String SESSION_USER_ID = "MERQO_USER_ID";

    private final AppUserRepository userRepository;
    private final PermissionService permissionService;

    /**
     * Creates the service.
     *
     * @param userRepository the accounts
     * @param permissionService the permission loader
     */
    public CurrentUserService(
            AppUserRepository userRepository, PermissionService permissionService) {
        this.userRepository = userRepository;
        this.permissionService = permissionService;
    }

    /**
     * The signed in user, if there is one.
     *
     * @return the user
     */
    @Transactional(readOnly = true)
    public Optional<AppUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof MerqoUserPrincipal principalUser)) {
            return Optional.empty();
        }
        return userRepository.findById(principalUser.userId());
    }

    /**
     * The signed in user.
     *
     * @return the user
     * @throws UnauthenticatedException when nobody is signed in or the account is gone
     */
    @Transactional(readOnly = true)
    public AppUser requireCurrentUser() {
        return currentUser().orElseThrow(() -> new UnauthenticatedException());
    }

    /**
     * The access context of the signed in user.
     *
     * @return the access context
     * @throws UnauthenticatedException when nobody is signed in or the account is gone
     */
    @Transactional(readOnly = true)
    public AccessContext requireAccessContext() {
        AppUser user = requireCurrentUser();
        if (!user.isActive()) {
            // A deactivated account may not do anything, even with an old session cookie.
            throw new UnauthenticatedException();
        }
        return permissionService.buildContext(user);
    }

    /**
     * The access context of the signed in user, or an empty one for an anonymous caller.
     *
     * @return the access context
     */
    @Transactional(readOnly = true)
    public AccessContext accessContextOrAnonymous() {
        return currentUser()
                .filter(AppUser::isActive)
                .map(permissionService::buildContext)
                .orElseGet(
                        () ->
                                new AccessContext(
                                        UUID.randomUUID(),
                                        "anonymous",
                                        com.merqo.access.AccountType.STAFF,
                                        com.merqo.access.EffectivePermissions.none(),
                                        java.util.Set.of(),
                                        false));
    }

    /**
     * Refuses the request when the user still has to change the password.
     *
     * <p>A user with {@code must_change_password} may only call the me, change password and logout
     * endpoints.
     *
     * @param context the access context
     */
    public void requirePasswordChanged(AccessContext context) {
        if (context.isMustChangePassword()) {
            throw new ForbiddenException(
                    ErrorCodes.PASSWORD_CHANGE_REQUIRED,
                    "You must change your password before you can continue.");
        }
    }
}
