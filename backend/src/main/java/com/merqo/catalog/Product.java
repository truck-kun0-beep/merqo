package com.merqo.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One product of the catalogue.
 *
 * <p>Responsibility: hold the data that every variant of this product shares: the code, the name,
 * the category, the brand and the tax rate.
 *
 * <p>The tax rate of the product may be empty. An empty rate means the global rate of the settings
 * is used, and the rule lives in {@link com.merqo.catalog.domain.TaxPolicy}.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - a product is deactivated with {@code isActive} and never deleted.
 *   <li><b>Composition</b> - the product holds the ids of the category and the brand instead of
 *       copying their data.
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 * </ul>
 */
@Entity
@Table(name = "products")
public class Product {

    @Id private UUID id;

    @Column(name = "product_code", nullable = false, length = 60)
    private String productCode;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "brand_id")
    private UUID brandId;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "tax_rate_percent", precision = 5, scale = 2)
    private BigDecimal taxRatePercent;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected Product() {}

    /**
     * Creates a product.
     *
     * @param id the id
     * @param productCode the code of the product
     * @param name the display name
     * @param categoryId the category, or {@code null}
     * @param brandId the brand, or {@code null}
     * @param createdAt when it was created
     */
    public Product(
            UUID id,
            String productCode,
            String name,
            UUID categoryId,
            UUID brandId,
            Instant createdAt) {
        this.id = id;
        this.productCode = productCode;
        this.name = name;
        this.categoryId = categoryId;
        this.brandId = brandId;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.active = true;
    }

    /**
     * The id of the product.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * The code of the product.
     *
     * @return the code
     */
    public String getProductCode() {
        return productCode;
    }

    /**
     * Sets the code of the product.
     *
     * @param productCode the code
     */
    public void setProductCode(String productCode) {
        this.productCode = productCode;
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
     * The category of the product, or {@code null}.
     *
     * @return the category id
     */
    public UUID getCategoryId() {
        return categoryId;
    }

    /**
     * Sets the category of the product.
     *
     * @param categoryId the category id
     */
    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    /**
     * The brand of the product, or {@code null}.
     *
     * @return the brand id
     */
    public UUID getBrandId() {
        return brandId;
    }

    /**
     * Sets the brand of the product.
     *
     * @param brandId the brand id
     */
    public void setBrandId(UUID brandId) {
        this.brandId = brandId;
    }

    /**
     * The description of the product.
     *
     * @return the description, or {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the description of the product.
     *
     * @param description the description
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * The tax rate of the product, or {@code null} when the global rate is used.
     *
     * @return the tax rate
     */
    public BigDecimal getTaxRatePercent() {
        return taxRatePercent;
    }

    /**
     * Sets the tax rate of the product.
     *
     * @param taxRatePercent the tax rate, may be {@code null}
     */
    public void setTaxRatePercent(BigDecimal taxRatePercent) {
        this.taxRatePercent = taxRatePercent;
    }

    /**
     * Whether the product is in use.
     *
     * @return {@code true} when the product is active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Activates or deactivates the product.
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
     * When the product was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * When the product was last changed.
     *
     * @return the update moment
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Records that the product was changed.
     *
     * @param updatedAt when it happened
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
