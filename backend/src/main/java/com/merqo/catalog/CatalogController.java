package com.merqo.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.merqo.access.AccessContext;
import com.merqo.catalog.domain.Scope;
import com.merqo.common.web.PageRequest;
import com.merqo.common.web.PageResponse;
import com.merqo.user.CurrentUserService;

/**
 * The endpoints of the catalogue: categories, brands, products and variants.
 *
 * <p>Responsibility: translate HTTP into calls on {@link CatalogService}.
 *
 * <p>The cost of a variant is only in the answer when the caller may see the costs. The service
 * decides that, so a caller can never read a cost by guessing an id.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Thin controller</b> - the controller only maps the request and the response.
 *   <li><b>Data transfer object</b> - the request bodies are small records.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/catalog")
@Tag(name = "Catalog", description = "Categories, brands, products and variants")
public class CatalogController {

    private final CatalogService catalogService;
    private final CatalogPricingService pricingService;
    private final CatalogImportService importService;
    private final CurrentUserService currentUserService;
    private final List<VariantDeactivationGuard> variantGuards;

    /**
     * Creates the controller.
     *
     * @param catalogService the catalogue rules
     * @param pricingService the price rules
     * @param importService the CSV import rules
     * @param currentUserService the signed in user
     * @param variantGuards the vetoes of the other modules on deactivating a variant
     */
    public CatalogController(
            CatalogService catalogService,
            CatalogPricingService pricingService,
            CatalogImportService importService,
            CurrentUserService currentUserService,
            List<VariantDeactivationGuard> variantGuards) {
        this.catalogService = catalogService;
        this.pricingService = pricingService;
        this.importService = importService;
        this.currentUserService = currentUserService;
        this.variantGuards = variantGuards;
    }

    // ---- Categories ---------------------------------------------------------

    /**
     * The categories.
     *
     * @param activeOnly whether only the active categories are wanted
     * @return the categories
     */
    @GetMapping("/categories")
    @Operation(summary = "The categories of the catalogue")
    public List<Map<String, Object>> listCategories(
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        return catalogService.listCategories(activeOnly);
    }

    /**
     * Creates a category.
     *
     * @param request the category to create
     * @return the new category
     */
    @PostMapping("/categories")
    @Operation(summary = "Create a category")
    public ResponseEntity<Map<String, Object>> createCategory(
            @Valid @RequestBody CreateCategoryRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        Category category =
                catalogService.createCategory(context, request.name(), request.parentId());
        return ResponseEntity.status(201).body(catalogService.toCategory(category));
    }

    /**
     * Edits a category.
     *
     * @param id the category
     * @param request the new values
     * @return the changed category
     */
    @PutMapping("/categories/{id}")
    @Operation(summary = "Edit a category")
    public Map<String, Object> updateCategory(
            @PathVariable UUID id, @Valid @RequestBody UpdateCategoryRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        Category category = catalogService.getCategory(id);
        Category updated =
                catalogService.updateCategory(
                        context, category, request.name(), request.parentId());
        return catalogService.toCategory(updated);
    }

    /**
     * Deactivates a category.
     *
     * @param id the category
     * @return the deactivated category
     */
    @PostMapping("/categories/{id}/deactivate")
    @Operation(summary = "Deactivate a category")
    public Map<String, Object> deactivateCategory(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        Category category = catalogService.getCategory(id);
        return catalogService.toCategory(catalogService.deactivateCategory(context, category));
    }

    /**
     * Activates a category again.
     *
     * @param id the category
     * @return the activated category
     */
    @PostMapping("/categories/{id}/activate")
    @Operation(summary = "Activate a category again")
    public Map<String, Object> activateCategory(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        Category category = catalogService.getCategory(id);
        return catalogService.toCategory(catalogService.activateCategory(context, category));
    }

    // ---- Brands -------------------------------------------------------------

    /**
     * The brands.
     *
     * @param activeOnly whether only the active brands are wanted
     * @return the brands
     */
    @GetMapping("/brands")
    @Operation(summary = "The brands of the catalogue")
    public List<Map<String, Object>> listBrands(
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        return catalogService.listBrands(activeOnly);
    }

    /**
     * Creates a brand.
     *
     * @param request the brand to create
     * @return the new brand
     */
    @PostMapping("/brands")
    @Operation(summary = "Create a brand")
    public ResponseEntity<Map<String, Object>> createBrand(
            @Valid @RequestBody CreateBrandRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        Brand brand = catalogService.createBrand(context, request.name());
        return ResponseEntity.status(201).body(catalogService.toBrand(brand));
    }

    /**
     * Edits a brand.
     *
     * @param id the brand
     * @param request the new values
     * @return the changed brand
     */
    @PutMapping("/brands/{id}")
    @Operation(summary = "Edit a brand")
    public Map<String, Object> updateBrand(
            @PathVariable UUID id, @Valid @RequestBody UpdateBrandRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        Brand brand = catalogService.getBrand(id);
        Brand updated = catalogService.updateBrand(context, brand, request.name());
        return catalogService.toBrand(updated);
    }

    /**
     * Deactivates a brand.
     *
     * @param id the brand
     * @return the deactivated brand
     */
    @PostMapping("/brands/{id}/deactivate")
    @Operation(summary = "Deactivate a brand")
    public Map<String, Object> deactivateBrand(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        Brand brand = catalogService.getBrand(id);
        return catalogService.toBrand(catalogService.deactivateBrand(context, brand));
    }

    /**
     * Activates a brand again.
     *
     * @param id the brand
     * @return the activated brand
     */
    @PostMapping("/brands/{id}/activate")
    @Operation(summary = "Activate a brand again")
    public Map<String, Object> activateBrand(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        Brand brand = catalogService.getBrand(id);
        return catalogService.toBrand(catalogService.activateBrand(context, brand));
    }

    // ---- Products -----------------------------------------------------------

    /**
     * The products of a page.
     *
     * @param q the search text
     * @param categoryId the category filter
     * @param brandId the brand filter
     * @param activeOnly whether only the active products are wanted
     * @param page the page number
     * @param size the page size
     * @return the page of products
     */
    @GetMapping("/products")
    @Operation(summary = "The products of a page")
    public PageResponse<Map<String, Object>> listProducts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        currentUserService.requireAccessContext();
        PageRequest request = PageRequest.of(page, size, "id", PageRequest.ASC);
        Page<Product> result =
                catalogService.searchProducts(
                        q, categoryId, brandId, activeOnly, request.toSpring());
        List<Map<String, Object>> items = new ArrayList<>();
        result.forEach(product -> items.add(catalogService.toProduct(product, false)));
        return PageResponse.of(
                new PageImpl<>(items, result.getPageable(), result.getTotalElements()));
    }

    /**
     * One product with its variants.
     *
     * @param id the product
     * @return the product
     */
    @GetMapping("/products/{id}")
    @Operation(summary = "One product with its variants")
    public Map<String, Object> getProduct(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        Product product = catalogService.getProduct(id);
        return catalogService.toProduct(product, maySeeCost(context));
    }

    /**
     * Creates a product.
     *
     * @param request the product to create
     * @return the new product
     */
    @PostMapping("/products")
    @Operation(summary = "Create a product")
    public ResponseEntity<Map<String, Object>> createProduct(
            @Valid @RequestBody CreateProductRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        Product product =
                catalogService.createProduct(
                        context,
                        request.productCode(),
                        request.name(),
                        request.categoryId(),
                        request.brandId(),
                        request.description(),
                        request.taxRatePercent());
        return ResponseEntity.status(201)
                .body(catalogService.toProduct(product, maySeeCost(context)));
    }

    /**
     * Edits a product.
     *
     * @param id the product
     * @param request the new values
     * @return the changed product
     */
    @PutMapping("/products/{id}")
    @Operation(summary = "Edit a product")
    public Map<String, Object> updateProduct(
            @PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        Product product = catalogService.getProduct(id);
        Product updated =
                catalogService.updateProduct(
                        context,
                        product,
                        request.name(),
                        request.categoryId(),
                        request.brandId(),
                        request.description(),
                        request.taxRatePercent());
        return catalogService.toProduct(updated, maySeeCost(context));
    }

    /**
     * Deactivates a product.
     *
     * @param id the product
     * @return the deactivated product
     */
    @PostMapping("/products/{id}/deactivate")
    @Operation(summary = "Deactivate a product")
    public Map<String, Object> deactivateProduct(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        Product product = catalogService.getProduct(id);
        Product updated = catalogService.deactivateProduct(context, product);
        return catalogService.toProduct(updated, maySeeCost(context));
    }

    /**
     * Activates a product again.
     *
     * @param id the product
     * @return the activated product
     */
    @PostMapping("/products/{id}/activate")
    @Operation(summary = "Activate a product again")
    public Map<String, Object> activateProduct(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        Product product = catalogService.getProduct(id);
        Product updated = catalogService.activateProduct(context, product);
        return catalogService.toProduct(updated, maySeeCost(context));
    }

    // ---- Variants -----------------------------------------------------------

    /**
     * The variants of a page.
     *
     * @param q the search text, which also matches a barcode
     * @param activeOnly whether only the active variants are wanted
     * @param page the page number
     * @param size the page size
     * @return the page of variants
     */
    @GetMapping("/variants")
    @Operation(summary = "The variants of a page")
    public PageResponse<Map<String, Object>> listVariants(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        AccessContext context = currentUserService.requireAccessContext();
        PageRequest request = PageRequest.of(page, size, "id", PageRequest.ASC);
        Page<ProductVariant> result =
                catalogService.searchVariants(q, activeOnly, request.toSpring());
        List<Map<String, Object>> items = new ArrayList<>();
        result.forEach(
                variant -> items.add(catalogService.toVariant(variant, maySeeCost(context))));
        return PageResponse.of(
                new PageImpl<>(items, result.getPageable(), result.getTotalElements()));
    }

    /**
     * One variant.
     *
     * @param id the variant
     * @return the variant
     */
    @GetMapping("/variants/{id}")
    @Operation(summary = "One variant")
    public Map<String, Object> getVariant(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        ProductVariant variant = catalogService.getVariant(id);
        Map<String, Object> body = catalogService.toVariant(variant, maySeeCost(context));
        List<Map<String, Object>> barcodes = new ArrayList<>();
        for (VariantBarcode barcode : catalogService.barcodesOf(id)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", barcode.getId());
            entry.put("barcode", barcode.getBarcode());
            entry.put("kind", barcode.getKind());
            barcodes.add(entry);
        }
        body.put("barcodes", barcodes);
        return body;
    }

    /**
     * Creates a variant of a product.
     *
     * @param productId the product
     * @param request the variant to create
     * @return the new variant
     */
    @PostMapping("/products/{productId}/variants")
    @Operation(summary = "Create a variant of a product")
    public ResponseEntity<Map<String, Object>> createVariant(
            @PathVariable UUID productId, @Valid @RequestBody CreateVariantRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        Product product = catalogService.getProduct(productId);
        ProductVariant variant =
                catalogService.createVariant(
                        context,
                        product,
                        request.sku(),
                        request.color(),
                        request.size(),
                        request.otherAttribute(),
                        request.sellingPrice(),
                        request.barcode());
        return ResponseEntity.status(201)
                .body(catalogService.toVariant(variant, maySeeCost(context)));
    }

    /**
     * Edits the details of a variant. The price is changed through its own endpoint.
     *
     * @param id the variant
     * @param request the new values
     * @return the changed variant
     */
    @PutMapping("/variants/{id}")
    @Operation(summary = "Edit the details of a variant")
    public Map<String, Object> updateVariant(
            @PathVariable UUID id, @Valid @RequestBody UpdateVariantRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        ProductVariant variant = catalogService.getVariant(id);
        ProductVariant updated =
                catalogService.updateVariant(
                        context,
                        variant,
                        request.sku(),
                        request.color(),
                        request.size(),
                        request.otherAttribute());
        return catalogService.toVariant(updated, maySeeCost(context));
    }

    /**
     * Deactivates a variant.
     *
     * @param id the variant
     * @return the deactivated variant
     */
    @PostMapping("/variants/{id}/deactivate")
    @Operation(summary = "Deactivate a variant")
    public Map<String, Object> deactivateVariant(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        ProductVariant variant = catalogService.getVariant(id);
        ProductVariant updated = catalogService.deactivateVariant(context, variant, variantGuards);
        return catalogService.toVariant(updated, maySeeCost(context));
    }

    /**
     * Activates a variant again.
     *
     * @param id the variant
     * @return the activated variant
     */
    @PostMapping("/variants/{id}/activate")
    @Operation(summary = "Activate a variant again")
    public Map<String, Object> activateVariant(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        ProductVariant variant = catalogService.getVariant(id);
        ProductVariant updated = catalogService.activateVariant(context, variant);
        return catalogService.toVariant(updated, maySeeCost(context));
    }

    /**
     * Changes the selling price of a variant.
     *
     * @param id the variant
     * @param request the new price
     * @return the changed variant
     */
    @PostMapping("/variants/{id}/price")
    @Operation(summary = "Change the selling price of a variant")
    public Map<String, Object> changePrice(
            @PathVariable UUID id, @Valid @RequestBody ChangePriceRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        ProductVariant variant = catalogService.getVariant(id);
        ProductVariant updated =
                catalogService.changePrice(
                        context,
                        variant,
                        request.sellingPrice(),
                        VariantPriceHistory.SourceType.MANUAL,
                        null);
        return catalogService.toVariant(updated, maySeeCost(context));
    }

    /**
     * Changes the cost of a variant.
     *
     * @param id the variant
     * @param request the new cost
     * @return the changed variant
     */
    @PostMapping("/variants/{id}/cost")
    @Operation(summary = "Change the cost of a variant")
    public Map<String, Object> changeCost(
            @PathVariable UUID id, @Valid @RequestBody ChangeCostRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        ProductVariant variant = catalogService.getVariant(id);
        ProductVariant updated =
                catalogService.changeCost(
                        context,
                        variant,
                        request.costPrice(),
                        VariantCostHistory.SourceType.MANUAL,
                        null);
        return catalogService.toVariant(updated, maySeeCost(context));
    }

    /**
     * Sets the primary barcode of a variant.
     *
     * @param id the variant
     * @param request the barcode
     * @return the changed variant
     */
    @PostMapping("/variants/{id}/barcode")
    @Operation(summary = "Set the primary barcode of a variant")
    public Map<String, Object> setPrimaryBarcode(
            @PathVariable UUID id, @Valid @RequestBody SetBarcodeRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        ProductVariant variant = catalogService.getVariant(id);
        catalogService.setPrimaryBarcode(context, variant, request.barcode());
        return getVariant(id);
    }

    /**
     * Adds an alternate barcode to a variant.
     *
     * @param id the variant
     * @param request the barcode
     * @return the changed variant
     */
    @PostMapping("/variants/{id}/barcodes")
    @Operation(summary = "Add an alternate barcode to a variant")
    public Map<String, Object> addAlternateBarcode(
            @PathVariable UUID id, @Valid @RequestBody SetBarcodeRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        ProductVariant variant = catalogService.getVariant(id);
        catalogService.addAlternateBarcode(context, variant, request.barcode());
        return getVariant(id);
    }

    /**
     * Removes a barcode of a variant.
     *
     * @param id the variant
     * @param barcodeId the barcode
     * @return the changed variant
     */
    @DeleteMapping("/variants/{id}/barcodes/{barcodeId}")
    @Operation(summary = "Remove a barcode of a variant")
    public Map<String, Object> removeBarcode(@PathVariable UUID id, @PathVariable UUID barcodeId) {
        AccessContext context = currentUserService.requireAccessContext();
        ProductVariant variant = catalogService.getVariant(id);
        VariantBarcode barcode =
                catalogService.barcodesOf(id).stream()
                        .filter(candidate -> candidate.getId().equals(barcodeId))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new com.merqo.common.error.NotFoundException(
                                                "The requested record was not found."));
        catalogService.removeBarcode(context, variant, barcode);
        return getVariant(id);
    }

    /**
     * The variant that carries a barcode.
     *
     * @param barcode the scanned code
     * @return the variant
     */
    @GetMapping("/barcode/{barcode}")
    @Operation(summary = "The variant that carries a barcode")
    public Map<String, Object> findByBarcode(@PathVariable String barcode) {
        AccessContext context = currentUserService.requireAccessContext();
        ProductVariant variant = catalogService.findByBarcode(barcode);
        return catalogService.toVariant(variant, maySeeCost(context));
    }

    // ---- Scan and price -----------------------------------------------------

    /**
     * Looks a scanned barcode up and answers with the variant and its price.
     *
     * <p>This is the endpoint the barcode scanner talks to. The match is exact and case sensitive,
     * an inactive variant is never returned, and the stock is only part of the answer when the
     * caller may view that location.
     *
     * @param barcode the scanned code
     * @param scope the channel the customer buys in
     * @param locationId the location of the purchase, may be {@code null}
     * @return the variant and the price breakdown
     */
    @GetMapping("/lookup")
    @Operation(summary = "Look a scanned barcode up and answer with the price")
    public Map<String, Object> lookup(
            @RequestParam String barcode,
            @RequestParam(required = false, defaultValue = "POS") Scope scope,
            @RequestParam(required = false) UUID locationId) {
        AccessContext context = currentUserService.requireAccessContext();
        return pricingService.lookup(context, barcode, scope, locationId);
    }

    /**
     * The price of a variant, so that the price can be checked in the browser.
     *
     * @param id the variant
     * @param scope the channel the customer buys in
     * @param locationId the location of the purchase, may be {@code null}
     * @param quantity how many pieces
     * @return the price breakdown
     */
    @GetMapping("/variants/{id}/price-preview")
    @Operation(summary = "The price of a variant, with the promotion that applies")
    public Map<String, Object> pricePreview(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "POS") Scope scope,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false, defaultValue = "1") int quantity) {
        AccessContext context = currentUserService.requireAccessContext();
        return pricingService.pricePreview(context, id, scope, locationId, quantity);
    }

    // ---- Import -------------------------------------------------------------

    /**
     * Checks a CSV file and answers with the errors. Nothing is written.
     *
     * @param file the uploaded file
     * @param openingLocationId the location the opening stock would go to, may be {@code null}
     * @return the errors and the summary
     */
    @PostMapping(path = "/import/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Check a CSV file of products and opening stock")
    public Map<String, Object> validateImport(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) UUID openingLocationId) {
        AccessContext context = currentUserService.requireAccessContext();
        return importService.validate(context, file, openingLocationId);
    }

    /**
     * Applies a CSV file. Everything is written, or nothing is.
     *
     * @param file the uploaded file
     * @param openingLocationId the location the opening stock goes to, may be {@code null}
     * @return what was written
     */
    @PostMapping(path = "/import/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Apply a CSV file of products and opening stock")
    public Map<String, Object> commitImport(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) UUID openingLocationId) {
        AccessContext context = currentUserService.requireAccessContext();
        return importService.commit(context, file, openingLocationId);
    }

    /**
     * The template file, so that the student knows the exact columns.
     *
     * @return the template as CSV text
     */
    @GetMapping("/import/template")
    @Operation(summary = "The template of the import file")
    public ResponseEntity<String> importTemplate() {
        currentUserService.requireAccessContext();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(
                        "Content-Disposition",
                        "attachment; filename=\"merqo-catalog-template.csv\"")
                .body(importService.template());
    }

    /**
     * Whether the caller may see the costs.
     *
     * @param context the caller
     * @return {@code true} when the caller holds COST_VIEW or PRICE_EDIT
     */
    private boolean maySeeCost(AccessContext context) {
        return context.can(com.merqo.access.Permission.COST_VIEW)
                || context.can(com.merqo.access.Permission.PRICE_EDIT);
    }

    /**
     * The body of a create category request.
     *
     * @param name the display name
     * @param parentId the parent category, may be {@code null}
     */
    public record CreateCategoryRequest(@NotBlank String name, UUID parentId) {}

    /**
     * The body of an update category request.
     *
     * @param name the display name
     * @param parentId the parent category, may be {@code null}
     */
    public record UpdateCategoryRequest(String name, UUID parentId) {}

    /**
     * The body of a create brand request.
     *
     * @param name the display name
     */
    public record CreateBrandRequest(@NotBlank String name) {}

    /**
     * The body of an update brand request.
     *
     * @param name the display name
     */
    public record UpdateBrandRequest(String name) {}

    /**
     * The body of a create product request.
     *
     * @param productCode the short code
     * @param name the display name
     * @param categoryId the category, may be {@code null}
     * @param brandId the brand, may be {@code null}
     * @param description the description, may be {@code null}
     * @param taxRatePercent the tax rate of this product, may be {@code null}
     */
    public record CreateProductRequest(
            @NotBlank String productCode,
            @NotBlank String name,
            UUID categoryId,
            UUID brandId,
            String description,
            @PositiveOrZero BigDecimal taxRatePercent) {}

    /**
     * The body of an update product request.
     *
     * @param name the display name
     * @param categoryId the category, may be {@code null}
     * @param brandId the brand, may be {@code null}
     * @param description the description, may be {@code null}
     * @param taxRatePercent the tax rate of this product, may be {@code null}
     */
    public record UpdateProductRequest(
            String name,
            UUID categoryId,
            UUID brandId,
            String description,
            @PositiveOrZero BigDecimal taxRatePercent) {}

    /**
     * The body of a create variant request.
     *
     * @param sku the stock keeping unit, may be empty to let the service suggest one
     * @param color the colour, may be {@code null}
     * @param size the size, may be {@code null}
     * @param otherAttribute the other attribute, may be {@code null}
     * @param sellingPrice the selling price
     * @param barcode the primary barcode, may be empty
     */
    public record CreateVariantRequest(
            String sku,
            String color,
            String size,
            String otherAttribute,
            @NotNull @PositiveOrZero BigDecimal sellingPrice,
            String barcode) {}

    /**
     * The body of an update variant request.
     *
     * @param sku the stock keeping unit
     * @param color the colour, may be {@code null}
     * @param size the size, may be {@code null}
     * @param otherAttribute the other attribute, may be {@code null}
     */
    public record UpdateVariantRequest(
            String sku, String color, String size, String otherAttribute) {}

    /**
     * The body of a change price request.
     *
     * @param sellingPrice the new selling price
     */
    public record ChangePriceRequest(@NotNull @PositiveOrZero BigDecimal sellingPrice) {}

    /**
     * The body of a change cost request.
     *
     * @param costPrice the new cost
     */
    public record ChangeCostRequest(@NotNull @PositiveOrZero BigDecimal costPrice) {}

    /**
     * The body of a set barcode request.
     *
     * @param barcode the barcode
     */
    public record SetBarcodeRequest(@NotBlank String barcode) {}
}
