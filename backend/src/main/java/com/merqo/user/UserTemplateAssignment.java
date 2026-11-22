package com.merqo.user;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * The link between a user and a permission template.
 *
 * <p>Responsibility: hold one link. It is a separate table and not a column on the user, because
 * one user may hold several templates.
 *
 * <p>OOP concept: mapping a many to many relation with its own entity gives the relation its own
 * attributes, such as who assigned it and when.
 */
@Entity
@Table(name = "user_templates")
@IdClass(UserTemplateAssignmentId.class)
public class UserTemplateAssignment {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected UserTemplateAssignment() {}

    /**
     * Creates the link.
     *
     * @param userId the user
     * @param templateId the template
     * @param assignedBy who assigned it
     * @param createdAt when it was assigned
     */
    public UserTemplateAssignment(
            UUID userId, UUID templateId, UUID assignedBy, Instant createdAt) {
        this.userId = userId;
        this.templateId = templateId;
        this.assignedBy = assignedBy;
        this.createdAt = createdAt;
    }

    /**
     * The user.
     *
     * @return the user id
     */
    public UUID getUserId() {
        return userId;
    }

    /**
     * The template.
     *
     * @return the template id
     */
    public UUID getTemplateId() {
        return templateId;
    }

    /**
     * Who assigned the template.
     *
     * @return the id of the user who assigned it
     */
    public UUID getAssignedBy() {
        return assignedBy;
    }

    /**
     * When the template was assigned.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
