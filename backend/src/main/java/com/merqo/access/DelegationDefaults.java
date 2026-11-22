package com.merqo.access;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The permissions a new manager may delegate, before the owner changes the list.
 *
 * <p>Responsibility: read the delegable permissions of the three starting templates. When the owner
 * creates a manager, the delegation list starts with these, and the owner may then change it.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - one small question, one small class.
 *   <li><b>Dependency injection</b> - the template repository is injected, so the class can be
 *       tested on its own.
 * </ul>
 */
public class DelegationDefaults {

    /** The templates the starting list is taken from. */
    public static final List<String> SOURCE_TEMPLATES =
            List.of("CASHIER", "ONLINE_ORDER_STAFF", "INVENTORY_STAFF");

    private final PermissionTemplateRepository templateRepository;

    /**
     * Creates the class.
     *
     * @param templateRepository the templates
     */
    public DelegationDefaults(PermissionTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    /**
     * The delegable permissions of the three starting templates.
     *
     * @return the permissions
     */
    public Set<Permission> delegablePermissions() {
        Set<Permission> permissions = new LinkedHashSet<>();
        for (String code : SOURCE_TEMPLATES) {
            templateRepository
                    .findByCode(code)
                    .ifPresent(
                            template -> {
                                for (String permissionCode :
                                        templateRepository.findPermissionCodes(template.getId())) {
                                    Permission.fromCode(permissionCode)
                                            .filter(Permission::isDelegable)
                                            .ifPresent(permissions::add);
                                }
                            });
        }
        return permissions;
    }
}
