package com.merqo.catalog;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One barcode of a variant.
 *
 * <p>Responsibility: hold one barcode and say whether it is the primary code of the variant or an
 * alternate one.
 *
 * <p>One table holds both kinds, so a code is unique in the whole catalogue and a duplicate is
 * refused whether it is a primary code or an alternate one.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Enums with behaviour</b> - the kind is an enum value, so it can never be a misspelled
 *       string.
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 * </ul>
 */
@Entity
@Table(name = "variant_barcodes")
public class VariantBarcode {

    /** The kinds of barcode a variant may have. */
    public enum Kind {
        /** The one code that identifies the variant. */
        PRIMARY,
        /** An extra code, for example the code of the old packaging. */
        ALTERNATE
    }

    @Id private UUID id;

    @Column(name = "barcode", nullable = false, length = 64)
    private String barcode;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private Kind kind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    /** Required by JPA. */
    protected VariantBarcode() {}

    /**
     * Creates a barcode.
     *
     * @param id the id
     * @param barcode the code itself
     * @param variantId the variant it belongs to
     * @param kind the kind of the code
     * @param createdAt when it was created
     * @param createdBy who created it
     */
    public VariantBarcode(
            UUID id, String barcode, UUID variantId, Kind kind, Instant createdAt, UUID createdBy) {
        this.id = id;
        this.barcode = barcode;
        this.variantId = variantId;
        this.kind = kind;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    /**
     * The id of the barcode row.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * The code itself.
     *
     * @return the barcode
     */
    public String getBarcode() {
        return barcode;
    }

    /**
     * Sets the code.
     *
     * <p>The code of a row may be changed, because the barcode of a variant may be corrected. The
     * history of the change is written to the audit log, so the old code is never lost.
     *
     * @param barcode the new code
     */
    public void setBarcodeForUpdate(String barcode) {
        this.barcode = barcode;
    }

    /**
     * The variant the code belongs to.
     *
     * @return the variant id
     */
    public UUID getVariantId() {
        return variantId;
    }

    /**
     * The kind of the code.
     *
     * @return the kind
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * When the code was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Who created the code.
     *
     * @return the creator id
     */
    public UUID getCreatedBy() {
        return createdBy;
    }
}
