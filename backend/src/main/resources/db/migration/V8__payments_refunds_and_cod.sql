-- ===========================================================================
-- Merqo V8: Order payments, immutable verification decisions, refunds,
-- COD collections, courier settlements, and mock provider transactions.
--
-- Rules that shape this file:
--   * V1 to V7 are never edited. Everything new goes into this version.
--   * UUID keys, TIMESTAMPTZ timestamps stored in UTC.
--   * Human readable numbers from sequences: PAY-000001, RFD-000001, STL-000001.
--   * Seven tables are append-only via merqo_reject_append_only_mutation():
--     order_payments, payment_decisions, refund_requests, refund_events,
--     cod_collections, courier_settlements, courier_settlement_lines.
--   * mock_provider_transactions is mutable simulator state (mock mode only).
--   * Money is NUMERIC(14,2). Compatible with Supabase PostgreSQL.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Sequences for human readable payment, refund, and settlement numbers.
-- ---------------------------------------------------------------------------
CREATE SEQUENCE payment_number_sequence START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE refund_number_sequence START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE settlement_number_sequence START WITH 1 INCREMENT BY 1;

-- ---------------------------------------------------------------------------
-- order_payments: append-only customer advance/prepayment records on orders.
-- ---------------------------------------------------------------------------
CREATE TABLE order_payments (
    id              UUID PRIMARY KEY,
    payment_number  VARCHAR(30)    NOT NULL,
    order_id        UUID           NOT NULL REFERENCES orders (id),
    method          VARCHAR(20)    NOT NULL,
    amount          NUMERIC(14,2)  NOT NULL,
    reference       VARCHAR(64),
    source          VARCHAR(20)    NOT NULL,
    payer_note      TEXT,
    recorded_by     UUID           NOT NULL REFERENCES app_users (id),
    recorded_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    demo_key        VARCHAR(60),
    CONSTRAINT ck_order_payments_method CHECK (method IN ('BKASH', 'NAGAD', 'CARD', 'BANK')),
    CONSTRAINT ck_order_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_order_payments_source CHECK (source IN ('WEBSITE_CLAIM', 'STAFF_ENTERED'))
);

CREATE UNIQUE INDEX ux_order_payments_number ON order_payments (payment_number);
CREATE UNIQUE INDEX ux_order_payments_demo_key
    ON order_payments (demo_key) WHERE demo_key IS NOT NULL;
CREATE INDEX ix_order_payments_order ON order_payments (order_id, recorded_at ASC);
CREATE INDEX ix_order_payments_method_ref
    ON order_payments (method, reference) WHERE reference IS NOT NULL;
CREATE INDEX ix_order_payments_recorded_at ON order_payments (recorded_at DESC);

COMMENT ON TABLE order_payments IS 'Append-only payment records for online and manual orders. Verification status derives from payment_decisions.';

CREATE TRIGGER trg_order_payments_append_only
    BEFORE UPDATE OR DELETE ON order_payments
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- payment_decisions: append-only one-time decision per order_payments row.
-- ---------------------------------------------------------------------------
CREATE TABLE payment_decisions (
    id                  UUID PRIMARY KEY,
    payment_id          UUID        NOT NULL REFERENCES order_payments (id),
    decision            VARCHAR(20) NOT NULL,
    decided_by          UUID        REFERENCES app_users (id),
    decider_kind        VARCHAR(40) NOT NULL,
    evidence            TEXT,
    provider_reference  VARCHAR(120),
    decided_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_payment_decisions_payment UNIQUE (payment_id),
    CONSTRAINT ck_payment_decisions_decision CHECK (decision IN ('VERIFIED', 'REJECTED'))
);

CREATE INDEX ix_payment_decisions_decided_at ON payment_decisions (decided_at DESC);

COMMENT ON TABLE payment_decisions IS 'Append-only immutable verification decisions; rejects release BKASH/NAGAD references.';

