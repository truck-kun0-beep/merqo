-- ===========================================================================
-- Merqo V6: POS sales, cash sessions, approval tokens, returns, reprints.
--
-- Rules that shape this file:
--   * V1 to V5 are never edited. Everything new goes into this version.
--   * UUID keys, TIMESTAMPTZ timestamps stored in UTC.
--   * Human readable numbers come from sequences: CS-000001, MQ-<year>-000001,
--     RET-000001.
--   * Six tables are append only via merqo_reject_append_only_mutation():
--     sales, sale_lines, sale_payments, sale_returns, sale_return_lines,
--     receipt_reprints.
--   * Closed cash sessions are immutable and cash sessions cannot be deleted.
--   * Money is NUMERIC(14,2). Quantities are whole integers.
--   * Compatible with Supabase PostgreSQL (no superuser-only extensions).
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Sequences for human readable POS document numbers.
-- ---------------------------------------------------------------------------
CREATE SEQUENCE cash_session_number_sequence START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE receipt_number_sequence START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE return_number_sequence START WITH 1 INCREMENT BY 1;

-- ---------------------------------------------------------------------------
-- app_users: manager approval PIN hash (nullable).
-- ---------------------------------------------------------------------------
ALTER TABLE app_users
    ADD COLUMN approval_pin_hash VARCHAR(255);

