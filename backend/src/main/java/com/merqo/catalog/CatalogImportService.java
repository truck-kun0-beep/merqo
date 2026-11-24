package com.merqo.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.catalog.domain.CatalogImportValidator;
import com.merqo.catalog.domain.CsvReader;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.ForbiddenException;
import com.merqo.common.error.ValidationException;
import com.merqo.inventory.InventoryCommand;
import com.merqo.inventory.InventoryService;
import com.merqo.inventory.domain.MovementType;

/**
 * Imports products and opening stock from a CSV file.
 *
 * <p>Responsibility: read a CSV file, check it, and then apply it through the same rules as the
 * screens use. The import never writes its own rows behind the back of the catalogue and the
 * inventory services: it calls them, so the barcode rules, the price history and the audit log are
 * exactly the same whether a product was typed in or imported.
 *
 * <p>Two steps, on purpose:
 *
 * <ol>
 *   <li><b>Validate</b> reads the file and answers with the errors, one per row, and a summary. It
 *       writes nothing at all.
 *   <li><b>Commit</b> reads the file again, checks it again and then writes everything. The commit
 *       is all or nothing: one bad row and nothing is written.
 * </ol>
 *
 * <p>Matching is by SKU. A row with a SKU that is already in the catalogue updates the descriptive
 * fields, the price and the cost of that variant. A row with a new SKU creates a product, a variant
 * and its barcode.
 *
 * <p>The opening stock goes to one location that the caller names. It is only written when the
 * variant has no movements at that location yet, so importing the same file twice changes nothing
 * the second time.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Facade</b> - the controller asks for "validate this file" or "commit this file".
 *   <li><b>Delegation</b> - every write goes through the catalogue service and the inventory
 *       service, so there is only one place where a barcode or a price may be written.
 *   <li><b>Fail fast</b> - a file that cannot be applied is refused before anything is written.
 *   <li><b>Immutability</b> - the parsed rows are copied into immutable records.
 * </ul>
 */
@Service
public class CatalogImportService {

    /** The biggest file the import accepts, in bytes. */
    public static final int MAX_FILE_BYTES = 1024 * 1024;

    private final CatalogService catalogService;
    private final InventoryService inventoryService;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param catalogService the catalogue rules
     * @param inventoryService the stock rules
     * @param auditService the audit log
     * @param clock the clock
     */
    public CatalogImportService(
            CatalogService catalogService,
            InventoryService inventoryService,
            AuditService auditService,
            Clock clock) {
        this.catalogService = catalogService;
        this.inventoryService = inventoryService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Checks a file and answers with the errors and the summary. Nothing is written.
     *
     * @param context the caller
     * @param file the uploaded file
     * @param openingLocationId the location the opening stock would go to, may be {@code null}
     * @return the errors and the summary
     */
    @Transactional(readOnly = true)
    public Map<String, Object> validate(
            AccessContext context, MultipartFile file, UUID openingLocationId) {
        requireProductPermission(context);
        if (openingLocationId != null) {
            context.require(Permission.INVENTORY_ADJUST, openingLocationId);
        }

        ParsedFile parsed = parse(file);
        CatalogImportValidator.Result result =
                CatalogImportValidator.validate(parsed.rows(), knownSkus(), knownBarcodes());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errors", errorsOf(result));
        body.put("toCreate", result.toCreate());
        body.put("toUpdate", result.toUpdate());
        body.put("openingStockLines", result.openingStockLines());
        body.put("totalRows", parsed.rows().size());
        body.put("isValid", result.isValid());
        return body;
    }

    /**
     * Applies a file. Everything is written, or nothing is.
     *
     * @param context the caller
     * @param file the uploaded file
     * @param openingLocationId the location the opening stock goes to, may be {@code null}
     * @return what was written
     */
    @Transactional
    public Map<String, Object> commit(
            AccessContext context, MultipartFile file, UUID openingLocationId) {
        requireProductPermission(context);
        if (openingLocationId != null) {
            context.require(Permission.INVENTORY_ADJUST, openingLocationId);
        }

        ParsedFile parsed = parse(file);
        CatalogImportValidator.Result result =
                CatalogImportValidator.validate(parsed.rows(), knownSkus(), knownBarcodes());
        if (!result.isValid()) {
            throw new ValidationException(
                    ErrorCodes.IMPORT_INVALID,
                    "The file has " + result.errors().size() + " problem(s). Nothing was written.");
        }

        int created = 0;
        int updated = 0;
        int openingLines = 0;
        List<String> skipped = new ArrayList<>();

        for (ImportRow row : parsed.dataRows()) {
            ImportOutcome outcome = applyRow(context, row, openingLocationId);
            switch (outcome) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case SKIPPED -> skipped.add(row.sku());
                case OPENING_WRITTEN -> {
                    updated++;
                    openingLines++;
                }
            }
        }

        auditService.record(
                new AuditEvent.Builder(AuditAction.CATALOG_IMPORTED, "products")
                        .actor(context.getUserId(), context.getUsername())
                        .locationId(openingLocationId)
                        .added("created", created)
                        .added("updated", updated)
                        .added("openingStockLines", openingLines)
                        .added("totalRows", parsed.dataRows().size())
                        .build());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("created", created);
        body.put("updated", updated);
        body.put("openingStockLines", openingLines);
        body.put("skipped", skipped);
        body.put("totalRows", parsed.dataRows().size());
        return body;
    }

