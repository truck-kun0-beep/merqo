package com.merqo.catalog;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One category of the product tree.
 *
 * <p>Responsibility: hold the data of one category. The rule that there are at most two levels
 * lives in {@link CatalogService}.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - a category is deactivated with {@code isActive} and never deleted,
 *       so the history of every product stays complete.
 *   <li><b>Mapping</b> - the class describes how the object is stored, and nothing else.
 * </ul>
 */
@Entity
@Table(name = "categories")
public class Category {

    @Id private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected Category() {}

    /**
     * Creates a category.
     *
     * @param id the id
     * @param name the display name
     * @param parentId the parent category, or {@code null} for a top level category
     * @param createdAt when it was created
     */
    public Category(UUID id, String name, UUID parentId, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.active = true;
    }

    /**
     * The id of the category.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
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
     * The parent category, or {@code null}.
     *
     * @return the parent id
     */
    public UUID getParentId() {
        return parentId;
    }

    /**
     * Sets the parent category.
     *
     * @param parentId the parent id
     */
    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    /**
     * Whether the category is in use.
     *
     * @return {@code true} when the category is active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Activates or deactivates the category.
     *
     * @param active the new state
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * The stable key of the demo row.
     *
     * @return the demo key, or {@code null}
     */
    public String getDemoKey() {
        return demoKey;
    }

    /**
     * Sets the demo key.
     *
     * @param demoKey the demo key
     */
    public void setDemoKey(String demoKey) {
        this.demoKey = demoKey;
    }

    /**
     * When the category was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * When the category was last changed.
     *
     * @return the update moment
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Records that the category was changed.
     *
     * @param updatedAt when it happened
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
