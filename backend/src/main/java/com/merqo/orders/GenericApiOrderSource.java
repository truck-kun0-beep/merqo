package com.merqo.orders;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import com.merqo.common.error.ErrorCodes;
import com.merqo.common.money.Money;
import com.merqo.orders.domain.IncomingOrder;
import com.merqo.orders.domain.IncomingOrder.IncomingOrderLine;
import com.merqo.orders.domain.OrderRuleException;
import com.merqo.orders.domain.PaymentTerms;
import com.merqo.orders.domain.PaymentTerms.ClaimedAdvanceMethod;
import com.merqo.orders.domain.PaymentTerms.PaymentMode;
import com.merqo.orders.domain.PhoneNumber;
import com.merqo.orders.domain.WebsiteOrderSource;

/** Default storefront webhook adapter parsing Merqo's canonical integration JSON order schema. */
@Component
public class GenericApiOrderSource implements WebsiteOrderSource {

    private final ObjectMapper objectMapper;

    public GenericApiOrderSource(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String mode() {
        return "generic";
    }

    @Override
    public IncomingOrder parse(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            throw new OrderRuleException(
                    ErrorCodes.VALIDATION_FAILED, 422, "Order payload must not be blank.");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawPayload);
        } catch (JacksonException ex) {
            throw new OrderRuleException(
                    ErrorCodes.BAD_REQUEST, 400, "Order payload is not valid JSON.");
        }
        if (root == null || !root.isObject()) {
            throw new OrderRuleException(
                    ErrorCodes.VALIDATION_FAILED, 422, "Order payload must be a JSON object.");
        }

        String externalOrderId = textOrNull(root.get("externalOrderId"));
        String customerName = textOrNull(root.get("customerName"));
        String rawPhone = textOrNull(root.get("phone"));
        PhoneNumber phone = PhoneNumber.parse(rawPhone);
        String address = textOrNull(root.get("address"));
        String district = textOrNull(root.get("district"));
        String note = textOrNull(root.get("note"));

        Money shippingFee = moneyOrDefault(root.get("shippingFee"), "shippingFee", Money.ZERO);
        Money discountTotal =
                moneyOrDefault(root.get("discountTotal"), "discountTotal", Money.ZERO);
        Money total = requiredMoney(root.get("total"), "total");

        PaymentMode paymentMode = PaymentMode.fromString(textOrNull(root.get("paymentMode")));
        Money advanceAmount =
                moneyOrDefault(root.get("advanceAmount"), "advanceAmount", Money.ZERO);
        Money codAmount = moneyOrDefault(root.get("codAmount"), "codAmount", Money.ZERO);
        ClaimedAdvanceMethod claimedMethod =
                ClaimedAdvanceMethod.fromNullable(textOrNull(root.get("claimedAdvanceMethod")));
        String claimedReference = textOrNull(root.get("claimedAdvanceReference"));
        PaymentTerms paymentTerms =
                new PaymentTerms(
                        paymentMode, advanceAmount, codAmount, claimedMethod, claimedReference);

        JsonNode linesNode = root.get("lines");
        if (linesNode == null || !linesNode.isArray()) {
            throw new OrderRuleException(
                    ErrorCodes.VALIDATION_FAILED, 422, "lines must be a JSON array.");
        }
        List<IncomingOrderLine> lines = new ArrayList<>();
        for (JsonNode lineNode : linesNode) {
            if (!lineNode.isObject()) {
                throw new OrderRuleException(
                        ErrorCodes.VALIDATION_FAILED, 422, "Each order line must be an object.");
            }
            String sku = textOrNull(lineNode.get("sku"));
            String productName = textOrNull(lineNode.get("productName"));
            int quantity = requiredInt(lineNode.get("quantity"), "quantity");
            Money unitPrice = requiredMoney(lineNode.get("unitPrice"), "unitPrice");
            lines.add(new IncomingOrderLine(sku, productName, quantity, unitPrice));
        }

        return new IncomingOrder(
                externalOrderId,
                customerName,
                phone,
                address,
                district,
                lines,
                shippingFee,
                discountTotal,
                total,
                paymentTerms,
                note);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String value = node.isString() ? node.asString() : node.toString();
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int requiredInt(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.canConvertToInt()) {
            throw new OrderRuleException(
                    ErrorCodes.VALIDATION_FAILED, 422, fieldName + " must be a whole number.");
        }
        return node.asInt();
    }

    private static Money requiredMoney(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new OrderRuleException(
                    ErrorCodes.VALIDATION_FAILED, 422, fieldName + " is required.");
        }
        return parseMoneyNode(node, fieldName);
    }

    private static Money moneyOrDefault(JsonNode node, String fieldName, Money defaultValue) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaultValue;
        }
        if (node.isString() && node.asString().isBlank()) {
            return defaultValue;
        }
        return parseMoneyNode(node, fieldName);
    }

    private static Money parseMoneyNode(JsonNode node, String fieldName) {
        try {
            if (node.isNumber()) {
                return Money.of(node.decimalValue());
            }
            if (node.isString()) {
                return Money.parse(node.asString());
            }
        } catch (IllegalArgumentException ex) {
            throw new OrderRuleException(
                    ErrorCodes.VALIDATION_FAILED,
                    422,
                    "Invalid money amount for " + fieldName + ".");
        }
        throw new OrderRuleException(
                ErrorCodes.VALIDATION_FAILED, 422, "Invalid money value for " + fieldName + ".");
    }
}
