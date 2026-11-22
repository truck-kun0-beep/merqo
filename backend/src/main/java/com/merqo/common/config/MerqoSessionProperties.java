package com.merqo.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The session settings of Merqo.
 *
 * <p>Responsibility: read the deployment configuration that decides how long a session lives and
 * how the session cookie is written.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the rest of the code never reads an environment variable.
 *   <li><b>Composition</b> - the session settings travel together in one small object.
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "merqo")
public class MerqoSessionProperties {

    /** The name of the session cookie. It is host only, so it never leaves the site. */
    private String cookieName = "MERQO_SESSION";

    /** How long a session may sit unused before it ends. */
    private int idleTimeoutMinutes = 480;

    /** How long a session may live in total, no matter how often it is used. */
    private int absoluteTimeoutMinutes = 720;

    /** Whether the session cookie carries the {@code Secure} flag. */
    private boolean secureCookie = false;

    /**
     * The name of the session cookie.
     *
     * @return the cookie name
     */
    public String getCookieName() {
        return cookieName;
    }

    /**
     * Sets the cookie name.
     *
     * @param cookieName the cookie name
     */
    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    /**
     * The idle timeout of a session.
     *
     * @return the idle timeout
     */
    public Duration getIdleTimeout() {
        return Duration.ofMinutes(idleTimeoutMinutes);
    }

    /**
     * The idle timeout in minutes.
     *
     * @return the number of minutes
     */
    public int getIdleTimeoutMinutes() {
        return idleTimeoutMinutes;
    }

    /**
     * Sets the idle timeout in minutes.
     *
     * @param idleTimeoutMinutes the number of minutes
     */
    public void setIdleTimeoutMinutes(int idleTimeoutMinutes) {
        this.idleTimeoutMinutes = idleTimeoutMinutes;
    }

    /**
     * The absolute timeout of a session.
     *
     * @return the absolute timeout
     */
    public Duration getAbsoluteTimeout() {
        return Duration.ofMinutes(absoluteTimeoutMinutes);
    }

    /**
     * The absolute timeout in minutes.
     *
     * @return the number of minutes
     */
    public int getAbsoluteTimeoutMinutes() {
        return absoluteTimeoutMinutes;
    }

    /**
     * Sets the absolute timeout in minutes.
     *
     * @param absoluteTimeoutMinutes the number of minutes
     */
    public void setAbsoluteTimeoutMinutes(int absoluteTimeoutMinutes) {
        this.absoluteTimeoutMinutes = absoluteTimeoutMinutes;
    }

    /**
     * Whether the session cookie carries the {@code Secure} flag.
     *
     * @return {@code true} when the cookie is secure
     */
    public boolean isSecureCookie() {
        return secureCookie;
    }

    /**
     * Sets the secure flag of the session cookie.
     *
     * @param secureCookie the new state
     */
    public void setSecureCookie(boolean secureCookie) {
        this.secureCookie = secureCookie;
    }
}