CREATE TRIGGER trg_payment_decisions_append_only
    BEFORE UPDATE OR DELETE ON payment_decisions
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- refund_requests: append-only refund requests on orders with verified pay.
-- ---------------------------------------------------------------------------
CREATE TABLE refund_requests (
    id              UUID PRIMARY KEY,
    refund_number   VARCHAR(30)   NOT NULL,
    order_id        UUID          NOT NULL REFERENCES orders (id),
    amount          NUMERIC(14,2) NOT NULL,
    reason          VARCHAR(30)   NOT NULL,
    note            TEXT,
    requested_by    UUID          NOT NULL REFERENCES app_users (id),
    requested_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    demo_key        VARCHAR(60),
    CONSTRAINT ck_refund_requests_amount CHECK (amount > 0),
    CONSTRAINT ck_refund_requests_reason CHECK (
        reason IN ('ORDER_CANCELLED', 'ORDER_DECLINED', 'OVERPAYMENT', 'CUSTOMER_REQUEST', 'OTHER')
    )
);

CREATE UNIQUE INDEX ux_refund_requests_number ON refund_requests (refund_number);
CREATE UNIQUE INDEX ux_refund_requests_demo_key
    ON refund_requests (demo_key) WHERE demo_key IS NOT NULL;
CREATE INDEX ix_refund_requests_order ON refund_requests (order_id, requested_at ASC);
CREATE INDEX ix_refund_requests_requested_at ON refund_requests (requested_at DESC);

COMMENT ON TABLE refund_requests IS 'Append-only refund requests for online/manual orders; lifecycle status derives from refund_events.';

CREATE TRIGGER trg_refund_requests_append_only
    BEFORE UPDATE OR DELETE ON refund_requests
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- refund_events: append-only lifecycle transitions on refund_requests.
-- ---------------------------------------------------------------------------
CREATE TABLE refund_events (
    id                  UUID PRIMARY KEY,
    refund_id           UUID        NOT NULL REFERENCES refund_requests (id),
    event_type          VARCHAR(20) NOT NULL,
    actor_id            UUID        NOT NULL REFERENCES app_users (id),
    note                TEXT,
    payout_method       VARCHAR(20),
    payout_reference    VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_refund_events_refund_type UNIQUE (refund_id, event_type),
    CONSTRAINT ck_refund_events_type CHECK (event_type IN ('APPROVED', 'REJECTED', 'PAID')),
    CONSTRAINT ck_refund_events_payout_method CHECK (
        payout_method IS NULL OR payout_method IN ('BKASH', 'NAGAD', 'CARD', 'BANK', 'CASH')
    )
);

CREATE UNIQUE INDEX ux_refund_events_payout_ref
    ON refund_events (payout_method, payout_reference)
    WHERE payout_reference IS NOT NULL;
CREATE INDEX ix_refund_events_refund ON refund_events (refund_id, created_at ASC);
CREATE INDEX ix_refund_events_created_at ON refund_events (created_at DESC);

COMMENT ON TABLE refund_events IS 'Append-only lifecycle events (APPROVED, REJECTED, PAID) for refund requests.';

CREATE TRIGGER trg_refund_events_append_only
    BEFORE UPDATE OR DELETE ON refund_events
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- cod_collections: append-only COD cash collection records at courier level.
-- ---------------------------------------------------------------------------
CREATE TABLE cod_collections (
    id              UUID PRIMARY KEY,
    order_id        UUID          NOT NULL REFERENCES orders (id),
    courier         VARCHAR(20)   NOT NULL,
    amount          NUMERIC(14,2) NOT NULL,
    collected_on    DATE          NOT NULL,
    recorded_by     UUID          NOT NULL REFERENCES app_users (id),
    recorded_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    note            TEXT,
    demo_key        VARCHAR(60),
    CONSTRAINT ck_cod_collections_courier CHECK (courier IN ('PATHAO', 'STEADFAST')),
    CONSTRAINT ck_cod_collections_amount CHECK (amount > 0)
);

CREATE UNIQUE INDEX ux_cod_collections_demo_key
    ON cod_collections (demo_key) WHERE demo_key IS NOT NULL;
CREATE INDEX ix_cod_collections_order ON cod_collections (order_id, collected_on ASC);
CREATE INDEX ix_cod_collections_courier ON cod_collections (courier, collected_on DESC);

COMMENT ON TABLE cod_collections IS 'Append-only COD collections recorded before courier settlement.';

