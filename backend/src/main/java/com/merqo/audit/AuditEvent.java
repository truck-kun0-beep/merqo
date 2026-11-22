package com.merqo.audit;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The description of one audited action.
 *
 * <p>Responsibility: carry what happened, by whom, to which record and at which location, and make
 * sure that a secret can never leave this object.
 *
 * <p>Redaction: any field whose name looks like a password, a hash, a token or a secret is replaced
 * with {@value #REDACTED} before the event is stored. The redaction happens in the constructor, so
 * it cannot be forgotten by a caller.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Immutability / value object</b> - a {@code record} with immutable components.
 *   <li><b>Encapsulation</b> - the redaction rule is private and applied once.
 *   <li><b>Defensive programming</b> - the maps are copied, so a later change by the caller cannot
 *       rewrite history.
 *   <li><b>Null Object</b> - the empty value maps instead of {@code null}.
 * </ul>
 *
 * @param action what happened
 * @param entityType the kind of record that was changed
 * @param entityId the id of that record
 * @param locationId the location the change belongs to, or {@code null}
 * @param actorUserId the id of the user who did it, or {@code null} for the system
 * @param actorUsername the username of that user, kept as a snapshot
 * @param oldValues the values before the change
 * @param newValues the values after the change
 * @param ipAddress the address the request came from
 * @param correlationId the id of the request
 */
public record AuditEvent(
        AuditAction action,
        String entityType,
        UUID entityId,
        UUID locationId,
        UUID actorUserId,
        String actorUsername,
        Map<String, Object> oldValues,
        Map<String, Object> newValues,
        String ipAddress,
        String correlationId) {

    /** The text that replaces a secret value. */
    public static final String REDACTED = "[redacted]";

    /** The names that mark a field as a secret. */
    private static final Set<String> FORBIDDEN_NAME_PARTS =
            Set.of("password", "passwd", "hash", "token", "secret", "credential");

    /**
     * Canonical constructor. The value maps are copied and redacted.
     *
     * @param action what happened
     * @param entityType the kind of record
     * @param entityId the id of the record
     * @param locationId the location
     * @param actorUserId the actor
     * @param actorUsername the actor username
     * @param oldValues the values before the change
     * @param newValues the values after the change
     * @param ipAddress the address
     * @param correlationId the correlation id
     */
    public AuditEvent {
        oldValues = redact(oldValues);
        newValues = redact(newValues);
    }

    private static Map<String, Object> redact(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        source.forEach(
                (key, value) -> {
                    if (isForbidden(key)) {
                        result.put(key, REDACTED);
                    } else {
                        result.put(key, value);
                    }
                });
        return result;
    }

    /**
     * Whether a field name marks a value as a secret.
     *
     * @param name the field name
     * @return {@code true} when the value must not be stored
     */
    public static boolean isForbidden(String name) {
        if (name == null) {
            return false;
        }
        String lowered = name.toLowerCase(Locale.ROOT);
        return FORBIDDEN_NAME_PARTS.stream().anyMatch(lowered::contains);
    }

    /**
     * A builder that makes an audit event step by step.
     *
     * <p>OOP concept: a builder keeps the many optional parts of an event readable at the call site
     * and gives the constructor one meaning.
     */
    public static final class Builder {

        private final AuditAction action;
        private final String entityType;
        private UUID entityId;
        private UUID locationId;
        private UUID actorUserId;
        private String actorUsername;
        private final Map<String, Object> oldValues = new LinkedHashMap<>();
        private final Map<String, Object> newValues = new LinkedHashMap<>();
        private String ipAddress;
        private String correlationId;

        /**
         * Starts a builder.
         *
         * @param action what happened
         * @param entityType the kind of record
         */
        public Builder(AuditAction action, String entityType) {
            this.action = action;
            this.entityType = entityType;
        }

        /**
         * Sets the id of the changed record.
         *
         * @param entityId the id
         * @return this builder
         */
        public Builder entityId(UUID entityId) {
            this.entityId = entityId;
            return this;
        }

        /**
         * Sets the location of the change.
         *
         * @param locationId the location
         * @return this builder
         */
        public Builder locationId(UUID locationId) {
            this.locationId = locationId;
            return this;
        }

        /**
         * Sets the actor.
         *
         * @param actorUserId the actor id
         * @param actorUsername the actor username
         * @return this builder
         */
        public Builder actor(UUID actorUserId, String actorUsername) {
            this.actorUserId = actorUserId;
            this.actorUsername = actorUsername;
            return this;
        }

        /**
         * Adds a value that changed.
         *
         * @param name the name of the field
         * @param oldValue the value before the change
         * @param newValue the value after the change
         * @return this builder
         */
        public Builder changed(String name, Object oldValue, Object newValue) {
            oldValues.put(name, oldValue);
            newValues.put(name, newValue);
            return this;
        }

        /**
         * Adds a value that did not exist before.
         *
         * @param name the name of the field
         * @param newValue the value
         * @return this builder
         */
        public Builder added(String name, Object newValue) {
            newValues.put(name, newValue);
            return this;
        }

        /**
         * Sets the address the request came from.
         *
         * @param ipAddress the address
         * @return this builder
         */
        public Builder ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        /**
         * Sets the correlation id of the request.
         *
         * @param correlationId the correlation id
         * @return this builder
         */
        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        /**
         * Builds the event.
         *
         * @return the event
         */
        public AuditEvent build() {
            return new AuditEvent(
                    action,
                    entityType,
                    entityId,
                    locationId,
                    actorUserId,
                    actorUsername,
                    oldValues,
                    newValues,
                    ipAddress,
                    correlationId);
        }
    }
}
