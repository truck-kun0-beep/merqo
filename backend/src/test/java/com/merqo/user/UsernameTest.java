package com.merqo.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The unit test of the username value object.
 *
 * <p>It runs without Spring. The username is normalised and validated in one place, so the database
 * only ever sees the normalised form.
 */
class UsernameTest {

    @Test
    @DisplayName("a username is trimmed and lowercased")
    void aUsernameIsTrimmedAndLowercased() {
        assertEquals("demo.cashier", Username.of("  Demo.Cashier  ").value());
    }

    @Test
    @DisplayName("a normalise call never throws")
    void aNormaliseCallNeverThrows() {
        assertEquals(null, Username.normalize(null));
        assertEquals("", Username.normalize("   "));
        assertEquals("abc", Username.normalize(" ABC "));
    }

    @Test
    @DisplayName("a valid username is accepted")
    void aValidUsernameIsAccepted() {
        assertTrue(Username.isValid("demo.cashier"));
        assertTrue(Username.isValid("a_b-c.1"));
        assertTrue(Username.isValid("abc"));
    }

    @Test
    @DisplayName("a username that is too short is refused")
    void aUsernameThatIsTooShortIsRefused() {
        assertFalse(Username.isValid("ab"));
        assertFalse(Username.isValid(""));
        assertFalse(Username.isValid(null));
    }

    @Test
    @DisplayName("a username that is too long is refused")
    void aUsernameThatIsTooLongIsRefused() {
        assertFalse(Username.isValid("a".repeat(Username.MAX_LENGTH + 1)));
        assertTrue(Username.isValid("a".repeat(Username.MAX_LENGTH)));
    }

    @Test
    @DisplayName("a username with a forbidden character is refused")
    void aUsernameWithAForbiddenCharacterIsRefused() {
        assertFalse(Username.isValid("demo cashier"));
        assertFalse(Username.isValid("demo@cashier"));
        assertFalse(Username.isValid("demo/cashier"));
        assertFalse(Username.isValid("demo#cashier"));
    }

    @Test
    @DisplayName("the constructor refuses an invalid username")
    void theConstructorRefusesAnInvalidUsername() {
        assertThrows(IllegalArgumentException.class, () -> new Username("ab"));
        assertThrows(IllegalArgumentException.class, () -> new Username("no spaces here"));
    }

    @Test
    @DisplayName("the record prints the normalised value")
    void theRecordPrintsTheNormalisedValue() {
        assertEquals("demo.cashier", Username.of("Demo.Cashier").toString());
    }
}
