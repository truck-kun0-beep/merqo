package com.merqo.orders;

/** Formats sequence values from {@code order_number_sequence} into {@code ORD-000001}. */
public final class OrderNumber {

    private OrderNumber() {}

    public static String format(long sequenceValue) {
        if (sequenceValue < 1) {
            throw new IllegalArgumentException("Order sequence must be positive.");
        }
        return String.format("ORD-%06d", sequenceValue);
    }
}
