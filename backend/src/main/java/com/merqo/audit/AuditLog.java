package com.merqo.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of the audit log.
 *
 * <p>Responsibility: hold what happened. The row is written once and never changed: the repository
 * has no update or delete method, and a database trigger raises an error on any UPDATE or DELETE.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 *   <li><b>Encapsulation</b> - there is no setter for the old and new values, so a row can never be
 *       rewritten through this class.
 * </ul>
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_email")
    private String actorEmail;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_username")
    private String actorUsername;

    @Column(name = "action", nullable = false, length = 80)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;

    @Column(name = "entity_id", length = 60)
    private String entityId;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_values", columnDefinition = "jsonb")
    private Map<String, Object> oldValues;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_values", columnDefinition = "jsonb")
    private Map<String, Object> newValues;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** Required by JPA. */
    protected AuditLog() {}

    /**
     * Creates an audit row.
     *
     * @param id the id
     * @param occurredAt when it happened
     * @param event the event to record
     */
    public AuditLog(UUID id, Instant occurredAt, AuditEvent event) {
        this.id = id;
        this.occurredAt = occurredAt;
        this.action = event.action().name();
        this.entityType = event.entityType();
        this.entityId = event.entityId() == null ? null : event.entityId().toString();
        this.locationId = event.locationId();
        this.actorId = event.actorUserId();
        this.actorUserId = event.actorUserId();
        this.actorUsername = event.actorUsername();
        this.oldValues = event.oldValues();
        this.newValues = event.newValues();
        this.ipAddress = event.ipAddress();
        this.correlationId = event.correlationId();
    }

    /**
     * The id of the row.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * When it happened.
     *
     * @return the moment
     */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /**
     * The action that was performed.
     *
     * @return the action name
     */
    public String getAction() {
        return action;
    }

    /**
     * The kind of record that was changed.
     *
     * @return the entity type
     */
    public String getEntityType() {
        return entityType;
    }

    /**
     * The id of the changed record.
     *
     * @return the entity id
     */
    public String getEntityId() {
        return entityId;
    }

    /**
     * The location of the change.
     *
     * @return the location id
     */
    public UUID getLocationId() {
        return locationId;
    }

    /**
     * The user who did it.
     *
     * @return the actor id
     */
    public UUID getActorUserId() {
        return actorUserId;
    }

    /**
     * The username of the user who did it, kept as a snapshot.
     *
     * @return the actor username
     */
    public String getActorUsername() {
        return actorUsername;
    }

    /**
     * The values before the change.
     *
     * @return the old values
     */
    public Map<String, Object> getOldValues() {
        return oldValues;
    }

    /**
     * The values after the change.
     *
     * @return the new values
     */
    public Map<String, Object> getNewValues() {
        return newValues;
    }

    /**
     * The address the request came from.
     *
     * @return the address
     */
    public String getIpAddress() {
        return ipAddress;
    }

    /**
     * The correlation id of the request.
     *
     * @return the correlation id
     */
    public String getCorrelationId() {
        return correlationId;
    }
}
