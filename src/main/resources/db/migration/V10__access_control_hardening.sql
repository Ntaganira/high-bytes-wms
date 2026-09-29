-- =====================================================================
-- V10 — Access control, second pass: what an independent re-audit of V9
--       found
--
-- 1. Every role the policy seeded is protected. COO and Sales carried no
--    permission, sat in no rule and signed no step, so a runtime edit
--    could have made them anything.
-- 2. Signing a step no longer expires with the chain. A document finishes
--    under the chain it began with, so the 2026 signers stay policy roles,
--    and operational, after 1 January 2027.
-- 3. The Internal Controller holds nothing operational that is not its
--    own. Assurance cannot test work it performed itself, whichever role
--    the work came through.
-- 4. A transactional right no policy role carries cannot be handed out at
--    runtime. Release, transfer approval and ticket rights belong to
--    modules not built yet; the segregation rules cannot see a right until
--    the policy places it, so it stays unusable until the migration that
--    builds its screen gives it to the role that owns it.
-- 5. A right counts for one side of a segregation pair only if no policy
--    role allowed alongside the other side carries it too: a runtime
--    receiving verifier may sit with Finance, as the Warehouse Manager may.
-- 6. A change to what a policy role carries, to the rules or to a
--    permission is judged for everyone, not only that role's holders.
-- 7. Smaller: a policy role's name and description are fixed too, and no
--    policy role is deactivated; an assignment cannot arrive already
--    revoked; waiting for the access lock gives up after ten seconds; role
--    names are Latin script, so none can pass for another.
-- =====================================================================

SET LOCAL highbytes.migration = 'on';

-- ---------------------------------------------------------------------
-- 1. The policy's roles, every one of them.
-- ---------------------------------------------------------------------
UPDATE role SET is_protected = TRUE
 WHERE code IN ('ASST_WH_MANAGER', 'COO', 'DIR_COMMERCIAL', 'DIR_SUPPLY_CHAIN', 'FINANCE',
                'HEAD_INVENTORY', 'INTERNAL_CTRL', 'INV_TX_OFFICER', 'MANAGING_DIR',
                'SALES', 'SYS_ADMIN', 'WH_MANAGER');

-- ---------------------------------------------------------------------
-- 3. Assurance roles.
-- ---------------------------------------------------------------------
ALTER TABLE role ADD COLUMN is_assurance BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN role.is_assurance IS
  'An assurance role (the Internal Controller). Whoever holds it holds no TRANSACT or CONFIGURE right it does not carry itself. Set by migration only.';

UPDATE role SET is_assurance = TRUE WHERE code = 'INTERNAL_CTRL';

-- ---------------------------------------------------------------------
-- 7. Role names are Latin script: letters, digits, spaces and a little
--    punctuation. A Cyrillic "о" or a zero-width space would otherwise
--    make a role that reads exactly like the Internal Controller. Names
--    already stored are not re-judged; a new or changed one is.
-- ---------------------------------------------------------------------
ALTER TABLE role ADD CONSTRAINT role_name_readable
    CHECK (name ~ '^[A-Za-z0-9À-ɏ &()''.,/–—-]+$'
           AND name = btrim(name) AND name !~ '  ')
    NOT VALID;

-- ---------------------------------------------------------------------
-- 2. A role the policy defines: protected, an assurance role, named by a
--    segregation rule, or a signer in any chain, in force or not.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION role_is_policy_defined(p_role UUID) RETURNS BOOLEAN AS $$
    SELECT EXISTS (SELECT 1 FROM role WHERE id = p_role AND (is_protected OR is_assurance))
        OR EXISTS (SELECT 1 FROM sod_rule
                    WHERE is_active AND p_role IN (role_a_id, role_b_id))
        OR EXISTS (SELECT 1 FROM workflow_step WHERE required_role_id = p_role);
$$ LANGUAGE sql STABLE;

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
          ORDER BY dt.sort_order, wd.effective_from, ws.sequence_no
          LIMIT 1),
        (SELECT format('carries a master-data right (%s)', p.code)
           FROM role_permission rp
           JOIN permission p ON p.id = rp.permission_id
          WHERE rp.role_id = p_role AND p.duty = 'CONFIGURE'
          ORDER BY p.code
          LIMIT 1));
