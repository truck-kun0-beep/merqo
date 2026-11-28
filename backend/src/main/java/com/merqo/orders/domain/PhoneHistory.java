package com.merqo.orders.domain;

/**
 * Aggregated order outcome counts for a normalized phone number, derived dynamically from orders.
 *
 * @param delivered completed deliveries
 * @param cancelled cancelled orders
 * @param declined declined orders
 * @param refused refused deliveries
 * @param returned returned deliveries
 * @param total total historical orders for this phone
 */
public record PhoneHistory(
        int delivered, int cancelled, int declined, int refused, int returned, int total) {

    public static final PhoneHistory EMPTY = new PhoneHistory(0, 0, 0, 0, 0, 0);
}
