-- ===========================================================================
-- Merqo V7: Integration clients, order intake inbox, customers, online &
-- manual orders, order lines, append-only status history & call logs.
--
-- Rules that shape this file:
--   * V1 to V6 are never edited. Everything new goes into this version.
--   * UUID keys, TIMESTAMPTZ timestamps stored in UTC.
--   * Human readable numbers come from sequences: ORD-000001.
--   * Two tables are append-only via merqo_reject_append_only_mutation():
--     order_status_history, order_call_logs.
--   * Compatible with Supabase PostgreSQL (no superuser-only extensions).
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Sequence for human readable order numbers (ORD-000001).
-- ---------------------------------------------------------------------------
CREATE SEQUENCE order_number_sequence START WITH 1 INCREMENT BY 1;

-- ---------------------------------------------------------------------------
-- integration_clients: API key owners for external website integrations.
-- ---------------------------------------------------------------------------
CREATE TABLE integration_clients (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(120) NOT NULL,
    key_id              VARCHAR(80)  NOT NULL,
    secret_encrypted    TEXT         NOT NULL,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by          UUID         REFERENCES app_users (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at        TIMESTAMPTZ,
    rotated_at          TIMESTAMPTZ,
    demo_key            VARCHAR(60)
);

CREATE UNIQUE INDEX ux_integration_clients_key_id ON integration_clients (key_id);
CREATE UNIQUE INDEX ux_integration_clients_demo_key
    ON integration_clients (demo_key) WHERE demo_key IS NOT NULL;

COMMENT ON TABLE integration_clients IS 'API key clients for external website order intake; secrets are AES-GCM encrypted.';

-- ---------------------------------------------------------------------------
-- integration_rate_attempts: DB-backed 60 req/min sliding window per API key.
-- ---------------------------------------------------------------------------
CREATE TABLE integration_rate_attempts (
    id                  UUID PRIMARY KEY,
    client_id           UUID        NOT NULL REFERENCES integration_clients (id) ON DELETE CASCADE,
    attempted_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_integration_rate_client_time
    ON integration_rate_attempts (client_id, attempted_at DESC);

COMMENT ON TABLE integration_rate_attempts IS 'Per-client request timestamps used for 60 req/min rate limiting.';

-- ---------------------------------------------------------------------------
-- customers: keyed by normalized Bangladeshi mobile phone.
-- ---------------------------------------------------------------------------
CREATE TABLE customers (
    id                  UUID PRIMARY KEY,
    normalized_phone    VARCHAR(20)  NOT NULL,
    name                VARCHAR(160) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_customers_normalized_phone ON customers (normalized_phone);

COMMENT ON TABLE customers IS 'Customer records keyed by normalized BD phone. History is derived from orders.';

-- ---------------------------------------------------------------------------
-- orders: online and manual orders fulfilled at the online warehouse.
-- ---------------------------------------------------------------------------
CREATE TABLE orders (
    id                          UUID PRIMARY KEY,
    order_number                VARCHAR(30)   NOT NULL,
    channel                     VARCHAR(20)   NOT NULL,
    status                      VARCHAR(30)   NOT NULL,
    location_id                 UUID          NOT NULL REFERENCES locations (id),
    customer_id                 UUID          REFERENCES customers (id),
    customer_name               VARCHAR(160)  NOT NULL,
    phone_raw                   VARCHAR(40)   NOT NULL,
    phone_normalized            VARCHAR(20),
    address                     TEXT          NOT NULL,
    district                    VARCHAR(80),
    note                        TEXT,
    external_order_id           VARCHAR(120),
    integration_client_id       UUID          REFERENCES integration_clients (id),
    source_label                VARCHAR(80),
    subtotal                    NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount_total              NUMERIC(14,2) NOT NULL DEFAULT 0,
    shipping_fee                NUMERIC(14,2) NOT NULL DEFAULT 0,
    total                       NUMERIC(14,2) NOT NULL DEFAULT 0,
    payment_mode                VARCHAR(30)   NOT NULL,
    advance_amount              NUMERIC(14,2) NOT NULL DEFAULT 0,
    cod_amount                  NUMERIC(14,2) NOT NULL DEFAULT 0,
    claimed_advance_method      VARCHAR(30),
    claimed_advance_reference   VARCHAR(120),
    price_mismatch              BOOLEAN       NOT NULL DEFAULT FALSE,
    follow_up_at                TIMESTAMPTZ,
    cancellation_requested_at   TIMESTAMPTZ,
    cancellation_request_note   TEXT,
    created_by                  UUID          REFERENCES app_users (id),
    demo_key                    VARCHAR(60),
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    confirmed_at                TIMESTAMPTZ,
    closed_at                   TIMESTAMPTZ,
    CONSTRAINT ck_orders_channel CHECK (channel IN ('ONLINE', 'MANUAL')),
    CONSTRAINT ck_orders_status CHECK (status IN (
        'PENDING_CONFIRMATION', 'FOLLOW_UP', 'CONFIRMED', 'DECLINED', 'CANCELLED',
        'READY_TO_SHIP', 'SHIPPED', 'DELIVERED', 'REFUSED', 'RETURNED'
    )),
    CONSTRAINT ck_orders_payment_mode CHECK (payment_mode IN ('FULL_PREPAID', 'COD', 'ADVANCE_COD')),
    CONSTRAINT ck_orders_claimed_method CHECK (
        claimed_advance_method IS NULL OR claimed_advance_method IN ('BKASH', 'NAGAD', 'CARD', 'BANK')
    ),
    CONSTRAINT ck_orders_amounts_non_negative CHECK (
        subtotal >= 0 AND discount_total >= 0 AND shipping_fee >= 0 AND total >= 0
        AND advance_amount >= 0 AND cod_amount >= 0
    )
);

CREATE UNIQUE INDEX ux_orders_number ON orders (order_number);
CREATE UNIQUE INDEX ux_orders_client_external
    ON orders (integration_client_id, external_order_id)
    WHERE integration_client_id IS NOT NULL AND external_order_id IS NOT NULL;
CREATE UNIQUE INDEX ux_orders_demo_key
    ON orders (demo_key) WHERE demo_key IS NOT NULL;
CREATE INDEX ix_orders_location_status_created
    ON orders (location_id, status, created_at DESC);
CREATE INDEX ix_orders_phone_created
    ON orders (phone_normalized, created_at DESC)
    WHERE phone_normalized IS NOT NULL;
CREATE INDEX ix_orders_customer ON orders (customer_id) WHERE customer_id IS NOT NULL;
CREATE INDEX ix_orders_created_at ON orders (created_at DESC);

COMMENT ON TABLE orders IS 'Online and manual orders fulfilled at the online-fulfillment warehouse.';

-- ---------------------------------------------------------------------------
-- order_lines: product and price snapshots at order time.
-- ---------------------------------------------------------------------------
CREATE TABLE order_lines (
    id                      UUID PRIMARY KEY,
    order_id                UUID          NOT NULL REFERENCES orders (id),
    line_index              INT           NOT NULL,
    variant_id              UUID          NOT NULL REFERENCES product_variants (id),
    product_name_snapshot   VARCHAR(160)  NOT NULL,
    sku_snapshot            VARCHAR(60)   NOT NULL,
    barcode_snapshot        VARCHAR(64),
    color_snapshot          VARCHAR(60),
    size_snapshot           VARCHAR(60),
    quantity                INT           NOT NULL,
    unit_price              NUMERIC(14,2) NOT NULL,
    line_total              NUMERIC(14,2) NOT NULL,
    catalog_online_price    NUMERIC(14,2),
    CONSTRAINT ck_order_lines_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_lines_prices CHECK (unit_price >= 0 AND line_total >= 0)
);

CREATE UNIQUE INDEX ux_order_lines_index ON order_lines (order_id, line_index);
CREATE INDEX ix_order_lines_variant ON order_lines (variant_id);

COMMENT ON TABLE order_lines IS 'Order line items with SKU/product snapshots and catalog ONLINE price at intake.';

-- ---------------------------------------------------------------------------
-- order_status_history: append-only order lifecycle log.
-- ---------------------------------------------------------------------------
CREATE TABLE order_status_history (
    id                  UUID PRIMARY KEY,
    order_id            UUID        NOT NULL REFERENCES orders (id),
    from_status         VARCHAR(30),
    to_status           VARCHAR(30) NOT NULL,
    changed_by          UUID        REFERENCES app_users (id),
    actor_label         VARCHAR(80) NOT NULL,
    reason              VARCHAR(60),
    note                TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_order_status_history_order
    ON order_status_history (order_id, created_at ASC);

COMMENT ON TABLE order_status_history IS 'Append-only audit trail of order status transitions.';

CREATE TRIGGER trg_order_status_history_append_only
    BEFORE UPDATE OR DELETE ON order_status_history
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- order_call_logs: append-only phone call attempt records.
-- ---------------------------------------------------------------------------
CREATE TABLE order_call_logs (
    id                  UUID PRIMARY KEY,
    order_id            UUID        NOT NULL REFERENCES orders (id),
    attempt_number      INT         NOT NULL,
    result              VARCHAR(40) NOT NULL,
    note                TEXT,
    follow_up_at        TIMESTAMPTZ,
    called_by           UUID        NOT NULL REFERENCES app_users (id),
    called_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_order_call_logs_attempt CHECK (attempt_number > 0),
    CONSTRAINT ck_order_call_logs_result CHECK (
        result IN ('ANSWERED', 'NO_ANSWER', 'BUSY', 'WRONG_NUMBER', 'CALL_BACK_REQUESTED')
    )
);

CREATE UNIQUE INDEX ux_order_call_logs_attempt ON order_call_logs (order_id, attempt_number);
CREATE INDEX ix_order_call_logs_order ON order_call_logs (order_id, called_at ASC);

COMMENT ON TABLE order_call_logs IS 'Append-only customer phone call log per order.';

CREATE TRIGGER trg_order_call_logs_append_only
    BEFORE UPDATE OR DELETE ON order_call_logs
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- integration_inbox: idempotency & hash-only intake state (no raw PII body).
-- ---------------------------------------------------------------------------
CREATE TABLE integration_inbox (
    id                  UUID PRIMARY KEY,
    client_id           UUID         NOT NULL REFERENCES integration_clients (id),
    external_order_id   VARCHAR(120) NOT NULL,
    payload_hash        VARCHAR(128) NOT NULL,
    status              VARCHAR(30)  NOT NULL,
    error_code          VARCHAR(80),
    error_message       TEXT,
    order_id            UUID         REFERENCES orders (id),
    attempts            INT          NOT NULL DEFAULT 1,
    received_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at        TIMESTAMPTZ,
    CONSTRAINT ck_integration_inbox_status CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED')),
    CONSTRAINT ck_integration_inbox_attempts CHECK (attempts > 0)
);

CREATE UNIQUE INDEX ux_integration_inbox_client_external
    ON integration_inbox (client_id, external_order_id);

COMMENT ON TABLE integration_inbox IS 'Website order intake idempotency state; keeps SHA-256 hash only, never raw request payload.';
