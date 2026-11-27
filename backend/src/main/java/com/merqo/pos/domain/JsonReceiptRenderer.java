package com.merqo.pos.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders {@link ReceiptData} into a JSON-serializable map matching the frontend {@code
 * ReceiptData} contract.
 *
 * <p>Responsibility: format all money fields as 2-decimal plain strings (e.g. {@code "1250.00"})
 * and preserve exact contract keys without including any sensitive cost fields.
 *
 * <p>OOP concept: <b>Strategy / Adapter Implementation</b> — implements {@link ReceiptRenderer} for
 * HTTP JSON responses while remaining pure Java with no framework dependencies.
 */
public final class JsonReceiptRenderer implements ReceiptRenderer<Map<String, Object>> {

    @Override
    public Map<String, Object> render(ReceiptData receipt) {
        if (receipt == null) {
            throw new IllegalArgumentException("Receipt data must not be null.");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("businessName", receipt.businessName());
        map.put("branchName", receipt.branchName());
        map.put("branchAddress", receipt.branchAddress());
        map.put("branchPhone", receipt.branchPhone());
        map.put("receiptNumber", receipt.receiptNumber());
        map.put("issuedAt", receipt.issuedAt().toString());
        map.put("cashierName", receipt.cashierName());

        List<Map<String, Object>> renderedLines = new ArrayList<>(receipt.lines().size());
        for (ReceiptLineItem line : receipt.lines()) {
            Map<String, Object> lineMap = new LinkedHashMap<>();
            lineMap.put("name", line.name());
            lineMap.put("variant", line.variant());
            lineMap.put("quantity", line.quantity());
            lineMap.put("unitPrice", line.unitPrice().amount().toPlainString());
            lineMap.put("discount", line.discount().amount().toPlainString());
            lineMap.put("lineTotal", line.lineTotal().amount().toPlainString());
            renderedLines.add(lineMap);
        }
        map.put("lines", renderedLines);

        map.put("subtotal", receipt.subtotal().amount().toPlainString());
        map.put("discount", receipt.discount().amount().toPlainString());
        map.put("tax", receipt.tax().amount().toPlainString());
        map.put("total", receipt.total().amount().toPlainString());
        map.put("paymentMethod", receipt.paymentMethod().name());
        map.put("paymentReference", receipt.paymentReference());
        map.put("footerText", receipt.footerText());
        map.put("isDuplicate", receipt.isDuplicate());
        return map;
    }
}
