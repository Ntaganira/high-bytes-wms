-- =====================================================================
-- V9 — Access control: segregation enforced, assignments kept as
--      history, and a stamp that makes access changes take effect at once
--
-- SRS: FR-SEC-02 (incompatible roles), FR-SEC-13 (administration holds no
-- transactional rights), FR-SEC-14..18 (users, roles, dated and delegated
-- assignments).
--
-- V1 made the segregation rules data, but nothing refused an assignment
-- that broke one: the rows were there, the check was not. The checks now
-- live here, where a bug in Java cannot skip them.
--
-- Some changes here are a migration's to make, never the application's:
-- what a role the policy defines permits, the segregation rules, and what
-- a permission is. A migration that makes one says so first, with
--     SET LOCAL highbytes.migration = 'on';
-- =====================================================================

-- ---------------------------------------------------------------------
-- What a permission lets its holder do, as a duty. Invariant 8 is stated
-- in these terms: whoever holds ADMINISTER holds nothing operational.
-- Derived from module and action, so a new permission classifies itself.
-- ---------------------------------------------------------------------
ALTER TABLE permission
    ADD COLUMN duty VARCHAR(12) GENERATED ALWAYS AS (
        CASE
            WHEN module = 'admin'     THEN 'ADMINISTER'
            WHEN action = 'VIEW'      THEN 'READ'
            WHEN action = 'CONFIGURE' THEN 'CONFIGURE'
            ELSE 'TRANSACT'
        END) STORED;

COMMENT ON COLUMN permission.duty IS
  'ADMINISTER (users, roles, workflows, branches), TRANSACT (raise, verify, approve, release, post, cancel), CONFIGURE (master data) or READ. Invariant 8: nobody holds ADMINISTER alongside TRANSACT or CONFIGURE over the same period.';

-- Whether the change in hand is a reviewed migration's.
CREATE OR REPLACE FUNCTION in_migration() RETURNS BOOLEAN AS $$
    SELECT COALESCE(current_setting('highbytes.migration', true), '') = 'on';
$$ LANGUAGE sql STABLE;

-- ---------------------------------------------------------------------
-- Users.
--
-- Two values the session checks on every request:
--   security_stamp  changes whenever what the user may do changes, and the
--                   session reloads its permissions on the next request;
--   session_epoch   rises when every open session must end: deactivation,
--                   a password reset, a password change, a lock-out.
-- Without them a revoked right lived on until the holder signed out.
-- ---------------------------------------------------------------------
ALTER TABLE app_user
    ADD COLUMN security_stamp      UUID        NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN session_epoch       INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN password_changed_at TIMESTAMPTZ;

-- Sign-in compares usernames ignoring case, so two accounts differing only
-- in case would make it ambiguous which one signed in.
CREATE UNIQUE INDEX app_user_username_ci ON app_user (lower(username));

CREATE OR REPLACE FUNCTION app_user_restamp() RETURNS TRIGGER AS $$
BEGIN
    IF (NEW.full_name, NEW.home_branch_id, NEW.is_active,
        NEW.must_change_password, NEW.password_hash)
       IS DISTINCT FROM
       (OLD.full_name, OLD.home_branch_id, OLD.is_active,
        OLD.must_change_password, OLD.password_hash)
    THEN
        NEW.security_stamp := gen_random_uuid();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER app_user_security_stamp
    BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION app_user_restamp();

-- ---------------------------------------------------------------------
-- Roles. Holders see a role's name as their title, and the audit trail
-- records every action under it: two roles sharing a name could not be
-- told apart in either place.
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX role_name_ci ON role (lower(name));

-- ---------------------------------------------------------------------
-- Role assignments become history. An assignment is revoked, never edited
-- or deleted: "who could release goods on 12 October" must stay answerable.
-- ---------------------------------------------------------------------
ALTER TABLE user_role
    ADD COLUMN revoked_at    TIMESTAMPTZ,
    ADD COLUMN revoked_by    UUID REFERENCES app_user(id),
    ADD COLUMN revoke_reason VARCHAR(240),
    -- A revocation names who made it and why.
    ADD CONSTRAINT user_role_revocation_complete
        CHECK ((revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason IS NULL)
            OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL
                AND revoke_reason IS NOT NULL AND btrim(revoke_reason) <> '')),
    -- Nobody changes their own access. An administrator who could grant
    -- themselves a right, use it and revoke it again is the Board's
    -- finding in another form.
    ADD CONSTRAINT user_role_not_self_assigned CHECK (assigned_by IS DISTINCT FROM user_id),
    ADD CONSTRAINT user_role_not_self_revoked  CHECK (revoked_by  IS DISTINCT FROM user_id),
    -- Cover for someone's leave is temporary by definition, and names them.
    ADD CONSTRAINT user_role_delegation_is_temporary
        CHECK ((NOT is_delegation AND delegated_for IS NULL)
            OR (is_delegation AND valid_to IS NOT NULL
                AND delegated_for IS NOT NULL AND delegated_for <> user_id));