    /**
     * The template file, so that the student knows the exact columns.
     *
     * @return the template as CSV text
     */
    public String template() {
        return String.join(",", CatalogImportValidator.COLUMNS)
                + "\n"
                + "OT001,Cotton T-Shirt,T-Shirts,Northline,Black,M,,OT001-BLK-M,2000000000001,750,400,10\n";
    }

    // ---- Rows ---------------------------------------------------------------

    /**
     * Applies one row of the file.
     *
     * @param context the caller
     * @param row the row
     * @param openingLocationId the location the opening stock goes to, may be {@code null}
     * @return what happened with the row
     */
    private ImportOutcome applyRow(AccessContext context, ImportRow row, UUID openingLocationId) {
        Optional<ProductVariant> existing = catalogService.findVariantBySku(row.sku());

        if (existing.isPresent()) {
            ProductVariant variant = existing.get();
            if (row.sellingPrice() != null) {
                catalogService.changePrice(
                        context,
                        variant,
                        row.sellingPrice(),
                        VariantPriceHistory.SourceType.IMPORT,
                        null);
            }
            if (row.cost() != null) {
                catalogService.changeCost(
                        context, variant, row.cost(), VariantCostHistory.SourceType.IMPORT, null);
            }
            if (row.openingQuantity() != null) {
                return writeOpeningStock(context, variant, row, openingLocationId);
            }
            return ImportOutcome.UPDATED;
        }

        Product product = findOrCreateProduct(context, row);
        ProductVariant variant =
                catalogService.createVariant(
                        context,
                        product,
                        row.sku(),
                        row.color(),
                        row.size(),
                        row.otherAttribute(),
                        row.sellingPrice() == null ? BigDecimal.ZERO : row.sellingPrice(),
                        row.barcode());
        if (row.cost() != null) {
            catalogService.changeCost(
                    context, variant, row.cost(), VariantCostHistory.SourceType.IMPORT, null);
        }
        if (row.openingQuantity() != null) {
            ImportOutcome outcome = writeOpeningStock(context, variant, row, openingLocationId);
            return outcome == ImportOutcome.SKIPPED
                    ? ImportOutcome.SKIPPED
                    : ImportOutcome.OPENING_WRITTEN;
        }
        return ImportOutcome.CREATED;
    }

    /**
     * Writes the opening stock of a row, but only when the variant has no movements at the location
     * yet. That is what makes a second run of the same file change nothing.
     *
     * @param context the caller
     * @param variant the variant
     * @param row the row
     * @param openingLocationId the location, may be {@code null}
     * @return what happened
     */
    private ImportOutcome writeOpeningStock(
            AccessContext context, ProductVariant variant, ImportRow row, UUID openingLocationId) {
        if (openingLocationId == null) {
            return ImportOutcome.SKIPPED;
        }
        if (inventoryService.countMovementsAt(openingLocationId, variant.getId()) > 0) {
            // The stock of this location already moved, so the opening quantity is not written
            // again.
            return ImportOutcome.SKIPPED;
        }
        inventoryService.receive(
                new InventoryCommand(
                        openingLocationId,
                        variant.getId(),
                        row.openingQuantity(),
                        MovementType.OPENING_BALANCE,
                        "CATALOG_IMPORT",
                        null,
                        null,
                        null,
                        row.cost(),
                        context.getUserId(),
                        null));
        return ImportOutcome.OPENING_WRITTEN;
    }

    /**
     * The product of a row: the one that has the product code, or a new one.
     *
     * @param context the caller
     * @param row the row
     * @return the product
     */
    private Product findOrCreateProduct(AccessContext context, ImportRow row) {
        Optional<Product> existing = catalogService.findProductByCode(row.productCode());
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID categoryId =
                row.category() == null ? null : catalogService.findOrCreateCategory(row.category());
        UUID brandId = row.brand() == null ? null : catalogService.findOrCreateBrand(row.brand());
        return catalogService.createProduct(
                context, row.productCode(), row.productName(), categoryId, brandId, null, null);
    }

    // ---- Files --------------------------------------------------------------

