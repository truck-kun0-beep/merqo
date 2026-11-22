package com.merqo.user;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.common.config.MerqoSessionProperties;

/**
 * Keeps the signed in user in the HTTP session.
 *
 * <p>Responsibility: read the user id from the session and build the {@link Authentication}, and
 * write the user id back when a session is created.
 *
 * <p><b>The session holds only the user id.</b> The account, the templates, the overrides and the
 * assignments are loaded again on every request, which is what makes a deactivation or a permission
 * change take effect on the very next request.
 *
 * <p>The two timeouts:
 *
 * <ul>
 *   <li>The <b>idle timeout</b> is handled by the session itself: the session ends after eight
 *       hours without a request.
 *   <li>The <b>absolute timeout</b> is handled here: the moment of the sign in is stored in the
 *       session, and the session is refused after twelve hours, no matter how often it was used.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - the class implements Spring Security's {@link
 *       SecurityContextRepository}.
 *   <li><b>Strategy</b> - where the session is kept is one replaceable piece.
 *   <li><b>Single responsibility</b> - reading and writing the session is all this class does.
 * </ul>
 */
@Component
public class SessionSecurityContextRepository implements SecurityContextRepository {

    /** The name of the session attribute that holds the user id. */
    public static final String SESSION_USER_ID = CurrentUserService.SESSION_USER_ID;

    /** The name of the session attribute that holds the moment of the sign in. */
    public static final String SESSION_LOGIN_AT = "MERQO_LOGIN_AT";

    private final AppUserRepository userRepository;
    private final MerqoSessionProperties sessionProperties;
    private final Clock clock;

    /**
     * Creates the repository.
     *
     * @param userRepository the accounts
     * @param sessionProperties the session settings
     * @param clock the clock
     */
    public SessionSecurityContextRepository(
            AppUserRepository userRepository,
            MerqoSessionProperties sessionProperties,
            Clock clock) {
        this.userRepository = userRepository;
        this.sessionProperties = sessionProperties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        HttpServletRequest request = holder.getRequest();
        HttpSession session = request.getSession(false);
        if (session == null) {
            return SecurityContextHolder.createEmptyContext();
        }
        Object rawUserId = session.getAttribute(SESSION_USER_ID);
        if (rawUserId == null) {
            return SecurityContextHolder.createEmptyContext();
        }
        if (isPastAbsoluteTimeout(session)) {
            session.invalidate();
            return SecurityContextHolder.createEmptyContext();
        }
        UUID userId;
        try {
            userId = UUID.fromString(rawUserId.toString());
        } catch (IllegalArgumentException exception) {
            session.invalidate();
            return SecurityContextHolder.createEmptyContext();
        }
        AppUser user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isActive()) {
            // The account is gone or was deactivated, so the session is no longer good for
            // anything.
            session.invalidate();
            return SecurityContextHolder.createEmptyContext();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticationFor(user));
        return context;
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(SESSION_USER_ID) != null;
    }

    @Override
    public void saveContext(
            SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = context.getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof MerqoUserPrincipal principal)) {
            // An empty context never overwrites a good session.
            return;
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_USER_ID, principal.userId().toString());
        if (session.getAttribute(SESSION_LOGIN_AT) == null) {
            session.setAttribute(SESSION_LOGIN_AT, Instant.now(clock).toString());
        }
    }

    /**
     * Starts a fresh session for a signed in user.
     *
     * <p>The old session is thrown away and a new one is created, so the session id changes on
     * every sign in. That is the session fixation protection: an id that an attacker managed to
     * plant in the browser is never reused.
     *
     * @param user the account
     * @param request the current request
     */
    public void startSession(AppUser user, HttpServletRequest request) {
        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_USER_ID, user.getId().toString());
        session.setAttribute(SESSION_LOGIN_AT, Instant.now(clock).toString());
        session.setMaxInactiveInterval((int) sessionProperties.getIdleTimeout().toSeconds());
    }

    /**
     * Whether the session has lived longer than the absolute timeout allows.
     *
     * @param session the session
     * @return {@code true} when the session is too old
     */
    public boolean isPastAbsoluteTimeout(HttpSession session) {
        Object rawLoginAt = session.getAttribute(SESSION_LOGIN_AT);
        if (rawLoginAt == null) {
            return false;
        }
        Instant loginAt;
        try {
            loginAt = Instant.parse(rawLoginAt.toString());
        } catch (RuntimeException exception) {
            return true;
        }
        Duration absoluteTimeout = sessionProperties.getAbsoluteTimeout();
        return loginAt.plus(absoluteTimeout).isBefore(Instant.now(clock));
    }

    /**
     * The authentication of one account.
     *
     * @param user the account
     * @return the authentication
     */
    public static Authentication authenticationFor(AppUser user) {
        MerqoUserPrincipal principal =
                new MerqoUserPrincipal(
                        user.getId(),
                        user.getUsername(),
                        user.getPasswordHash(),
                        true,
                        user.isMustChangePassword());
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
