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
 * Reads and writes the products.
 *
 * <p>Responsibility: the only place that writes SQL about products.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface ProductRepository extends JpaRepository<Product, UUID> {

    /**
     * A product by its code, ignoring the case.
     *
     * @param productCode the code
     * @return the product, if there is one
     */
    Optional<Product> findByProductCodeIgnoreCase(String productCode);

    /**
     * The products of a page.
     *
     * @param q the search text, may be {@code null}
     * @param categoryId the category, may be {@code null}
     * @param brandId the brand, may be {@code null}
     * @param activeOnly whether only the active products are wanted
     * @param pageable the page request
     * @return the page
     */
    @Query(
            """
            select p from Product p
            where (coalesce(:q, '') = ''
                   or lower(p.name) like lower(concat('%', :q, '%'))
                   or lower(p.productCode) like lower(concat('%', :q, '%')))
              and (:categoryId is null or p.categoryId = :categoryId)
              and (:brandId is null or p.brandId = :brandId)
              and (:activeOnly = false or p.active = true)
            """)
    Page<Product> search(
            @Param("q") String q,
            @Param("categoryId") UUID categoryId,
            @Param("brandId") UUID brandId,
            @Param("activeOnly") boolean activeOnly,
            Pageable pageable);

    /**
     * The products of the demo rows.
     *
     * @return the products
     */
    List<Product> findByDemoKeyNotNull();

    /**
     * A product by its demo key.
     *
     * @param demoKey the stable key of the demo row
     * @return the product, if there is one
     */
    Optional<Product> findByDemoKey(String demoKey);
}
