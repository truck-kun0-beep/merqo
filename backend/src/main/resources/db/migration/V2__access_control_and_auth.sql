-- ===========================================================================
-- Merqo V2: authentication, users, locations, permissions, audit.
--
-- Rules that shape this file:
--   * V1__baseline_schema.sql is never edited. It may already be applied on the
--     student's databases, so everything new goes into a new version.
--   * UUID keys, TIMESTAMPTZ timestamps stored in UTC, human readable codes
--     (employee_code) generated from a sequence.
--   * Master data is deactivated with is_active, never deleted.
--   * Assignments are ended with ended_at, never deleted, so history is kept.
--   * The audit log is append only; a trigger enforces that in the database as
--     well as in the code.
--   * Permission codes are stored as text and validated against the Java enum.
--     An unknown code must not crash the application, so it is only logged.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- The employee code sequence. Human readable staff numbers such as EMP-0001.
-- ---------------------------------------------------------------------------
CREATE SEQUENCE employee_code_sequence START WITH 1 INCREMENT BY 1;

-- ---------------------------------------------------------------------------
-- app_users: extend the V1 table to the full account model.
--
-- V1 already created app_users with id, email, password_hash, full_name, role,
-- is_active, last_login_at, created_at and updated_at. The columns below are
-- added or changed here.
--
-- email becomes nullable because a user may sign in with a username only.
-- role becomes account_type, because "role" is used for other things elsewhere.
-- ---------------------------------------------------------------------------
ALTER TABLE app_users ALTER COLUMN email DROP NOT NULL;
ALTER TABLE app_users RENAME COLUMN role TO account_type;

ALTER TABLE app_users
    ADD COLUMN username                    VARCHAR(40),
    ADD COLUMN phone                       VARCHAR(40),
    ADD COLUMN must_change_password        BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN failed_login_count          INT          NOT NULL DEFAULT 0,
    ADD COLUMN locked_until                TIMESTAMPTZ,
    ADD COLUMN employee_code               VARCHAR(20),
    ADD COLUMN max_discount_percent        NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN is_demo                     BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN demo_key                    VARCHAR(60),
    ADD COLUMN created_by                  UUID REFERENCES app_users (id);

-- The username is required and unique, and always stored lowercase.
UPDATE app_users SET username = split_part(email, '@', 1) WHERE username IS NULL;
ALTER TABLE app_users ALTER COLUMN username SET NOT NULL;

-- The username is unique, case insensitively, because it is stored lowercase.
CREATE UNIQUE INDEX ux_app_users_username ON app_users (username);

-- The email is unique, case insensitively, but only when it is present.
CREATE UNIQUE INDEX ux_app_users_email_lower ON app_users (lower(email)) WHERE email IS NOT NULL;

-- The demo key is unique when it is present.
CREATE UNIQUE INDEX ux_app_users_demo_key ON app_users (demo_key) WHERE demo_key IS NOT NULL;

-- The employee code comes from the sequence and is unique.
CREATE UNIQUE INDEX ux_app_users_employee_code ON app_users (employee_code) WHERE employee_code IS NOT NULL;

ALTER TABLE app_users ALTER COLUMN employee_code SET DEFAULT 'EMP-' || lpad(nextval('employee_code_sequence')::text, 4, '0');

-- At most one owner. The partial unique index is the guard that makes the
-- first owner bootstrap race safe.
CREATE UNIQUE INDEX ux_app_users_single_owner ON app_users ((account_type)) WHERE account_type = 'OWNER';

-- The account type may only hold the three known values.
ALTER TABLE app_users ADD CONSTRAINT ck_app_users_account_type
    CHECK (account_type IN ('OWNER', 'MANAGER', 'STAFF'));

-- The discount ceiling may not be negative and may not exceed 100 percent.
ALTER TABLE app_users ADD CONSTRAINT ck_app_users_max_discount
    CHECK (max_discount_percent >= 0 AND max_discount_percent <= 100);

