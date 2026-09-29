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
-- 9. Segregation: whoever manages access holds no transactional rights
-- ---------------------------------------------------------------------
DO $$
DECLARE n INT;
BEGIN
  SELECT COUNT(*) INTO n
    FROM role r
    JOIN role_permission rp ON rp.role_id = r.id
    JOIN permission p ON p.id = rp.permission_id
   WHERE r.code = 'SYS_ADMIN'
     AND p.action IN ('POST','APPROVE','RELEASE','CREATE','VERIFY');
  IF n = 0 THEN
    RAISE NOTICE 'ok    9   the administrator role holds no transactional rights';
  ELSE
    RAISE WARNING 'FAIL  9   SYS_ADMIN holds % transactional permissions', n;
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

ROLLBACK;

\echo ''
\echo 'Every line above must read ok. A FAIL means a control has been weakened.'
\echo ''
