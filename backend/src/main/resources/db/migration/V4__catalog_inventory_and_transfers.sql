-- ===========================================================================
-- Merqo V4: catalog, pricing, promotions, inventory, stock counts, transfers.
--
-- Rules that shape this file:
--   * V1, V2 and V3 are never edited. Everything new goes into this version.
--   * UUID keys, TIMESTAMPTZ timestamps stored in UTC.
--   * Human readable numbers come from a sequence: ADJ-000001, CNT-000001,
--     TRF-000001.
--   * Master data is deactivated with is_active, never deleted.
--   * Four tables are append only and a database trigger blocks UPDATE and
--     DELETE on them: stock_movements, variant_price_history,
--     variant_cost_history and transfer_status_history.
--   * Money is NUMERIC(14,2). Quantities are whole numbers (pieces).
--   * No images anywhere.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- The number sequences for the human readable document numbers.
-- ---------------------------------------------------------------------------
CREATE SEQUENCE adjustment_number_sequence START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE count_number_sequence START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE transfer_number_sequence START WITH 1 INCREMENT BY 1;

-- ---------------------------------------------------------------------------
-- The shared append only guard.
--
-- The audit log has its own function because it was written first. This one
-- is used by the four Phase 2 tables. TG_TABLE_NAME gives the table name in
-- the error message, so the message always names the right table.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION merqo_reject_append_only_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'The table % is append only; % is not allowed.', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- categories: the product tree. At most two levels deep.
-- ---------------------------------------------------------------------------
CREATE TABLE categories (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(120) NOT NULL,
    parent_id           UUID REFERENCES categories (id),
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    demo_key            VARCHAR(60),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The name is unique among the siblings, case insensitively. A child and its
-- parent may of course carry the same name, so the parent is part of the key.
CREATE UNIQUE INDEX ux_categories_sibling_name
    ON categories (COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));
CREATE UNIQUE INDEX ux_categories_demo_key ON categories (demo_key) WHERE demo_key IS NOT NULL;
CREATE INDEX ix_categories_parent ON categories (parent_id);

COMMENT ON TABLE categories IS 'The product tree. At most two levels: a category and a sub category.';

