package com.merqo.user;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The password rules of Merqo.
 *
 * <p>Responsibility: decide whether a password may be used, and explain why not. The rules are:
 *
 * <ul>
 *   <li>At least 8 characters.
 *   <li>At least 10 characters for the owner account.
 *   <li>The password may not be the same as the username.
 *   <li>The password may not be one of a small list of very common passwords.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the class only knows about passwords.
 *   <li><b>Encapsulation</b> - the common password list is private; callers only get a decision.
 *   <li><b>Value object</b> - {@link Result} carries the decision and the reason together.
 *   <li><b>Strategy</b> - the minimum length is a parameter, so the same rules serve the owner and
 *       everybody else.
 * </ul>
 */
public class PasswordPolicy {

    /** The minimum length for a normal account. */
    public static final int MINIMUM_LENGTH = 8;

    /** The minimum length for the owner account. */
    public static final int OWNER_MINIMUM_LENGTH = 10;

    private static final Set<String> COMMON_PASSWORDS =
            Set.of(
                    "password",
                    "password1",
                    "password123",
                    "12345678",
                    "123456789",
                    "1234567890",
                    "qwerty123",
                    "letmein123",
                    "welcome123",
                    "admin1234",
                    "iloveyou123",
                    "merqo1234",
                    "merqo12345",
                    "abcd1234",
                    "abc12345",
                    "passw0rd",
                    "changeme123",
                    "shop123456");

    /**
     * Checks a password for a normal account.
     *
     * @param password the password to check
     * @param username the username of the account, may be {@code null}
     * @return the result
     */
    public Result validate(String password, String username) {
        return validate(password, username, MINIMUM_LENGTH);
    }

    /**
     * Checks a password for the owner account, which needs a longer password.
     *
     * @param password the password to check
     * @param username the username of the account, may be {@code null}
     * @return the result
     */
    public Result validateForOwner(String password, String username) {
        return validate(password, username, OWNER_MINIMUM_LENGTH);
    }

    /**
     * Checks a password against a minimum length.
     *
     * @param password the password to check
     * @param username the username of the account, may be {@code null}
     * @param minimumLength the smallest allowed length
     * @return the result
     */
    public Result validate(String password, String username, int minimumLength) {
        if (password == null || password.isEmpty()) {
            return Result.rejected("The password must not be empty.");
        }
        if (password.length() < minimumLength) {
            return Result.rejected(
                    "The password must have at least " + minimumLength + " characters.");
        }
        if (username != null && !username.isBlank() && password.equalsIgnoreCase(username)) {
            return Result.rejected("The password must not be the same as the username.");
        }
        String lowered = password.toLowerCase(Locale.ROOT);
        if (COMMON_PASSWORDS.contains(lowered)) {
            return Result.rejected("This password is too common. Choose a different one.");
        }
        return Result.accepted();
    }

    /**
     * The outcome of a password check.
     *
     * @param valid whether the password may be used
     * @param message why it may not be used, {@code null} when it is valid
     */
    public record Result(boolean valid, String message) {

        /**
         * Creates a result.
         *
         * @param valid whether the password may be used
         * @param message the message
         */
        public Result {
            if (valid && message != null) {
                throw new IllegalArgumentException("A valid result has no message");
            }
            if (!valid && message == null) {
                throw new IllegalArgumentException("An invalid result needs a message");
            }
        }

        /**
         * A passing result.
         *
         * @return the result
         */
        public static Result accepted() {
            return new Result(true, null);
        }

        /**
         * A failing result.
         *
         * @param message why it failed
         * @return the result
         */
        public static Result rejected(String message) {
            return new Result(false, message);
        }
    }

    /**
     * The common passwords that are refused.
     *
     * <p>Exposed for the test that proves the list is not empty, and for the documentation.
     *
     * @return the common passwords
     */
    public List<String> commonPasswords() {
        return List.copyOf(COMMON_PASSWORDS);
    }
}
