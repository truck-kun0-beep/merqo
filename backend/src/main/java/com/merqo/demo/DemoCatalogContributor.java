package com.merqo.demo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.catalog.Brand;
import com.merqo.catalog.BrandRepository;
import com.merqo.catalog.Category;
import com.merqo.catalog.CategoryRepository;
import com.merqo.catalog.Product;
import com.merqo.catalog.ProductRepository;
import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.ProductVariantRepository;
import com.merqo.catalog.VariantBarcode;
import com.merqo.catalog.VariantBarcodeRepository;
import com.merqo.catalog.VariantCostHistory;
import com.merqo.catalog.VariantCostHistoryRepository;
import com.merqo.catalog.VariantPriceHistory;
import com.merqo.catalog.VariantPriceHistoryRepository;

/**
 * Seeds the demo catalogue.
 *
 * <p>Responsibility: write the demo categories, brands, products and variants with their barcodes,
 * prices and costs, once. The seed is idempotent: every row is looked up by its stable demo key, so
 * running the seeder twice gives the same result as running it once.
 *
 * <p>The names are plain product words. The business name, the prices and the tax rate come from
 * the settings, never from here.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Strategy</b> - the contributor is one implementation of {@link DemoDataContributor}.
 *   <li><b>Idempotency</b> - the demo key is the stable key of every row.
 *   <li><b>Open/closed principle</b> - a later prompt adds its own contributor and touches nothing
 *       else.
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "merqo.demo-mode", havingValue = "true")
public class DemoCatalogContributor implements DemoDataContributor {

    /** The demo categories. The parent is the code of the parent category. */
    private static final List<DemoCategory> CATEGORIES =
            List.of(
                    new DemoCategory("cat-apparel", "Apparel", null),
                    new DemoCategory("cat-apparel-top", "Tops", "cat-apparel"),
                    new DemoCategory("cat-apparel-bottom", "Bottoms", "cat-apparel"),
                    new DemoCategory("cat-shoes", "Shoes", null),
                    new DemoCategory("cat-jewellery", "Jewellery", null));

    /** The demo brands. */
    private static final List<String> BRANDS = List.of("Northline", "Riverbend", "Aster");

    /** The demo products and their variants. */
    private static final List<DemoProduct> PRODUCTS =
            List.of(
                    new DemoProduct(
                            "prod-tee",
                            "TE-100",
                            "Everyday Tee",
                            "cat-apparel-top",
                            "Northline",
                            List.of(
                                    new DemoVariant(
                                            "var-tee-black",
                                            "TE-100-BLK",
                                            "Black",
                                            null,
                                            "750",
                                            "400"),
                                    new DemoVariant(
                                            "var-tee-white",
                                            "TE-100-WHT",
                                            "White",
                                            null,
                                            "750",
                                            "400"),
                                    new DemoVariant(
                                            "var-tee-blue",
                                            "TE-100-BLU",
                                            "Blue",
                                            null,
                                            "800",
                                            "430"))),
                    new DemoProduct(
                            "prod-hoodie",
                            "HD-200",
                            "Zip Hoodie",
                            "cat-apparel-top",
                            "Northline",
                            List.of(
                                    new DemoVariant(
                                            "var-hoodie-m",
                                            "HD-200-M",
                                            "Grey",
                                            "M",
                                            "2200",
                                            "1200"),
                                    new DemoVariant(
                                            "var-hoodie-l",
                                            "HD-200-L",
                                            "Grey",
                                            "L",
                                            "2200",
                                            "1200"),
                                    new DemoVariant(
                                            "var-hoodie-xl",
                                            "HD-200-XL",
                                            "Grey",
                                            "XL",
                                            "2250",
                                            "1230"))),
                    new DemoProduct(
                            "prod-jeans",
                            "JN-300",
                            "Straight Jeans",
                            "cat-apparel-bottom",
                            "Riverbend",
                            List.of(
                                    new DemoVariant(
                                            "var-jeans-30",
                                            "JN-300-30",
                                            "Indigo",
                                            "30",
                                            "2600",
                                            "1500"),
                                    new DemoVariant(
                                            "var-jeans-32",
                                            "JN-300-32",
                                            "Indigo",
                                            "32",
                                            "2600",
                                            "1500"),
                                    new DemoVariant(
                                            "var-jeans-34",
                                            "JN-300-34",
                                            "Indigo",
                                            "34",
                                            "2650",
                                            "1530"))),
                    new DemoProduct(
                            "prod-sneaker",
                            "SN-400",
                            "Canvas Sneaker",
                            "cat-shoes",
                            "Riverbend",
                            List.of(
                                    new DemoVariant(
                                            "var-sneaker-40",
                                            "SN-400-40",
                                            "White",
                                            "40",
                                            "3100",
                                            "1900"),
                                    new DemoVariant(
                                            "var-sneaker-42",
                                            "SN-400-42",
                                            "White",
                                            "42",
                                            "3100",
                                            "1900"),
                                    new DemoVariant(
                                            "var-sneaker-43",
                                            "SN-400-43",
                                            "Black",
                                            "43",
                                            "3200",
                                            "1980"))),
                    new DemoProduct(
                            "prod-necklace",
                            "JW-500",
                            "Pendant Necklace",
                            "cat-jewellery",
                            "Aster",
                            List.of(
                                    new DemoVariant(
                                            "var-necklace-g",
                                            "JW-500-G",
                                            "Gold",
                                            null,
                                            "5400",
                                            "3100"),
                                    new DemoVariant(
                                            "var-necklace-s",
                                            "JW-500-S",
                                            "Silver",
                                            null,
                                            "4800",
                                            "2750"))));

    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final VariantBarcodeRepository barcodeRepository;
    private final VariantPriceHistoryRepository priceHistoryRepository;
    private final VariantCostHistoryRepository costHistoryRepository;
    private final Clock clock;

    /**
     * Creates the contributor.
     *
     * @param categoryRepository the categories
     * @param brandRepository the brands
     * @param productRepository the products
     * @param variantRepository the variants
     * @param barcodeRepository the barcodes
     * @param priceHistoryRepository the price history
     * @param costHistoryRepository the cost history
     * @param clock the clock
     */
    public DemoCatalogContributor(
            CategoryRepository categoryRepository,
            BrandRepository brandRepository,
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            VariantBarcodeRepository barcodeRepository,
            VariantPriceHistoryRepository priceHistoryRepository,
            VariantCostHistoryRepository costHistoryRepository,
            Clock clock) {
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.barcodeRepository = barcodeRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.costHistoryRepository = costHistoryRepository;
        this.clock = clock;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    @Transactional
    public void seed() {
        Instant now = Instant.now(clock);

        for (DemoCategory demo : CATEGORIES) {
            if (categoryRepository.findByDemoKey(demo.demoKey()).isPresent()) {
                continue;
            }
            UUID parentId = null;
            if (demo.parentDemoKey() != null) {
                parentId =
                        categoryRepository
                                .findByDemoKey(demo.parentDemoKey())
                                .map(Category::getId)
                                .orElse(null);
            }
            Category category = new Category(UUID.randomUUID(), demo.name(), parentId, now);
            category.setDemoKey(demo.demoKey());
            categoryRepository.save(category);
        }

        for (String name : BRANDS) {
            if (brandRepository.findByNameIgnoreCase(name).isPresent()) {
                continue;
            }
            Brand brand = new Brand(UUID.randomUUID(), name, now);
            brand.setDemoKey("brand-" + name.toLowerCase(java.util.Locale.ROOT));
            brandRepository.save(brand);
        }

        for (DemoProduct demo : PRODUCTS) {
            seedProduct(demo, now);
        }
    }

    private void seedProduct(DemoProduct demo, Instant now) {
        Optional<Product> existing = productRepository.findByDemoKey(demo.demoKey());
        Product product;
        if (existing.isPresent()) {
            product = existing.get();
        } else {
            UUID categoryId =
                    categoryRepository
                            .findByDemoKey(demo.categoryDemoKey())
                            .map(Category::getId)
                            .orElse(null);
            UUID brandId =
                    brandRepository
                            .findByNameIgnoreCase(demo.brandName())
                            .map(Brand::getId)
                            .orElse(null);
            product =
                    new Product(
                            UUID.randomUUID(),
                            demo.productCode(),
                            demo.name(),
                            categoryId,
                            brandId,
                            now);
            product.setDemoKey(demo.demoKey());
            productRepository.save(product);
        }

        for (DemoVariant demoVariant : demo.variants()) {
            seedVariant(product, demoVariant, now);
        }
    }

    private void seedVariant(Product product, DemoVariant demo, Instant now) {
        if (variantRepository.findByDemoKey(demo.demoKey()).isPresent()) {
            return;
        }
        BigDecimal price = new BigDecimal(demo.price());
        BigDecimal cost = new BigDecimal(demo.cost());

        ProductVariant variant =
                new ProductVariant(
                        UUID.randomUUID(),
                        product.getId(),
                        demo.sku(),
                        demo.color(),
                        demo.size(),
                        null,
                        price,
                        now);
        variant.setDemoKey(demo.demoKey());
        variantRepository.save(variant);

        barcodeRepository.save(
                new VariantBarcode(
                        UUID.randomUUID(),
                        demo.barcode(),
                        variant.getId(),
                        VariantBarcode.Kind.PRIMARY,
                        now,
                        null));

        priceHistoryRepository.save(
                new VariantPriceHistory(
                        UUID.randomUUID(),
                        variant.getId(),
                        price,
                        now,
                        VariantPriceHistory.SourceType.IMPORT,
                        null,
                        null,
                        now));

        costHistoryRepository.save(
                new VariantCostHistory(
                        UUID.randomUUID(),
                        variant.getId(),
                        cost,
                        now,
                        VariantCostHistory.SourceType.IMPORT,
                        null,
                        null,
                        now));
    }

    /**
     * One demo category.
     *
     * @param demoKey the stable key
     * @param name the display name
     * @param parentDemoKey the key of the parent category, {@code null} for a top level one
     */
    private record DemoCategory(String demoKey, String name, String parentDemoKey) {}

    /**
     * One demo product.
     *
     * @param demoKey the stable key
     * @param productCode the code of the product
     * @param name the display name
     * @param categoryDemoKey the key of the category
     * @param brandName the name of the brand
     * @param variants the variants of the product
     */
    private record DemoProduct(
            String demoKey,
            String productCode,
            String name,
            String categoryDemoKey,
            String brandName,
            List<DemoVariant> variants) {}

    /**
     * One demo variant.
     *
     * @param demoKey the stable key
     * @param sku the stock keeping unit
     * @param color the colour, may be {@code null}
     * @param size the size, may be {@code null}
     * @param price the selling price
     * @param cost the cost
     */
    private record DemoVariant(
            String demoKey, String sku, String color, String size, String price, String cost) {

        /**
         * The barcode of the variant. It is built from the SKU, so it is stable and unique.
         *
         * @return the barcode
         */
        String barcode() {
            return "890" + sku.replace("-", "") + "01";
        }
    }
}
