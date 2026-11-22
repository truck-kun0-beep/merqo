package com.merqo.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The unit test of the audit event and its redaction.
 *
 * <p>It runs without Spring. The redaction happens in the constructor of the event, so a caller
 * cannot forget it.
 */
class AuditEventTest {

    @Test
    @DisplayName("a field named like a password is redacted")
    void aFieldNamedLikeAPasswordIsRedacted() {
        AuditEvent event =
                new AuditEvent.Builder(AuditAction.PASSWORD_CHANGED, "app_users")
                        .changed("password", "old", "new")
                        .build();

        assertEquals(AuditEvent.REDACTED, event.oldValues().get("password"));
        assertEquals(AuditEvent.REDACTED, event.newValues().get("password"));
    }

    @Test
    @DisplayName("a field named like a hash is redacted")
    void aFieldNamedLikeAHashIsRedacted() {
        AuditEvent event =
                new AuditEvent.Builder(AuditAction.PASSWORD_RESET, "app_users")
                        .changed("passwordHash", "old", "new")
                        .build();

        assertEquals(AuditEvent.REDACTED, event.oldValues().get("passwordHash"));
    }

    @Test
    @DisplayName("a field named like a token or a secret is redacted")
    void aFieldNamedLikeATokenOrASecretIsRedacted() {
        AuditEvent event =
                new AuditEvent.Builder(AuditAction.LOGIN_SUCCEEDED, "app_users")
                        .added("sessionToken", "abc")
                        .added("clientSecret", "abc")
                        .added("apiCredential", "abc")
                        .build();

        assertEquals(AuditEvent.REDACTED, event.newValues().get("sessionToken"));
        assertEquals(AuditEvent.REDACTED, event.newValues().get("clientSecret"));
        assertEquals(AuditEvent.REDACTED, event.newValues().get("apiCredential"));
    }

    @Test
    @DisplayName("the name of a forbidden field is recognised without the case")
    void theNameOfAForbiddenFieldIsRecognisedWithoutTheCase() {
        assertTrue(AuditEvent.isForbidden("Password"));
        assertTrue(AuditEvent.isForbidden("PASSWORD_HASH"));
        assertTrue(AuditEvent.isForbidden("myToken"));
    }

    @Test
    @DisplayName("an ordinary field name is not forbidden")
    void anOrdinaryFieldNameIsNotForbidden() {
        assertFalse(AuditEvent.isForbidden("fullName"));
        assertFalse(AuditEvent.isForbidden("username"));
        assertFalse(AuditEvent.isForbidden(null));
    }

    @Test
    @DisplayName("an ordinary value is kept as it is")
    void anOrdinaryValueIsKeptAsItIs() {
        AuditEvent event =
                new AuditEvent.Builder(AuditAction.USER_UPDATED, "app_users")
                        .changed("fullName", "Old name", "New name")
                        .build();

        assertEquals("Old name", event.oldValues().get("fullName"));
        assertEquals("New name", event.newValues().get("fullName"));
    }

    @Test
    @DisplayName("the value maps are copied, so a later change cannot rewrite the event")
    void theValueMapsAreCopied() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fullName", "Old name");

        AuditEvent event =
                new AuditEvent.Builder(AuditAction.USER_UPDATED, "app_users")
                        .changed("fullName", values.get("fullName"), "New name")
                        .build();

        values.put("fullName", "Tampered");

        assertEquals("Old name", event.oldValues().get("fullName"));
    }

    @Test
    @DisplayName("an event without values holds empty maps, not null")
    void anEventWithoutValuesHoldsEmptyMaps() {
        AuditEvent event = new AuditEvent.Builder(AuditAction.LOGIN_SUCCEEDED, "app_users").build();

        assertTrue(event.oldValues().isEmpty());
        assertTrue(event.newValues().isEmpty());
    }

    @Test
    @DisplayName("the builder keeps the action, the entity and the actor")
    void theBuilderKeepsTheActionTheEntityAndTheActor() {
        UUID entityId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AuditEvent event =
                new AuditEvent.Builder(AuditAction.LOCATION_UPDATED, "locations")
                        .entityId(entityId)
                        .actor(actorId, "demo.owner")
                        .build();

        assertEquals(AuditAction.LOCATION_UPDATED, event.action());
        assertEquals("locations", event.entityType());
        assertEquals(entityId, event.entityId());
        assertEquals(actorId, event.actorUserId());
        assertEquals("demo.owner", event.actorUsername());
    }
}
