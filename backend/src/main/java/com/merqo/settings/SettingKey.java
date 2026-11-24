package com.merqo.settings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Every business setting Merqo knows, with its type, its default and its validation.
 *
 * <p>Responsibility: describe one setting completely, so that nothing in the code base has to
 * hardcode a business value. Reading a missing key returns the default from this enum; writing a
 * key is validated here.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - each constant carries its own type, default and rule.
 *   <li><b>Polymorphism</b> - the {@link #parse(String)} method switches on the type of the
 *       constant, so every key validates its own value.
 *   <li><b>Type safety</b> - a setting key is an enum value, not a string.
 *   <li><b>Open/closed principle</b> - a new setting is a new constant; nothing else changes.
 * </ul>
 */
public enum SettingKey {
    BUSINESS_NAME("business.name", ValueType.TEXT, "Merqo Demo Store"),
    BUSINESS_LEGAL_NAME("business.legal_name", ValueType.TEXT, ""),
    BUSINESS_PHONE("business.phone", ValueType.TEXT, ""),
    BUSINESS_EMAIL("business.email", ValueType.TEXT, ""),
    BUSINESS_ADDRESS("business.address", ValueType.TEXT, ""),

    RECEIPT_FOOTER("receipt.footer", ValueType.TEXT, "Thank you for shopping with us."),
    RECEIPT_PAPER_WIDTH_MM("receipt.paper_width_mm", ValueType.PAPER_WIDTH, "80"),

    TAX_RATE_PERCENT("tax.rate_percent", ValueType.DECIMAL, "0"),
    TAX_INCLUSIVE("tax.inclusive", ValueType.BOOLEAN, "false"),

    INVENTORY_DEFAULT_REORDER_POINT("inventory.default_reorder_point", ValueType.INTEGER, "5"),

    THRESHOLD_DEAD_STOCK_DAYS("threshold.dead_stock_days", ValueType.INTEGER, "60"),
    THRESHOLD_MARGIN_DROP_POINTS("threshold.margin_drop_points", ValueType.DECIMAL, "5"),
    THRESHOLD_SUPPLIER_PRICE_RISE_PERCENT(
            "threshold.supplier_price_rise_percent", ValueType.DECIMAL, "10"),
    THRESHOLD_UNCONFIRMED_ORDER_HOURS("threshold.unconfirmed_order_hours", ValueType.INTEGER, "2"),
    THRESHOLD_REFUSAL_RISK_COUNT("threshold.refusal_risk_count", ValueType.INTEGER, "2"),
    THRESHOLD_COD_GAP_DAYS("threshold.cod_gap_days", ValueType.INTEGER, "7"),
    THRESHOLD_UNUSUAL_ADJUSTMENT_VALUE(
            "threshold.unusual_adjustment_value", ValueType.AMOUNT, "5000.00");

    /** The paper widths a receipt printer may use, in millimetres. */
    public static final Set<Integer> ALLOWED_PAPER_WIDTHS = Set.of(58, 80);

    private final String key;
    private final ValueType type;
    private final String defaultValue;

    SettingKey(String key, ValueType type, String defaultValue) {
        this.key = key;
        this.type = type;
        this.defaultValue = defaultValue;
    }

    /**
     * The stable key used in the database and in the API.
     *
     * @return the key
     */
    public String getKey() {
        return key;
    }

    /**
     * The type of the value.
     *
     * @return the type
     */
    public ValueType getType() {
        return type;
    }

    /**
     * The value used when the setting was never written.
     *
     * @return the default value as stored
     */
    public String getDefaultValue() {
        return defaultValue;
    }

    /**
     * The default value in the type of this setting.
     *
     * @return the default value
     */
    public Object getDefault() {
        return parse(defaultValue);
    }

    /**
     * The human readable label of the setting.
     *
     * @return the label
     */
    public String getLabel() {
        int lastDot = key.lastIndexOf('.');
        String tail = lastDot < 0 ? key : key.substring(lastDot + 1);
        StringBuilder label = new StringBuilder();
        for (String part : tail.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (label.length() > 0) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return label.toString();
    }

    /**
     * Parses and validates a raw value for this key.
     *
     * @param raw the raw value
     * @return the value in the type of this setting
     * @throws IllegalArgumentException when the value is not allowed
     */
    public Object parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        return switch (type) {
            case TEXT -> value;
            case BOOLEAN -> parseBoolean(value);
            case INTEGER -> parseInteger(value);
            case DECIMAL -> parseDecimal(value);
            case AMOUNT -> parseAmount(value);
            case PAPER_WIDTH -> parsePaperWidth(value);
        };
    }

    private Boolean parseBoolean(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        if ("true".equals(lowered)) {
            return Boolean.TRUE;
        }
        if ("false".equals(lowered)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException(getKey() + " must be true or false.");
    }

    private Integer parseInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(getKey() + " must not be negative.");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(getKey() + " must be a whole number.");
        }
    }

    private BigDecimal parseDecimal(String value) {
        try {
            BigDecimal parsed = new BigDecimal(value);
            if (parsed.signum() < 0) {
                throw new IllegalArgumentException(getKey() + " must not be negative.");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(getKey() + " must be a number.");
        }
    }

    private BigDecimal parseAmount(String value) {
        BigDecimal parsed = parseDecimal(value);
        if (parsed.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(getKey() + " must have at most two decimal places.");
        }
        // An amount always carries two decimal places, so "5000" and "5000.00" are the same amount.
        return parsed.setScale(2, RoundingMode.UNNECESSARY);
    }

    private Integer parsePaperWidth(String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(getKey() + " must be one of 58 or 80.");
        }
        if (!ALLOWED_PAPER_WIDTHS.contains(parsed)) {
            throw new IllegalArgumentException(getKey() + " must be one of 58 or 80.");
        }
        return parsed;
    }

    /**
     * Looks a key up by its stored key.
     *
     * @param key the stored key
     * @return the setting key, or {@code null} when the key is unknown
     */
    public static SettingKey fromKey(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        for (SettingKey candidate : values()) {
            if (candidate.key.equals(trimmed)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Every key, in the order they are shown in the settings screen.
     *
     * @return the keys
     */
    public static List<SettingKey> all() {
        return Arrays.asList(values());
    }

    /**
     * The keys that belong to one group.
     *
     * @param group the group
     * @return the keys of that group
     */
    public static List<SettingKey> inGroup(Group group) {
        return Arrays.stream(values()).filter(key -> key.getGroup() == group).toList();
    }

    /** The groups the settings screen shows. */
    public enum Group {
        BUSINESS("Business"),
        RECEIPT("Receipt"),
        TAX("Tax"),
        INVENTORY("Inventory"),
        THRESHOLDS("Thresholds");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        /**
         * The label of the group.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }
    }

    /**
     * The group a setting belongs to.
     *
     * @return the group
     */
    public Group getGroup() {
        if (key.startsWith("business.")) {
            return Group.BUSINESS;
        }
        if (key.startsWith("receipt.")) {
            return Group.RECEIPT;
        }
        if (key.startsWith("tax.")) {
            return Group.TAX;
        }
        return Group.THRESHOLDS;
    }

    /** The type of a setting value. */
    public enum ValueType {
        /** Free text. */
        TEXT,
        /** True or false. */
        BOOLEAN,
        /** A whole number. */
        INTEGER,
        /** A number with decimals, for example a percentage. */
        DECIMAL,
        /** A money amount with at most two decimal places. */
        AMOUNT,
        /** The paper width of a receipt printer. */
        PAPER_WIDTH
    }

    /**
     * The keys that are never written through the settings screen, because they come from the
     * deployment configuration.
     *
     * @return the read only keys
     */
    public static Set<String> readOnlyKeys() {
        return new LinkedHashSet<>(
                Arrays.asList("session.idle_timeout_minutes", "session.absolute_timeout_minutes"));
    }
}
