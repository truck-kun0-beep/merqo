package com.merqo.access;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The database access for permission templates and the permissions they hold.
 *
 * <p>Responsibility: read and write the template tables. The permissions of a template live in
 * their own table, because one template has many permissions.
 *
 * <p>OOP concept: two repositories in one interface would be confusing, so the template permissions
 * have their own small repository.
 */
public interface PermissionTemplateRepository extends JpaRepository<PermissionTemplate, UUID> {

    /**
     * Finds a template by its stable code.
     *
     * @param code the code
     * @return the template, if it exists
     */
    Optional<PermissionTemplate> findByCode(String code);

    /**
     * Finds every template that is still active.
     *
     * @return the active templates
     */
    List<PermissionTemplate> findByActiveTrueOrderByCodeAsc();

    /**
     * Finds every template, active or not.
     *
     * @return the templates
     */
    List<PermissionTemplate> findAllByOrderByCodeAsc();

    /**
     * Finds the permission codes of one template.
     *
     * @param templateId the template
     * @return the permission codes
     */
    @Query("select tp.permission from TemplatePermission tp where tp.templateId = :templateId")
    List<String> findPermissionCodes(@Param("templateId") UUID templateId);
}
