package com.merqo.catalog;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.catalog.domain.BarcodeRules;
import com.merqo.catalog.domain.SkuSuggester;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.ForbiddenException;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;
import com.merqo.common.money.Money;

/**
 * The rules of the catalogue.
 *
 * <p>Responsibility: create, edit, deactivate and activate the categories, the brands, the products
 * and the variants, and keep the barcodes and the prices of the variants correct.
 *
 * <p>The rules that matter:
 *
 * <ul>
 *   <li>A variant may not be active without a primary barcode. A variant that is created without
 *       one is saved inactive, and activating it is refused with {@code BARCODE_REQUIRED}.
 *   <li>The primary barcode of an active variant may not be removed.
 *   <li>A barcode is unique in the whole catalogue, and the answer names the SKU that owns it.
 *   <li>Every price change writes a history row and an audit row.
 *   <li>The cost is sensitive. It only reaches a user who holds {@code COST_VIEW} or {@code
 *       PRICE_EDIT} at the location of the answer.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - this class holds the rules of the catalogue. The controller
 *       holds none of them.
 *   <li><b>Dependency injection</b> - the repositories, the audit log and the clock are injected.
 *   <li><b>Facade</b> - the callers ask for a variant and get a map back, and never touch the
 *       repositories.
 *   <li><b>Fail fast</b> - every rule that is broken throws, so a broken request never writes half
 *       of what it wanted.
 * </ul>
 */
