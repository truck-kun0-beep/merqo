package com.merqo.access;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One permission template: a named set of permissions.
 *
 * <p>Responsibility: hold the data of one template. The permissions it holds live in their own
 * table, because one template has many permissions.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 *   <li><b>Encapsulation</b> - the active flag keeps an unused template in the database instead of
 *       deleting it, so the history of who held it stays readable.
 * </ul>
 */
@Entity
@Table(name = "permission_templates")
public class PermissionTemplate {

    @Id private UUID id;

    @Column(name = "code", nullable = false, length = 60)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 240)
    private String description;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected PermissionTemplate() {}

    /**
     * Creates a template.
     *
     * @param id the id
     * @param code the stable code
     * @param name the display name
     * @param description the description
     * @param system whether the template is one of the seeded system templates
     * @param createdAt when it was created
     */
    public PermissionTemplate(
            UUID id,
            String code,
            String name,
            String description,
            boolean system,
            Instant createdAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.description = description;
        this.system = system;
        this.active = true;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /**
     * The id of the template.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * The stable code of the template.
     *
     * @return the code
     */
    public String getCode() {
        return code;
    }

    /**
     * Sets the code.
     *
     * @param code the code
     */
    public void setCode(String code) {
        this.code = code;
    }

    /**
     * The display name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the display name.
     *
     * @param name the name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * The description.
     *
     * @return the description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the description.
     *
     * @param description the description
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Whether this is one of the seeded system templates.
     *
     * @return {@code true} for a system template
     */
    public boolean isSystem() {
        return system;
    }

    /**
     * Whether the template may be given to a user.
     *
     * @return {@code true} when the template is active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Activates or deactivates the template.
     *
     * @param active the new state
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * When the template was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * When the template was last changed.
     *
     * @return the update moment
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Records that the template was changed.
     *
     * @param updatedAt when it happened
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
