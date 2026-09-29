-- =====================================================================
-- V1 — Branches, users, roles, permissions, segregation of duties
--
-- SRS: FR-MD-11..13 (branches), FR-SEC-01..29 (access and audit)
--
-- Two rules are enforced here rather than in Java:
--   * a user who has signed anything can never be deleted (FR-SEC-14)
--   * incompatible role pairs are data, not code (FR-SEC-02)
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ---------------------------------------------------------------------
-- Branches. Kigali is the main branch, Rubavu the second; further
-- branches are an administrative act, not a development task.
-- ---------------------------------------------------------------------
CREATE TABLE branch (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(16)  NOT NULL UNIQUE,
    name            VARCHAR(120) NOT NULL,
    branch_type     VARCHAR(24)  NOT NULL
                    CHECK (branch_type IN ('MAIN', 'BRANCH', 'BONDED')),
    city            VARCHAR(80),
    country_code    CHAR(2)      NOT NULL DEFAULT 'RW',
    -- A bonded branch keeps duty-suspended stock and must record a customs
    -- reference on every receipt and release.
    is_bonded       BOOLEAN      NOT NULL DEFAULT FALSE,
    customs_regime  VARCHAR(60),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT branch_bonded_needs_regime
        CHECK (is_bonded = FALSE OR customs_regime IS NOT NULL)
);

COMMENT ON TABLE branch IS
  'A trading location. Stock, documents, serial sequences and users are scoped to one; item master, categories, units and customers are shared company-wide.';

-- ---------------------------------------------------------------------
-- Permissions: the smallest addressable action in a module.
-- ---------------------------------------------------------------------
CREATE TABLE permission (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(80)  NOT NULL UNIQUE,   -- e.g. 'grn.post'
    module      VARCHAR(40)  NOT NULL,          -- e.g. 'receiving'
    action      VARCHAR(24)  NOT NULL
                CHECK (action IN ('VIEW','CREATE','AMEND','SUBMIT','VERIFY',
                                  'APPROVE','RELEASE','POST','CANCEL','CONFIGURE')),
    description VARCHAR(240) NOT NULL
);

COMMENT ON TABLE permission IS
  'FR-SEC-16. Catalogued per module and action; assigned to roles through a matrix screen, never to users directly.';

-- ---------------------------------------------------------------------
-- Roles are created at runtime (FR-SEC-15). A protected role cannot be
-- deleted while assigned or named in an active workflow definition.
-- ---------------------------------------------------------------------
CREATE TABLE role (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code          VARCHAR(48)  NOT NULL UNIQUE,
    name          VARCHAR(120) NOT NULL,
    description   VARCHAR(400),
    -- Roles that exist in the 2026 policy, the 2027 restructuring, or both.
    -- A role dormant today is still selectable in a workflow definition.
    structure     VARCHAR(16)  NOT NULL DEFAULT 'BOTH'
                  CHECK (structure IN ('POLICY_2026','RESTRUCTURE_2027','BOTH')),
    is_protected  BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE role_permission (
    role_id       UUID NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permission(id) ON DELETE CASCADE,
    granted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission_id)
);

-- ---------------------------------------------------------------------
-- Users. Never deleted once they have signed or posted anything: an
-- orphaned signature makes the audit trail worthless three years later.
-- ---------------------------------------------------------------------
CREATE TABLE app_user (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username               VARCHAR(60)  NOT NULL UNIQUE,
    full_name              VARCHAR(160) NOT NULL,
    email                  VARCHAR(160),
    phone                  VARCHAR(40),
    password_hash          VARCHAR(100),
    home_branch_id         UUID REFERENCES branch(id),
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password   BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_count     SMALLINT     NOT NULL DEFAULT 0,
    locked_until           TIMESTAMPTZ,
    last_login_at          TIMESTAMPTZ,
    -- Board paper §5.1: ten consecutive working days' leave is a control.
    -- A user with no recorded absence in twelve months is reportable.
    last_leave_start       DATE,
    last_leave_end         DATE,
    deactivated_at         TIMESTAMPTZ,
    deactivated_reason     VARCHAR(240),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT app_user_inactive_has_reason
        CHECK (is_active = TRUE OR deactivated_at IS NOT NULL)
);

COMMENT ON COLUMN app_user.password_hash IS 'BCrypt. Null until the invitation is accepted.';

