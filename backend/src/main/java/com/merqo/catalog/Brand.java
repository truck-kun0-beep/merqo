package com.merqo.catalog;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One brand of the products.
 *
 * <p>Responsibility: hold the data of one brand. A brand is deactivated, never deleted.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the state of the brand is changed through the service, which also
 *       checks that no active product still uses it.
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 * </ul>
 */
@Entity
@Table(name = "brands")
public class Brand {

    @Id private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected Brand() {}

    /**
     * Creates a brand.
     *
     * @param id the id
     * @param name the display name
     * @param createdAt when it was created
     */
    public Brand(UUID id, String name, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.active = true;
    }

    /**
     * The id of the brand.
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
     * Whether the brand is in use.
     *
     * @return {@code true} when the brand is active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Activates or deactivates the brand.
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
     * When the brand was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * When the brand was last changed.
     *
     * @return the update moment
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Records that the brand was changed.
     *
     * @param updatedAt when it happened
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
