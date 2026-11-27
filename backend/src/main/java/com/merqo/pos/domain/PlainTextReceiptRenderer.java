package com.merqo.pos.domain;

import java.util.ArrayList;
import java.util.List;

import com.merqo.common.time.BusinessTime;

/**
 * Renders {@link ReceiptData} into fixed-width plain text for 80 mm (42 columns) or 58 mm (32
 * columns) thermal receipt printers.
 *
 * <p>Responsibility: format receipt header, duplicate banner, line items, totals, payment info, and
 * footer into lines guaranteed not to exceed the configured column width.
 *
 * <p>OOP concept: <b>Strategy / Adapter Implementation</b> — implements {@link ReceiptRenderer} for
 * fixed-width plain-text output, adapting domain data for thermal printer widths.
 */
public final class PlainTextReceiptRenderer implements ReceiptRenderer<String> {

    /** Standard column width for 80 mm paper. */
    public static final int COLUMNS_80MM = 42;

    /** Standard column width for 58 mm paper. */
    public static final int COLUMNS_58MM = 32;

    private final int columns;

    /**
     * Creates a renderer for the specified paper width in millimetres (58 or 80).
     *
     * @param paperWidthMm {@code 58} for 32 columns or {@code 80} for 42 columns
     */
    public PlainTextReceiptRenderer(int paperWidthMm) {
        if (paperWidthMm == 58) {
            this.columns = COLUMNS_58MM;
        } else if (paperWidthMm == 80) {
            this.columns = COLUMNS_80MM;
        } else {
            throw new IllegalArgumentException("Unsupported paper width: " + paperWidthMm);
        }
    }

    /**
     * Returns the configured column width.
     *
     * @return 32 or 42
     */
    public int getColumns() {
        return columns;
    }

    @Override
    public String render(ReceiptData receipt) {
        if (receipt == null) {
            throw new IllegalArgumentException("Receipt data must not be null.");
        }
        StringBuilder sb = new StringBuilder();
        String divider = "-".repeat(columns);

        appendCentered(sb, safe(receipt.businessName()));
        appendCentered(sb, safe(receipt.branchName()));
        if (receipt.branchAddress() != null && !receipt.branchAddress().isBlank()) {
            appendCentered(sb, receipt.branchAddress().trim());
        }
        if (receipt.branchPhone() != null && !receipt.branchPhone().isBlank()) {
            appendCentered(sb, receipt.branchPhone().trim());
        }
        if (receipt.isDuplicate()) {
            appendCentered(sb, "*** DUPLICATE ***");
        }
        sb.append(divider).append('\n');

        appendLeftRight(sb, "Receipt:", safe(receipt.receiptNumber()));
        appendLeftRight(sb, "Date:", BusinessTime.formatForDisplay(receipt.issuedAt()));
        appendLeftRight(sb, "Cashier:", safe(receipt.cashierName()));
        sb.append(divider).append('\n');

        for (ReceiptLineItem item : receipt.lines()) {
            String title =
                    item.variant() == null || item.variant().isBlank()
                            ? item.name()
                            : item.name() + " (" + item.variant() + ")";
            for (String wrapped : wrap(title, columns)) {
                sb.append(wrapped).append('\n');
            }
            String qtyAndUnit =
                    "  " + item.quantity() + " x " + item.unitPrice().amount().toPlainString();
            appendLeftRight(sb, qtyAndUnit, item.lineTotal().amount().toPlainString());
            if (!item.discount().isZero()) {
                appendLeftRight(sb, "  Discount", "-" + item.discount().amount().toPlainString());
            }
        }
        sb.append(divider).append('\n');

        appendLeftRight(sb, "Subtotal", receipt.subtotal().amount().toPlainString());
        if (!receipt.discount().isZero()) {
            appendLeftRight(sb, "Discount", "-" + receipt.discount().amount().toPlainString());
        }
        if (!receipt.tax().isZero()) {
            appendLeftRight(sb, "Tax", receipt.tax().amount().toPlainString());
        }
        appendLeftRight(sb, "TOTAL", "BDT " + receipt.total().amount().toPlainString());
        sb.append(divider).append('\n');

        String paymentLabel = receipt.paymentMethod().name();
        String paymentRight =
                receipt.paymentReference() == null || receipt.paymentReference().isBlank()
                        ? receipt.total().amount().toPlainString()
                        : receipt.paymentReference();
        appendLeftRight(sb, "Paid (" + paymentLabel + ")", paymentRight);

        if (receipt.footerText() != null && !receipt.footerText().isBlank()) {
            sb.append(divider).append('\n');
            for (String wrapped : wrap(receipt.footerText().trim(), columns)) {
                appendCentered(sb, wrapped);
            }
        }
        return sb.toString();
    }

    private void appendCentered(StringBuilder sb, String text) {
        for (String line : wrap(text, columns)) {
            int pad = Math.max(0, (columns - line.length()) / 2);
            sb.append(" ".repeat(pad)).append(line).append('\n');
        }
    }

    private void appendLeftRight(StringBuilder sb, String left, String right) {
        String l = safe(left);
        String r = safe(right);
        if (l.length() + 1 + r.length() <= columns) {
            int spaces = columns - l.length() - r.length();
            sb.append(l).append(" ".repeat(spaces)).append(r).append('\n');
        } else {
            int maxLeft = Math.max(1, columns - r.length() - 1);
            String trimmedLeft = l.length() > maxLeft ? l.substring(0, maxLeft) : l;
            int spaces = Math.max(1, columns - trimmedLeft.length() - r.length());
            String combined = trimmedLeft + " ".repeat(spaces) + r;
            sb.append(combined.length() > columns ? combined.substring(0, columns) : combined)
                    .append('\n');
        }
    }

    private static List<String> wrap(String text, int width) {
        String clean = safe(text);
        if (clean.isEmpty()) {
            return List.of("");
        }
        List<String> lines = new ArrayList<>();
        int idx = 0;
        while (idx < clean.length()) {
            int end = Math.min(idx + width, clean.length());
            lines.add(clean.substring(idx, end));
            idx = end;
        }
        return lines;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
