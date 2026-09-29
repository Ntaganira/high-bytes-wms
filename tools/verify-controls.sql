-- =====================================================================
-- Control invariant checks
--
-- Run after any schema change. Every line must print "ok".
-- A FAIL means a control the Board relies on has been weakened.
--
--   psql "$DB_URL" -f tools/verify-controls.sql
-- =====================================================================

\set ON_ERROR_STOP off
\pset pager off
\echo ''
\echo '=== HIGH BYTES WMS — control invariants ==='
\echo ''

-- ---------------------------------------------------------------------
-- Fixtures, rolled back at the end.
-- ---------------------------------------------------------------------
BEGIN;

-- The access checks normally run at commit. Here each check must fire
-- inside the block that provokes it.
SET CONSTRAINTS ALL IMMEDIATE;

INSERT INTO app_user (username, full_name, password_hash, is_active, must_change_password)
VALUES ('_verify', 'Verification Fixture', 'x', TRUE, FALSE);

INSERT INTO item (item_code, description, product_type, thickness_mm, base_uom_id)
SELECT '_VERIFY-GLASS', 'Verification fixture', 'GLASS', 6.0, id FROM uom WHERE code = 'SHEET';

INSERT INTO document (document_type_id, branch_id, serial_no, status, document_date,
                      created_by, posted_at, posted_by)
SELECT dt.id, b.id, '_VERIFY-0001', 'POSTED', CURRENT_DATE - 1, u.id, now(), u.id
  FROM document_type dt, branch b, app_user u
 WHERE dt.code = 'GRN' AND b.code = 'KGL' AND u.username = '_verify';

INSERT INTO stock_movement (document_id, branch_id, item_id, location_id, direction,
                            quantity_base_uom, signed_quantity, unit_cost, value,
                            running_balance, business_date, posted_by)
SELECT d.id, b.id, i.id, l.id, 'IN', 100, 100, 1000, 100000, 100, CURRENT_DATE - 1, u.id
  FROM document d, branch b, item i, location l, app_user u
 WHERE d.serial_no = '_VERIFY-0001' AND b.code = 'KGL'
   AND i.item_code = '_VERIFY-GLASS' AND l.code = 'KGL-MAIN' AND u.username = '_verify';

-- ---------------------------------------------------------------------
-- 1. The stock ledger is append-only
-- ---------------------------------------------------------------------
DO $$ BEGIN
  UPDATE stock_movement SET quantity_base_uom = 999 WHERE document_id IN
    (SELECT id FROM document WHERE serial_no = '_VERIFY-0001');
  RAISE WARNING 'FAIL  1a  ledger UPDATE was accepted';
EXCEPTION WHEN others THEN RAISE NOTICE 'ok    1a  ledger refuses UPDATE';
END $$;

DO $$ BEGIN
  DELETE FROM stock_movement WHERE document_id IN
    (SELECT id FROM document WHERE serial_no = '_VERIFY-0001');
  RAISE WARNING 'FAIL  1b  ledger DELETE was accepted';
EXCEPTION WHEN others THEN RAISE NOTICE 'ok    1b  ledger refuses DELETE';
END $$;

-- ---------------------------------------------------------------------
-- 2. Direction and sign must agree
-- ---------------------------------------------------------------------
DO $$ BEGIN
  INSERT INTO stock_movement (document_id, branch_id, item_id, location_id, direction,
                              quantity_base_uom, signed_quantity, running_balance,
                              business_date, posted_by)
  SELECT d.id, b.id, i.id, l.id, 'OUT', 5, 5, 95, CURRENT_DATE, u.id
    FROM document d, branch b, item i, location l, app_user u
   WHERE d.serial_no = '_VERIFY-0001' AND b.code = 'KGL'
     AND i.item_code = '_VERIFY-GLASS' AND l.code = 'KGL-MAIN' AND u.username = '_verify';
  RAISE WARNING 'FAIL  2   OUT with a positive signed quantity was accepted';
EXCEPTION WHEN check_violation THEN RAISE NOTICE 'ok    2   direction and sign must agree';
END $$;