-- ---------------------------------------------------------------------
-- Role assignment: scoped to one or more branches, optionally dated,
-- expiring on its own (FR-SEC-18).
-- ---------------------------------------------------------------------
CREATE TABLE user_role (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES app_user(id),
    role_id         UUID NOT NULL REFERENCES role(id),
    -- NULL branch means the role applies at every branch.
    branch_id       UUID REFERENCES branch(id),
    valid_from      DATE NOT NULL DEFAULT CURRENT_DATE,
    valid_to        DATE,
    -- A delegation covering someone's mandatory leave is temporary and logged.
    is_delegation   BOOLEAN NOT NULL DEFAULT FALSE,
    delegated_for   UUID REFERENCES app_user(id),
    assigned_by     UUID REFERENCES app_user(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT user_role_dates CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE UNIQUE INDEX user_role_unique_live
    ON user_role (user_id, role_id, COALESCE(branch_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE valid_to IS NULL;

CREATE INDEX user_role_by_user ON user_role (user_id) WHERE valid_to IS NULL;

-- ---------------------------------------------------------------------
-- Segregation of duties. The Board's central finding was that one office
-- authorised, executed, held custody of, recorded and reconciled the same
-- transaction. These rows make that combination refusable.
-- ---------------------------------------------------------------------
CREATE TABLE sod_rule (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_a_id      UUID NOT NULL REFERENCES role(id),
    role_b_id      UUID NOT NULL REFERENCES role(id),
    enforcement    VARCHAR(8) NOT NULL DEFAULT 'BLOCK'
                   CHECK (enforcement IN ('BLOCK','WARN')),
    source_ref     VARCHAR(120),      -- e.g. 'Board paper Table 6'
    rationale      VARCHAR(400),
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT sod_rule_distinct CHECK (role_a_id <> role_b_id)
);

-- Order-independent uniqueness: (A,B) and (B,A) are the same rule.
CREATE UNIQUE INDEX sod_rule_unique_pair ON sod_rule (
    LEAST(role_a_id, role_b_id), GREATEST(role_a_id, role_b_id)
);

-- ---------------------------------------------------------------------
-- Audit log. FR-SEC-24..29: field-level before/after with display labels
-- captured at write time, so a role later renamed does not rewrite history.
-- ---------------------------------------------------------------------
CREATE TABLE audit_log (
    id               BIGSERIAL PRIMARY KEY,
    entity_name      VARCHAR(80)  NOT NULL,
    entity_id        UUID,
    entity_label     VARCHAR(200),          -- as it read at the time
    document_id      UUID,
    action           VARCHAR(24)  NOT NULL
                     CHECK (action IN ('CREATE','UPDATE','APPROVE','REJECT',
                                       'POST','CANCEL','DEACTIVATE','LOGIN',
                                       'LOGIN_FAILED','LOGOUT','ATTACH','DETACH')),
    -- Whole-row snapshots. Storing the diff alone loses context when a
    -- field is renamed later; the diff is computed at display time.
    before_state     JSONB,
    after_state      JSONB,
    actor_user_id    UUID REFERENCES app_user(id),
    actor_name       VARCHAR(160),          -- captured, not joined
    actor_username   VARCHAR(60),
    actor_role       VARCHAR(120),          -- the role the action was performed under
    branch_id        UUID REFERENCES branch(id),
    branch_name      VARCHAR(120),
    occurred_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    client_address   VARCHAR(60),
    user_agent       VARCHAR(300),
    reason           VARCHAR(400)
);

CREATE INDEX audit_log_entity   ON audit_log (entity_name, entity_id, occurred_at DESC);
CREATE INDEX audit_log_document ON audit_log (document_id, occurred_at DESC);
CREATE INDEX audit_log_actor    ON audit_log (actor_user_id, occurred_at DESC);
CREATE INDEX audit_log_when     ON audit_log (occurred_at DESC);

COMMENT ON TABLE audit_log IS
  'Append-only, retained ten years (NFR-06). No application role holds UPDATE or DELETE on this table.';

-- The audit trail cannot be edited by any user, administrators included
-- (FR-SEC-06). Enforced here so a bug in Java cannot bypass it.
CREATE OR REPLACE FUNCTION audit_log_is_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_is_append_only();
