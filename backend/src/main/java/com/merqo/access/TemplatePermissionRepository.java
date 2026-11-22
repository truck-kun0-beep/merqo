package com.merqo.access;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The database access for the permissions of a template.
 *
 * <p>Responsibility: read and write the {@code template_permissions} table.
 *
 * <p>OOP concept: the interface is the contract the template service depends on.
 */
public interface TemplatePermissionRepository
        extends JpaRepository<TemplatePermission, TemplatePermissionId> {

    /**
     * Finds the permission codes of one template.
     *
     * @param templateId the template
     * @return the codes
     */
    @org.springframework.data.jpa.repository.Query(
            "select tp.permission from TemplatePermission tp where tp.templateId = :templateId")
    List<String> findPermissionCodesByTemplateId(UUID templateId);
}