-- ---------------------------------------------------------------------
-- 3. A locked business date refuses movements
-- ---------------------------------------------------------------------
INSERT INTO daily_close (branch_id, business_date, status, locked_at)
SELECT id, CURRENT_DATE - 1, 'LOCKED', now() FROM branch WHERE code = 'KGL'
ON CONFLICT (branch_id, business_date) DO UPDATE SET status = 'LOCKED', locked_at = now();

DO $$ BEGIN
  INSERT INTO stock_movement (document_id, branch_id, item_id, location_id, direction,
                              quantity_base_uom, signed_quantity, running_balance,
                              business_date, posted_by)
  SELECT d.id, b.id, i.id, l.id, 'OUT', 5, -5, 95, CURRENT_DATE - 1, u.id
    FROM document d, branch b, item i, location l, app_user u
   WHERE d.serial_no = '_VERIFY-0001' AND b.code = 'KGL'
     AND i.item_code = '_VERIFY-GLASS' AND l.code = 'KGL-MAIN' AND u.username = '_verify';
  RAISE WARNING 'FAIL  3   a movement was backdated into a locked day';
EXCEPTION WHEN others THEN RAISE NOTICE 'ok    3   locked business date refuses movements';
END $$;

-- ---------------------------------------------------------------------
-- 4. A posted document cannot be altered
-- ---------------------------------------------------------------------
DO $$ BEGIN
  UPDATE document SET document_date = DATE '2020-01-01' WHERE serial_no = '_VERIFY-0001';
  RAISE WARNING 'FAIL  4   a posted document was altered';
EXCEPTION WHEN others THEN RAISE NOTICE 'ok    4   posted documents are immutable';
END $$;

-- ---------------------------------------------------------------------
-- 5. Approvals are immutable, and one signature per user per document
-- ---------------------------------------------------------------------
DO $$
DECLARE doc UUID; step UUID; usr UUID;
BEGIN
  SELECT id INTO doc FROM document WHERE serial_no = '_VERIFY-0001';
  SELECT id INTO usr FROM app_user WHERE username = '_verify';
  SELECT ws.id INTO step FROM workflow_step ws
    JOIN workflow_definition wd ON wd.id = ws.workflow_definition_id
    JOIN document_type dt ON dt.id = wd.document_type_id AND dt.code = 'GRN'
   ORDER BY ws.sequence_no LIMIT 1;

  INSERT INTO document_approval (document_id, workflow_step_id, actor_user_id,
                                 actor_name, actor_role_label, decision)
  VALUES (doc, step, usr, 'Verification Fixture', 'Fixture', 'APPROVED');

  BEGIN
    UPDATE document_approval SET decision = 'REJECTED' WHERE document_id = doc;
    RAISE WARNING 'FAIL  5a  an approval was rewritten';
  EXCEPTION WHEN others THEN RAISE NOTICE 'ok    5a  approvals are immutable';
  END;

  BEGIN
    SELECT ws.id INTO step FROM workflow_step ws
      JOIN workflow_definition wd ON wd.id = ws.workflow_definition_id
      JOIN document_type dt ON dt.id = wd.document_type_id AND dt.code = 'GRN'
     ORDER BY ws.sequence_no OFFSET 1 LIMIT 1;
    INSERT INTO document_approval (document_id, workflow_step_id, actor_user_id,
                                   actor_name, actor_role_label, decision)
    VALUES (doc, step, usr, 'Verification Fixture', 'Fixture', 'APPROVED');
    RAISE WARNING 'FAIL  5b  one user signed two steps on the same document';
  EXCEPTION WHEN unique_violation THEN RAISE NOTICE 'ok    5b  one signature per user per document';
  END;
END $$;

-- ---------------------------------------------------------------------
-- 6. The audit log is append-only
-- ---------------------------------------------------------------------
INSERT INTO audit_log (entity_name, action, actor_name) VALUES ('_verify', 'CREATE', 'Fixture');