    /**
     * Reads the uploaded file and turns it into rows.
     *
     * @param file the uploaded file
     * @return the parsed file
     */
    private ParsedFile parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException(ErrorCodes.IMPORT_INVALID, "The file is empty.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ValidationException(
                    ErrorCodes.IMPORT_INVALID,
                    "The file is bigger than " + (MAX_FILE_BYTES / 1024) + " KB.");
        }
        String text;
        try {
            text = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new ValidationException(
                    ErrorCodes.IMPORT_INVALID, "The file could not be read as UTF-8 text.");
        }

        List<List<String>> rawRows = CsvReader.read(text);
        if (rawRows.isEmpty()) {
            throw new ValidationException(ErrorCodes.IMPORT_INVALID, "The file has no rows.");
        }
        List<String> header = rawRows.get(0);
        if (!header.stream()
                .map(String::trim)
                .map(String::toLowerCase)
                .toList()
                .equals(
                        CatalogImportValidator.COLUMNS.stream()
                                .map(String::toLowerCase)
                                .toList())) {
            throw new ValidationException(
                    ErrorCodes.IMPORT_INVALID,
                    "The header of the file must be exactly: "
                            + String.join(", ", CatalogImportValidator.COLUMNS));
        }

        List<List<String>> dataRows = rawRows.subList(1, rawRows.size());
        List<ImportRow> rows = new ArrayList<>();
        for (List<String> row : dataRows) {
            rows.add(toImportRow(row));
        }
        return new ParsedFile(dataRows, rows);
    }

    /**
     * Turns one row of cells into the values of a product.
     *
     * @param cells the cells
     * @return the row
     */
    private ImportRow toImportRow(List<String> cells) {
        List<String> padded = new ArrayList<>(cells);
        while (padded.size() < CatalogImportValidator.COLUMNS.size()) {
            padded.add("");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < CatalogImportValidator.COLUMNS.size(); index++) {
            values.put(CatalogImportValidator.COLUMNS.get(index), padded.get(index).trim());
        }
        return new ImportRow(
                values.get("product_code"),
                values.get("product_name"),
                emptyToNull(values.get("category")),
                emptyToNull(values.get("brand")),
                emptyToNull(values.get("color")),
                emptyToNull(values.get("size")),
                emptyToNull(values.get("other")),
                values.get("sku"),
                emptyToNull(values.get("barcode")),
                parseDecimal(values.get("selling_price")),
                parseDecimal(values.get("cost")),
                parseWholeNumber(values.get("opening_qty")));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static BigDecimal parseDecimal(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            throw new ValidationException(
                    ErrorCodes.IMPORT_INVALID, "The value " + value + " is not a number.");
        }
    }

    private static Integer parseWholeNumber(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new ValidationException(
                    ErrorCodes.IMPORT_INVALID, "The quantity " + value + " is not a whole number.");
        }
    }

    // ---- Helpers ------------------------------------------------------------

    /**
     * The SKUs that are already in the catalogue, in lower case.
     *
     * @return the SKUs
     */
    private Set<String> knownSkus() {
        Set<String> skus = new LinkedHashSet<>();
        for (ProductVariant variant : catalogService.allVariants()) {
            skus.add(variant.getSku().toLowerCase(Locale.ROOT));
        }
        return skus;
    }

    /**
     * The barcodes that are already in the catalogue.
     *
     * @return the barcodes
     */
    private Set<String> knownBarcodes() {
        return new LinkedHashSet<>(catalogService.allBarcodes());
    }

    /**
     * The errors of the validator, as the interface shows them.
     *
     * @param result the result of the validator
     * @return the errors
     */
    private List<Map<String, Object>> errorsOf(CatalogImportValidator.Result result) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (CatalogImportValidator.Error error : result.errors()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("row", error.row());
            entry.put("message", error.message());
            errors.add(entry);
        }
        return errors;
    }

    /**
     * Refuses a caller that may not write to the catalogue.
     *
     * @param context the caller
     */
    private void requireProductPermission(AccessContext context) {
        if (!context.can(Permission.PRODUCT_CREATE) && !context.can(Permission.PRODUCT_EDIT)) {
            throw new ForbiddenException(
                    ErrorCodes.FORBIDDEN,
                    "You need the permission PRODUCT_CREATE or PRODUCT_EDIT to import.");
        }
    }

    /** What happened with one row of the file. */
    private enum ImportOutcome {
        /** The row created a new variant. */
        CREATED,
        /** The row updated an existing variant. */
        UPDATED,
        /** The row wrote an opening quantity. */
        OPENING_WRITTEN,
        /** The row was left alone, because the stock had already moved. */
        SKIPPED
    }

    /**
     * The parsed file.
     *
     * @param rows the raw rows, without the header
     * @param dataRows the rows as values
     */
    private record ParsedFile(List<List<String>> rows, List<ImportRow> dataRows) {}

    /**
     * One row of the import file.
     *
     * @param productCode the code of the product
     * @param productName the name of the product
     * @param category the category, may be {@code null}
     * @param brand the brand, may be {@code null}
     * @param color the colour, may be {@code null}
     * @param size the size, may be {@code null}
     * @param otherAttribute the other attribute, may be {@code null}
     * @param sku the stock keeping unit
     * @param barcode the barcode, may be {@code null}
     * @param sellingPrice the selling price, may be {@code null}
     * @param cost the purchase cost, may be {@code null}
     * @param openingQuantity the opening quantity, may be {@code null}
     */
    private record ImportRow(
            String productCode,
            String productName,
            String category,
            String brand,
            String color,
            String size,
            String otherAttribute,
            String sku,
            String barcode,
            BigDecimal sellingPrice,
            BigDecimal cost,
            Integer openingQuantity) {}
}