$$ LANGUAGE sql STABLE;

-- A protected or assurance role stays so; a policy role keeps its name,
-- its description and its place.
CREATE OR REPLACE FUNCTION role_policy_guard() RETURNS TRIGGER AS $$
BEGIN
    IF NOT in_migration() THEN
        IF (NEW.is_protected, NEW.is_assurance) IS DISTINCT FROM (OLD.is_protected, OLD.is_assurance) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z01',
                  MESSAGE = format('Whether %s is protected, or an assurance role, is the policy''s decision, '
                                   'changed only by a reviewed migration.', OLD.name);
        END IF;
        IF role_is_policy_defined(OLD.id) THEN
            IF (NEW.code, NEW.name, NEW.description, NEW.structure)
               IS DISTINCT FROM (OLD.code, OLD.name, OLD.description, OLD.structure) THEN
                RAISE EXCEPTION USING ERRCODE = '23Z01',
                      MESSAGE = format('%s is defined by the policy, so its name and description change only '
                                       'by a reviewed migration.', OLD.name);
            END IF;
            IF OLD.is_active AND NOT NEW.is_active THEN
                RAISE EXCEPTION USING ERRCODE = '23Z01',
                      MESSAGE = format('%s is defined by the policy, so it is never deactivated.', OLD.name);
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- 5. How rights are read against a segregation rule.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION roles_blocked(p_a UUID, p_b UUID) RETURNS BOOLEAN AS $$
    SELECT EXISTS (SELECT 1 FROM sod_rule
                    WHERE is_active AND enforcement = 'BLOCK'
                      AND ((role_a_id = p_a AND role_b_id = p_b)
                        OR (role_a_id = p_b AND role_b_id = p_a)));
$$ LANGUAGE sql STABLE;

-- Whether a right makes whoever carries it stand for SIDE of a rule
-- against OTHER: a transactional right SIDE carries and OTHER does not,
-- and which no other policy role allowed alongside OTHER carries too.
CREATE OR REPLACE FUNCTION right_stands_for(p_permission UUID, p_side UUID, p_other UUID) RETURNS BOOLEAN AS $$
    SELECT EXISTS (SELECT 1 FROM permission WHERE id = p_permission AND duty = 'TRANSACT')
       AND EXISTS (SELECT 1 FROM role_permission WHERE role_id = p_side AND permission_id = p_permission)
       AND NOT EXISTS (SELECT 1 FROM role_permission WHERE role_id = p_other AND permission_id = p_permission)
       AND NOT EXISTS (SELECT 1 FROM role_permission zp
                        WHERE zp.permission_id = p_permission
                          AND zp.role_id NOT IN (p_side, p_other)
                          AND role_is_policy_defined(zp.role_id)
                          AND NOT roles_blocked(zp.role_id, p_other));
$$ LANGUAGE sql STABLE;