DO $$ BEGIN
  UPDATE audit_log SET actor_name = 'Someone Else' WHERE entity_name = '_verify';
  RAISE WARNING 'FAIL  6a  an audit row was rewritten';
EXCEPTION WHEN others THEN RAISE NOTICE 'ok    6a  audit log refuses UPDATE';
END $$;

DO $$ BEGIN
  DELETE FROM audit_log WHERE entity_name = '_verify';
  RAISE WARNING 'FAIL  6b  an audit row was deleted';
EXCEPTION WHEN others THEN RAISE NOTICE 'ok    6b  audit log refuses DELETE';
END $$;

-- ---------------------------------------------------------------------
-- 7. Workflow definitions cannot overlap
-- ---------------------------------------------------------------------
DO $$ BEGIN
  INSERT INTO workflow_definition (document_type_id, version, effective_from, effective_to, basis)
  SELECT id, 999, DATE '2026-09-01', DATE '2027-06-01', 'POLICY_2026'
    FROM document_type WHERE code = 'DAO';
  RAISE WARNING 'FAIL  7   two workflow definitions cover the same day';
EXCEPTION WHEN exclusion_violation THEN RAISE NOTICE 'ok    7   workflow definitions cannot overlap';
END $$;

-- ---------------------------------------------------------------------
-- 8. Both chains exist and select by date
-- ---------------------------------------------------------------------
DO $$
DECLARE old_basis TEXT; new_basis TEXT;
BEGIN
  SELECT wd.basis INTO old_basis FROM workflow_definition wd
    JOIN document_type dt ON dt.id = wd.document_type_id AND dt.code = 'DAO'
   WHERE DATE '2026-12-31' >= wd.effective_from
     AND (wd.effective_to IS NULL OR DATE '2026-12-31' < wd.effective_to);

  SELECT wd.basis INTO new_basis FROM workflow_definition wd
    JOIN document_type dt ON dt.id = wd.document_type_id AND dt.code = 'DAO'
   WHERE DATE '2027-01-01' >= wd.effective_from
     AND (wd.effective_to IS NULL OR DATE '2027-01-01' < wd.effective_to);

  IF old_basis = 'POLICY_2026' AND new_basis = 'RESTRUCTURE_2027' THEN
    RAISE NOTICE 'ok    8   the 1 Jan 2027 chain switch works';
  ELSE
    RAISE WARNING 'FAIL  8   chain selection wrong: 2026 gave %, 2027 gave %', old_basis, new_basis;
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- 9. Segregation: whoever manages access holds nothing operational
-- ---------------------------------------------------------------------
DO $$
DECLARE n INT;
BEGIN
  SELECT COUNT(*) INTO n
    FROM role r
    JOIN role_permission rp ON rp.role_id = r.id
    JOIN permission p ON p.id = rp.permission_id
   WHERE r.code = 'SYS_ADMIN'
     AND p.duty IN ('TRANSACT', 'CONFIGURE');
  IF n = 0 THEN
    RAISE NOTICE 'ok    9   the administrator role holds nothing operational';
  ELSE
    RAISE WARNING 'FAIL  9   SYS_ADMIN holds % operational permissions', n;
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- 10. Glass must carry a thickness
-- ---------------------------------------------------------------------
DO $$ BEGIN
  INSERT INTO item (item_code, description, product_type, base_uom_id)
  SELECT '_VERIFY-NOTHICK', 'No thickness', 'GLASS', id FROM uom WHERE code = 'SHEET';
  RAISE WARNING 'FAIL 10   glass without a thickness was accepted';
EXCEPTION WHEN check_violation THEN RAISE NOTICE 'ok   10   glass must carry a thickness';
END $$;

