package com.merqo.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One sellable variant of a product.
 *
 * <p>Responsibility: hold the details a customer chooses: the SKU, the colour, the size and the
 * price.
 *
 * <p>The cost is not stored here. The cost is the latest row of the cost history, so the cost is
 * never in two places at once.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the price and the state are changed through the service, which also
 *       writes the price history and the audit row.
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 * </ul>
 */
@Entity
@Table(name = "product_variants")
public class ProductVariant {

    @Id private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "sku", nullable = false, length = 60)
    private String sku;

    @Column(name = "color", length = 60)
    private String color;

    @Column(name = "size", length = 60)
    private String size;

    @Column(name = "other_attribute", length = 60)
    private String otherAttribute;

    @Column(name = "selling_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal sellingPrice;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected ProductVariant() {}

    /**
     * Creates a variant.
     *
     * @param id the id
     * @param productId the product it belongs to
     * @param sku the stock keeping unit
     * @param color the colour, may be {@code null}
     * @param size the size, may be {@code null}
     * @param otherAttribute the other attribute, may be {@code null}
     * @param sellingPrice the current selling price
     * @param createdAt when it was created
     */
    public ProductVariant(
            UUID id,
            UUID productId,
            String sku,
            String color,
            String size,
            String otherAttribute,
            BigDecimal sellingPrice,
            Instant createdAt) {
        this.id = id;
        this.productId = productId;
        this.sku = sku;
        this.color = color;
        this.size = size;
        this.otherAttribute = otherAttribute;
        this.sellingPrice = sellingPrice;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.active = true;
    }

    /**
     * The id of the variant.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * The product the variant belongs to.
     *
     * @return the product id
     */
    public UUID getProductId() {
        return productId;
    }

    /**
     * The stock keeping unit.
     *
     * @return the SKU
     */
    public String getSku() {
        return sku;
    }

    /**
     * Sets the stock keeping unit.
     *
     * @param sku the SKU
     */
    public void setSku(String sku) {
        this.sku = sku;
    }

    /**
     * The colour.
     *
     * @return the colour, or {@code null}
     */
    public String getColor() {
        return color;
    }

    /**
     * Sets the colour.
     *
     * @param color the colour
     */
    public void setColor(String color) {
        this.color = color;
    }

    /**
     * The size.
     *
     * @return the size, or {@code null}
     */
    public String getSize() {
        return size;
    }

    /**
     * Sets the size.
     *
     * @param size the size
     */
    public void setSize(String size) {
        this.size = size;
    }

    /**
     * The other attribute.
     *
     * @return the other attribute, or {@code null}
     */
    public String getOtherAttribute() {
        return otherAttribute;
    }

    /**
     * Sets the other attribute.
     *
     * @param otherAttribute the other attribute
     */
    public void setOtherAttribute(String otherAttribute) {
        this.otherAttribute = otherAttribute;
    }

    /**
     * The current selling price.
     *
     * @return the selling price
     */
    public BigDecimal getSellingPrice() {
        return sellingPrice;
    }

    /**
     * Sets the current selling price.
     *
     * @param sellingPrice the selling price
     */
    public void setSellingPrice(BigDecimal sellingPrice) {
        this.sellingPrice = sellingPrice;
    }

    /**
     * Whether the variant is in use.
     *
     * @return {@code true} when the variant is active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Activates or deactivates the variant.
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
     * When the variant was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * When the variant was last changed.
     *
     * @return the update moment
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Records that the variant was changed.
     *
     * @param updatedAt when it happened
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
