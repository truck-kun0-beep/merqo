package com.merqo.common.web;

import java.util.Set;

/**
 * The one pagination request every list endpoint uses.
 *
 * <p>Responsibility: read the paging parameters from the query, cap the page size, and refuse a
 * sort field that is not on the whitelist. The whitelist is what stops a caller from sorting by a
 * column that does not exist or is expensive.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the raw query parameters never reach the services.
 *   <li><b>Whitelisting</b> - a closed set of allowed sort fields is part of the type, so an
 *       unknown field cannot be passed on.
 *   <li><b>Value object</b> - an immutable request that can be handed around safely.
 * </ul>
 *
 * @param page the zero based page number
 * @param size the page size, never more than {@value #MAX_PAGE_SIZE}
 * @param sort the whitelisted sort field
 * @param direction {@code ASC} or {@code DESC}
 */
public record PageRequest(int page, int size, String sort, String direction) {

    /** The biggest page the API will answer. */
    public static final int MAX_PAGE_SIZE = 100;

    /** The page size used when the caller does not ask for one. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** The sort direction for ascending. */
    public static final String ASC = "ASC";

    /** The sort direction for descending. */
    public static final String DESC = "DESC";

    /** The sort fields every list endpoint accepts. */
    public static final Set<String> SORT_FIELDS =
            Set.of(
                    "id",
                    "username",
                    "fullName",
                    "accountType",
                    "isActive",
                    "createdAt",
                    "lastLoginAt",
                    "code",
                    "name",
                    "locationType",
                    "isOnlineFulfillment",
                    "role",
                    "action",
                    "entityType",
                    "occurredAt",
                    "key",
                    "valueType");

    /**
     * Builds a page request, applying the defaults and the cap.
     *
     * @param page the requested page
     * @param size the requested size
     * @param sort the requested sort field
     * @param direction the requested direction
     * @return the page request
     * @throws IllegalArgumentException when the sort field is not whitelisted
     */
    public static PageRequest of(Integer page, Integer size, String sort, String direction) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize =
                size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String safeSort = sort == null || sort.isBlank() ? "id" : sort.trim();
        if (!SORT_FIELDS.contains(safeSort)) {
            throw new IllegalArgumentException("Unsupported sort field: " + safeSort);
        }
        String safeDirection = DESC.equalsIgnoreCase(direction) ? DESC : ASC;
        return new PageRequest(safePage, safeSize, safeSort, safeDirection);
    }

    /**
     * The offset of this page.
     *
     * @return the offset
     */
    public long offset() {
        return (long) page * size;
    }

    /**
     * The sort field as the entity property it names.
     *
     * <p>The frontend sends camel case names, and those are exactly the names of the entity
     * properties. The database columns use snake case, but a JPQL query is written against the
     * entity, not against the table, so the property name is what the sort needs.
     *
     * @return the entity property name
     */
    public String sortProperty() {
        return sort;
    }

    /**
     * The page as a Spring Data request.
     *
     * @return the page request
     */
    public org.springframework.data.domain.PageRequest toSpring() {
        return org.springframework.data.domain.PageRequest.of(
                page,
                size,
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.fromString(direction),
                        sortProperty()));
    }
}