-- One unrevoked assignment per user, role and scope on any day. Replaces
-- the V1 index, which covered only open-ended assignments and would have
-- kept a revoked assignment blocking its own re-grant.
DROP INDEX user_role_unique_live;
ALTER TABLE user_role
    ADD CONSTRAINT user_role_no_overlap
    EXCLUDE USING gist (
        user_id WITH =,
        role_id WITH =,
        (COALESCE(branch_id, '00000000-0000-0000-0000-000000000000'::uuid)) WITH =,
        daterange(valid_from, valid_to, '[]') WITH &&
    ) WHERE (revoked_at IS NULL);

CREATE INDEX user_role_by_role ON user_role (role_id) WHERE revoked_at IS NULL;

-- The assignments in force today: not revoked, and between their dates.
CREATE VIEW live_user_role AS
SELECT *
  FROM user_role
 WHERE revoked_at IS NULL
   AND valid_from <= CURRENT_DATE
   AND (valid_to IS NULL OR valid_to >= CURRENT_DATE);

-- A grant names who made it, starts no earlier than today, and is dated
-- when it is made. The install's own grant (V6) predates this rule.
CREATE OR REPLACE FUNCTION user_role_check_new() RETURNS TRIGGER AS $$
BEGIN
    IF NOT in_migration() THEN
        IF NEW.assigned_by IS NULL THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = 'A role assignment must name who granted it.';
        END IF;
        IF NEW.valid_from < CURRENT_DATE THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = 'A role cannot be granted from a past date: that would claim access nobody had at the time.';
        END IF;
        NEW.created_at := now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER user_role_new_grant
    BEFORE INSERT ON user_role
    FOR EACH ROW EXECUTE FUNCTION user_role_check_new();

CREATE OR REPLACE FUNCTION user_role_is_history() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'A role assignment is access history and cannot be deleted. Revoke it instead.';
    END IF;
    IF OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'This role assignment was revoked on % and cannot change again.', OLD.revoked_at;
    END IF;
    IF (NEW.id, NEW.user_id, NEW.role_id, NEW.branch_id, NEW.valid_from, NEW.valid_to,
        NEW.is_delegation, NEW.delegated_for, NEW.assigned_by, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.user_id, OLD.role_id, OLD.branch_id, OLD.valid_from, OLD.valid_to,
        OLD.is_delegation, OLD.delegated_for, OLD.assigned_by, OLD.created_at)
    THEN
        RAISE EXCEPTION 'A role assignment cannot be rewritten. Revoke it and grant a new one.';
    END IF;
    -- A revocation takes effect when it is made, not on a date someone picks.
    IF NEW.revoked_at IS NOT NULL THEN
        NEW.revoked_at := now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER user_role_history_only
    BEFORE UPDATE OR DELETE ON user_role
    FOR EACH ROW EXECUTE FUNCTION user_role_is_history();

-- ---------------------------------------------------------------------
-- Roles the policy defines. The segregation rules and the approval chains
-- name roles, and assume what each carries: the Internal Controller of
-- Board Table 6 verifies and posts nothing. A role that is protected, is
-- named by a segregation rule or signs a step in a chain in force or
-- scheduled keeps the permissions the policy gave it; they change only by
-- a reviewed migration. A role created at runtime is edited freely, within
-- the access checks below.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION role_is_policy_defined(p_role UUID) RETURNS BOOLEAN AS $$
    SELECT EXISTS (SELECT 1 FROM role WHERE id = p_role AND is_protected)
        OR EXISTS (SELECT 1 FROM sod_rule
                    WHERE is_active AND p_role IN (role_a_id, role_b_id))
        OR EXISTS (SELECT 1 FROM workflow_step ws
                     JOIN workflow_definition wd ON wd.id = ws.workflow_definition_id
                    WHERE ws.required_role_id = p_role
                      AND (wd.effective_to IS NULL OR wd.effective_to > CURRENT_DATE));
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION role_permission_policy_guard() RETURNS TRIGGER AS $$
DECLARE
    old_role UUID;
    new_role UUID;
    fixed    TEXT;
