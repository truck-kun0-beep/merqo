package com.merqo.user;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The signed in user as Spring Security sees it.
 *
 * <p>Responsibility: carry the id of the user through the security context. The id is the only
 * thing that is kept, because everything else is loaded again on every request.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - the class implements Spring Security's {@link
 *       UserDetails}.
 *   <li><b>Composition</b> - the principal holds the user object instead of copying its fields.
 * </ul>
 */
public class MerqoUserPrincipal implements UserDetails {

    private static final long serialVersionUID = 1L;

    /** The authority every signed in user has. */
    public static final String ROLE_PREFIX = "ROLE_";

    private final UUID userId;
    private final String username;
    private final String passwordHash;
    private final boolean active;
    private final boolean mustChangePassword;
    private final List<GrantedAuthority> authorities;

    /**
     * Creates the principal.
     *
     * @param userId the id of the user
     * @param username the login name
     * @param passwordHash the BCrypt hash, needed by Spring Security but never sent anywhere
     * @param active whether the account is active
     * @param mustChangePassword whether the account has to change its password first
     */
    public MerqoUserPrincipal(
            UUID userId,
            String username,
            String passwordHash,
            boolean active,
            boolean mustChangePassword) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.active = active;
        this.mustChangePassword = mustChangePassword;
        this.authorities = List.of(new SimpleGrantedAuthority(ROLE_PREFIX + "USER"));
    }

    /**
     * The id of the user.
     *
     * @return the user id
     */
    public UUID userId() {
        return userId;
    }

    /**
     * Whether the account has to change its password before it may do anything else.
     *
     * <p>The flag travels with the principal, so the rule can be checked without asking the
     * database a second time. The principal is built again on every request from the account that
     * was just read, so the flag is never stale.
     *
     * @return {@code true} when a change is required
     */
    public boolean mustChangePassword() {
        return mustChangePassword;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
