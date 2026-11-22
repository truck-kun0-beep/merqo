package com.merqo.access;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One permission that belongs to one template.
 *
 * <p>Responsibility: hold the link between a template and one permission code.
 *
 * <p>OOP concept: the permission is stored as text because the catalogue is the Java enum. An
 * unknown code in the database must not crash the application, so the loader logs it once and
 * ignores it. The two columns together identify the row, so the key is a separate {@link
 * TemplatePermissionId} value object.
 */
@Entity
@Table(name = "template_permissions")
@IdClass(TemplatePermissionId.class)
public class TemplatePermission {

    @Id
    @Column(name = "template_id")
    private UUID templateId;

    @Id
    @Column(name = "permission", length = 60)
    private String permission;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected TemplatePermission() {}

    /**
     * Creates the link.
     *
     * @param templateId the template
     * @param permission the permission code
     * @param createdAt when it was created
     */
    public TemplatePermission(UUID templateId, String permission, Instant createdAt) {
        this.templateId = templateId;
        this.permission = permission;
        this.createdAt = createdAt;
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
     * The stored permission code.
     *
     * @return the code
     */
    public String getPermission() {
        return permission;
    }

    /**
     * When the link was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