-- ---------------------------------------------------------------------
-- Access fixtures: one person per check, so no check sees another's
-- grants. Every grant names the fixture as the one who made it.
-- ---------------------------------------------------------------------
INSERT INTO app_user (username, full_name, password_hash, is_active, must_change_password)
VALUES ('_verify_sod',    'Verification Segregation',   'x', TRUE, FALSE),
       ('_verify_admin',  'Verification Admin',         'x', TRUE, FALSE),
       ('_verify_signer', 'Verification Signer',        'x', TRUE, FALSE),
       ('_verify_md',     'Verification Master Data',   'x', TRUE, FALSE),
       ('_verify_move',   'Verification Job Change',    'x', TRUE, FALSE),
       ('_verify_hist',   'Verification History',       'x', TRUE, FALSE),
       ('_verify_self',   'Verification Self',          'x', TRUE, FALSE),
       ('_verify_new',    'Verification New Grants',    'x', TRUE, FALSE),
       ('_verify_split',  'Verification Split Rights',  'x', TRUE, FALSE),
       ('_verify_copy',   'Verification Copied Rights', 'x', TRUE, FALSE),
       ('_verify_store',  'Verification Store Keeper',  'x', TRUE, FALSE),
       ('_verify_ic1',    'Verification Controller 1',  'x', TRUE, FALSE),
       ('_verify_ic2',    'Verification Controller 2',  'x', TRUE, FALSE),
       ('_verify_rv',     'Verification Verifier',      'x', TRUE, FALSE),
       ('_verify_mig',    'Verification Migration',     'x', TRUE, FALSE);

-- Roles made at runtime: nothing in the policy names them.
INSERT INTO role (code, name) VALUES
       ('_VERIFY_APPROVER', 'Verification Approver'),
       ('_VERIFY_MIXED',    'Verification Mixed'),
       ('_VERIFY_ITEMS',    'Verification Item Keeper'),
       ('_VERIFY_BOTH',     'Verification Both Sides'),
       ('_VERIFY_ASSURE',   'Verification Assurance Copy'),
       ('_VERIFY_FINANCE',  'Verification Finance Copy'),
       ('_VERIFY_STORE',    'Verification Store Keeper'),
       ('_VERIFY_CLOSER',   'Verification Day Closer'),
       ('_VERIFY_DVERIFY',  'Verification Dispatch Verifier');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM role r
  JOIN permission p ON (r.code, p.code) IN (
       ('_VERIFY_APPROVER', 'dispatch.approve'),  -- Finance's
       ('_VERIFY_MIXED',    'admin.users'),
       ('_VERIFY_MIXED',    'stock.view'),
       ('_VERIFY_ITEMS',    'item.manage'),
       ('_VERIFY_BOTH',     'count.verify'),      -- the Internal Controller's, not Finance's
       ('_VERIFY_ASSURE',   'count.verify'),
       ('_VERIFY_FINANCE',  'count.approve'),     -- Finance's, not the Internal Controller's
       ('_VERIFY_STORE',    'receiving.create'),  -- the Warehouse Manager's
       ('_VERIFY_STORE',    'count.enter'),
       ('_VERIFY_CLOSER',   'close.lock'),        -- the Managing Director's
       ('_VERIFY_DVERIFY',  'dispatch.verify'));  -- the Warehouse Manager's and the Internal Controller's

-- A grant of a role to a user, made by the verification fixture.
CREATE FUNCTION pg_temp.grant_role(p_user TEXT, p_role TEXT,
                                   p_from DATE DEFAULT CURRENT_DATE, p_to DATE DEFAULT NULL)
RETURNS UUID AS $$
    INSERT INTO user_role (user_id, role_id, valid_from, valid_to, assigned_by)
    SELECT u.id, r.id, p_from, p_to, g.id
      FROM app_user u, role r, app_user g
     WHERE u.username = p_user AND r.code = p_role AND g.username = '_verify'
    RETURNING id;
$$ LANGUAGE sql;

-- ---------------------------------------------------------------------
-- 11. Segregation of duties: a forbidden pair is refused on one person
-- ---------------------------------------------------------------------
DO $$ BEGIN
  PERFORM pg_temp.grant_role('_verify_sod', 'WH_MANAGER');
  PERFORM pg_temp.grant_role('_verify_sod', 'INTERNAL_CTRL');
  RAISE WARNING 'FAIL 11   one person was given Warehouse Manager and Internal Controller';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   11   a segregation pair is refused on one person';
END $$;