-- ---------------------------------------------------------------------
-- The access check for one person. Returns why their assignments may not
-- stand, or NULL. Every two unrevoked assignments that overlap in time and
-- have not ended are compared:
--
--   1. an active BLOCK rule names their two roles;
--   2. one grants an ADMINISTER permission and the other, or the same
--      role, is operational (invariant 8);
--   3. segregation follows the rights: a role no rule names stands for a
--      side of a rule through a right that stands for that side;
--   4. an assurance role's holder holds, through another role, a
--      transactional or master-data right the assurance role lacks.
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
        -- holding a role no rule names, through a right that stands for a side
        SELECT h.id, k.id, x.side, p.code
          FROM held h
          JOIN role_permission hp ON hp.role_id = h.role_id
          JOIN permission p       ON p.id = hp.permission_id AND p.duty = 'TRANSACT'
          CROSS JOIN block k
          CROSS JOIN LATERAL (VALUES (k.role_a_id, k.role_b_id), (k.role_b_id, k.role_a_id)) AS x(side, other)
         WHERE NOT EXISTS (SELECT 1 FROM block n WHERE h.role_id IN (n.role_a_id, n.role_b_id))
           AND right_stands_for(p.id, x.side, x.other)
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
    IF msg IS NOT NULL THEN
        RETURN msg;
    END IF;

    SELECT format('%s is an assurance role, and %s carries %s, which %s does not. Assurance cannot test '
                  'work it performed itself, so whoever holds %s holds no transactional or master-data '
                  'right beyond its own.',
                  ra.name, rb.name, p.code, ra.name, ra.name)
      INTO msg
      FROM user_role a
      JOIN role ra            ON ra.id = a.role_id AND ra.is_assurance
      JOIN user_role b        ON b.user_id = a.user_id AND b.id <> a.id
      JOIN role rb            ON rb.id = b.role_id
      JOIN role_permission bp ON bp.role_id = b.role_id
      JOIN permission p       ON p.id = bp.permission_id AND p.duty IN ('TRANSACT', 'CONFIGURE')
     WHERE a.user_id = p_user
       AND NOT EXISTS (SELECT 1 FROM role_permission ap
                        WHERE ap.role_id = a.role_id AND ap.permission_id = p.id)
       AND a.revoked_at IS NULL AND b.revoked_at IS NULL
       AND COALESCE(a.valid_to, 'infinity') >= CURRENT_DATE
       AND COALESCE(b.valid_to, 'infinity') >= CURRENT_DATE
       AND daterange(a.valid_from, a.valid_to, '[]') && daterange(b.valid_from, b.valid_to, '[]')
     ORDER BY ra.name, rb.name, p.code
     LIMIT 1;
    RETURN msg;
END;
$$ LANGUAGE plpgsql STABLE;

-- ---------------------------------------------------------------------
-- What one role may carry, whoever holds it:
--   - no administration right alongside anything operational;
--   - if no rule names it, no rights that stand for both sides of a rule;
--   - if the policy does not define it, no transactional right that no
--     policy role carries (4 above): such a right is invisible to the
--     rules until the policy places it.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION role_carry_conflict(p_role UUID) RETURNS TEXT AS $$
DECLARE
    msg TEXT;
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

    SELECT format('%s carries %s, a right of %s, and %s, a right of %s. %s and %s cannot be held by the '
                  'same person at the same time, so one role cannot carry both. %s (%s)',
                  r.name, pa.code, sa.name, pb.code, sb.name, sa.name, sb.name, k.rationale, k.source_ref)
      INTO msg
      FROM role r
      JOIN sod_rule k          ON k.is_active AND k.enforcement = 'BLOCK'
      JOIN role sa             ON sa.id = k.role_a_id
      JOIN role sb             ON sb.id = k.role_b_id
      JOIN role_permission ra  ON ra.role_id = r.id
      JOIN permission pa       ON pa.id = ra.permission_id
      JOIN role_permission rb  ON rb.role_id = r.id
      JOIN permission pb       ON pb.id = rb.permission_id
     WHERE r.id = p_role
       AND NOT EXISTS (SELECT 1 FROM sod_rule n
                        WHERE n.is_active AND n.enforcement = 'BLOCK' AND r.id IN (n.role_a_id, n.role_b_id))
       AND right_stands_for(pa.id, sa.id, sb.id)
       AND right_stands_for(pb.id, sb.id, sa.id)
     ORDER BY sa.name, sb.name, pa.code, pb.code
     LIMIT 1;
    IF msg IS NOT NULL THEN
        RETURN msg;
    END IF;

    SELECT format('%s carries %s, which no role the policy defines carries, so the segregation rules '
                  'cannot yet judge who may hold it. It can be given to new roles once the migration '
                  'that builds its screen gives it to the role the policy says owns it.',
                  r.name, p.code)
      INTO msg
      FROM role r
      JOIN role_permission rp ON rp.role_id = r.id
      JOIN permission p       ON p.id = rp.permission_id AND p.duty = 'TRANSACT'
     WHERE r.id = p_role
       AND NOT role_is_policy_defined(r.id)
       AND NOT EXISTS (SELECT 1 FROM role_permission op
                        WHERE op.permission_id = p.id AND op.role_id <> r.id
                          AND role_is_policy_defined(op.role_id))
     ORDER BY p.code
     LIMIT 1;
    RETURN msg;