-- ---------------------------------------------------------------------------
-- pin_attempts: operational throttle tracking for approval PIN attempts.
-- ---------------------------------------------------------------------------
CREATE TABLE pin_attempts (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    location_id         UUID REFERENCES locations (id),
    success             BOOLEAN NOT NULL,
    attempted_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_pin_attempts_user ON pin_attempts (user_id, attempted_at DESC);
CREATE INDEX ix_pin_attempts_time ON pin_attempts (attempted_at);

COMMENT ON TABLE pin_attempts IS 'Operational throttle data for manager approval PIN attempts.';

-- ---------------------------------------------------------------------------
-- cash_sessions: cashier shift cash drawer sessions.
-- ---------------------------------------------------------------------------
CREATE TABLE cash_sessions (
    id                  UUID PRIMARY KEY,
    session_number      VARCHAR(20) NOT NULL,
    location_id         UUID NOT NULL REFERENCES locations (id),
    cashier_id          UUID NOT NULL REFERENCES app_users (id),
    opening_cash        NUMERIC(14,2) NOT NULL,
    opened_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    status              VARCHAR(20) NOT NULL,
    closed_at           TIMESTAMPTZ,
    closed_by           UUID REFERENCES app_users (id),
    counted_cash        NUMERIC(14,2),
    counted_bkash       NUMERIC(14,2),
    counted_nagad       NUMERIC(14,2),
    counted_card        NUMERIC(14,2),
    expected_cash       NUMERIC(14,2),
    expected_bkash      NUMERIC(14,2),
    expected_nagad      NUMERIC(14,2),
    expected_card       NUMERIC(14,2),
    difference_cash     NUMERIC(14,2),
    difference_bkash    NUMERIC(14,2),
    difference_nagad    NUMERIC(14,2),
    difference_card     NUMERIC(14,2),
    close_note          TEXT,
    demo_key            VARCHAR(60),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_cash_sessions_opening_non_negative CHECK (opening_cash >= 0),
    CONSTRAINT ck_cash_sessions_status CHECK (status IN ('OPEN', 'CLOSED'))
);

CREATE UNIQUE INDEX ux_cash_sessions_number ON cash_sessions (session_number);
CREATE UNIQUE INDEX ux_cash_sessions_one_open_per_cashier
    ON cash_sessions (cashier_id) WHERE status = 'OPEN';
CREATE UNIQUE INDEX ux_cash_sessions_demo_key
    ON cash_sessions (demo_key) WHERE demo_key IS NOT NULL;
CREATE INDEX ix_cash_sessions_location ON cash_sessions (location_id, opened_at DESC);
CREATE INDEX ix_cash_sessions_cashier ON cash_sessions (cashier_id, opened_at DESC);

COMMENT ON TABLE cash_sessions IS 'Cashier cash drawer sessions. At most one OPEN session per cashier.';

CREATE OR REPLACE FUNCTION merqo_protect_cash_session() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Cash sessions cannot be deleted.';
    END IF;
    IF OLD.status = 'CLOSED' THEN
        RAISE EXCEPTION 'A closed cash session is immutable and cannot be updated.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_cash_sessions_protect
    BEFORE UPDATE OR DELETE ON cash_sessions
    FOR EACH ROW EXECUTE FUNCTION merqo_protect_cash_session();

-- ---------------------------------------------------------------------------
-- approval_tokens: single-use expiring tokens for manager discount overrides.
-- ---------------------------------------------------------------------------
CREATE TABLE approval_tokens (
    id                   UUID PRIMARY KEY,
    token_hash           VARCHAR(128) NOT NULL,
    cashier_id           UUID NOT NULL REFERENCES app_users (id),
    location_id          UUID NOT NULL REFERENCES locations (id),
    max_discount_percent NUMERIC(5,2) NOT NULL,
    approver_id          UUID NOT NULL REFERENCES app_users (id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at           TIMESTAMPTZ NOT NULL,
    used_at              TIMESTAMPTZ,
    CONSTRAINT ck_approval_tokens_max_discount CHECK (max_discount_percent > 0 AND max_discount_percent <= 100)
);

CREATE UNIQUE INDEX ux_approval_tokens_hash ON approval_tokens (token_hash);
CREATE INDEX ix_approval_tokens_cashier ON approval_tokens (cashier_id, location_id, expires_at DESC);

COMMENT ON TABLE approval_tokens IS 'Single-use expiring approval tokens; only SHA-256 token hashes are stored.';

-- ---------------------------------------------------------------------------
-- sales: append-only header for completed POS sales.
-- ---------------------------------------------------------------------------
CREATE TABLE sales (
    id                   UUID PRIMARY KEY,
    receipt_number       VARCHAR(30) NOT NULL,
    location_id          UUID NOT NULL REFERENCES locations (id),
    cash_session_id      UUID NOT NULL REFERENCES cash_sessions (id),
    cashier_id           UUID NOT NULL REFERENCES app_users (id),
    subtotal             NUMERIC(14,2) NOT NULL,
    promotion_discount   NUMERIC(14,2) NOT NULL DEFAULT 0,
    manual_discount      NUMERIC(14,2) NOT NULL DEFAULT 0,
    bill_discount_type   VARCHAR(20),
    bill_discount_value  NUMERIC(14,2),
    tax_total            NUMERIC(14,2) NOT NULL DEFAULT 0,
    total                NUMERIC(14,2) NOT NULL,
    payment_method       VARCHAR(20) NOT NULL,
    discount_approved_by UUID REFERENCES app_users (id),
    approval_token_id    UUID REFERENCES approval_tokens (id),
    idempotency_key      VARCHAR(120) NOT NULL,
    request_hash         VARCHAR(128) NOT NULL,
    note                 TEXT,
    demo_key             VARCHAR(60),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_sales_payment_method CHECK (payment_method IN ('CASH', 'BKASH', 'NAGAD', 'CARD')),
    CONSTRAINT ck_sales_bill_discount_type CHECK (bill_discount_type IS NULL OR bill_discount_type IN ('PERCENT', 'FIXED')),
    CONSTRAINT ck_sales_totals_non_negative CHECK (subtotal >= 0 AND promotion_discount >= 0 AND manual_discount >= 0 AND tax_total >= 0 AND total >= 0)
);

CREATE UNIQUE INDEX ux_sales_receipt_number ON sales (receipt_number);
CREATE UNIQUE INDEX ux_sales_idempotency ON sales (cashier_id, idempotency_key);
CREATE UNIQUE INDEX ux_sales_demo_key ON sales (demo_key) WHERE demo_key IS NOT NULL;
CREATE INDEX ix_sales_location_created ON sales (location_id, created_at DESC);
CREATE INDEX ix_sales_session ON sales (cash_session_id);
CREATE INDEX ix_sales_cashier_created ON sales (cashier_id, created_at DESC);

COMMENT ON TABLE sales IS 'Append-only POS sales. Corrections happen via sale_returns.';

CREATE TRIGGER trg_sales_append_only
    BEFORE UPDATE OR DELETE ON sales
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- sale_lines: append-only line items with product and cost snapshots.
-- ---------------------------------------------------------------------------
CREATE TABLE sale_lines (
    id                      UUID PRIMARY KEY,
    sale_id                 UUID NOT NULL REFERENCES sales (id),
    line_index              INT NOT NULL,
    variant_id              UUID NOT NULL REFERENCES product_variants (id),
    product_name_snapshot   VARCHAR(160) NOT NULL,
    sku_snapshot            VARCHAR(60) NOT NULL,
    barcode_snapshot        VARCHAR(64),
    color_snapshot          VARCHAR(60),
    size_snapshot           VARCHAR(60),
    quantity                INT NOT NULL,
    list_price              NUMERIC(14,2) NOT NULL,
    promotion_id            UUID REFERENCES promotions (id),
    promotion_name_snapshot VARCHAR(160),
    promotion_discount      NUMERIC(14,2) NOT NULL DEFAULT 0,
    manual_discount_type    VARCHAR(20),
    manual_discount_value   NUMERIC(14,2),
    manual_discount         NUMERIC(14,2) NOT NULL DEFAULT 0,
    bill_discount_allocated NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount_applied_by     UUID REFERENCES app_users (id),
    tax_rate_percent        NUMERIC(5,2) NOT NULL DEFAULT 0,
    tax_inclusive           BOOLEAN NOT NULL DEFAULT FALSE,
    tax_amount              NUMERIC(14,2) NOT NULL DEFAULT 0,
    net_unit_price          NUMERIC(14,2) NOT NULL,
    line_subtotal           NUMERIC(14,2) NOT NULL,
    line_total              NUMERIC(14,2) NOT NULL,
    unit_cost_snapshot      NUMERIC(14,2),
    below_cost              BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_sale_lines_quantity CHECK (quantity > 0),
    CONSTRAINT ck_sale_lines_manual_discount_type CHECK (manual_discount_type IS NULL OR manual_discount_type IN ('PERCENT', 'FIXED'))
);

CREATE UNIQUE INDEX ux_sale_lines_order ON sale_lines (sale_id, line_index);
CREATE INDEX ix_sale_lines_variant ON sale_lines (variant_id);

COMMENT ON TABLE sale_lines IS 'Append-only POS sale lines. unit_cost_snapshot is internal and never exposed without COST_VIEW.';

CREATE TRIGGER trg_sale_lines_append_only
    BEFORE UPDATE OR DELETE ON sale_lines
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- sale_payments: append-only single payment record per sale.
-- ---------------------------------------------------------------------------
CREATE TABLE sale_payments (
    id                  UUID PRIMARY KEY,
    sale_id             UUID NOT NULL REFERENCES sales (id),
    method              VARCHAR(20) NOT NULL,
    amount              NUMERIC(14,2) NOT NULL,
    tendered            NUMERIC(14,2),
    change_given        NUMERIC(14,2) NOT NULL DEFAULT 0,
    reference           VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_sale_payments_method CHECK (method IN ('CASH', 'BKASH', 'NAGAD', 'CARD')),
    CONSTRAINT ck_sale_payments_amount CHECK (amount >= 0 AND change_given >= 0)
);

CREATE UNIQUE INDEX ux_sale_payments_sale ON sale_payments (sale_id);
CREATE UNIQUE INDEX ux_sale_payments_trx_id
    ON sale_payments (method, reference)
    WHERE reference IS NOT NULL AND method IN ('BKASH', 'NAGAD');

COMMENT ON TABLE sale_payments IS 'Append-only payment records. BKASH/NAGAD TrxIDs are globally unique across all locations.';

CREATE TRIGGER trg_sale_payments_append_only
    BEFORE UPDATE OR DELETE ON sale_payments
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- sale_returns and sale_return_lines: append-only return records.
-- ---------------------------------------------------------------------------
CREATE TABLE sale_returns (
    id                  UUID PRIMARY KEY,
    return_number       VARCHAR(20) NOT NULL,
    sale_id             UUID NOT NULL REFERENCES sales (id),
    location_id         UUID NOT NULL REFERENCES locations (id),
    reason              VARCHAR(30) NOT NULL,
    note                TEXT,
    refund_method       VARCHAR(20) NOT NULL,
    refund_amount       NUMERIC(14,2) NOT NULL,
    cash_session_id     UUID REFERENCES cash_sessions (id),
    idempotency_key     VARCHAR(120) NOT NULL,
    request_hash        VARCHAR(128) NOT NULL,
    created_by          UUID NOT NULL REFERENCES app_users (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    demo_key            VARCHAR(60),
    CONSTRAINT ck_sale_returns_reason CHECK (reason IN ('CHANGED_MIND', 'WRONG_SIZE', 'DEFECTIVE', 'OTHER')),
    CONSTRAINT ck_sale_returns_method CHECK (refund_method IN ('CASH', 'BKASH', 'NAGAD', 'CARD')),
    CONSTRAINT ck_sale_returns_amount CHECK (refund_amount >= 0)
);

CREATE UNIQUE INDEX ux_sale_returns_number ON sale_returns (return_number);
CREATE UNIQUE INDEX ux_sale_returns_idempotency ON sale_returns (created_by, idempotency_key);
CREATE UNIQUE INDEX ux_sale_returns_demo_key ON sale_returns (demo_key) WHERE demo_key IS NOT NULL;
CREATE INDEX ix_sale_returns_sale ON sale_returns (sale_id, created_at DESC);
CREATE INDEX ix_sale_returns_session ON sale_returns (cash_session_id);

COMMENT ON TABLE sale_returns IS 'Append-only return records against completed sales.';

CREATE TRIGGER trg_sale_returns_append_only
    BEFORE UPDATE OR DELETE ON sale_returns
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

CREATE TABLE sale_return_lines (
    id                  UUID PRIMARY KEY,
    sale_return_id      UUID NOT NULL REFERENCES sale_returns (id),
    sale_line_id        UUID NOT NULL REFERENCES sale_lines (id),
    variant_id          UUID NOT NULL REFERENCES product_variants (id),
    quantity            INT NOT NULL,
    refund_amount       NUMERIC(14,2) NOT NULL,
    disposition         VARCHAR(20) NOT NULL,
    CONSTRAINT ck_sale_return_lines_quantity CHECK (quantity > 0),
    CONSTRAINT ck_sale_return_lines_amount CHECK (refund_amount >= 0),
    CONSTRAINT ck_sale_return_lines_disposition CHECK (disposition IN ('RESTOCK', 'INSPECTION'))
);

CREATE UNIQUE INDEX ux_sale_return_lines_pair ON sale_return_lines (sale_return_id, sale_line_id);
CREATE INDEX ix_sale_return_lines_sale_line ON sale_return_lines (sale_line_id);

COMMENT ON TABLE sale_return_lines IS 'Append-only line items for POS returns.';

CREATE TRIGGER trg_sale_return_lines_append_only
    BEFORE UPDATE OR DELETE ON sale_return_lines
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- receipt_reprints: append-only log of duplicate receipt prints.
-- ---------------------------------------------------------------------------
CREATE TABLE receipt_reprints (
    id                  UUID PRIMARY KEY,
    sale_id             UUID NOT NULL REFERENCES sales (id),
    printed_by          UUID NOT NULL REFERENCES app_users (id),
    printed_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_receipt_reprints_sale ON receipt_reprints (sale_id, printed_at ASC);

COMMENT ON TABLE receipt_reprints IS 'Append-only log of receipt reprints. First print is not logged.';

CREATE TRIGGER trg_receipt_reprints_append_only
    BEFORE UPDATE OR DELETE ON receipt_reprints
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();
