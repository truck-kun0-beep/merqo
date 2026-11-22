package com.merqo.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The unit test of the password policy.
 *
 * <p>It runs without Spring. The policy decides whether a password may be used and explains why
 * not.
 */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    @DisplayName("a long enough password is accepted")
    void aLongEnoughPasswordIsAccepted() {
        PasswordPolicy.Result result = policy.validate("correct-horse", "someuser");

        assertTrue(result.valid());
        assertEquals(null, result.message());
    }

    @Test
    @DisplayName("an empty password is refused")
    void anEmptyPasswordIsRefused() {
        assertFalse(policy.validate("", "someuser").valid());
        assertFalse(policy.validate(null, "someuser").valid());
    }

    @Test
    @DisplayName("a password that is too short is refused")
    void aPasswordThatIsTooShortIsRefused() {
        PasswordPolicy.Result result = policy.validate("Ab1!c", "someuser");

        assertFalse(result.valid());
        assertTrue(result.message().contains(String.valueOf(PasswordPolicy.MINIMUM_LENGTH)));
    }

    @Test
    @DisplayName("the owner needs a longer password")
    void theOwnerNeedsALongerPassword() {
        String nineCharacters = "Abcdefg1!";

        assertTrue(policy.validate(nineCharacters, "owner").valid());
        assertFalse(policy.validateForOwner(nineCharacters, "owner").valid());
    }

    @Test
    @DisplayName("a password that equals the username is refused")
    void aPasswordThatEqualsTheUsernameIsRefused() {
        PasswordPolicy.Result result = policy.validate("someuser", "someuser");

        assertFalse(result.valid());
        assertTrue(result.message().contains("username"));
    }

    @Test
    @DisplayName("the username is compared without the case")
    void theUsernameIsComparedWithoutTheCase() {
        assertFalse(policy.validate("SomeUser", "someuser").valid());
    }

    @Test
    @DisplayName("a very common password is refused")
    void aVeryCommonPasswordIsRefused() {
        PasswordPolicy.Result result = policy.validate("password123", "someuser");

        assertFalse(result.valid());
        assertTrue(result.message().contains("common"));
    }

    @Test
    @DisplayName("the common password list is not empty")
    void theCommonPasswordListIsNotEmpty() {
        assertFalse(policy.commonPasswords().isEmpty());
    }

    @Test
    @DisplayName("a result that is valid may not carry a message")
    void aResultThatIsValidMayNotCarryAMessage() {
        assertThrows(
                IllegalArgumentException.class, () -> new PasswordPolicy.Result(true, "a message"));
    }

    @Test
    @DisplayName("a result that is invalid needs a message")
    void aResultThatIsInvalidNeedsAMessage() {
        assertThrows(IllegalArgumentException.class, () -> new PasswordPolicy.Result(false, null));
    }
}