-- ---------------------------------------------------------------------
-- 12. Whoever administers access takes no part in operations, for roles
--     made at runtime too, which no segregation rule names
-- ---------------------------------------------------------------------
DO $$ BEGIN
  PERFORM pg_temp.grant_role('_verify_admin', 'SYS_ADMIN');
  PERFORM pg_temp.grant_role('_verify_admin', '_VERIFY_APPROVER');
  RAISE WARNING 'FAIL 12a  an administrator was also given an approval right';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   12a  an administrator cannot also hold a transactional role';
END $$;

DO $$ BEGIN
  INSERT INTO role_permission (role_id, permission_id)
  SELECT r.id, p.id FROM role r, permission p WHERE r.code = '_VERIFY_MIXED' AND p.code = 'dispatch.approve';
  RAISE WARNING 'FAIL 12b  a role was given administration and an approval right';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   12b  a role cannot carry administration and transactions';
END $$;

-- An UPDATE reaches the same place as an INSERT, and is judged the same.
DO $$ BEGIN
  UPDATE role_permission SET permission_id = (SELECT id FROM permission WHERE code = 'dispatch.approve')
   WHERE role_id = (SELECT id FROM role WHERE code = '_VERIFY_MIXED')
     AND permission_id = (SELECT id FROM permission WHERE code = 'stock.view');
  RAISE WARNING 'FAIL 12c  an UPDATE gave a role administration and an approval right';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   12c  rewriting a permission is judged like adding one';
END $$;

-- A role that signs an approval step is operational without a single permission.
DO $$ BEGIN
  PERFORM pg_temp.grant_role('_verify_signer', 'SYS_ADMIN');
  PERFORM pg_temp.grant_role('_verify_signer', 'INV_TX_OFFICER');
  RAISE WARNING 'FAIL 12d  an administrator was also given a role that signs approval steps';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   12d  an administrator cannot also sign approval steps';
END $$;

DO $$ BEGIN
  PERFORM pg_temp.grant_role('_verify_md', 'SYS_ADMIN');
  PERFORM pg_temp.grant_role('_verify_md', '_VERIFY_ITEMS');
  RAISE WARNING 'FAIL 12e  an administrator was also given master-data rights';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   12e  an administrator cannot also manage master data';
END $$;

-- A role that administers cannot be made a signer of an approval chain.
DO $$ BEGIN
  INSERT INTO workflow_step (workflow_definition_id, sequence_no, required_role_id, action_label)
  SELECT wd.id, 99, r.id, 'APPROVE'
    FROM workflow_definition wd
    JOIN document_type dt ON dt.id = wd.document_type_id AND dt.code = 'TRF'
    JOIN role r ON r.code = '_VERIFY_MIXED'
   ORDER BY wd.effective_from DESC
   LIMIT 1;
  RAISE WARNING 'FAIL 12g  an administration role was made to sign an approval step';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   12g  an administration role cannot sign approval steps';
END $$;

-- A job change is not a conflict: administration ending before the
-- transactional role begins is allowed.
DO $$ BEGIN
  PERFORM pg_temp.grant_role('_verify_move', 'SYS_ADMIN', CURRENT_DATE, CURRENT_DATE + 30);
  PERFORM pg_temp.grant_role('_verify_move', 'FINANCE',   CURRENT_DATE + 31);
  RAISE NOTICE 'ok   12f  administration may be followed by a transactional role';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE WARNING 'FAIL 12f  a job change was refused as a conflict';
END $$;