BEGIN
    IF NOT in_migration() THEN
        IF TG_OP <> 'INSERT' THEN old_role := OLD.role_id; END IF;
        IF TG_OP <> 'DELETE' THEN new_role := NEW.role_id; END IF;
        SELECT r.name INTO fixed
          FROM role r
         WHERE r.id IN (old_role, new_role)
           AND role_is_policy_defined(r.id)
         LIMIT 1;
        IF fixed IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z01',
                  MESSAGE = format('%s is defined by the policy, so what it permits changes only by a reviewed '
                                   'migration: the segregation rules and approval chains that name it assume '
                                   'what it carries.', fixed);
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER role_permission_policy_defined
    BEFORE INSERT OR UPDATE OR DELETE ON role_permission
    FOR EACH ROW EXECUTE FUNCTION role_permission_policy_guard();

-- A protected role stays protected, and in force.
CREATE OR REPLACE FUNCTION role_policy_guard() RETURNS TRIGGER AS $$
BEGIN
    IF NOT in_migration() THEN
        IF NEW.is_protected IS DISTINCT FROM OLD.is_protected THEN
            RAISE EXCEPTION USING ERRCODE = '23Z01',
                  MESSAGE = format('Whether %s is protected is the policy''s decision, changed only by a reviewed migration.', OLD.name);
        END IF;
        IF OLD.is_protected AND OLD.is_active AND NOT NEW.is_active THEN
            RAISE EXCEPTION USING ERRCODE = '23Z01',
                  MESSAGE = format('%s is protected: the policy defines it, so it is never deactivated.', OLD.name);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER role_policy_defined
    BEFORE UPDATE ON role
    FOR EACH ROW EXECUTE FUNCTION role_policy_guard();

-- The segregation rules are the Board's.
CREATE OR REPLACE FUNCTION sod_rule_policy_guard() RETURNS TRIGGER AS $$
BEGIN
    IF NOT in_migration() THEN
        RAISE EXCEPTION USING ERRCODE = '23Z01',
              MESSAGE = 'The segregation rules are the Board''s, and change only by a reviewed migration.';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER sod_rule_policy_defined
    BEFORE INSERT OR UPDATE OR DELETE ON sod_rule
    FOR EACH ROW EXECUTE FUNCTION sod_rule_policy_guard();

-- What a permission is decides its duty, and so every check below.
CREATE OR REPLACE FUNCTION permission_policy_guard() RETURNS TRIGGER AS $$
BEGIN
    IF NOT in_migration()
       AND (NEW.code, NEW.module, NEW.action) IS DISTINCT FROM (OLD.code, OLD.module, OLD.action) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z01',
              MESSAGE = format('What %s permits, and so its duty, changes only by a reviewed migration.', OLD.code);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER permission_policy_defined
    BEFORE UPDATE ON permission
    FOR EACH ROW EXECUTE FUNCTION permission_policy_guard();

-- ---------------------------------------------------------------------
-- What makes a role operational, for invariant 8: a transactional right,
-- a step to sign in a chain in force or scheduled, or a master-data
-- right. NULL when it has none. Worded to follow the role's name.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION role_operational_right(p_role UUID) RETURNS TEXT AS $$
    SELECT COALESCE(
        (SELECT format('carries a transactional right (%s)', p.code)
           FROM role_permission rp
           JOIN permission p ON p.id = rp.permission_id
          WHERE rp.role_id = p_role AND p.duty = 'TRANSACT'
          ORDER BY p.code
          LIMIT 1),
        (SELECT format('signs step %s of the %s chain', ws.sequence_no, dt.name)
           FROM workflow_step ws
           JOIN workflow_definition wd ON wd.id = ws.workflow_definition_id
           JOIN document_type dt       ON dt.id = wd.document_type_id
          WHERE ws.required_role_id = p_role
            AND (wd.effective_to IS NULL OR wd.effective_to > CURRENT_DATE)
          ORDER BY dt.sort_order, wd.effective_from, ws.sequence_no
          LIMIT 1),
        (SELECT format('carries a master-data right (%s)', p.code)
           FROM role_permission rp
           JOIN permission p ON p.id = rp.permission_id
          WHERE rp.role_id = p_role AND p.duty = 'CONFIGURE'
          ORDER BY p.code
          LIMIT 1));
