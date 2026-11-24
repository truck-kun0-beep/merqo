package com.merqo.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and writes the barcodes.
 *
 * <p>Responsibility: the only place that writes SQL about barcodes.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface VariantBarcodeRepository extends JpaRepository<VariantBarcode, UUID> {

    /**
     * A barcode by the code itself.
     *
     * @param barcode the code
     * @return the barcode row, if there is one
     */
    Optional<VariantBarcode> findByBarcode(String barcode);

    /**
     * Whether the code is already used.
     *
     * @param barcode the code
     * @return {@code true} when it is used
     */
    boolean existsByBarcode(String barcode);

    /**
     * The barcodes of one variant.
     *
     * @param variantId the variant
     * @return the barcodes
     */
    List<VariantBarcode> findByVariantIdOrderByKindAscBarcodeAsc(UUID variantId);

    /**
     * The primary barcode of one variant.
     *
     * @param variantId the variant
     * @return the barcode, if there is one
     */
    Optional<VariantBarcode> findFirstByVariantIdAndKind(UUID variantId, VariantBarcode.Kind kind);

    /**
     * Every barcode of the catalogue.
     *
     * @return the barcodes
     */
    List<VariantBarcode> findAll();
}
