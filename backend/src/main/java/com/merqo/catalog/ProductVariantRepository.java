package com.merqo.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the variants.
 *
 * <p>Responsibility: the only place that writes SQL about variants.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    /**
     * A variant by its SKU, ignoring the case.
     *
     * @param sku the SKU
     * @return the variant, if there is one
     */
    Optional<ProductVariant> findBySkuIgnoreCase(String sku);

    /**
     * Whether a variant with this SKU exists.
     *
     * @param sku the SKU
     * @return {@code true} when it exists
     */
    boolean existsBySkuIgnoreCase(String sku);

    /**
     * The variants of a page, searched by name, SKU or barcode.
     *
     * @param q the search text, may be {@code null}
     * @param activeOnly whether only the active variants are wanted
     * @param pageable the page request
     * @return the page
     */
    @Query(
            """
            select distinct v from ProductVariant v, Product p
            where v.productId = p.id
              and (:activeOnly = false or (v.active = true and p.active = true))
              and (coalesce(:q, '') = ''
                   or lower(v.sku) like lower(concat('%', :q, '%'))
                   or lower(p.name) like lower(concat('%', :q, '%'))
                   or exists (select b from VariantBarcode b
                              where b.variantId = v.id
                                and lower(b.barcode) like lower(concat('%', :q, '%'))))
            """)
    Page<ProductVariant> search(
            @Param("q") String q, @Param("activeOnly") boolean activeOnly, Pageable pageable);

    /**
     * The variants of the demo rows.
     *
     * @return the variants
     */
    List<ProductVariant> findByDemoKeyNotNull();

    /**
     * A variant by its demo key.
     *
     * @param demoKey the stable key of the demo row
     * @return the variant, if there is one
     */
    Optional<ProductVariant> findByDemoKey(String demoKey);

    /**
     * The variants of the products of a brand.
     *
     * @param brandId the brand
     * @return the variants
     */
    @Query(
            """
            select v from ProductVariant v, Product p
            where v.productId = p.id and p.brandId = :brandId
            """)
    List<ProductVariant> findByBrandId(@Param("brandId") UUID brandId);

    /**
     * The active variants of the products of a brand.
     *
     * @param brandId the brand
     * @return the variants
     */
    @Query(
            """
            select v from ProductVariant v, Product p
            where v.productId = p.id and p.brandId = :brandId and v.active = true and p.active = true
            """)
    List<ProductVariant> findActiveByBrandId(@Param("brandId") UUID brandId);

    /**
     * The active variants of the products of a category.
     *
     * @param categoryId the category
     * @return the variants
     */
    @Query(
            """
            select v from ProductVariant v, Product p
            where v.productId = p.id and p.categoryId = :categoryId
              and v.active = true and p.active = true
            """)
    List<ProductVariant> findActiveByCategoryId(@Param("categoryId") UUID categoryId);

    /**
     * The variants of one product, ordered by the SKU.
     *
     * @param productId the product
     * @return the variants
     */
    List<ProductVariant> findByProductIdOrderBySkuAsc(UUID productId);

    /**
     * The active variants of one product, ordered by the SKU.
     *
     * @param productId the product
     * @return the variants
     */
    List<ProductVariant> findByProductIdAndActiveTrueOrderBySkuAsc(UUID productId);

    /**
     * The variants of the products of a category.
     *
     * @param categoryId the category
     * @return the variants
     */
    @Query(
            """
            select v from ProductVariant v, Product p
            where v.productId = p.id and p.categoryId = :categoryId
            """)
    List<ProductVariant> findByCategoryId(@Param("categoryId") UUID categoryId);

    /**
     * The variants of the given ids.
     *
     * @param ids the ids
     * @return the variants
     */
    List<ProductVariant> findByIdIn(List<UUID> ids);
}