$$ LANGUAGE sql STABLE;

-- ---------------------------------------------------------------------
-- The access check. Returns why a user's assignments may not stand, or
-- NULL when they may. It compares every two unrevoked assignments that
-- overlap in time and have not already ended:
--
--   1. an active BLOCK rule in sod_rule names their two roles (FR-SEC-02);
--   2. one grants an ADMINISTER permission and the other, or the same
--      role, is operational (see role_operational_right). That is
--      invariant 8, and it covers roles created at runtime, which no
--      sod_rule row names;
--   3. segregation follows the rights, not the name. A role no BLOCK rule
--      names stands for a side of a rule when it carries a transactional
--      right that side's role has and the other side's does not. So the
--      Internal Controller's rights copied into a new role still cannot
--      sit with Finance.
--
-- Dated grants count: a conflict scheduled for next month is refused
-- today, not discovered then. Assignments that have already ended are
-- history and are not re-judged. Branch scope is ignored on purpose:
-- segregation is about the person, not the premises.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION access_conflict(p_user UUID) RETURNS TEXT AS $$
DECLARE
    msg TEXT;
BEGIN
    SELECT format('%s and %s cannot be held by the same person at the same time. %s (%s)',
                  ra.name, rb.name, s.rationale, s.source_ref)
      INTO msg
      FROM user_role a
      JOIN user_role b ON b.user_id = a.user_id AND b.id <> a.id
      JOIN sod_rule s  ON s.is_active AND s.enforcement = 'BLOCK'
                      AND s.role_a_id = a.role_id AND s.role_b_id = b.role_id
      JOIN role ra     ON ra.id = a.role_id
      JOIN role rb     ON rb.id = b.role_id
     WHERE a.user_id = p_user
       AND a.revoked_at IS NULL AND b.revoked_at IS NULL
       AND COALESCE(a.valid_to, 'infinity') >= CURRENT_DATE
       AND COALESCE(b.valid_to, 'infinity') >= CURRENT_DATE
       AND daterange(a.valid_from, a.valid_to, '[]') && daterange(b.valid_from, b.valid_to, '[]')
     ORDER BY ra.name, rb.name
     LIMIT 1;
    IF msg IS NOT NULL THEN
        RETURN msg;
    END IF;

    -- The message names the most telling administration right: user
    -- management before the rest.
    SELECT CASE WHEN a.role_id = b.role_id
                THEN format('%s carries an administration right (%s), and it also %s. '
                            'Whoever manages access takes no part in operations: no transactions, '
                            'no approval signatures, no master data.',
                            ra.name, pa.code, o.what)
                ELSE format('%s is an administration role (%s), and %s %s. '
                            'Whoever manages access takes no part in operations, so one person cannot hold both at the same time.',
                            ra.name, pa.code, rb.name, o.what)
           END
      INTO msg
      FROM user_role a
      JOIN role_permission ap ON ap.role_id = a.role_id
      JOIN permission pa      ON pa.id = ap.permission_id AND pa.duty = 'ADMINISTER'
      JOIN user_role b        ON b.user_id = a.user_id
      JOIN role ra            ON ra.id = a.role_id
      JOIN role rb            ON rb.id = b.role_id
      CROSS JOIN LATERAL (SELECT role_operational_right(b.role_id) AS what) o
     WHERE a.user_id = p_user
       AND o.what IS NOT NULL
       AND a.revoked_at IS NULL AND b.revoked_at IS NULL
       AND COALESCE(a.valid_to, 'infinity') >= CURRENT_DATE
       AND COALESCE(b.valid_to, 'infinity') >= CURRENT_DATE
       AND daterange(a.valid_from, a.valid_to, '[]') && daterange(b.valid_from, b.valid_to, '[]')
     ORDER BY ra.name,
              CASE pa.code WHEN 'admin.users' THEN 0 WHEN 'admin.roles' THEN 1 ELSE 2 END,
              pa.code, rb.name
     LIMIT 1;
    IF msg IS NOT NULL THEN
        RETURN msg;
    END IF;

    WITH held AS (
        SELECT ur.id, ur.role_id, r.name, ur.valid_from, ur.valid_to
          FROM user_role ur
          JOIN role r ON r.id = ur.role_id
         WHERE ur.user_id = p_user AND ur.revoked_at IS NULL
           AND COALESCE(ur.valid_to, 'infinity') >= CURRENT_DATE
    ),
    block AS (
        SELECT id, role_a_id, role_b_id, rationale, source_ref
          FROM sod_rule
         WHERE is_active AND enforcement = 'BLOCK'
    ),
    stands AS (
        -- holding a role the rule names
        SELECT h.id AS assignment, k.id AS rule, k.role_a_id AS side, NULL::text AS via
          FROM held h JOIN block k ON k.role_a_id = h.role_id
        UNION ALL
        SELECT h.id, k.id, k.role_b_id, NULL
          FROM held h JOIN block k ON k.role_b_id = h.role_id
        UNION ALL
        -- holding a role no rule names, through a right only one side has
        SELECT h.id, k.id, x.side, p.code
          FROM held h
          JOIN role_permission hp ON hp.role_id = h.role_id
          JOIN permission p       ON p.id = hp.permission_id AND p.duty = 'TRANSACT'
          CROSS JOIN block k
          CROSS JOIN LATERAL (VALUES (k.role_a_id, k.role_b_id), (k.role_b_id, k.role_a_id)) AS x(side, other)
         WHERE NOT EXISTS (SELECT 1 FROM block n WHERE h.role_id IN (n.role_a_id, n.role_b_id))
           AND EXISTS     (SELECT 1 FROM role_permission sp WHERE sp.role_id = x.side  AND sp.permission_id = p.id)
           AND NOT EXISTS (SELECT 1 FROM role_permission op WHERE op.role_id = x.other AND op.permission_id = p.id)
    )
    SELECT format('%s; %s. %s and %s cannot be held by the same person at the same time, '
                  'whatever roles carry their rights. %s (%s)',
                  CASE WHEN a.via IS NULL THEN format('%s is held', ha.name)
                       ELSE format('%s carries %s, a right of %s and not of %s', ha.name, a.via, sa.name, sb.name) END,
                  CASE WHEN b.via IS NULL THEN format('%s is held', hb.name)
                       ELSE format('%s carries %s, a right of %s and not of %s', hb.name, b.via, sb.name, sa.name) END,
                  sa.name, sb.name, k.rationale, k.source_ref)
      INTO msg
      FROM stands a
      JOIN block k  ON k.id = a.rule AND k.role_a_id = a.side
      JOIN stands b ON b.rule = a.rule AND b.side = k.role_b_id
      JOIN held ha  ON ha.id = a.assignment
      JOIN held hb  ON hb.id = b.assignment
      JOIN role sa  ON sa.id = k.role_a_id
      JOIN role sb  ON sb.id = k.role_b_id
     WHERE (a.via IS NOT NULL OR b.via IS NOT NULL)
       AND daterange(ha.valid_from, ha.valid_to, '[]') && daterange(hb.valid_from, hb.valid_to, '[]')
     ORDER BY sa.name, sb.name, ha.name, hb.name, a.via, b.via
     LIMIT 1;
    RETURN msg;
