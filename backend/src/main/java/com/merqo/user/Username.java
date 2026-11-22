package com.merqo.user;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The login name of a user.
 *
 * <p>Responsibility: normalise and validate the login name in one place, so that the database only
 * ever sees the normalised form.
 *
 * <p>The rules:
 *
 * <ul>
 *   <li>Between 3 and 40 characters.
 *   <li>Only lowercase letters, digits, dot, underscore and hyphen.
 *   <li>Stored lowercase, so the login matches the same way every time.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Value object</b> - a {@code record} that is only created through the factory, so an
 *       invalid username can never exist.
 *   <li><b>Encapsulation</b> - the normalisation rules are private.
 *   <li><b>Fail fast</b> - the constructor throws instead of returning a broken object.
 * </ul>
 *
 * @param value the normalised username
 */
public record Username(String value) {

    /** The pattern a username must match. */
    public static final Pattern PATTERN = Pattern.compile("^[a-z0-9._-]{3,40}$");

    /** The shortest allowed username. */
    public static final int MIN_LENGTH = 3;

    /** The longest allowed username. */
    public static final int MAX_LENGTH = 40;

    /**
     * Canonical constructor. It normalises the value and refuses an invalid one.
     *
     * @param value the raw username
     */
    public Username {
        value = normalize(value);
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "A username must have 3 to 40 characters of a-z, 0-9, dot, underscore or hyphen.");
        }
    }

    /**
     * Normalises a raw username without validating it.
     *
     * @param raw the raw value
     * @return the normalised value, or {@code null} when the input was {@code null} or blank
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a raw value is a valid username.
     *
     * @param raw the raw value
     * @return {@code true} when it is valid
     */
    public static boolean isValid(String raw) {
        String normalized = normalize(raw);
        return normalized != null && PATTERN.matcher(normalized).matches();
    }

    /**
     * Creates a username.
     *
     * @param raw the raw value
     * @return the username
     * @throws IllegalArgumentException when the value is not a valid username
     */
    public static Username of(String raw) {
        return new Username(raw);
    }

    /**
     * The normalised username as a plain string.
     *
     * @return the value
     */
    @Override
    public String toString() {
        return value;
    }
}
