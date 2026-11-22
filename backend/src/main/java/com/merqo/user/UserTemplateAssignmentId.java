package com.merqo.user;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The key of a template link: the user and the template.
 *
 * <p>Responsibility: name the two columns that together identify one row of {@code user_templates}.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Value object</b> - the key has no state of its own beyond the two ids.
 *   <li><b>Immutability</b> - the components are final.
 * </ul>
 *
 * @param userId the user
 * @param templateId the template
 */
public record UserTemplateAssignmentId(UUID userId, UUID templateId) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The default constructor, needed by JPA. */
    public UserTemplateAssignmentId() {
        this(null, null);
    }

    /**
     * Creates the key.
     *
     * @param userId the user
     * @param templateId the template
     */
    public UserTemplateAssignmentId {
        Objects.requireNonNull(userId, "The user id is required");
        Objects.requireNonNull(templateId, "The template id is required");
    }
}