END;
$$ LANGUAGE plpgsql STABLE;

-- The same question for a role: can it carry its permissions, and can
-- every current holder still carry it?
CREATE OR REPLACE FUNCTION role_access_conflict(p_role UUID) RETURNS TEXT AS $$
DECLARE
    msg    TEXT;
    holder RECORD;
BEGIN
    SELECT format('%s carries an administration right (%s), and it also %s. '
                  'Whoever manages access takes no part in operations: no transactions, '
                  'no approval signatures, no master data.',
                  r.name, pa.code, o.what)
      INTO msg
      FROM role r
      JOIN role_permission ap ON ap.role_id = r.id
      JOIN permission pa      ON pa.id = ap.permission_id AND pa.duty = 'ADMINISTER'
      CROSS JOIN LATERAL (SELECT role_operational_right(r.id) AS what) o
     WHERE r.id = p_role
       AND o.what IS NOT NULL
     ORDER BY CASE pa.code WHEN 'admin.users' THEN 0 WHEN 'admin.roles' THEN 1 ELSE 2 END, pa.code
     LIMIT 1;
    IF msg IS NOT NULL THEN
        RETURN msg;
    END IF;

    -- A role no rule names may not carry rights only one side of a rule
    -- has alongside rights only the other side has.
    SELECT format('%s carries %s, a right of %s, and %s, a right of %s. %s and %s cannot be held by the '
                  'same person at the same time, so one role cannot carry both. %s (%s)',
                  r.name, pa.code, sa.name, pb.code, sb.name, sa.name, sb.name, k.rationale, k.source_ref)
      INTO msg
      FROM role r
      JOIN sod_rule k          ON k.is_active AND k.enforcement = 'BLOCK'
      JOIN role sa             ON sa.id = k.role_a_id
      JOIN role sb             ON sb.id = k.role_b_id
      JOIN role_permission ra  ON ra.role_id = r.id
      JOIN permission pa       ON pa.id = ra.permission_id AND pa.duty = 'TRANSACT'
      JOIN role_permission rb  ON rb.role_id = r.id
      JOIN permission pb       ON pb.id = rb.permission_id AND pb.duty = 'TRANSACT'
     WHERE r.id = p_role
       AND NOT EXISTS (SELECT 1 FROM sod_rule n
                        WHERE n.is_active AND n.enforcement = 'BLOCK' AND r.id IN (n.role_a_id, n.role_b_id))
       AND EXISTS     (SELECT 1 FROM role_permission x WHERE x.role_id = sa.id AND x.permission_id = pa.id)
       AND NOT EXISTS (SELECT 1 FROM role_permission x WHERE x.role_id = sb.id AND x.permission_id = pa.id)
       AND EXISTS     (SELECT 1 FROM role_permission x WHERE x.role_id = sb.id AND x.permission_id = pb.id)
       AND NOT EXISTS (SELECT 1 FROM role_permission x WHERE x.role_id = sa.id AND x.permission_id = pb.id)
     ORDER BY sa.name, sb.name, pa.code, pb.code
     LIMIT 1;
    IF msg IS NOT NULL THEN
        RETURN msg;
    END IF;

    FOR holder IN
        SELECT DISTINCT u.id, u.full_name
          FROM user_role ur
          JOIN app_user u ON u.id = ur.user_id
         WHERE ur.role_id = p_role AND ur.revoked_at IS NULL
         ORDER BY u.full_name
    LOOP
        msg := access_conflict(holder.id);
        IF msg IS NOT NULL THEN
            RETURN format('For %s: %s', holder.full_name, msg);
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

