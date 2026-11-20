-- ===========================================================================
-- Merqo baseline schema (Flyway V1).
--
-- Rules that shape this file:
--   * The schema is owned by Flyway. Hibernate only validates it (ddl-auto=validate).
--   * Money is NUMERIC(14,2) and never float.
--   * Timestamps are TIMESTAMPTZ and always stored in UTC.
--   * Primary keys are UUIDs. Human readable numbers (receipt, order, transfer)
--     live in separate columns and are the only thing staff ever see.
--   * Append-only ledgers (stock movements, payments, audit) are never updated or
--     deleted; corrections are new rows.
--   * Master data is deactivated with is_active, never deleted.
--
-- Only the bootstrap tables belong here. Business tables (products, sales,
-- orders, ...) arrive with the prompt that needs them.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Business settings: name, address, phone, tax rate, receipt footer, thresholds.
-- Nothing in the code base may hardcode these values.
-- ---------------------------------------------------------------------------
CREATE TABLE settings (
    id                  UUID PRIMARY KEY,
    setting_key         VARCHAR(120) NOT NULL,
    setting_value       TEXT,
    value_type          VARCHAR(30)  NOT NULL DEFAULT 'STRING',
    effective_from      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_settings_key_from UNIQUE (setting_key, effective_from)
);

COMMENT ON TABLE settings IS 'Business settings read by every module. Never hardcode a business value.';

-- ---------------------------------------------------------------------------
-- Locations: one warehouse and several branches, each with independent stock.
-- ---------------------------------------------------------------------------
CREATE TABLE locations (
    id                  UUID PRIMARY KEY,
    code                VARCHAR(40)  NOT NULL,
    name                VARCHAR(120) NOT NULL,
    location_type       VARCHAR(30)  NOT NULL,
    address_line        VARCHAR(240),
    phone               VARCHAR(40),
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_locations_code UNIQUE (code),
    CONSTRAINT ck_locations_type CHECK (location_type IN ('WAREHOUSE', 'BRANCH'))
);

COMMENT ON TABLE locations IS 'Warehouse and branches. Branch stock is never pooled into online availability.';

-- ---------------------------------------------------------------------------
-- Users. The owner is created on first start from INITIAL_OWNER_* when
-- DEMO_MODE=false (Prompt 1 adds the full user and permission model).
-- ---------------------------------------------------------------------------
CREATE TABLE app_users (
    id                  UUID PRIMARY KEY,
    email               VARCHAR(190) NOT NULL,
    password_hash       VARCHAR(255) NOT NULL,
    full_name           VARCHAR(120) NOT NULL,
    role                VARCHAR(30)  NOT NULL,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_app_users_email UNIQUE (email),
    CONSTRAINT ck_app_users_role CHECK (role IN ('OWNER', 'MANAGER', 'STAFF'))
);

COMMENT ON TABLE app_users IS 'Individual accounts. Passwords are always stored as a BCrypt hash.';

-- ---------------------------------------------------------------------------
-- Audit log. Append-only: who did what, when, old value, new value.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id                  UUID PRIMARY KEY,
    occurred_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor_id            UUID,
    actor_email         VARCHAR(190),
    action              VARCHAR(80)  NOT NULL,
    entity_type         VARCHAR(60)  NOT NULL,
    entity_id           VARCHAR(60),
    old_value           TEXT,
    new_value           TEXT,
    correlation_id      VARCHAR(64),
    CONSTRAINT fk_audit_logs_actor FOREIGN KEY (actor_id) REFERENCES app_users (id)
);

CREATE INDEX ix_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX ix_audit_logs_actor ON audit_logs (actor_id, occurred_at DESC);

COMMENT ON TABLE audit_logs IS 'Append-only audit trail. Rows are never updated or deleted.';

-- ---------------------------------------------------------------------------
-- Spring Session tables. Sessions live in PostgreSQL so a login survives the
-- backend restarting, which happens constantly on a free Render instance.
-- ---------------------------------------------------------------------------
CREATE TABLE spring_session (
    primary_id          CHAR(36) NOT NULL,
    session_id          CHAR(36) NOT NULL,
    creation_time       BIGINT  NOT NULL,
    last_access_time    BIGINT  NOT NULL,
    max_inactive_interval INT   NOT NULL,
    expiry_time         BIGINT  NOT NULL,
    principal_name      VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id  CHAR(36)    NOT NULL,
    attribute_name      VARCHAR(200) NOT NULL,
    attribute_bytes     BYTEA       NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