-- ---------------------------------------------------------------------
-- 13. A role assignment is history: revoked, never rewritten or deleted
-- ---------------------------------------------------------------------
DO $$
DECLARE a UUID; g UUID;
BEGIN
  a := pg_temp.grant_role('_verify_hist', 'SALES');
  SELECT id INTO g FROM app_user WHERE username = '_verify';

  BEGIN
    DELETE FROM user_role WHERE id = a;
    RAISE WARNING 'FAIL 13a  a role assignment was deleted';
  EXCEPTION WHEN others THEN RAISE NOTICE 'ok   13a  role assignments cannot be deleted';
  END;

  BEGIN
    UPDATE user_role SET valid_from = CURRENT_DATE - 365 WHERE id = a;
    RAISE WARNING 'FAIL 13b  a role assignment was backdated';
  EXCEPTION WHEN others THEN RAISE NOTICE 'ok   13b  role assignments cannot be rewritten';
  END;

  BEGIN
    UPDATE user_role SET id = gen_random_uuid() WHERE id = a;
    RAISE WARNING 'FAIL 13c  a role assignment was given another identity';
  EXCEPTION WHEN others THEN RAISE NOTICE 'ok   13c  a role assignment keeps its identity';
  END;

  BEGIN
    UPDATE user_role SET revoked_at = now(), revoke_reason = 'Verification' WHERE id = a;
    RAISE WARNING 'FAIL 13d  a revocation naming nobody was accepted';
  EXCEPTION WHEN check_violation THEN RAISE NOTICE 'ok   13d  a revocation names who made it';
  END;

  BEGIN
    UPDATE user_role SET revoked_at = now(), revoked_by = g, revoke_reason = '  ' WHERE id = a;
    RAISE WARNING 'FAIL 13e  a revocation without a reason was accepted';
  EXCEPTION WHEN check_violation THEN RAISE NOTICE 'ok   13e  a revocation says why';
  END;

  UPDATE user_role SET revoked_at = now(), revoked_by = g, revoke_reason = 'Verification' WHERE id = a;
  BEGIN
    UPDATE user_role SET revoked_at = NULL, revoked_by = NULL, revoke_reason = NULL WHERE id = a;
    RAISE WARNING 'FAIL 13f  a revoked role assignment was restored';
  EXCEPTION WHEN others THEN RAISE NOTICE 'ok   13f  a revocation is final';
  END;
END $$;

-- ---------------------------------------------------------------------
-- 14. Nobody grants themselves a role, and a grant is honest about who
--     made it and from when
-- ---------------------------------------------------------------------
DO $$ BEGIN
  INSERT INTO user_role (user_id, role_id, assigned_by)
  SELECT u.id, r.id, u.id FROM app_user u, role r WHERE u.username = '_verify_self' AND r.code = 'SALES';
  RAISE WARNING 'FAIL 14a  a user granted themselves a role';
EXCEPTION WHEN check_violation THEN RAISE NOTICE 'ok   14a  nobody grants themselves a role';
END $$;

DO $$ BEGIN
  INSERT INTO user_role (user_id, role_id)
  SELECT u.id, r.id FROM app_user u, role r WHERE u.username = '_verify_new' AND r.code = 'SALES';
  RAISE WARNING 'FAIL 14b  a grant naming nobody was accepted';
EXCEPTION WHEN check_violation THEN RAISE NOTICE 'ok   14b  a grant names who made it';
END $$;

DO $$ BEGIN
  PERFORM pg_temp.grant_role('_verify_new', 'SALES', CURRENT_DATE - 1);
  RAISE WARNING 'FAIL 14c  a grant was backdated';
EXCEPTION WHEN check_violation THEN RAISE NOTICE 'ok   14c  a grant cannot start in the past';
END $$;

-- ---------------------------------------------------------------------
-- 15. What the policy defines changes only by migration
-- ---------------------------------------------------------------------
DO $$ BEGIN
  INSERT INTO role_permission (role_id, permission_id)
  SELECT r.id, p.id FROM role r, permission p WHERE r.code = 'INTERNAL_CTRL' AND p.code = 'receiving.post';
  RAISE WARNING 'FAIL 15a  the Internal Controller was given a posting right at runtime';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   15a  a policy role cannot gain a permission';
END $$;

DO $$ BEGIN
  DELETE FROM role_permission
   WHERE role_id = (SELECT id FROM role WHERE code = 'WH_MANAGER')
     AND permission_id = (SELECT id FROM permission WHERE code = 'dispatch.verify');
  RAISE WARNING 'FAIL 15b  a policy role lost a permission at runtime';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   15b  a policy role cannot lose a permission';
END $$;