@Service
public class CatalogService {

    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final VariantBarcodeRepository barcodeRepository;
    private final VariantPriceHistoryRepository priceHistoryRepository;
    private final VariantCostHistoryRepository costHistoryRepository;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param categoryRepository the categories
     * @param brandRepository the brands
     * @param productRepository the products
     * @param variantRepository the variants
     * @param barcodeRepository the barcodes
     * @param priceHistoryRepository the price history
     * @param costHistoryRepository the cost history
     * @param auditService the audit log
     * @param clock the clock
     */
    public CatalogService(
            CategoryRepository categoryRepository,
            BrandRepository brandRepository,
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            VariantBarcodeRepository barcodeRepository,
            VariantPriceHistoryRepository priceHistoryRepository,
            VariantCostHistoryRepository costHistoryRepository,
            AuditService auditService,
            Clock clock) {
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.barcodeRepository = barcodeRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.costHistoryRepository = costHistoryRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ---- Categories ---------------------------------------------------------

    /**
     * Every category, with the products that still use it.
     *
     * @param activeOnly whether only the active categories are wanted
     * @return the categories
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCategories(boolean activeOnly) {
        List<Category> categories =
                activeOnly
                        ? categoryRepository.findByActiveTrueOrderByNameAsc()
                        : categoryRepository.findAllByOrderByNameAsc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Category category : categories) {
            result.add(toCategory(category));
        }
        return result;
    }

    /**
     * Creates a category.
     *
     * @param context the caller
     * @param name the display name
     * @param parentId the parent category, or {@code null}
     * @return the new category
     */
    @Transactional
    public Category createCategory(AccessContext context, String name, UUID parentId) {
        context.requireGlobal(Permission.PRODUCT_EDIT);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ValidationException("VALIDATION_FAILED", "The category needs a name.");
        }
        if (categoryRepository.findByNameIgnoreCase(trimmed).isPresent()) {
            throw new ConflictException(
                    "DUPLICATE_VALUE", "A category with this name already exists.");
        }
        CatalogRules.requireValidCategoryDepth(categoryRepository, parentId, null);

        Instant now = Instant.now(clock);
        Category category = new Category(UUID.randomUUID(), trimmed, parentId, now);
        categoryRepository.save(category);

        auditService.record(
                new AuditEvent.Builder(AuditAction.CATEGORY_CREATED, "categories")
                        .entityId(category.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("name", category.getName())
                        .added("parentId", parentId)
                        .build());
        return category;
    }

    /**
     * Edits a category.
     *
     * @param context the caller
     * @param category the category
     * @param name the new name
     * @param parentId the new parent, or {@code null}
     * @return the changed category
     */
    @Transactional
    public Category updateCategory(
            AccessContext context, Category category, String name, UUID parentId) {
        context.requireGlobal(Permission.PRODUCT_EDIT);
        String oldName = category.getName();
        UUID oldParent = category.getParentId();

        if (name != null && !name.isBlank()) {
            String trimmed = name.trim();
            categoryRepository
                    .findByNameIgnoreCase(trimmed)
                    .filter(other -> !other.getId().equals(category.getId()))
                    .ifPresent(
                            other -> {
                                throw new ConflictException(
                                        "DUPLICATE_VALUE",
                                        "A category with this name already exists.");
                            });
            category.setName(trimmed);
        }
        if (parentId != null) {
            if (parentId.equals(category.getId())) {
                throw new ValidationException(
                        "VALIDATION_FAILED", "A category may not be its own parent.");
            }
            CatalogRules.requireValidCategoryDepth(categoryRepository, parentId, category.getId());
            category.setParentId(parentId);
        }
        category.setUpdatedAt(Instant.now(clock));
        categoryRepository.save(category);

        auditService.record(
                new AuditEvent.Builder(AuditAction.CATEGORY_UPDATED, "categories")
                        .entityId(category.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("name", oldName, category.getName())
                        .changed("parentId", oldParent, category.getParentId())
                        .build());
        return category;
    }

    /**
     * Deactivates a category. A category that still has active products may not be deactivated.
     *
     * @param context the caller
     * @param category the category
     * @return the deactivated category
     */
    @Transactional
    public Category deactivateCategory(AccessContext context, Category category) {
        context.requireGlobal(Permission.PRODUCT_EDIT);
        long activeProducts = categoryRepository.countActiveProductsInCategory(category.getId());
        if (activeProducts > 0) {
            throw new ConflictException(
                    ErrorCodes.CATEGORY_IN_USE,
                    "The category still has "
                            + activeProducts
                            + " active product(s). Deactivate them first.");
        }
        category.setActive(false);
        category.setUpdatedAt(Instant.now(clock));
        categoryRepository.save(category);

        auditService.record(
                new AuditEvent.Builder(AuditAction.CATEGORY_DEACTIVATED, "categories")
                        .entityId(category.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("isActive", true, false)
                        .build());
        return category;
    }

    /**
     * Activates a category again.
     *
     * @param context the caller
     * @param category the category
     * @return the activated category
     */
    @Transactional
    public Category activateCategory(AccessContext context, Category category) {
        context.requireGlobal(Permission.PRODUCT_EDIT);
        category.setActive(true);
        category.setUpdatedAt(Instant.now(clock));
        categoryRepository.save(category);

        auditService.record(
                new AuditEvent.Builder(AuditAction.CATEGORY_ACTIVATED, "categories")
                        .entityId(category.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("isActive", false, true)
                        .build());
        return category;
    }

    // ---- Brands -------------------------------------------------------------

    /**
     * Every brand.
     *
     * @param activeOnly whether only the active brands are wanted
     * @return the brands
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listBrands(boolean activeOnly) {
        List<Brand> brands =
                activeOnly
                        ? brandRepository.findByActiveTrueOrderByNameAsc()
                        : brandRepository.findAllByOrderByNameAsc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Brand brand : brands) {
            result.add(toBrand(brand));
        }
        return result;
    }

    /**
     * Creates a brand.
     *
     * @param context the caller
     * @param name the display name
     * @return the new brand
     */
    @Transactional
    public Brand createBrand(AccessContext context, String name) {
        context.requireGlobal(Permission.PRODUCT_EDIT);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ValidationException("VALIDATION_FAILED", "The brand needs a name.");
        }
        if (brandRepository.findByNameIgnoreCase(trimmed).isPresent()) {
            throw new ConflictException(
                    "DUPLICATE_VALUE", "A brand with this name already exists.");
        }
        Brand brand = new Brand(UUID.randomUUID(), trimmed, Instant.now(clock));
        brandRepository.save(brand);

        auditService.record(
                new AuditEvent.Builder(AuditAction.BRAND_CREATED, "brands")
                        .entityId(brand.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("name", brand.getName())
                        .build());
        return brand;
    }

    /**
     * Edits a brand.
     *
     * @param context the caller
     * @param brand the brand
     * @param name the new name
     * @return the changed brand
     */
    @Transactional
    public Brand updateBrand(AccessContext context, Brand brand, String name) {
        context.requireGlobal(Permission.PRODUCT_EDIT);
        String oldName = brand.getName();
        if (name != null && !name.isBlank()) {
            String trimmed = name.trim();
            brandRepository
                    .findByNameIgnoreCase(trimmed)
                    .filter(other -> !other.getId().equals(brand.getId()))
                    .ifPresent(
                            other -> {
                                throw new ConflictException(
                                        "DUPLICATE_VALUE",
                                        "A brand with this name already exists.");
                            });
            brand.setName(trimmed);
        }
        brand.setUpdatedAt(Instant.now(clock));
        brandRepository.save(brand);

        auditService.record(
                new AuditEvent.Builder(AuditAction.BRAND_UPDATED, "brands")
                        .entityId(brand.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("name", oldName, brand.getName())
                        .build());
        return brand;
    }

    /**
     * Deactivates a brand. A brand that still has active products may not be deactivated.
     *
     * @param context the caller
     * @param brand the brand
     * @return the deactivated brand
     */
    @Transactional
    public Brand deactivateBrand(AccessContext context, Brand brand) {
        context.requireGlobal(Permission.PRODUCT_EDIT);
        long activeProducts = brandRepository.countActiveProductsOfBrand(brand.getId());
        if (activeProducts > 0) {
            throw new ConflictException(
                    ErrorCodes.CATEGORY_IN_USE,
                    "The brand still has "
                            + activeProducts
                            + " active product(s). Deactivate them first.");
        }
        brand.setActive(false);
        brand.setUpdatedAt(Instant.now(clock));
        brandRepository.save(brand);

        auditService.record(
                new AuditEvent.Builder(AuditAction.BRAND_DEACTIVATED, "brands")
                        .entityId(brand.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("isActive", true, false)
                        .build());
        return brand;
    }

    /**
     * Activates a brand again.
     *
     * @param context the caller
     * @param brand the brand
     * @return the activated brand
     */
    @Transactional
    public Brand activateBrand(AccessContext context, Brand brand) {
        context.requireGlobal(Permission.PRODUCT_EDIT);
        brand.setActive(true);
        brand.setUpdatedAt(Instant.now(clock));
        brandRepository.save(brand);

        auditService.record(
                new AuditEvent.Builder(AuditAction.BRAND_ACTIVATED, "brands")
                        .entityId(brand.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("isActive", false, true)
                        .build());
        return brand;
    }

    // ---- Products -----------------------------------------------------------

    /**
     * Creates a product.
     *
     * @param context the caller
     * @param productCode the code of the product
     * @param name the display name
     * @param categoryId the category, or {@code null}
     * @param brandId the brand, or {@code null}
     * @param description the description, or {@code null}
     * @param taxRatePercent the tax rate of the product, or {@code null} for the global rate
     * @return the new product
     */
    @Transactional
    public Product createProduct(
            AccessContext context,
            String productCode,
            String name,
            UUID categoryId,
            UUID brandId,
            String description,
            BigDecimal taxRatePercent) {
        context.requireGlobal(Permission.PRODUCT_CREATE);
        String trimmedCode = productCode == null ? "" : productCode.trim();
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedCode.isEmpty()) {
            throw new ValidationException("VALIDATION_FAILED", "The product needs a code.");
        }
        if (trimmedName.isEmpty()) {
            throw new ValidationException("VALIDATION_FAILED", "The product needs a name.");
        }
        if (productRepository.findByProductCodeIgnoreCase(trimmedCode).isPresent()) {
            throw new ConflictException(
                    "DUPLICATE_VALUE", "A product with this code already exists.");
        }
        if (categoryId != null && !categoryRepository.existsById(categoryId)) {
            throw new NotFoundException("The requested record was not found.");
        }
        if (brandId != null && !brandRepository.existsById(brandId)) {
            throw new NotFoundException("The requested record was not found.");
        }

        Instant now = Instant.now(clock);
        Product product =
                new Product(UUID.randomUUID(), trimmedCode, trimmedName, categoryId, brandId, now);
        product.setDescription(description);
        product.setTaxRatePercent(taxRatePercent);
        productRepository.save(product);

        auditService.record(
                new AuditEvent.Builder(AuditAction.PRODUCT_CREATED, "products")
                        .entityId(product.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("productCode", product.getProductCode())
                        .added("name", product.getName())
                        .added("categoryId", categoryId)
                        .added("brandId", brandId)
                        .build());
        return product;
    }

    /**
     * Edits a product.
     *
     * @param context the caller
     * @param product the product
     * @param name the new name
     * @param categoryId the new category, or {@code null} to leave it
     * @param brandId the new brand, or {@code null} to leave it
     * @param description the new description, or {@code null} to leave it
     * @param taxRatePercent the new tax rate, or {@code null} to leave it
     * @return the changed product
     */
    @Transactional
    public Product updateProduct(
            AccessContext context,
            Product product,
            String name,
            UUID categoryId,
            UUID brandId,
            String description,
            BigDecimal taxRatePercent) {
        context.requireGlobal(Permission.PRODUCT_EDIT);
        String oldName = product.getName();
        UUID oldCategory = product.getCategoryId();
        UUID oldBrand = product.getBrandId();
        BigDecimal oldTaxRate = product.getTaxRatePercent();

        if (name != null && !name.isBlank()) {
            product.setName(name.trim());
        }
        if (categoryId != null) {
            if (!categoryRepository.existsById(categoryId)) {
                throw new NotFoundException("The requested record was not found.");
            }
            product.setCategoryId(categoryId);
        }
        if (brandId != null) {
            if (!brandRepository.existsById(brandId)) {
                throw new NotFoundException("The requested record was not found.");
            }
            product.setBrandId(brandId);
        }
        if (description != null) {
            product.setDescription(description);
        }
        if (taxRatePercent != null) {
            product.setTaxRatePercent(taxRatePercent);
        }
        product.setUpdatedAt(Instant.now(clock));
        productRepository.save(product);

        auditService.record(
                new AuditEvent.Builder(AuditAction.PRODUCT_UPDATED, "products")
                        .entityId(product.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("name", oldName, product.getName())
                        .changed("categoryId", oldCategory, product.getCategoryId())
                        .changed("brandId", oldBrand, product.getBrandId())
                        .changed("taxRatePercent", oldTaxRate, product.getTaxRatePercent())
                        .build());
        return product;
    }

    /**
     * Deactivates a product.
     *
     * @param context the caller
     * @param product the product
     * @return the deactivated product
     */
    @Transactional
    public Product deactivateProduct(AccessContext context, Product product) {
        context.requireGlobal(Permission.PRODUCT_EDIT);
        product.setActive(false);
        product.setUpdatedAt(Instant.now(clock));
        productRepository.save(product);

        auditService.record(
                new AuditEvent.Builder(AuditAction.PRODUCT_DEACTIVATED, "products")
                        .entityId(product.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("isActive", true, false)
                        .build());
        return product;
    }

    /**
     * Activates a product again.
     *
     * @param context the caller
     * @param product the product
     * @return the activated product
     */
    @Transactional
    public Product activateProduct(AccessContext context, Product product) {
        context.requireGlobal(Permission.PRODUCT_EDIT);
        product.setActive(true);
        product.setUpdatedAt(Instant.now(clock));
        productRepository.save(product);

        auditService.record(
                new AuditEvent.Builder(AuditAction.PRODUCT_ACTIVATED, "products")
                        .entityId(product.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("isActive", false, true)
                        .build());
        return product;
    }

    // ---- Variants -----------------------------------------------------------

    /**
     * Creates a variant.
     *
     * <p>A variant without a primary barcode is saved inactive, because a variant that cannot be
     * scanned may not be sold.
     *
     * @param context the caller
     * @param product the product
     * @param sku the stock keeping unit, may be empty to let the service suggest one
     * @param color the colour, may be {@code null}
     * @param size the size, may be {@code null}
     * @param otherAttribute the other attribute, may be {@code null}
     * @param sellingPrice the selling price
     * @param barcode the primary barcode, may be empty
     * @return the new variant
     */
    @Transactional
    public ProductVariant createVariant(
            AccessContext context,
            Product product,
            String sku,
            String color,
            String size,
            String otherAttribute,
            BigDecimal sellingPrice,
            String barcode) {
        context.requireGlobal(Permission.PRODUCT_CREATE);
        Instant now = Instant.now(clock);

        String effectiveSku =
                sku == null || sku.isBlank()
                        ? SkuSuggester.suggest(
                                product.getProductCode(), color, size, otherAttribute)
                        : sku.trim();
        CatalogRules.requireFreeSku(variantRepository, effectiveSku, null);

        if (sellingPrice == null || sellingPrice.signum() < 0) {
            throw new ValidationException(
                    "VALIDATION_FAILED", "The selling price must not be negative.");
        }
        String trimmedBarcode = BarcodeRules.normalize(barcode);
        boolean hasBarcode = !trimmedBarcode.isEmpty();
        if (hasBarcode) {
            CatalogRules.requireValidBarcode(trimmedBarcode);
            requireFreeBarcode(trimmedBarcode);
        }

        ProductVariant variant =
                new ProductVariant(
                        UUID.randomUUID(),
                        product.getId(),
                        effectiveSku,
                        color,
                        size,
                        otherAttribute,
                        sellingPrice.setScale(2, java.math.RoundingMode.HALF_UP),
                        now);
        variant.setActive(hasBarcode);
        variantRepository.save(variant);

        if (hasBarcode) {
            barcodeRepository.save(
                    new VariantBarcode(
                            UUID.randomUUID(),
                            trimmedBarcode,
                            variant.getId(),
                            VariantBarcode.Kind.PRIMARY,
                            now,
                            context.getUserId()));
        }

        priceHistoryRepository.save(
                new VariantPriceHistory(
                        UUID.randomUUID(),
                        variant.getId(),
                        variant.getSellingPrice(),
                        now,
                        VariantPriceHistory.SourceType.MANUAL,
                        null,
                        context.getUserId(),
                        now));

        auditService.record(
                new AuditEvent.Builder(AuditAction.VARIANT_CREATED, "product_variants")
                        .entityId(variant.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("sku", variant.getSku())
                        .added("sellingPrice", variant.getSellingPrice())
                        .added("isActive", variant.isActive())
                        .added("barcode", hasBarcode ? trimmedBarcode : null)
                        .build());
        return variant;
    }

    /**
     * Edits a variant.
     *
     * @param context the caller
     * @param variant the variant
     * @param sku the new SKU
     * @param color the new colour
     * @param size the new size
     * @param otherAttribute the new other attribute
     * @return the changed variant
     */
    @Transactional
    public ProductVariant updateVariant(
            AccessContext context,
            ProductVariant variant,
            String sku,
            String color,
            String size,
            String otherAttribute) {
        context.requireGlobal(Permission.PRODUCT_EDIT);
        String oldSku = variant.getSku();
        String oldColor = variant.getColor();
        if (sku != null && !sku.isBlank()) {
            String trimmed = sku.trim();
            CatalogRules.requireFreeSku(variantRepository, trimmed, variant.getId());
            variant.setSku(trimmed);
        }
        if (color != null) {
            variant.setColor(color);
        }
        if (size != null) {
            variant.setSize(size);
        }
        if (otherAttribute != null) {
            variant.setOtherAttribute(otherAttribute);
        }
        variant.setUpdatedAt(Instant.now(clock));
        variantRepository.save(variant);

        auditService.record(
                new AuditEvent.Builder(AuditAction.VARIANT_UPDATED, "product_variants")
                        .entityId(variant.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("sku", oldSku, variant.getSku())
                        .changed("color", oldColor, variant.getColor())
                        .build());
        return variant;
    }

    /**
     * Deactivates a variant.
     *
     * <p>The guards are asked first. A variant that is on an open transfer may not be deactivated,
     * and the guard of the transfer module decides that.
     *
     * @param context the caller
     * @param variant the variant
     * @param guards the guards of the other modules
     * @return the deactivated variant
     */
    @Transactional
    public ProductVariant deactivateVariant(
            AccessContext context, ProductVariant variant, List<VariantDeactivationGuard> guards) {
        context.requireGlobal(Permission.PRODUCT_EDIT);
        for (VariantDeactivationGuard guard : guards) {
            VariantDeactivationGuard.GuardResult result = guard.check(variant);
            if (!result.allowed()) {
                throw new ConflictException(ErrorCodes.VARIANT_IN_USE, result.reason());
            }
        }
        variant.setActive(false);
        variant.setUpdatedAt(Instant.now(clock));
        variantRepository.save(variant);

        auditService.record(
                new AuditEvent.Builder(AuditAction.VARIANT_DEACTIVATED, "product_variants")
                        .entityId(variant.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("isActive", true, false)
                        .build());
        return variant;
    }

    /**
     * Activates a variant again. A variant without a primary barcode may not be activated.
     *
     * @param context the caller
     * @param variant the variant
     * @return the activated variant
     */
    @Transactional
    public ProductVariant activateVariant(AccessContext context, ProductVariant variant) {
        context.requireGlobal(Permission.PRODUCT_EDIT);
        Optional<VariantBarcode> primary =
                barcodeRepository.findFirstByVariantIdAndKind(
                        variant.getId(), VariantBarcode.Kind.PRIMARY);
        if (primary.isEmpty()) {
            throw new ConflictException(
                    ErrorCodes.BARCODE_REQUIRED,
                    "The variant needs a primary barcode before it may be activated.");
        }
        variant.setActive(true);
        variant.setUpdatedAt(Instant.now(clock));
        variantRepository.save(variant);

        auditService.record(
                new AuditEvent.Builder(AuditAction.VARIANT_ACTIVATED, "product_variants")
                        .entityId(variant.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("isActive", false, true)
                        .build());
        return variant;
    }

    // ---- Barcodes -----------------------------------------------------------

    /**
     * Sets the primary barcode of a variant.
     *
     * @param context the caller
     * @param variant the variant
     * @param barcode the new primary barcode
     * @return the new barcode row
     */
    @Transactional
    public VariantBarcode setPrimaryBarcode(
            AccessContext context, ProductVariant variant, String barcode) {
        context.requireGlobal(Permission.BARCODE_EDIT);
        String trimmed = BarcodeRules.normalize(barcode);
        CatalogRules.requireValidBarcode(trimmed);

        Optional<VariantBarcode> existing =
                barcodeRepository.findFirstByVariantIdAndKind(
                        variant.getId(), VariantBarcode.Kind.PRIMARY);
        VariantBarcode row = existing.orElse(null);
        if (row == null) {
            requireFreeBarcode(trimmed);
            row =
                    new VariantBarcode(
                            UUID.randomUUID(),
                            trimmed,
                            variant.getId(),
                            VariantBarcode.Kind.PRIMARY,
                            Instant.now(clock),
                            context.getUserId());
            barcodeRepository.save(row);
            auditService.record(
                    new AuditEvent.Builder(AuditAction.BARCODE_ADDED, "variant_barcodes")
                            .entityId(variant.getId())
                            .actor(context.getUserId(), context.getUsername())
                            .added("barcode", trimmed)
                            .added("kind", VariantBarcode.Kind.PRIMARY.name())
                            .build());
            return row;
        }

        String oldBarcode = row.getBarcode();
        if (!oldBarcode.equals(trimmed)) {
            requireFreeBarcode(trimmed);
            row.setBarcodeForUpdate(trimmed);
            barcodeRepository.save(row);
            auditService.record(
                    new AuditEvent.Builder(AuditAction.BARCODE_CHANGED, "variant_barcodes")
                            .entityId(variant.getId())
                            .actor(context.getUserId(), context.getUsername())
                            .changed("barcode", oldBarcode, trimmed)
                            .changed(
                                    "kind",
                                    VariantBarcode.Kind.PRIMARY.name(),
                                    VariantBarcode.Kind.PRIMARY.name())
                            .build());
        }
        return row;
    }

    /**
     * Adds an alternate barcode to a variant.
     *
     * @param context the caller
     * @param variant the variant
     * @param barcode the alternate barcode
     * @return the new barcode row
     */
    @Transactional
    public VariantBarcode addAlternateBarcode(
            AccessContext context, ProductVariant variant, String barcode) {
        context.requireGlobal(Permission.BARCODE_EDIT);
        String trimmed = BarcodeRules.normalize(barcode);
        CatalogRules.requireValidBarcode(trimmed);
        requireFreeBarcode(trimmed);

        VariantBarcode row =
                new VariantBarcode(
                        UUID.randomUUID(),
                        trimmed,
                        variant.getId(),
                        VariantBarcode.Kind.ALTERNATE,
                        Instant.now(clock),
                        context.getUserId());
        barcodeRepository.save(row);

        auditService.record(
                new AuditEvent.Builder(AuditAction.BARCODE_ADDED, "variant_barcodes")
                        .entityId(variant.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("barcode", trimmed)
                        .added("kind", VariantBarcode.Kind.ALTERNATE.name())
                        .build());
        return row;
    }

    /**
     * Removes an alternate barcode of a variant.
     *
     * <p>The primary barcode of an active variant may not be removed.
     *
     * @param context the caller
     * @param variant the variant
     * @param barcodeId the barcode row
     * @return the removed barcode row
     */
    @Transactional
    public VariantBarcode removeBarcode(
            AccessContext context, ProductVariant variant, VariantBarcode barcode) {
        context.requireGlobal(Permission.BARCODE_EDIT);
        if (barcode.getKind() == VariantBarcode.Kind.PRIMARY) {
            if (variant.isActive()) {
                throw new ConflictException(
                        ErrorCodes.VARIANT_IN_USE,
                        "The primary barcode of an active variant may not be removed.");
            }
        }
        String oldBarcode = barcode.getBarcode();
        barcodeRepository.delete(barcode);

        auditService.record(
                new AuditEvent.Builder(AuditAction.BARCODE_REMOVED, "variant_barcodes")
                        .entityId(variant.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("barcode", oldBarcode, null)
                        .changed("kind", barcode.getKind().name(), null)
                        .build());
        return barcode;
    }

    // ---- Prices and costs ---------------------------------------------------

    /**
     * Changes the selling price of a variant.
     *
     * <p>The price history and the audit row are written in the same transaction, so the history
     * can never disagree with the current price.
     *
     * @param context the caller
     * @param variant the variant
     * @param newPrice the new price
     * @param sourceType where the price came from
     * @param sourceId the id of the source, may be {@code null}
     * @return the changed variant
     */
    @Transactional
    public ProductVariant changePrice(
            AccessContext context,
            ProductVariant variant,
            BigDecimal newPrice,
            VariantPriceHistory.SourceType sourceType,
            UUID sourceId) {
        context.requireGlobal(Permission.PRICE_EDIT);
        if (newPrice == null || newPrice.signum() < 0) {
            throw new ValidationException(
                    "VALIDATION_FAILED", "The selling price must not be negative.");
        }
        BigDecimal rounded = newPrice.setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal oldPrice = variant.getSellingPrice();
        Instant now = Instant.now(clock);

        variant.setSellingPrice(rounded);
        variant.setUpdatedAt(now);
        variantRepository.save(variant);

        priceHistoryRepository.save(
                new VariantPriceHistory(
                        UUID.randomUUID(),
                        variant.getId(),
                        rounded,
                        now,
                        sourceType,
                        sourceId,
                        context.getUserId(),
                        now));

        auditService.record(
                new AuditEvent.Builder(AuditAction.PRICE_CHANGED, "product_variants")
                        .entityId(variant.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("sellingPrice", oldPrice, rounded)
                        .build());
        return variant;
    }

    /**
     * Changes the cost of a variant.
     *
     * @param context the caller
     * @param variant the variant
     * @param newCost the new cost
     * @param sourceType where the cost came from
     * @param sourceId the id of the source, may be {@code null}
     * @return the changed variant
     */
    @Transactional
    public ProductVariant changeCost(
            AccessContext context,
            ProductVariant variant,
            BigDecimal newCost,
            VariantCostHistory.SourceType sourceType,
            UUID sourceId) {
        context.requireGlobal(Permission.PRICE_EDIT);
        if (newCost == null || newCost.signum() < 0) {
            throw new ValidationException("VALIDATION_FAILED", "The cost must not be negative.");
        }
        BigDecimal rounded = newCost.setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal oldCost =
                costHistoryRepository
                        .findLatest(variant.getId())
                        .map(VariantCostHistory::getValue)
                        .orElse(null);
        Instant now = Instant.now(clock);

        costHistoryRepository.save(
                new VariantCostHistory(
                        UUID.randomUUID(),
                        variant.getId(),
                        rounded,
                        now,
                        sourceType,
                        sourceId,
                        context.getUserId(),
                        now));

        auditService.record(
                new AuditEvent.Builder(AuditAction.COST_CHANGED, "product_variants")
                        .entityId(variant.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("cost", oldCost, rounded)
                        .build());
        return variant;
    }

    // ---- Reads --------------------------------------------------------------

    /**
     * One category.
     *
     * @param id the id of the category
     * @return the category
     */
    @Transactional(readOnly = true)
    public Category getCategory(UUID id) {
        return categoryRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("The requested record was not found."));
    }

    /**
     * One brand.
     *
     * @param id the id of the brand
     * @return the brand
     */
    @Transactional(readOnly = true)
    public Brand getBrand(UUID id) {
        return brandRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("The requested record was not found."));
    }

    /**
     * One product.
     *
     * @param id the id of the product
     * @return the product
     */
    @Transactional(readOnly = true)
    public Product getProduct(UUID id) {
        return productRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("The requested record was not found."));
    }

    /**
     * One variant.
     *
     * @param id the id of the variant
     * @return the variant
     */
    @Transactional(readOnly = true)
    public ProductVariant getVariant(UUID id) {
        return variantRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("The requested record was not found."));
    }

    /**
     * The current cost of a variant.
     *
     * @param variantId the variant
     * @return the cost, or {@code null} when the variant never had one
     */
    @Transactional(readOnly = true)
    public BigDecimal currentCost(UUID variantId) {
        return costHistoryRepository
                .findLatest(variantId)
                .map(VariantCostHistory::getValue)
                .orElse(null);
    }

    /**
     * A variant by its SKU, ignoring the case.
     *
     * <p>The import matches its rows by SKU, so it needs to find a variant without knowing its id.
     *
     * @param sku the SKU
     * @return the variant, if there is one
     */
    @Transactional(readOnly = true)
    public Optional<ProductVariant> findVariantBySku(String sku) {
        if (sku == null || sku.isBlank()) {
            return Optional.empty();
        }
        return variantRepository.findBySkuIgnoreCase(sku.trim());
    }

    /**
     * A product by its code, ignoring the case.
     *
     * @param productCode the code
     * @return the product, if there is one
     */
    @Transactional(readOnly = true)
    public Optional<Product> findProductByCode(String productCode) {
        if (productCode == null || productCode.isBlank()) {
            return Optional.empty();
        }
        return productRepository.findByProductCodeIgnoreCase(productCode.trim());
    }

    /**
     * The category with this name, or a new one.
     *
     * <p>The import creates the categories and the brands that the file names and that are not in
     * the catalogue yet.
     *
     * @param name the name of the category
     * @return the id of the category
     */
    @Transactional
    public UUID findOrCreateCategory(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Optional<Category> existing = categoryRepository.findByNameIgnoreCase(trimmed);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        Category category = new Category(UUID.randomUUID(), trimmed, null, Instant.now(clock));
        categoryRepository.save(category);
        return category.getId();
    }

    /**
     * The brand with this name, or a new one.
     *
     * @param name the name of the brand
     * @return the id of the brand
     */
    @Transactional
    public UUID findOrCreateBrand(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Optional<Brand> existing = brandRepository.findByNameIgnoreCase(trimmed);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        Brand brand = new Brand(UUID.randomUUID(), trimmed, Instant.now(clock));
        brandRepository.save(brand);
        return brand.getId();
    }

    /**
     * Every variant of the catalogue.
     *
     * @return the variants
     */
    @Transactional(readOnly = true)
    public List<ProductVariant> allVariants() {
        return variantRepository.findAll();
    }

    /**
     * Every barcode of the catalogue.
     *
     * @return the codes
     */
    @Transactional(readOnly = true)
    public List<String> allBarcodes() {
        List<String> codes = new ArrayList<>();
        for (VariantBarcode barcode : barcodeRepository.findAll()) {
            codes.add(barcode.getBarcode());
        }
        return codes;
    }

    /**
     * The barcodes of a variant.
     *
     * @param variantId the variant
     * @return the barcodes
     */
    @Transactional(readOnly = true)
    public List<VariantBarcode> barcodesOf(UUID variantId) {
        return barcodeRepository.findByVariantIdOrderByKindAscBarcodeAsc(variantId);
    }

    /**
     * The variants of a product.
     *
     * @param productId the product
     * @return the variants
     */
    @Transactional(readOnly = true)
    public List<ProductVariant> variantsOf(UUID productId) {
        return variantRepository.findByProductIdOrderBySkuAsc(productId);
    }

    /**
     * The variants the caller may see, searched by name, SKU or barcode.
     *
     * @param q the search text, may be {@code null}
     * @param activeOnly whether only the active variants are wanted
     * @param pageable the page request
     * @return the page of variants
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ProductVariant> searchVariants(
            String q, boolean activeOnly, org.springframework.data.domain.Pageable pageable) {
        String search = q == null || q.isBlank() ? null : q.trim();
        return variantRepository.search(search, activeOnly, pageable);
    }

    /**
     * The products the caller may see.
     *
     * @param q the search text, may be {@code null}
     * @param categoryId the category, may be {@code null}
     * @param brandId the brand, may be {@code null}
     * @param activeOnly whether only the active products are wanted
     * @param pageable the page request
     * @return the page of products
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Product> searchProducts(
            String q,
            UUID categoryId,
            UUID brandId,
            boolean activeOnly,
            org.springframework.data.domain.Pageable pageable) {
        String search = q == null || q.isBlank() ? null : q.trim();
        return productRepository.search(search, categoryId, brandId, activeOnly, pageable);
    }

    /**
     * The variant a barcode belongs to.
     *
     * @param barcode the code
     * @return the variant and its barcodes
     */
    @Transactional(readOnly = true)
    public ProductVariant findByBarcode(String barcode) {
        VariantBarcode row =
                barcodeRepository
                        .findByBarcode(BarcodeRules.normalize(barcode))
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCodes.BARCODE_NOT_FOUND,
                                                "No variant carries the barcode "
                                                        + BarcodeRules.normalize(barcode)
                                                        + "."));
        return variantRepository
                .findById(row.getVariantId())
                .orElseThrow(() -> new NotFoundException("The requested record was not found."));
    }

    /**
     * The variants a promotion selector expands to.
     *
     * @param variantIds the chosen variants, may be empty
     * @param productIds the chosen products, may be empty
     * @param categoryId the chosen category, may be {@code null}
     * @param brandId the chosen brand, may be {@code null}
     * @return the variants
     */
    @Transactional(readOnly = true)
    public Set<UUID> expandSelector(
            List<UUID> variantIds, List<UUID> productIds, UUID categoryId, UUID brandId) {
        Set<UUID> variants = new LinkedHashSet<>();
        if (variantIds != null && !variantIds.isEmpty()) {
            variants.addAll(variantIds);
        }
        if (productIds != null && !productIds.isEmpty()) {
            for (Product product : productRepository.findAllById(productIds)) {
                variants.addAll(
                        variantRepository.findByProductIdOrderBySkuAsc(product.getId()).stream()
                                .map(ProductVariant::getId)
                                .toList());
            }
        }
        if (categoryId != null) {
            variants.addAll(
                    variantRepository.findActiveByCategoryId(categoryId).stream()
                            .map(ProductVariant::getId)
                            .toList());
        }
        if (brandId != null) {
            variants.addAll(
                    variantRepository.findActiveByBrandId(brandId).stream()
                            .map(ProductVariant::getId)
                            .toList());
        }
        return variants;
    }

    /**
     * Refuses a barcode that is already used, and names the SKU that owns it.
     *
     * @param barcode the code
     */
    private void requireFreeBarcode(String barcode) {
        VariantBarcode existing = barcodeRepository.findByBarcode(barcode).orElse(null);
        if (existing == null) {
            return;
        }
        String ownerSku =
                variantRepository
                        .findById(existing.getVariantId())
                        .map(ProductVariant::getSku)
                        .orElse("another variant");
        throw new ConflictException(
                ErrorCodes.BARCODE_IN_USE,
                "The barcode " + barcode + " is already used by the SKU " + ownerSku + ".");
    }

    /**
     * The map of one category.
     *
     * @param category the category
     * @return the map
     */
    public Map<String, Object> toCategory(Category category) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", category.getId());
        body.put("name", category.getName());
        body.put("parentId", category.getParentId());
        body.put("isActive", category.isActive());
        return body;
    }

    /**
     * The map of one brand.
     *
     * @param brand the brand
     * @return the map
     */
    public Map<String, Object> toBrand(Brand brand) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", brand.getId());
        body.put("name", brand.getName());
        body.put("isActive", brand.isActive());
        return body;
    }

    /**
     * The map of one variant.
     *
     * @param variant the variant
     * @param includeCost whether the caller may see the cost
     * @return the map
     */
    public Map<String, Object> toVariant(ProductVariant variant, boolean includeCost) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", variant.getId());
        body.put("productId", variant.getProductId());
        body.put("sku", variant.getSku());
        body.put("color", variant.getColor());
        body.put("size", variant.getSize());
        body.put("otherAttribute", variant.getOtherAttribute());
        body.put("sellingPrice", variant.getSellingPrice());
        body.put("isActive", variant.isActive());
        if (includeCost) {
            body.put("costPrice", currentCost(variant.getId()));
        }
        return body;
    }

    /**
     * The map of one product.
     *
     * @param product the product
     * @param includeCost whether the caller may see the costs
     * @return the map
     */
    public Map<String, Object> toProduct(Product product, boolean includeCost) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", product.getId());
        body.put("productCode", product.getProductCode());
        body.put("name", product.getName());
        body.put("categoryId", product.getCategoryId());
        body.put("brandId", product.getBrandId());
        body.put("description", product.getDescription());
        body.put("taxRatePercent", product.getTaxRatePercent());
        body.put("isActive", product.isActive());
        List<Map<String, Object>> variants = new ArrayList<>();
        for (ProductVariant variant :
                variantRepository.findByProductIdOrderBySkuAsc(product.getId())) {
            variants.add(toVariant(variant, includeCost));
        }
        body.put("variants", variants);
        return body;
    }

    /**
     * The price of a variant as a {@link Money} value.
     *
     * @param variant the variant
     * @return the price
     */
    public Money priceOf(ProductVariant variant) {
        return Money.of(variant.getSellingPrice());
    }

    /**
     * Whether the caller may see the purchase cost.
     *
     * <p>The cost is a sensitive number. Only a user who holds {@code COST_VIEW} or {@code
     * PRICE_EDIT} may see it, and for everybody else the field is absent from the answer instead of
     * being zero.
     *
     * @param context the caller
     * @return {@code true} when the caller may see the costs
     */
    public boolean maySeeCost(AccessContext context) {
        return context.can(Permission.COST_VIEW) || context.can(Permission.PRICE_EDIT);
    }

    /**
     * Refuses a caller that may not see the cost.
     *
     * @param context the caller
     * @param locationId the location the answer is about
     */
    public void requireCostVisibility(AccessContext context, UUID locationId) {
        if (!context.can(Permission.COST_VIEW, locationId)
                && !context.can(Permission.PRICE_EDIT, locationId)) {
            throw new ForbiddenException(
                    ErrorCodes.FORBIDDEN, "You do not have the permission COST_VIEW.");
        }
    }
}