-- ---------------------------------------------------------------------------
-- brands.
-- ---------------------------------------------------------------------------
CREATE TABLE brands (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(120) NOT NULL,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    demo_key            VARCHAR(60),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_brands_name_lower ON brands (lower(name));
CREATE UNIQUE INDEX ux_brands_demo_key ON brands (demo_key) WHERE demo_key IS NOT NULL;

COMMENT ON TABLE brands IS 'The brands of the products. Deactivated, never deleted.';

-- ---------------------------------------------------------------------------
-- products: the thing a customer buys. The variant carries the details.
-- ---------------------------------------------------------------------------
CREATE TABLE products (
    id                  UUID PRIMARY KEY,
    product_code        VARCHAR(60)  NOT NULL,
    name                VARCHAR(160) NOT NULL,
    category_id         UUID REFERENCES categories (id),
    brand_id            UUID REFERENCES brands (id),
    description         TEXT,
    tax_rate_percent    NUMERIC(5,2),
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    demo_key            VARCHAR(60),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_products_code_lower ON products (lower(product_code));
CREATE UNIQUE INDEX ux_products_demo_key ON products (demo_key) WHERE demo_key IS NOT NULL;
CREATE INDEX ix_products_category ON products (category_id);
CREATE INDEX ix_products_brand ON products (brand_id);

COMMENT ON COLUMN products.tax_rate_percent IS 'The tax rate of this product. Null means the global setting is used.';
COMMENT ON TABLE products IS 'The products. The details that a customer chooses live on the variant.';

-- ---------------------------------------------------------------------------
-- product_variants: the sellable thing, for example "T-shirt, black, medium".
-- ---------------------------------------------------------------------------
CREATE TABLE product_variants (
    id                  UUID PRIMARY KEY,
    product_id          UUID NOT NULL REFERENCES products (id),
    sku                 VARCHAR(60)  NOT NULL,
    color               VARCHAR(60),
    size                VARCHAR(60),
    other_attribute     VARCHAR(60),
    selling_price       NUMERIC(14,2) NOT NULL,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    demo_key            VARCHAR(60),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The SKU is unique in the whole catalogue, case insensitively.
CREATE UNIQUE INDEX ux_product_variants_sku_lower ON product_variants (lower(sku));
CREATE UNIQUE INDEX ux_product_variants_demo_key ON product_variants (demo_key) WHERE demo_key IS NOT NULL;
CREATE INDEX ix_product_variants_product ON product_variants (product_id);

-- The attribute combination is unique per product, case insensitively. A
-- missing attribute is an empty string in the index, so "black, medium" and
-- "black, medium, " are the same row.
CREATE UNIQUE INDEX ux_product_variants_attribute_combination
    ON product_variants (
        product_id,
        lower(COALESCE(color, '')),
        lower(COALESCE(size, '')),
        lower(COALESCE(other_attribute, '')));

COMMENT ON COLUMN product_variants.selling_price IS 'The current selling price. Every change is also written to variant_price_history.';
COMMENT ON TABLE product_variants IS 'The sellable variants of a product. The cost is not stored here; the latest cost history row is the cost.';

-- ---------------------------------------------------------------------------
-- variant_barcodes: one table for the primary code and the alternates.
--
-- One table gives the uniqueness across primary and alternate codes for free:
-- the barcode itself is unique in the whole table.
-- ---------------------------------------------------------------------------
CREATE TABLE variant_barcodes (
    id                  UUID PRIMARY KEY,
    barcode             VARCHAR(64)  NOT NULL,
    variant_id          UUID NOT NULL REFERENCES product_variants (id),
    kind                VARCHAR(20)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          UUID REFERENCES app_users (id)
);

CREATE UNIQUE INDEX ux_variant_barcodes_barcode ON variant_barcodes (barcode);
-- At most one PRIMARY barcode per variant.
CREATE UNIQUE INDEX ux_variant_barcodes_one_primary
    ON variant_barcodes (variant_id) WHERE kind = 'PRIMARY';
CREATE INDEX ix_variant_barcodes_variant ON variant_barcodes (variant_id);

ALTER TABLE variant_barcodes ADD CONSTRAINT ck_variant_barcodes_kind
    CHECK (kind IN ('PRIMARY', 'ALTERNATE'));
-- The reserved prefix of the Merqo document numbers may never be a barcode.
ALTER TABLE variant_barcodes ADD CONSTRAINT ck_variant_barcodes_no_reserved_prefix
    CHECK (barcode NOT LIKE 'MQ-%');

COMMENT ON TABLE variant_barcodes IS 'The primary barcode of a variant and its alternates. One table, so a code is unique across both kinds.';

-- ---------------------------------------------------------------------------
-- variant_price_history: append only.
-- ---------------------------------------------------------------------------
CREATE TABLE variant_price_history (
    id                  UUID PRIMARY KEY,
    variant_id          UUID NOT NULL REFERENCES product_variants (id),
    value               NUMERIC(14,2) NOT NULL,
    effective_from      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    source_type         VARCHAR(20)  NOT NULL,
    source_id           UUID,
    set_by              UUID REFERENCES app_users (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_variant_price_history_variant ON variant_price_history (variant_id, effective_from DESC);
ALTER TABLE variant_price_history ADD CONSTRAINT ck_variant_price_history_source
    CHECK (source_type IN ('MANUAL', 'IMPORT', 'PURCHASE'));

COMMENT ON TABLE variant_price_history IS 'Append only. The current price is also kept on the variant, and both are written in one transaction.';

CREATE TRIGGER trg_variant_price_history_append_only
    BEFORE UPDATE OR DELETE ON variant_price_history
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- variant_cost_history: append only. The latest row is the current cost.
-- ---------------------------------------------------------------------------
CREATE TABLE variant_cost_history (
    id                  UUID PRIMARY KEY,
    variant_id          UUID NOT NULL REFERENCES product_variants (id),
    value               NUMERIC(14,2) NOT NULL,
    effective_from      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    source_type         VARCHAR(20)  NOT NULL,
    source_id           UUID,
    set_by              UUID REFERENCES app_users (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_variant_cost_history_variant ON variant_cost_history (variant_id, effective_from DESC);
ALTER TABLE variant_cost_history ADD CONSTRAINT ck_variant_cost_history_source
    CHECK (source_type IN ('MANUAL', 'IMPORT', 'PURCHASE'));

COMMENT ON TABLE variant_cost_history IS 'Append only. The cost is sensitive: it is only shown to a user who holds COST_VIEW or PRICE_EDIT.';

CREATE TRIGGER trg_variant_cost_history_append_only
    BEFORE UPDATE OR DELETE ON variant_cost_history
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- promotions: the configurable discounts.
-- ---------------------------------------------------------------------------
CREATE TABLE promotions (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(160) NOT NULL,
    discount_type       VARCHAR(20)  NOT NULL,
    value               NUMERIC(14,2) NOT NULL,
    scope               VARCHAR(20)  NOT NULL,
    all_locations       BOOLEAN      NOT NULL DEFAULT FALSE,
    starts_at           TIMESTAMPTZ,
    ends_at             TIMESTAMPTZ,
    is_enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    note                TEXT,
    created_by          UUID REFERENCES app_users (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    demo_key            VARCHAR(60)
);

ALTER TABLE promotions ADD CONSTRAINT ck_promotions_discount_type
    CHECK (discount_type IN ('PERCENT', 'FIXED'));
ALTER TABLE promotions ADD CONSTRAINT ck_promotions_scope
    CHECK (scope IN ('POS', 'ONLINE', 'BOTH'));
-- A percentage discount is above zero and at most one hundred.
ALTER TABLE promotions ADD CONSTRAINT ck_promotions_percent_value
    CHECK (discount_type <> 'PERCENT' OR (value > 0 AND value <= 100));
-- The end is never before the start.
ALTER TABLE promotions ADD CONSTRAINT ck_promotions_dates
    CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at);
CREATE UNIQUE INDEX ux_promotions_demo_key ON promotions (demo_key) WHERE demo_key IS NOT NULL;
CREATE INDEX ix_promotions_enabled ON promotions (is_enabled, starts_at, ends_at);

COMMENT ON TABLE promotions IS 'The discounts. A promotion is never deleted; it is disabled or ended.';
COMMENT ON COLUMN promotions.value IS 'The discount: a percentage when discount_type is PERCENT, otherwise an amount per unit.';

-- The variants a promotion covers. They are expanded at creation time, so a
-- later variant is not covered automatically.
CREATE TABLE promotion_variants (
    promotion_id        UUID NOT NULL REFERENCES promotions (id) ON DELETE CASCADE,
    variant_id          UUID NOT NULL REFERENCES product_variants (id),
    PRIMARY KEY (promotion_id, variant_id)
);

CREATE INDEX ix_promotion_variants_variant ON promotion_variants (variant_id);

-- The locations a promotion covers. An empty set with all_locations = TRUE
-- means every location.
CREATE TABLE promotion_locations (
    promotion_id        UUID NOT NULL REFERENCES promotions (id) ON DELETE CASCADE,
    location_id         UUID NOT NULL REFERENCES locations (id),
    PRIMARY KEY (promotion_id, location_id)
);

CREATE INDEX ix_promotion_locations_location ON promotion_locations (location_id);

-- ---------------------------------------------------------------------------
-- inventory_balances: one row per location and variant.
-- ---------------------------------------------------------------------------
CREATE TABLE inventory_balances (
    id                  UUID PRIMARY KEY,
    location_id         UUID NOT NULL REFERENCES locations (id),
    variant_id          UUID NOT NULL REFERENCES product_variants (id),
    on_hand             INT          NOT NULL DEFAULT 0,
    reserved            INT          NOT NULL DEFAULT 0,
    in_inspection       INT          NOT NULL DEFAULT 0,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_inventory_balances_pair ON inventory_balances (location_id, variant_id);
CREATE INDEX ix_inventory_balances_variant ON inventory_balances (variant_id);

-- Nothing is negative and the reservation never exceeds the stock.
ALTER TABLE inventory_balances ADD CONSTRAINT ck_inventory_balances_non_negative
    CHECK (on_hand >= 0 AND reserved >= 0 AND in_inspection >= 0);
ALTER TABLE inventory_balances ADD CONSTRAINT ck_inventory_balances_reserved
    CHECK (reserved <= on_hand);

COMMENT ON TABLE inventory_balances IS 'The stock of one variant at one location. The row is created on the first movement.';
COMMENT ON COLUMN inventory_balances.in_inspection IS 'Goods that are in inspection. Not sellable and not part of the available stock.';

-- ---------------------------------------------------------------------------
-- inventory_policies: the reorder point and its friends.
-- ---------------------------------------------------------------------------
CREATE TABLE inventory_policies (
    id                  UUID PRIMARY KEY,
    location_id         UUID NOT NULL REFERENCES locations (id),
    variant_id          UUID NOT NULL REFERENCES product_variants (id),
    reorder_point       INT          NOT NULL DEFAULT 0,
    target_stock        INT          NOT NULL DEFAULT 0,
    online_safety_stock INT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_inventory_policies_pair ON inventory_policies (location_id, variant_id);

ALTER TABLE inventory_policies ADD CONSTRAINT ck_inventory_policies_non_negative
    CHECK (reorder_point >= 0 AND target_stock >= 0 AND online_safety_stock >= 0);

COMMENT ON TABLE inventory_policies IS 'How much of a variant to keep at a location. The reorder point decides the low stock flag.';
COMMENT ON COLUMN inventory_policies.online_safety_stock IS 'Kept for Phase 4. The online availability comes from the warehouse only.';

-- ---------------------------------------------------------------------------
-- stock_movements: append only. Every physical change writes one row.
-- ---------------------------------------------------------------------------
CREATE TABLE stock_movements (
    id                  UUID PRIMARY KEY,
    location_id         UUID NOT NULL REFERENCES locations (id),
    variant_id          UUID NOT NULL REFERENCES product_variants (id),
    movement_type       VARCHAR(30)  NOT NULL,
    quantity_delta      INT          NOT NULL,
    on_hand_after       INT          NOT NULL,
    reference_type      VARCHAR(30),
    reference_id        UUID,
    reason              VARCHAR(40),
    note                TEXT,
    unit_cost           NUMERIC(14,2),
    created_by          UUID REFERENCES app_users (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    correlation_id      VARCHAR(64)
);

CREATE INDEX ix_stock_movements_location_variant ON stock_movements (location_id, variant_id, created_at DESC);
CREATE INDEX ix_stock_movements_variant ON stock_movements (variant_id, created_at DESC);
CREATE INDEX ix_stock_movements_reference ON stock_movements (reference_type, reference_id);

ALTER TABLE stock_movements ADD CONSTRAINT ck_stock_movements_non_negative
    CHECK (on_hand_after >= 0);

COMMENT ON TABLE stock_movements IS 'Append only. The sum of the deltas of a balance is always its on_hand.';

CREATE TRIGGER trg_stock_movements_append_only
    BEFORE UPDATE OR DELETE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- stock_adjustments and their lines: a whole document or nothing.
-- ---------------------------------------------------------------------------
CREATE TABLE stock_adjustments (
    id                  UUID PRIMARY KEY,
    number              VARCHAR(20)  NOT NULL,
    location_id         UUID NOT NULL REFERENCES locations (id),
    reason              VARCHAR(30)  NOT NULL,
    note                TEXT,
    is_flagged          BOOLEAN      NOT NULL DEFAULT FALSE,
    total_value_at_cost NUMERIC(14,2) NOT NULL DEFAULT 0,
    created_by          UUID REFERENCES app_users (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_stock_adjustments_number ON stock_adjustments (number);
CREATE INDEX ix_stock_adjustments_location ON stock_adjustments (location_id, created_at DESC);

ALTER TABLE stock_adjustments ADD CONSTRAINT ck_stock_adjustments_reason
    CHECK (reason IN ('DAMAGED', 'LOST', 'FOUND', 'DATA_CORRECTION', 'OTHER'));

COMMENT ON TABLE stock_adjustments IS 'A document that changes the stock of a location. All lines are applied or none is.';
COMMENT ON COLUMN stock_adjustments.is_flagged IS 'True when the value at cost is above the threshold. The Action Center of Phase 8 reads this flag.';

CREATE TABLE stock_adjustment_lines (
    id                  UUID PRIMARY KEY,
    adjustment_id       UUID NOT NULL REFERENCES stock_adjustments (id) ON DELETE CASCADE,
    variant_id          UUID NOT NULL REFERENCES product_variants (id),
    quantity_delta      INT          NOT NULL,
    unit_cost           NUMERIC(14,2),
    value_at_cost       NUMERIC(14,2) NOT NULL DEFAULT 0
);

CREATE INDEX ix_stock_adjustment_lines_adjustment ON stock_adjustment_lines (adjustment_id);
CREATE INDEX ix_stock_adjustment_lines_variant ON stock_adjustment_lines (variant_id);

-- ---------------------------------------------------------------------------
-- stock_counts and their lines.
-- ---------------------------------------------------------------------------
CREATE TABLE stock_counts (
    id                  UUID PRIMARY KEY,
    number              VARCHAR(20)  NOT NULL,
    location_id         UUID NOT NULL REFERENCES locations (id),
    status              VARCHAR(20)  NOT NULL,
    category_id         UUID REFERENCES categories (id),
    created_by          UUID REFERENCES app_users (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    submitted_at        TIMESTAMPTZ,
    submitted_by        UUID REFERENCES app_users (id),
    applied_at          TIMESTAMPTZ,
    applied_by          UUID REFERENCES app_users (id),
    is_flagged          BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX ux_stock_counts_number ON stock_counts (number);
CREATE INDEX ix_stock_counts_location ON stock_counts (location_id, created_at DESC);

ALTER TABLE stock_counts ADD CONSTRAINT ck_stock_counts_status
    CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'APPLIED', 'CANCELLED'));

COMMENT ON TABLE stock_counts IS 'A stock count of one location. While it is IN_PROGRESS the counter does not see the system quantities.';
COMMENT ON COLUMN stock_counts.category_id IS 'Optional. When it is set, only the variants of that category are counted.';

CREATE TABLE stock_count_lines (
    id                  UUID PRIMARY KEY,
    count_id            UUID NOT NULL REFERENCES stock_counts (id) ON DELETE CASCADE,
    variant_id          UUID NOT NULL REFERENCES product_variants (id),
    system_qty_at_start INT          NOT NULL,
    counted_qty         INT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_stock_count_lines_pair ON stock_count_lines (count_id, variant_id);

-- ---------------------------------------------------------------------------
-- stock_transfers, their lines and their status history.
-- ---------------------------------------------------------------------------
CREATE TABLE stock_transfers (
    id                  UUID PRIMARY KEY,
    number              VARCHAR(20)  NOT NULL,
    source_location_id  UUID NOT NULL REFERENCES locations (id),
    destination_location_id UUID NOT NULL REFERENCES locations (id),
    status              VARCHAR(30)  NOT NULL,
    note                TEXT,
    requested_by        UUID REFERENCES app_users (id),
    requested_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    approved_by         UUID REFERENCES app_users (id),
    approved_at         TIMESTAMPTZ,
    dispatched_by       UUID REFERENCES app_users (id),
    dispatched_at       TIMESTAMPTZ,
    received_by         UUID REFERENCES app_users (id),
    received_at         TIMESTAMPTZ,
    closed_by           UUID REFERENCES app_users (id),
    closed_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    demo_key            VARCHAR(60)
);

CREATE UNIQUE INDEX ux_stock_transfers_number ON stock_transfers (number);
CREATE UNIQUE INDEX ux_stock_transfers_demo_key ON stock_transfers (demo_key) WHERE demo_key IS NOT NULL;
CREATE INDEX ix_stock_transfers_source ON stock_transfers (source_location_id, created_at DESC);
CREATE INDEX ix_stock_transfers_destination ON stock_transfers (destination_location_id, created_at DESC);

ALTER TABLE stock_transfers ADD CONSTRAINT ck_stock_transfers_status
    CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'DISPATCHED',
                      'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED_WITH_SHORTAGE', 'CANCELLED'));
-- The source and the destination are never the same place.
ALTER TABLE stock_transfers ADD CONSTRAINT ck_stock_transfers_different_locations
    CHECK (source_location_id <> destination_location_id);

COMMENT ON TABLE stock_transfers IS 'A transfer of stock from one location to another.';
COMMENT ON COLUMN stock_transfers.status IS 'The state of the transfer. The transition table lives in TransferTransitions.';

CREATE TABLE stock_transfer_items (
    id                  UUID PRIMARY KEY,
    transfer_id         UUID NOT NULL REFERENCES stock_transfers (id) ON DELETE CASCADE,
    variant_id          UUID NOT NULL REFERENCES product_variants (id),
    requested_qty       INT          NOT NULL,
    approved_qty        INT,
    dispatched_qty      INT          NOT NULL DEFAULT 0,
    received_qty        INT          NOT NULL DEFAULT 0,
    shortage_qty        INT          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_stock_transfer_items_pair ON stock_transfer_items (transfer_id, variant_id);
CREATE INDEX ix_stock_transfer_items_variant ON stock_transfer_items (variant_id);

ALTER TABLE stock_transfer_items ADD CONSTRAINT ck_stock_transfer_items_non_negative
    CHECK (requested_qty > 0 AND approved_qty >= 0 AND dispatched_qty >= 0
           AND received_qty >= 0 AND shortage_qty >= 0);

COMMENT ON TABLE stock_transfer_items IS 'The lines of a transfer. The stock moves only at dispatch and at receive.';

CREATE TABLE transfer_status_history (
    id                  UUID PRIMARY KEY,
    transfer_id         UUID NOT NULL REFERENCES stock_transfers (id) ON DELETE CASCADE,
    from_status         VARCHAR(30),
    to_status           VARCHAR(30)  NOT NULL,
    actor_user_id       UUID REFERENCES app_users (id),
    actor_username      VARCHAR(40),
    note                TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_transfer_status_history_transfer ON transfer_status_history (transfer_id, created_at ASC);

COMMENT ON TABLE transfer_status_history IS 'Append only. Every status change writes one row.';

CREATE TRIGGER trg_transfer_status_history_append_only
    BEFORE UPDATE OR DELETE ON transfer_status_history
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();
