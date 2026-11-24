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
 * Reads and writes the categories.
 *
 * <p>Responsibility: the only place that writes SQL about categories.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    /**
     * The categories ordered by name.
     *
     * @return the categories
     */
    List<Category> findAllByOrderByNameAsc();

    /**
     * The active categories ordered by name.
     *
     * @return the categories
     */
    List<Category> findByActiveTrueOrderByNameAsc();

    /**
     * A category by its name, ignoring the case.
     *
     * @param name the name
     * @return the category, if there is one
     */
    Optional<Category> findByNameIgnoreCase(String name);

    /**
     * The categories with the given parent.
     *
     * @param parentId the parent
     * @return the categories
     */
    List<Category> findByParentId(UUID parentId);

    /**
     * The active categories with the given parent.
     *
     * @param parentId the parent
     * @return the categories
     */
    List<Category> findByParentIdAndActiveTrue(UUID parentId);

    /**
     * The categories of the demo rows.
     *
     * @return the categories
     */
    List<Category> findByDemoKeyNotNull();

    /**
     * A category by its demo key.
     *
     * @param demoKey the stable key of the demo row
     * @return the category, if there is one
     */
    Optional<Category> findByDemoKey(String demoKey);

    /**
     * The number of active products in the categories.
     *
     * @param categoryIds the categories
     * @return the number of active products
     */
    @Query("select count(p) from Product p where p.active = true and p.categoryId in :categoryIds")
    long countActiveProductsInCategories(@Param("categoryIds") List<UUID> categoryIds);

    /**
     * The number of active products in one category.
     *
     * @param categoryId the category
     * @return the number of active products
     */
    @Query("select count(p) from Product p where p.active = true and p.categoryId = :categoryId")
    long countActiveProductsInCategory(@Param("categoryId") UUID categoryId);

    /**
     * The number of categories below the given one.
     *
     * @param parentId the parent
     * @return the number of children
     */
    long countByParentId(UUID parentId);

    /**
     * The categories of a page, ordered by name.
     *
     * @param pageable the page request
     * @return the page
     */
    @Query("select c from Category c order by c.name asc")
    Page<Category> findPage(Pageable pageable);
}
