package com.merqo.common.web;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * The one page shape every list endpoint answers with.
 *
 * <p>Responsibility: describe a page of results in a way the frontend can render without guessing.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Composition</b> - the page holds the rows and the meta data instead of flattening them.
 *   <li><b>Static factory</b> - {@link #of(Page)} converts a Spring page into this one, so the rest
 *       of the code never sees the Spring type.
 *   <li><b>Generics</b> - one class serves every list endpoint.
 * </ul>
 *
 * @param items the rows of this page
 * @param page the zero based page number
 * @param size the page size
 * @param totalItems the total number of rows that match the filter
 * @param totalPages the total number of pages
 * @param hasNext whether another page follows
 */
public record PageResponse<T>(
        List<T> items, int page, int size, long totalItems, int totalPages, boolean hasNext) {

    /**
     * Canonical constructor. The list is copied so the page can never change.
     *
     * @param items the rows
     * @param page the page number
     * @param size the page size
     * @param totalItems the total number of rows
     * @param totalPages the total number of pages
     * @param hasNext whether another page follows
     */
    public PageResponse {
        items = List.copyOf(items);
    }

    /**
     * An empty page.
     *
     * @param <T> the row type
     * @return the empty page
     */
    public static <T> PageResponse<T> empty() {
        return new PageResponse<>(List.of(), 0, 0, 0, 0, false);
    }

    /**
     * Converts a Spring page into this one.
     *
     * @param page the Spring page
     * @param <T> the row type
     * @return the page
     */
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