CREATE TRIGGER trg_cod_collections_append_only
    BEFORE UPDATE OR DELETE ON cod_collections
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- courier_settlements: append-only courier settlement batch headers.
-- ---------------------------------------------------------------------------
CREATE TABLE courier_settlements (
    id                  UUID PRIMARY KEY,
    settlement_number   VARCHAR(30)   NOT NULL,
    courier             VARCHAR(20)   NOT NULL,
    reference           VARCHAR(80)   NOT NULL,
    received_on         DATE          NOT NULL,
    receive_method      VARCHAR(20)   NOT NULL,
    amount_received     NUMERIC(14,2) NOT NULL,
    fees_total          NUMERIC(14,2) NOT NULL,
    difference          NUMERIC(14,2) NOT NULL,
    difference_kind     VARCHAR(10)   NOT NULL,
    note                TEXT,
    recorded_by         UUID          NOT NULL REFERENCES app_users (id),
    recorded_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    demo_key            VARCHAR(60),
    CONSTRAINT ux_courier_settlements_courier_ref UNIQUE (courier, reference),
    CONSTRAINT ck_courier_settlements_courier CHECK (courier IN ('PATHAO', 'STEADFAST')),
    CONSTRAINT ck_courier_settlements_method CHECK (receive_method IN ('BANK', 'BKASH', 'NAGAD', 'CASH')),
    CONSTRAINT ck_courier_settlements_amounts CHECK (amount_received >= 0 AND fees_total >= 0),
    CONSTRAINT ck_courier_settlements_diff_kind CHECK (difference_kind IN ('NONE', 'OVER', 'SHORT'))
);

CREATE UNIQUE INDEX ux_courier_settlements_number ON courier_settlements (settlement_number);
CREATE UNIQUE INDEX ux_courier_settlements_demo_key
    ON courier_settlements (demo_key) WHERE demo_key IS NOT NULL;
CREATE INDEX ix_courier_settlements_received
    ON courier_settlements (received_on DESC, recorded_at DESC);

COMMENT ON TABLE courier_settlements IS 'Append-only courier COD settlement batch headers.';

CREATE TRIGGER trg_courier_settlements_append_only
    BEFORE UPDATE OR DELETE ON courier_settlements
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- courier_settlement_lines: append-only per-order allocation in a settlement.
-- ---------------------------------------------------------------------------
CREATE TABLE courier_settlement_lines (
    id                      UUID PRIMARY KEY,
    settlement_id           UUID          NOT NULL REFERENCES courier_settlements (id),
    order_id                UUID          NOT NULL REFERENCES orders (id),
    cod_collected_amount    NUMERIC(14,2) NOT NULL,
    fee                     NUMERIC(14,2) NOT NULL,
    net                     NUMERIC(14,2) NOT NULL,
    CONSTRAINT ux_courier_settlement_lines_settlement_order UNIQUE (settlement_id, order_id),
    CONSTRAINT ck_courier_settlement_lines_amounts CHECK (
        cod_collected_amount > 0 AND fee >= 0 AND net >= 0
    )
);

CREATE INDEX ix_courier_settlement_lines_order ON courier_settlement_lines (order_id);

COMMENT ON TABLE courier_settlement_lines IS 'Append-only per-order lines belonging to a courier settlement batch.';

CREATE TRIGGER trg_courier_settlement_lines_append_only
    BEFORE UPDATE OR DELETE ON courier_settlement_lines
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_append_only_mutation();

-- ---------------------------------------------------------------------------
-- mock_provider_transactions: simulator state in mock mode only (mutable).
-- ---------------------------------------------------------------------------
CREATE TABLE mock_provider_transactions (
    id                      UUID PRIMARY KEY,
    method                  VARCHAR(20)   NOT NULL,
    reference               VARCHAR(64)   NOT NULL,
    amount                  NUMERIC(14,2) NOT NULL,
    sender_hint             VARCHAR(80),
    claimed_by_payment_id   UUID          REFERENCES order_payments (id),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_mock_provider_tx_method_ref UNIQUE (method, reference),
    CONSTRAINT ck_mock_provider_tx_method CHECK (method IN ('BKASH', 'NAGAD', 'CARD', 'BANK')),
    CONSTRAINT ck_mock_provider_tx_amount CHECK (amount > 0)
);

COMMENT ON TABLE mock_provider_transactions IS 'Simulated provider transactions created in demo mode for MockPaymentVerifier.';
