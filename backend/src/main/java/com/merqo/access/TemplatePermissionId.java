package com.merqo.access;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The key of a template permission: the template and the permission code.
 *
 * <p>Responsibility: name the two columns that together identify one row of {@code
 * template_permissions}.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Value object</b> - the key has no state of its own beyond the two parts.
 *   <li><b>Immutability</b> - the components are final.
 * </ul>
 *
 * @param templateId the template
 * @param permission the permission code
 */
public record TemplatePermissionId(UUID templateId, String permission) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The default constructor, needed by JPA. */
    public TemplatePermissionId() {
        this(null, null);
    }

    /**
     * Creates the key.
     *
     * @param templateId the template
     * @param permission the permission code
     */
    public TemplatePermissionId {
        Objects.requireNonNull(templateId, "The template id is required");
        Objects.requireNonNull(permission, "The permission is required");
    }
}
