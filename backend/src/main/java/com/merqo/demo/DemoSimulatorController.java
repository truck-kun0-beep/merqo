package com.merqo.demo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.ObjectMapper;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.access.AccessContext;
import com.merqo.catalog.Product;
import com.merqo.catalog.ProductRepository;
import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.ProductVariantRepository;
import com.merqo.common.config.MerqoModeProperties;
import com.merqo.common.error.BusinessException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.ForbiddenException;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.money.Money;
import com.merqo.integration.IntegrationClient;
import com.merqo.integration.IntegrationClientRepository;
import com.merqo.integration.SecretCipher;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.orders.OnlinePricingService;
import com.merqo.orders.OrderIntakeService;
import com.merqo.orders.OrderIntakeService.OrderIntakeOutcome;
import com.merqo.orders.domain.OrderRuleException;
import com.merqo.orders.domain.RequestSignature;
import com.merqo.user.CurrentUserService;

/**
 * Owner-only Demo Console endpoints for inspecting simulator presets/credentials and firing signed
 * test website orders when {@code DEMO_MODE=true}.
 */
@RestController
@RequestMapping("/api/v1/demo")
@Tag(name = "Demo Console", description = "Owner-only website order simulation in demo mode")
public class DemoSimulatorController {

    public static final String DEMO_CLIENT_KEY = "demo-webshop";
    public static final String DEMO_CLIENT_KEY_ID = "mk_demo_webshop";
    public static final String DEMO_CLIENT_SECRET = "ms_demo_webshop_local_only_secret_2026";

