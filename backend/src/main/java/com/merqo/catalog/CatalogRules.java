package com.merqo.catalog;

import com.merqo.common.error.ConflictException;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;

/**
 * The rules of the catalogue that are too small for a service but too important to repeat.
 *
 * <p>Responsibility: answer the small questions the catalog service asks over and over: is this
 * barcode allowed, is this SKU free, may this category have a child.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the class holds the small rules of the catalogue in one
 *       place.
 *   <li><b>Fail fast</b> - the methods throw the right exception, so the service does not have to
 *       build the answer itself.
 *   <li><b>Static utility with explicit parameter</b> - every method takes the repository it needs,
 *       so the class holds no state.
 * </ul>
 */
public final class CatalogRules {

    /** How deep the category tree may be. A category and a sub category are the two levels. */
    public static final int MAX_CATEGORY_DEPTH = 2;

    private CatalogRules() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Refuses a barcode that does not follow the rules.
     *
     * @param raw the barcode as it was written
     */
    public static void requireValidBarcode(String raw) {
        String reason = com.merqo.catalog.domain.BarcodeRules.rejectionReason(raw);
        if (reason != null) {
            throw new ValidationException(
                    com.merqo.common.error.ErrorCodes.BARCODE_INVALID, reason);
        }
    }

    /**
     * Refuses a SKU that is already used.
     *
     * @param repository the variants
     * @param sku the SKU
     * @param variantId the variant that is being changed, or {@code null} for a new one
     */
    public static void requireFreeSku(
            ProductVariantRepository repository, String sku, java.util.UUID variantId) {
        String trimmed = sku == null ? "" : sku.trim();
        if (trimmed.isEmpty()) {
            throw new ValidationException("VALIDATION_FAILED", "The SKU is empty.");
        }
        ProductVariant existing = repository.findBySkuIgnoreCase(trimmed).orElse(null);
        if (existing != null && !existing.getId().equals(variantId)) {
            throw new ConflictException(
                    com.merqo.common.error.ErrorCodes.SKU_IN_USE,
                    "The SKU " + trimmed + " is already used by another variant.");
        }
    }

    /**
     * Refuses a category that would be deeper than the two levels Merqo allows.
     *
     * @param parentId the parent category, or {@code null} for a top level category
     * @param parentRepository the categories
     * @param categoryId the category that is being changed, or {@code null} for a new one
     */
    public static void requireValidCategoryDepth(
            CategoryRepository parentRepository,
            java.util.UUID parentId,
            java.util.UUID categoryId) {
        if (parentId == null) {
            return;
        }
        Category parent =
                parentRepository
                        .findById(parentId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        if (parent.getParentId() != null && !parent.getId().equals(categoryId)) {
            throw new ValidationException(
                    "VALIDATION_FAILED", "A category may only have one level of sub categories.");
        }
    }
}