END;
$$ LANGUAGE plpgsql STABLE;

-- A role, and every current holder of it.
CREATE OR REPLACE FUNCTION role_access_conflict(p_role UUID) RETURNS TEXT AS $$
DECLARE
    msg    TEXT;
    holder RECORD;
BEGIN
    msg := role_carry_conflict(p_role);
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
-- 6. Everyone at once. What a policy role carries decides how every other
--    role is read, so a change to it, to a rule or to a permission is
--    judged here. A migration that changes access ends by asking this.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION access_conflict_anywhere() RETURNS TEXT AS $$
DECLARE
    r   RECORD;
    msg TEXT;
BEGIN
    FOR r IN SELECT id FROM role ORDER BY code LOOP
        msg := role_carry_conflict(r.id);
        IF msg IS NOT NULL THEN
            RETURN msg;
        END IF;
    END LOOP;
    FOR r IN
        SELECT DISTINCT u.id, u.full_name
          FROM user_role ur
          JOIN app_user u ON u.id = ur.user_id
         WHERE ur.revoked_at IS NULL
           AND COALESCE(ur.valid_to, 'infinity') >= CURRENT_DATE
         ORDER BY u.full_name
    LOOP
        msg := access_conflict(r.id);
        IF msg IS NOT NULL THEN
            RETURN format('For %s: %s', r.full_name, msg);
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

CREATE OR REPLACE FUNCTION role_permission_check_access() RETURNS TRIGGER AS $$
DECLARE
    old_role UUID;
    new_role UUID;
    msg      TEXT;
BEGIN
    IF TG_OP <> 'INSERT' THEN old_role := OLD.role_id; END IF;
    IF TG_OP <> 'DELETE' THEN new_role := NEW.role_id; END IF;
    IF role_is_policy_defined(old_role) OR role_is_policy_defined(new_role) THEN
        msg := access_conflict_anywhere();
    ELSIF new_role IS NOT NULL THEN
        msg := role_access_conflict(new_role);
    END IF;
    IF msg IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z01', MESSAGE = msg;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER role_permission_access_check ON role_permission;
CREATE CONSTRAINT TRIGGER role_permission_access_check
    AFTER INSERT OR UPDATE OR DELETE ON role_permission
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION role_permission_check_access();

CREATE OR REPLACE FUNCTION sod_rule_check_access() RETURNS TRIGGER AS $$
DECLARE
    msg TEXT;
BEGIN
    msg := access_conflict_anywhere();
    IF msg IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z01', MESSAGE = msg;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER sod_rule_access_check ON sod_rule;
CREATE CONSTRAINT TRIGGER sod_rule_access_check
    AFTER INSERT OR UPDATE OR DELETE ON sod_rule
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION sod_rule_check_access();

CREATE OR REPLACE FUNCTION permission_check_access() RETURNS TRIGGER AS $$
DECLARE
    msg TEXT;
BEGIN
    msg := access_conflict_anywhere();
    IF msg IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z01', MESSAGE = msg;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- 7. An assignment arrives unrevoked: a revocation is made, and dated,
--    when it happens.
-- ---------------------------------------------------------------------
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
        IF NEW.revoked_at IS NOT NULL OR NEW.revoked_by IS NOT NULL OR NEW.revoke_reason IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = 'A role assignment is granted unrevoked; a revocation is made when it happens.';
        END IF;
        NEW.created_at := now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- 7. The access lock, with a limit on the wait. The application also
--    takes it before reading what it is about to change.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lock_access_changes() RETURNS BOOLEAN AS $$
BEGIN
    PERFORM set_config('lock_timeout', '10s', true);
    PERFORM pg_advisory_xact_lock(hashtext('highbytes.access_change'));
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION access_change_serialize() RETURNS TRIGGER AS $$
BEGIN
    PERFORM lock_access_changes();
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- The rules hold from the moment they exist.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    msg TEXT;
BEGIN
    msg := access_conflict_anywhere();
    IF msg IS NOT NULL THEN
        RAISE EXCEPTION 'V10 cannot apply: existing access breaks the rules it adds. %', msg;
    END IF;
END $$;