    private final MerqoModeProperties modeProperties;
    private final CurrentUserService currentUserService;
    private final IntegrationClientRepository clientRepository;
    private final SecretCipher secretCipher;
    private final LocationRepository locationRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final OnlinePricingService onlinePricingService;
    private final OrderIntakeService orderIntakeService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DemoSimulatorController(
            MerqoModeProperties modeProperties,
            CurrentUserService currentUserService,
            IntegrationClientRepository clientRepository,
            SecretCipher secretCipher,
            LocationRepository locationRepository,
            ProductVariantRepository variantRepository,
            ProductRepository productRepository,
            OnlinePricingService onlinePricingService,
            OrderIntakeService orderIntakeService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.modeProperties = modeProperties;
        this.currentUserService = currentUserService;
        this.clientRepository = clientRepository;
        this.secretCipher = secretCipher;
        this.locationRepository = locationRepository;
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.onlinePricingService = onlinePricingService;
        this.orderIntakeService = orderIntakeService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @GetMapping("/simulator-info")
    @Operation(
            summary =
                    "Return demo website API key, sample SKUs, and payload presets (owner + demo mode only)")
    public Map<String, Object> simulatorInfo() {
        AccessContext context = requireOwnerInDemoMode();
        IntegrationClient demoClient = ensureDemoClient(context.getUserId());
        String plaintextSecret = secretCipher.decrypt(demoClient.getSecretEncrypted());
        Location warehouse =
                locationRepository.findFirstByOnlineFulfillmentTrueAndActiveTrue().orElse(null);

        List<Map<String, Object>> sampleSkus = new ArrayList<>();
        for (ProductVariant variant :
                variantRepository
                        .search("", true, PageRequest.of(0, 12, Sort.by("sku")))
                        .getContent()) {
            String productName =
                    productRepository
                            .findById(variant.getProductId())
                            .map(Product::getName)
                            .orElse(variant.getSku());
            Money onlinePrice =
                    warehouse == null
                            ? Money.of(variant.getSellingPrice())
                            : onlinePricingService.effectiveUnitPrice(variant, warehouse.getId());
            Map<String, Object> skuRow = new LinkedHashMap<>();
            skuRow.put("variantId", variant.getId());
            skuRow.put("sku", variant.getSku());
            skuRow.put("productName", productName);
            skuRow.put("onlinePrice", onlinePrice.amount());
            sampleSkus.add(skuRow);
        }

        Map<String, Object> clientMap = new LinkedHashMap<>();
        clientMap.put("id", demoClient.getId());
        clientMap.put("name", demoClient.getName());
        clientMap.put("keyId", demoClient.getKeyId());
        clientMap.put("secret", plaintextSecret);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("demoMode", modeProperties.isDemoMode());
        response.put("websiteMode", modeProperties.getWebsiteMode());
        response.put("demoClient", clientMap);
        response.put("sampleSkus", sampleSkus);
        response.put(
                "presets",
                List.of(
                        "STANDARD_COD",
                        "FULL_PREPAID_BKASH",
                        "ADVANCE_COD_NAGAD",
                        "PRICE_MISMATCH",
                        "INVALID_PHONE",
                        "UNKNOWN_SKU"));
        return response;
    }

    @PostMapping("/simulate-order")
    @Operation(
            summary = "Sign and submit a simulated external website order (owner + demo mode only)")
    public Map<String, Object> simulateOrder(
            @RequestBody(required = false) SimulateOrderRequest body,
            HttpServletRequest httpRequest) {
        AccessContext context = requireOwnerInDemoMode();
        IntegrationClient demoClient = ensureDemoClient(context.getUserId());
        String plaintextSecret = secretCipher.decrypt(demoClient.getSecretEncrypted());
        SimulateOrderRequest req =
                body == null
                        ? new SimulateOrderRequest(
                                null, null, null, null, null, null, null, null, null, null, null,
                                null)
                        : body;

        Location warehouse =
                locationRepository.findFirstByOnlineFulfillmentTrueAndActiveTrue().orElse(null);
        ProductVariant defaultVariant =
                variantRepository
                        .search("", true, PageRequest.of(0, 1, Sort.by("sku")))
                        .getContent()
                        .stream()
                        .findFirst()
                        .orElse(null);

        String sku = req.sku();
        Money resolvedPrice = Money.parse("500.00");
        if (sku == null || sku.isBlank()) {
            if ("UNKNOWN_SKU".equalsIgnoreCase(req.preset())) {
                sku = "DEMO-UNKNOWN-SKU-999";
            } else if (defaultVariant != null) {
                sku = defaultVariant.getSku();
                resolvedPrice =
                        warehouse == null
                                ? Money.of(defaultVariant.getSellingPrice())
                                : onlinePricingService.effectiveUnitPrice(
                                        defaultVariant, warehouse.getId());
            } else {
                sku = "DEMO-SKU-1";
            }
        } else {
            ProductVariant matched = variantRepository.findBySkuIgnoreCase(sku.trim()).orElse(null);
            if (matched != null) {
                resolvedPrice =
                        warehouse == null
                                ? Money.of(matched.getSellingPrice())
                                : onlinePricingService.effectiveUnitPrice(
                                        matched, warehouse.getId());
            }
        }

        int qty = req.quantity() != null && req.quantity() > 0 ? req.quantity() : 1;
        Money unitPrice =
                req.unitPrice() != null
                        ? Money.of(req.unitPrice())
                        : ("PRICE_MISMATCH".equalsIgnoreCase(req.preset())
                                ? resolvedPrice.minus(Money.parse("50.00")).isNegative()
                                        ? resolvedPrice.plus(Money.parse("50.00"))
                                        : resolvedPrice.minus(Money.parse("50.00"))
                                : resolvedPrice);
        Money shippingFee =
                req.shippingFee() != null ? Money.of(req.shippingFee()) : Money.parse("60.00");
        Money subtotal = unitPrice.multipliedBy(BigDecimal.valueOf(qty));
        Money total = subtotal.plus(shippingFee);

        String paymentMode = req.paymentMode();
        Money advanceAmount;
        Money codAmount;
        String claimedMethod = req.claimedAdvanceMethod();
        String claimedRef = req.claimedAdvanceReference();

        if (paymentMode == null || paymentMode.isBlank()) {
            if ("FULL_PREPAID_BKASH".equalsIgnoreCase(req.preset())) {
                paymentMode = "FULL_PREPAID";
                claimedMethod = "BKASH";
                claimedRef = "SIM-BK-" + UUID.randomUUID().toString().substring(0, 6);
            } else if ("ADVANCE_COD_NAGAD".equalsIgnoreCase(req.preset())) {
                paymentMode = "ADVANCE_COD";
                claimedMethod = "NAGAD";
                claimedRef = "SIM-NG-" + UUID.randomUUID().toString().substring(0, 6);
            } else {
                paymentMode = "COD";
            }
        }

        switch (paymentMode) {
            case "FULL_PREPAID" -> {
                advanceAmount = total;
                codAmount = Money.ZERO;
            }
            case "ADVANCE_COD" -> {
                advanceAmount =
                        req.advanceAmount() != null
                                ? Money.of(req.advanceAmount())
                                : total.multipliedBy(new BigDecimal("0.5"));
                codAmount = total.minus(advanceAmount);
            }
            default -> {
                paymentMode = "COD";
                advanceAmount = Money.ZERO;
                codAmount = total;
            }
        }

        String externalOrderId =
                req.externalOrderId() != null && !req.externalOrderId().isBlank()
                        ? req.externalOrderId().trim()
                        : "SIM-" + UUID.randomUUID().toString().substring(0, 8);
        String phone =
                req.phone() != null && !req.phone().isBlank()
                        ? req.phone().trim()
                        : ("INVALID_PHONE".equalsIgnoreCase(req.preset())
                                ? "000-BAD-PHONE"
                                : "01711223344");
        String customerName =
                req.customerName() != null && !req.customerName().isBlank()
                        ? req.customerName().trim()
                        : "Demo Website Customer";
        String address =
                req.address() != null && !req.address().isBlank()
                        ? req.address().trim()
                        : "House 21, Road 7, Dhanmondi, Dhaka";
        String district =
                req.district() != null && !req.district().isBlank()
                        ? req.district().trim()
                        : "Dhaka";

        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("externalOrderId", externalOrderId);
        payloadMap.put("customerName", customerName);
        payloadMap.put("phone", phone);
        payloadMap.put("address", address);
        payloadMap.put("district", district);
        payloadMap.put(
                "lines",
                List.of(
                        Map.of(
                                "sku", sku,
                                "quantity", qty,
                                "unitPrice", unitPrice.amount().toPlainString())));
        payloadMap.put("shippingFee", shippingFee.amount().toPlainString());
        payloadMap.put("discountTotal", "0.00");
        payloadMap.put("total", total.amount().toPlainString());
        payloadMap.put("paymentMode", paymentMode);
        payloadMap.put("advanceAmount", advanceAmount.amount().toPlainString());
        payloadMap.put("codAmount", codAmount.amount().toPlainString());
        if (claimedMethod != null) {
            payloadMap.put("claimedAdvanceMethod", claimedMethod);
        }
        if (claimedRef != null) {
            payloadMap.put("claimedAdvanceReference", claimedRef);
        }

        String rawPayload = objectMapper.writeValueAsString(payloadMap);
        long timestamp = Instant.now(clock).getEpochSecond();
        String signature = RequestSignature.computeHex(plaintextSecret, timestamp, rawPayload);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("externalOrderId", externalOrderId);
        result.put(
                "signedHeaders",
                Map.of(
                        "X-Merqo-Key", demoClient.getKeyId(),
                        "X-Merqo-Timestamp", String.valueOf(timestamp),
                        "X-Merqo-Signature", signature));
        result.put("requestBody", payloadMap);

        try {
            OrderIntakeOutcome outcome =
                    orderIntakeService.receiveOrder(demoClient, rawPayload, httpRequest);
            result.put("accepted", true);
            result.put("httpStatus", outcome.idempotentReplay() ? 200 : 201);
            result.put("idempotentReplay", outcome.idempotentReplay());
            result.put("order", outcome.order());
        } catch (OrderRuleException | BusinessException ex) {
            int code =
                    ex instanceof OrderRuleException ore
                            ? ore.getHttpStatus()
                            : ((BusinessException) ex).getHttpStatus();
            String errCode =
                    ex instanceof OrderRuleException ore
                            ? ore.getErrorCode()
                            : ((BusinessException) ex).getErrorCode();
            result.put("accepted", false);
            result.put("httpStatus", code);
            result.put("errorCode", errCode);
            result.put("errorMessage", ex.getMessage());
        }
        return result;
    }

    private AccessContext requireOwnerInDemoMode() {
        if (!modeProperties.isDemoMode()) {
            throw new NotFoundException("The requested record was not found.");
        }
        AccessContext context = currentUserService.requireAccessContext();
        if (!context.isOwner()) {
            throw new ForbiddenException(
                    ErrorCodes.FORBIDDEN, "Only the owner may use the demo order simulator.");
        }
        return context;
    }

    private IntegrationClient ensureDemoClient(UUID ownerUserId) {
        return clientRepository
                .findByDemoKey(DEMO_CLIENT_KEY)
                .orElseGet(
                        () ->
                                clientRepository.save(
                                        new IntegrationClient(
                                                UUID.randomUUID(),
                                                "Demo Webshop",
                                                DEMO_CLIENT_KEY_ID,
                                                secretCipher.encrypt(DEMO_CLIENT_SECRET),
                                                true,
                                                ownerUserId,
                                                Instant.now(clock),
                                                DEMO_CLIENT_KEY)));
    }

    public record SimulateOrderRequest(
            String preset,
            String externalOrderId,
            String customerName,
            String phone,
            String address,
            String district,
            String sku,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal shippingFee,
            String paymentMode,
            BigDecimal advanceAmount,
            String claimedAdvanceMethod,
            String claimedAdvanceReference) {

        // Convenience constructor for 12-arg internal default fallback
        public SimulateOrderRequest(
                String preset,
                String externalOrderId,
                String customerName,
                String phone,
                String address,
                String district,
                String sku,
                Integer quantity,
                BigDecimal unitPrice,
                BigDecimal shippingFee,
                String paymentMode,
                BigDecimal advanceAmount) {
            this(
                    preset,
                    externalOrderId,
                    customerName,
                    phone,
                    address,
                    district,
                    sku,
                    quantity,
                    unitPrice,
                    shippingFee,
                    paymentMode,
                    advanceAmount,
                    null,
                    null);
        }
    }
}
