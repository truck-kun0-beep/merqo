package com.merqo.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the brands.
 *
 * <p>Responsibility: the only place that writes SQL about brands.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface BrandRepository extends JpaRepository<Brand, UUID> {

    /**
     * The brands ordered by name.
     *
     * @return the brands
     */
    List<Brand> findAllByOrderByNameAsc();

    /**
     * The active brands ordered by name.
     *
     * @return the brands
     */
    List<Brand> findByActiveTrueOrderByNameAsc();

    /**
     * A brand by its name, ignoring the case.
     *
     * @param name the name
     * @return the brand, if there is one
     */
    Optional<Brand> findByNameIgnoreCase(String name);

    /**
     * The brands of the demo rows.
     *
     * @return the brands
     */
    List<Brand> findByDemoKeyNotNull();

    /**
     * A brand by its demo key.
     *
     * @param demoKey the stable key of the demo row
     * @return the brand, if there is one
     */
    Optional<Brand> findByDemoKey(String demoKey);

    /**
     * The number of active products of a brand.
     *
     * @param brandId the brand
     * @return the number of active products
     */
    @Query("select count(p) from Product p where p.active = true and p.brandId = :brandId")
    long countActiveProductsOfBrand(@Param("brandId") UUID brandId);
}