COMMENT ON TABLE app_users IS 'Accounts. The owner is unique; everybody else is scoped to locations.';

-- ---------------------------------------------------------------------------
-- locations: extend the V1 table.
-- ---------------------------------------------------------------------------
ALTER TABLE locations
    ADD COLUMN is_online_fulfillment BOOLEAN NOT NULL DEFAULT FALSE;

-- Online fulfilment is only legal for a warehouse.
ALTER TABLE locations ADD CONSTRAINT ck_locations_online_fulfillment
    CHECK (is_online_fulfillment = FALSE OR location_type = 'WAREHOUSE');

-- At most one active online fulfilment location.
CREATE UNIQUE INDEX ux_locations_single_online ON locations ((is_online_fulfillment))
    WHERE is_online_fulfillment = TRUE AND is_active = TRUE;

COMMENT ON COLUMN locations.is_online_fulfillment IS 'The one warehouse that fulfils online orders.';

-- ---------------------------------------------------------------------------
-- Permission templates and the permissions they hold.
-- ---------------------------------------------------------------------------
CREATE TABLE permission_templates (
    id                  UUID PRIMARY KEY,
    code                VARCHAR(60)  NOT NULL,
    name                VARCHAR(120) NOT NULL,
    description         VARCHAR(240),
    is_system           BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_permission_templates_code UNIQUE (code)
);

COMMENT ON TABLE permission_templates IS 'Named sets of permissions. The owner may create and edit them.';

CREATE TABLE template_permissions (
    template_id         UUID NOT NULL REFERENCES permission_templates (id) ON DELETE CASCADE,
    permission          VARCHAR(60) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_template_permissions PRIMARY KEY (template_id, permission)
);

COMMENT ON TABLE template_permissions IS 'The permissions of one template. Codes are validated in Java.';