DO $$ BEGIN
  INSERT INTO role_permission (role_id, permission_id)
  SELECT r.id, p.id FROM role r, permission p WHERE r.code = 'HEAD_INVENTORY' AND p.code = 'transfer.view';
  RAISE WARNING 'FAIL 15c  a chain-signing role was changed at runtime';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   15c  a role that signs a chain step is the policy''s too';
END $$;

DO $$ BEGIN
  UPDATE permission SET action = 'VIEW' WHERE code = 'dispatch.release';
  RAISE WARNING 'FAIL 15d  a transactional permission was reclassified as read-only';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   15d  a permission cannot be reclassified';
END $$;

DO $$ BEGIN
  UPDATE sod_rule SET is_active = FALSE WHERE enforcement = 'BLOCK';
  RAISE WARNING 'FAIL 15e  the segregation rules were switched off';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   15e  the segregation rules cannot be changed';
END $$;

DO $$ BEGIN
  UPDATE role SET is_protected = FALSE WHERE code = 'INTERNAL_CTRL';
  RAISE WARNING 'FAIL 15f  a protected role was unprotected';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   15f  a protected role stays protected';
END $$;

-- ---------------------------------------------------------------------
-- 16. Segregation follows the rights, not the role's name
-- ---------------------------------------------------------------------
DO $$ BEGIN
  INSERT INTO role_permission (role_id, permission_id)
  SELECT r.id, p.id FROM role r, permission p WHERE r.code = '_VERIFY_BOTH' AND p.code = 'count.approve';
  RAISE WARNING 'FAIL 16a  one role was given the rights of both sides of a segregation pair';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   16a  one role cannot carry both sides of a pair';
END $$;

DO $$ BEGIN
  PERFORM pg_temp.grant_role('_verify_split', '_VERIFY_ASSURE');
  PERFORM pg_temp.grant_role('_verify_split', '_VERIFY_FINANCE');
  RAISE WARNING 'FAIL 16b  two new roles carried a forbidden pair to one person';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   16b  a pair split across new roles is refused';
END $$;

DO $$ BEGIN
  PERFORM pg_temp.grant_role('_verify_copy', 'FINANCE');
  PERFORM pg_temp.grant_role('_verify_copy', '_VERIFY_ASSURE');
  RAISE WARNING 'FAIL 16c  the Internal Controller''s rights reached Finance in another role';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   16c  a copied right counts as the role it came from';
END $$;

-- ...but only against the pairs its rights fall into: warehouse rights
-- next to Finance, a pair the Board allows, stay allowed.
DO $$ BEGIN
  PERFORM pg_temp.grant_role('_verify_store', 'FINANCE');
  PERFORM pg_temp.grant_role('_verify_store', '_VERIFY_STORE');
  RAISE NOTICE 'ok   16d  a new role is judged only against the pairs its rights fall into';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE WARNING 'FAIL 16d  a combination no rule forbids was refused';
END $$;

-- ---------------------------------------------------------------------
-- 17. Role names are unique: holders and the audit trail read the name
-- ---------------------------------------------------------------------
DO $$ BEGIN
  INSERT INTO role (code, name) VALUES ('_VERIFY_TWIN', 'finance department');
  RAISE WARNING 'FAIL 17   two roles share a name';
EXCEPTION WHEN unique_violation THEN RAISE NOTICE 'ok   17   role names are unique';
END $$;

-- ---------------------------------------------------------------------
-- 18. Assurance, rights the policy has not placed, and the policy's roles
-- ---------------------------------------------------------------------
DO $$ BEGIN
  PERFORM pg_temp.grant_role('_verify_ic1', 'INTERNAL_CTRL');
  PERFORM pg_temp.grant_role('_verify_ic1', '_VERIFY_CLOSER');
  RAISE WARNING 'FAIL 18a  the Internal Controller was also given a day-close right';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   18a  the Internal Controller holds no transactional right beyond its own';
END $$;