-- ---------------------------------------------------------------------
-- The checks run at commit, so a change made in several statements is
-- judged whole, and the service can ask access_conflict() first and show
-- the reason. SQLSTATE 23Z01 marks a refused access change.
--
-- Only a grant can create a conflict. A revocation is never checked, so
-- that removing access always works, even to cure a conflict.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION user_role_check_access() RETURNS TRIGGER AS $$
DECLARE
    msg TEXT;
BEGIN
    msg := access_conflict(NEW.user_id);
    IF msg IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z01', MESSAGE = msg;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER user_role_access_check
    AFTER INSERT ON user_role
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION user_role_check_access();

-- A role gaining a permission, whichever statement gave it.
CREATE OR REPLACE FUNCTION role_permission_check_access() RETURNS TRIGGER AS $$
DECLARE
    msg TEXT;
BEGIN
    msg := role_access_conflict(NEW.role_id);
    IF msg IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z01', MESSAGE = msg;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER role_permission_access_check
    AFTER INSERT OR UPDATE ON role_permission
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION role_permission_check_access();

-- A new or re-activated BLOCK rule is either in force or refused: it may
-- not arrive while someone already holds the pair it forbids.
CREATE OR REPLACE FUNCTION sod_rule_check_access() RETURNS TRIGGER AS $$
DECLARE
    msg    TEXT;
    holder RECORD;
BEGIN
    IF NEW.is_active AND NEW.enforcement = 'BLOCK' THEN
        FOR holder IN
            SELECT DISTINCT u.id, u.full_name
              FROM user_role ur
              JOIN app_user u ON u.id = ur.user_id
             WHERE ur.revoked_at IS NULL
             ORDER BY u.full_name
        LOOP
            msg := access_conflict(holder.id);
            IF msg IS NOT NULL THEN
                RAISE EXCEPTION USING ERRCODE = '23Z01',
                      MESSAGE = format('For %s: %s', holder.full_name, msg);
            END IF;
        END LOOP;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER sod_rule_access_check
    AFTER INSERT OR UPDATE ON sod_rule
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION sod_rule_check_access();

-- A role newly asked to sign a step becomes operational.
CREATE OR REPLACE FUNCTION workflow_step_check_access() RETURNS TRIGGER AS $$
DECLARE
    msg TEXT;