-- ---------------------------------------------------------------------------
-- Which templates a user holds.
-- ---------------------------------------------------------------------------
CREATE TABLE user_templates (
    user_id             UUID NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    template_id         UUID NOT NULL REFERENCES permission_templates (id) ON DELETE CASCADE,
    assigned_by         UUID REFERENCES app_users (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_templates PRIMARY KEY (user_id, template_id)
);

COMMENT ON TABLE user_templates IS 'The templates of one user. Editing them takes effect on the next request.';

-- ---------------------------------------------------------------------------
-- Per user overrides: one ALLOW or DENY of one permission.
-- ---------------------------------------------------------------------------
CREATE TABLE user_permission_overrides (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    permission          VARCHAR(60) NOT NULL,
    effect              VARCHAR(10) NOT NULL,
    location_id         UUID REFERENCES locations (id),
    created_by          UUID REFERENCES app_users (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_permission_overrides_effect CHECK (effect IN ('ALLOW', 'DENY'))
);

-- PostgreSQL treats two NULLs as different, so a plain unique constraint would
-- allow the same global override twice. The COALESCE makes "no location" one
-- concrete value for the index.
CREATE UNIQUE INDEX ux_user_permission_overrides
    ON user_permission_overrides (
        user_id, permission, effect, COALESCE(location_id, '00000000-0000-0000-0000-000000000000'::uuid)
    );

CREATE INDEX ix_user_permission_overrides_user ON user_permission_overrides (user_id);

COMMENT ON TABLE user_permission_overrides IS 'ALLOW and DENY overrides. A DENY always beats an ALLOW.';

-- ---------------------------------------------------------------------------
-- Assignments: a user works at a location as MANAGER or STAFF.
-- ---------------------------------------------------------------------------
CREATE TABLE location_assignments (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    location_id         UUID NOT NULL REFERENCES locations (id) ON DELETE CASCADE,
    assignment_type     VARCHAR(20) NOT NULL,
    assigned_by         UUID REFERENCES app_users (id),
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at            TIMESTAMPTZ,
    CONSTRAINT ck_location_assignments_type CHECK (assignment_type IN ('MANAGER', 'STAFF'))
);

-- One active assignment per user and location.
CREATE UNIQUE INDEX ux_location_assignments_active_pair
    ON location_assignments (user_id, location_id) WHERE ended_at IS NULL;

-- One active manager per location. This is the database half of the
-- "one manager per branch" rule; the Java half lives in AssignmentRules.
CREATE UNIQUE INDEX ux_location_assignments_active_manager
    ON location_assignments (location_id) WHERE ended_at IS NULL AND assignment_type = 'MANAGER';

CREATE INDEX ix_location_assignments_user ON location_assignments (user_id, ended_at);

COMMENT ON TABLE location_assignments IS 'Who works where. An assignment is ended, never deleted.';

-- ---------------------------------------------------------------------------
-- Delegation lists: the permissions a manager may hand to a staff member.
-- ---------------------------------------------------------------------------
CREATE TABLE manager_delegations (
    manager_user_id     UUID NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    permission          VARCHAR(60) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_manager_delegations PRIMARY KEY (manager_user_id, permission)
);

COMMENT ON TABLE manager_delegations IS 'The owner decides what each manager may delegate.';

-- ---------------------------------------------------------------------------
-- Login attempts: operational data for the throttle, never business data.
-- ---------------------------------------------------------------------------
CREATE TABLE login_attempts (
    id                  UUID PRIMARY KEY,
    identifier          VARCHAR(190) NOT NULL,
    ip_address          VARCHAR(64),
    success             BOOLEAN      NOT NULL,
    attempted_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_login_attempts_identifier ON login_attempts (identifier, attempted_at DESC);
CREATE INDEX ix_login_attempts_ip ON login_attempts (ip_address, attempted_at DESC);
CREATE INDEX ix_login_attempts_time ON login_attempts (attempted_at);

COMMENT ON TABLE login_attempts IS 'Operational throttle data. Older than 24 hours it is deleted by a job.';

-- ---------------------------------------------------------------------------
-- settings: the typed settings table. The setting_key is unique and the
-- value_type column records the type the value was validated against.
-- ---------------------------------------------------------------------------
DELETE FROM settings;
ALTER TABLE settings DROP CONSTRAINT IF EXISTS uk_settings_key_from;
ALTER TABLE settings ADD CONSTRAINT uk_settings_key UNIQUE (setting_key);
ALTER TABLE settings ADD CONSTRAINT ck_settings_value_type
    CHECK (value_type IN ('STRING', 'BOOLEAN', 'INTEGER', 'DECIMAL', 'AMOUNT', 'PAPER_WIDTH'));

COMMENT ON TABLE settings IS 'Typed business settings. Reading a missing key returns the Java default.';

-- ---------------------------------------------------------------------------
-- audit_logs: extend the V1 table to the full audit model.
--
-- V1 already created id, occurred_at, actor_id, actor_email, action,
-- entity_type, entity_id, old_value, new_value and correlation_id.
-- ---------------------------------------------------------------------------
ALTER TABLE audit_logs
    ADD COLUMN actor_user_id   UUID REFERENCES app_users (id),
    ADD COLUMN actor_username  VARCHAR(40),
    ADD COLUMN location_id     UUID REFERENCES locations (id),
    ADD COLUMN ip_address      VARCHAR(64),
    ADD COLUMN old_values      JSONB,
    ADD COLUMN new_values      JSONB;

CREATE INDEX ix_audit_logs_location ON audit_logs (location_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_action ON audit_logs (action, occurred_at DESC);

COMMENT ON COLUMN audit_logs.old_values IS 'The values before the change, as JSON. Secrets are redacted.';

-- The append only guard. Any UPDATE or DELETE raises an error, so the audit
-- log cannot be rewritten even by accident or by a bug in the application.
CREATE OR REPLACE FUNCTION merqo_reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'The audit log is append only; % is not allowed on audit_logs.', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION merqo_reject_audit_mutation();

COMMENT ON TRIGGER trg_audit_logs_append_only ON audit_logs IS 'Blocks UPDATE and DELETE on the audit log.';