DO $$ BEGIN
  PERFORM pg_temp.grant_role('_verify_ic2', 'INTERNAL_CTRL');
  PERFORM pg_temp.grant_role('_verify_ic2', '_VERIFY_ITEMS');
  RAISE WARNING 'FAIL 18b  the Internal Controller was also given master-data rights';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   18b  the Internal Controller holds no master-data right';
END $$;

-- A right the Warehouse Manager shares with the Internal Controller does not
-- make its holder the Internal Controller: the Warehouse Manager may sit
-- with Finance, so may a dispatch verifier.
DO $$ BEGIN
  PERFORM pg_temp.grant_role('_verify_rv', 'FINANCE');
  PERFORM pg_temp.grant_role('_verify_rv', '_VERIFY_DVERIFY');
  RAISE NOTICE 'ok   18c  a right a permitted role shares is not read as the other side''s';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE WARNING 'FAIL 18c  a combination the policy allows was refused';
END $$;

-- The same person, once dispatch verification is no longer the Warehouse
-- Manager's: a migration's change to a policy role is judged for everyone.
DO $$ BEGIN
  PERFORM pg_temp.grant_role('_verify_mig', 'FINANCE');
  PERFORM pg_temp.grant_role('_verify_mig', '_VERIFY_DVERIFY');
  PERFORM set_config('highbytes.migration', 'on', true);
  DELETE FROM role_permission
   WHERE role_id = (SELECT id FROM role WHERE code = 'WH_MANAGER')
     AND permission_id = (SELECT id FROM permission WHERE code = 'dispatch.verify');
  PERFORM set_config('highbytes.migration', 'off', true);
  RAISE WARNING 'FAIL 18d  a migration left someone holding what the rules now forbid';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   18d  a change to a policy role is judged for everyone';
END $$;

DO $$ BEGIN
  INSERT INTO role_permission (role_id, permission_id)
  SELECT r.id, p.id FROM role r, permission p WHERE r.code = '_VERIFY_CLOSER' AND p.code = 'dispatch.release';
  RAISE WARNING 'FAIL 18e  a right no policy role carries was handed out';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   18e  a right the policy has not placed cannot be handed out';
END $$;

DO $$ BEGIN
  INSERT INTO role_permission (role_id, permission_id)
  SELECT r.id, p.id FROM role r, permission p WHERE r.code = 'COO' AND p.code = 'report.view';
  RAISE WARNING 'FAIL 18f  a seeded role was changed at runtime';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   18f  every role the policy seeded is fixed';
END $$;

DO $$ BEGIN
  UPDATE role SET name = 'Cleaner' WHERE code = 'INTERNAL_CTRL';
  RAISE WARNING 'FAIL 18g  a policy role was renamed';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   18g  a policy role keeps its name';
END $$;

DO $$ BEGIN
  UPDATE role SET is_active = FALSE WHERE code = 'INV_TX_OFFICER';
  RAISE WARNING 'FAIL 18h  a policy role was deactivated';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   18h  a policy role is never deactivated';
END $$;

DO $$ BEGIN
  INSERT INTO user_role (user_id, role_id, assigned_by, revoked_at, revoked_by, revoke_reason)
  SELECT u.id, r.id, g.id, TIMESTAMPTZ '2020-01-01', g.id, 'Invented'
    FROM app_user u, role r, app_user g
   WHERE u.username = '_verify_new' AND r.code = 'SALES' AND g.username = '_verify';
  RAISE WARNING 'FAIL 18i  an assignment arrived already revoked';
EXCEPTION WHEN check_violation THEN RAISE NOTICE 'ok   18i  an assignment cannot arrive already revoked';
END $$;

DO $$ BEGIN
  INSERT INTO role (code, name) VALUES ('_VERIFY_LOOKALIKE', 'Internal Contr' || chr(1086) || 'ller');
  RAISE WARNING 'FAIL 18j  a role name with a Cyrillic letter was accepted';
EXCEPTION WHEN check_violation THEN RAISE NOTICE 'ok   18j  role names are Latin script';
END $$;

ROLLBACK;

\echo ''
\echo 'Every line above must read ok. A FAIL means a control has been weakened.'
\echo ''