BEGIN
    msg := role_access_conflict(NEW.required_role_id);
    IF msg IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z01', MESSAGE = msg;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER workflow_step_access_check
    AFTER INSERT OR UPDATE ON workflow_step
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION workflow_step_check_access();

-- A permission a migration reclassifies is judged again for every role
-- that carries it.
CREATE OR REPLACE FUNCTION permission_check_access() RETURNS TRIGGER AS $$
DECLARE
    msg     TEXT;
    carrier RECORD;
BEGIN
    FOR carrier IN SELECT role_id FROM role_permission WHERE permission_id = NEW.id LOOP
        msg := role_access_conflict(carrier.role_id);
        IF msg IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z01', MESSAGE = msg;
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER permission_access_check
    AFTER UPDATE ON permission
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION permission_check_access();

-- One access change at a time. Two administrators granting the two halves
-- of a forbidden pair at the same moment would otherwise each check
-- against the other's uncommitted absence, and both succeed. Access
-- changes are rare; queueing them costs nothing.
CREATE OR REPLACE FUNCTION access_change_serialize() RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('highbytes.access_change'));
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER user_role_serialize
    BEFORE INSERT OR UPDATE OR DELETE ON user_role
    FOR EACH STATEMENT EXECUTE FUNCTION access_change_serialize();
CREATE TRIGGER role_permission_serialize
    BEFORE INSERT OR UPDATE OR DELETE ON role_permission
    FOR EACH STATEMENT EXECUTE FUNCTION access_change_serialize();
CREATE TRIGGER sod_rule_serialize
    BEFORE INSERT OR UPDATE OR DELETE ON sod_rule
    FOR EACH STATEMENT EXECUTE FUNCTION access_change_serialize();
CREATE TRIGGER workflow_step_serialize
    BEFORE INSERT OR UPDATE OR DELETE ON workflow_step
    FOR EACH STATEMENT EXECUTE FUNCTION access_change_serialize();
CREATE TRIGGER permission_serialize
    BEFORE UPDATE ON permission
    FOR EACH STATEMENT EXECUTE FUNCTION access_change_serialize();

-- ---------------------------------------------------------------------
-- Every change to what someone may do restamps them, so their open
-- sessions pick it up on the next request.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION user_role_restamp() RETURNS TRIGGER AS $$
BEGIN
    UPDATE app_user SET security_stamp = gen_random_uuid() WHERE id = NEW.user_id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER user_role_security_stamp
    AFTER INSERT OR UPDATE ON user_role
    FOR EACH ROW EXECUTE FUNCTION user_role_restamp();

CREATE OR REPLACE FUNCTION role_permission_restamp() RETURNS TRIGGER AS $$
DECLARE
    changed_role UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        changed_role := OLD.role_id;
    ELSE
        changed_role := NEW.role_id;
    END IF;
    UPDATE app_user SET security_stamp = gen_random_uuid()
     WHERE id IN (SELECT user_id FROM user_role WHERE role_id = changed_role);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER role_permission_security_stamp
    AFTER INSERT OR UPDATE OR DELETE ON role_permission
    FOR EACH ROW EXECUTE FUNCTION role_permission_restamp();

-- A role's name is what its holders see as their title; its state decides
-- whether it grants anything at all.
CREATE OR REPLACE FUNCTION role_restamp() RETURNS TRIGGER AS $$
BEGIN
    IF (NEW.name, NEW.is_active) IS DISTINCT FROM (OLD.name, OLD.is_active) THEN
        UPDATE app_user SET security_stamp = gen_random_uuid()
         WHERE id IN (SELECT user_id FROM user_role WHERE role_id = NEW.id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER role_security_stamp
    AFTER UPDATE ON role
    FOR EACH ROW EXECUTE FUNCTION role_restamp();

-- ---------------------------------------------------------------------
-- The rules hold from the moment they exist: an installation whose roles
-- or assignments already break them stops here, rather than carrying the
-- breach forward unnoticed.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    r   RECORD;
    msg TEXT;
BEGIN
    FOR r IN SELECT id FROM role ORDER BY code LOOP
        msg := role_access_conflict(r.id);
        IF msg IS NOT NULL THEN
            RAISE EXCEPTION 'V9 cannot apply: existing access already breaks the access rules. %', msg;
        END IF;
    END LOOP;
END $$;
