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
-- The one exception: "a posted receipt must have moved its stock" is judged
-- at commit so a posting may be written in any order. The fixtures post in
-- steps, so it is asked for explicitly in check 23.
SET CONSTRAINTS document_grn_posted_moved_stock DEFERRED;
SET CONSTRAINTS stock_movement_ticket_posted DEFERRED;

INSERT INTO app_user (username, full_name, password_hash, is_active, must_change_password)
VALUES ('_verify', 'Verification Fixture', 'x', TRUE, FALSE);

INSERT INTO item (item_code, description, product_type, thickness_mm, base_uom_id)
SELECT '_VERIFY-GLASS', 'Verification fixture', 'GLASS', 6.0, id FROM uom WHERE code = 'SHEET';

INSERT INTO supplier (code, name) VALUES ('_VERIFY-SUP', 'Verification Supplier');

-- ---------------------------------------------------------------------
-- Fixture helpers. They walk a receipt through its real lifecycle: the
-- controls under test are the ones that refuse anything else, so the
-- fixtures cannot take a shortcut around them.
-- ---------------------------------------------------------------------
CREATE TEMP TABLE fx (k TEXT PRIMARY KEY, id UUID NOT NULL);

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

-- One fixture person who holds exactly one role, created on first use.
CREATE FUNCTION pg_temp.holder(p_role TEXT) RETURNS UUID AS $$
DECLARE
    u     UUID;
    uname TEXT := '_verify_h_' || lower(p_role);
BEGIN
    SELECT id INTO u FROM app_user WHERE username = uname;
    IF u IS NULL THEN
        INSERT INTO app_user (username, full_name, password_hash, is_active, must_change_password)
        VALUES (uname, 'Holder ' || p_role, 'x', TRUE, FALSE) RETURNING id INTO u;
        PERFORM pg_temp.grant_role(uname, p_role);
    END IF;
    RETURN u;
END $$ LANGUAGE plpgsql;

CREATE FUNCTION pg_temp.fx_id(p_key TEXT) RETURNS UUID AS $$
    SELECT id FROM fx WHERE k = p_key;
$$ LANGUAGE sql;

CREATE FUNCTION pg_temp.verify_user() RETURNS UUID AS $$
    SELECT id FROM app_user WHERE username = '_verify';
$$ LANGUAGE sql;

-- A draft receipt with a header and one glass line.
CREATE FUNCTION pg_temp.make_grn(p_serial TEXT, p_creator UUID DEFAULT NULL,
                                 p_branch TEXT DEFAULT 'KGL', p_loc TEXT DEFAULT 'KGL-MAIN',
                                 p_customs TEXT DEFAULT NULL) RETURNS UUID AS $$
DECLARE d UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, b.id, p_serial, COALESCE(p_creator, pg_temp.verify_user())
      FROM document_type dt, branch b WHERE dt.code = 'GRN' AND b.code = p_branch
    RETURNING id INTO d;
    INSERT INTO goods_received_note (document_id, supplier_id, location_id, customs_reference)
    SELECT d, s.id, l.id, p_customs FROM supplier s, location l
     WHERE s.code = '_VERIFY-SUP' AND l.code = p_loc;
    INSERT INTO goods_received_line (document_id, line_no, item_id, uom_id, quantity, qty_base_uom,
                                     unit_price, measured_thickness_mm)
    SELECT d, 1, i.id, u.id, 100, 100, 1000, 6.0
      FROM item i, uom u WHERE i.item_code = '_VERIFY-GLASS' AND u.code = 'SHEET';
    RETURN d;
END $$ LANGUAGE plpgsql;

-- The signing step numbered p_seq in the chain a document is bound to.
CREATE FUNCTION pg_temp.step_id(p_doc UUID, p_seq INT) RETURNS UUID AS $$
    SELECT ws.id FROM workflow_step ws JOIN document d ON d.workflow_definition_id = ws.workflow_definition_id
     WHERE d.id = p_doc AND ws.sequence_no = p_seq;
$$ LANGUAGE sql;

CREATE FUNCTION pg_temp.step_role(p_doc UUID, p_seq INT) RETURNS TEXT AS $$
    SELECT r.code FROM workflow_step ws JOIN document d ON d.workflow_definition_id = ws.workflow_definition_id
      JOIN role r ON r.id = ws.required_role_id
     WHERE d.id = p_doc AND ws.sequence_no = p_seq;
$$ LANGUAGE sql;

CREATE FUNCTION pg_temp.steps_in(p_doc UUID) RETURNS INT AS $$
    SELECT COUNT(*)::int FROM workflow_step ws JOIN document d ON d.workflow_definition_id = ws.workflow_definition_id
     WHERE d.id = p_doc;
$$ LANGUAGE sql;

CREATE FUNCTION pg_temp.sign(p_doc UUID, p_seq INT, p_user UUID, p_decision TEXT DEFAULT 'APPROVED') RETURNS VOID AS $$
    INSERT INTO document_approval (document_id, workflow_step_id, actor_user_id, actor_name, actor_role_label, decision)
    VALUES (p_doc, pg_temp.step_id(p_doc, p_seq), p_user, 'placeholder', 'placeholder', p_decision);
$$ LANGUAGE sql;

-- The holder of each step's role signs steps 1..p_upto in order.
CREATE FUNCTION pg_temp.sign_upto(p_doc UUID, p_upto INT) RETURNS VOID AS $$
DECLARE n INT;
BEGIN
    FOR n IN 1..p_upto LOOP
        PERFORM pg_temp.sign(p_doc, n, pg_temp.holder(pg_temp.step_role(p_doc, n)));
    END LOOP;
END $$ LANGUAGE plpgsql;

-- A receipt taken to a state: DRAFT, PENDING, STEP1 (one signature),
-- APPROVED, or POSTED (by the Finance holder).
CREATE FUNCTION pg_temp.grn_at(p_serial TEXT, p_state TEXT, p_creator UUID DEFAULT NULL) RETURNS UUID AS $$
DECLARE d UUID;
BEGIN
    d := pg_temp.make_grn(p_serial, p_creator);
    IF p_state = 'DRAFT' THEN RETURN d; END IF;
    UPDATE document SET status = 'PENDING' WHERE id = d;
    IF p_state = 'PENDING' THEN RETURN d; END IF;
    IF p_state = 'STEP1' THEN PERFORM pg_temp.sign_upto(d, 1); RETURN d; END IF;
    PERFORM pg_temp.sign_upto(d, pg_temp.steps_in(d));
    UPDATE document SET status = 'APPROVED' WHERE id = d;
    IF p_state = 'APPROVED' THEN RETURN d; END IF;
    UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('FINANCE') WHERE id = d;
    RETURN d;
END $$ LANGUAGE plpgsql;

-- The transaction ticket behind a receipt: document, ticket, one line per
-- receipt line. The stock movements are separate, so each can be tested.
CREATE FUNCTION pg_temp.make_ticket(p_grn UUID, p_poster UUID, p_serial TEXT) RETURNS UUID AS $$
DECLARE t UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, g.branch_id, p_serial, p_poster
      FROM document_type dt, document g WHERE dt.code = 'TT' AND g.id = p_grn
    RETURNING id INTO t;
    INSERT INTO transaction_ticket (document_id, movement_type, direction, to_location_id,
                                    source_document_id, customs_reference)
    SELECT t, 'RECEIPT', 'IN', n.location_id, p_grn, n.customs_reference
      FROM goods_received_note n WHERE n.document_id = p_grn;
    INSERT INTO ticket_line (ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom, storage_bin_id)
    SELECT t, l.line_no, l.item_id, l.quantity, l.uom_id, l.qty_base_uom, l.storage_bin_id
      FROM goods_received_line l WHERE l.document_id = p_grn;
    RETURN t;
END $$ LANGUAGE plpgsql;

CREATE FUNCTION pg_temp.move_ticket(p_ticket UUID, p_poster UUID, p_date DATE,
                                    p_qty NUMERIC DEFAULT NULL) RETURNS VOID AS $$
    INSERT INTO stock_movement (ticket_line_id, document_id, branch_id, item_id, location_id, storage_bin_id,
                                direction, quantity_base_uom, signed_quantity, unit_cost, value,
                                running_balance, business_date, posted_by)
    SELECT tl.id, d.id, d.branch_id, tl.item_id, t.to_location_id, tl.storage_bin_id,
           'IN', COALESCE(p_qty, tl.qty_base_uom), COALESCE(p_qty, tl.qty_base_uom), 1000,
           COALESCE(p_qty, tl.qty_base_uom) * 1000, COALESCE(p_qty, tl.qty_base_uom), p_date, p_poster
      FROM ticket_line tl
      JOIN transaction_ticket t ON t.document_id = tl.ticket_id
      JOIN document d ON d.id = t.document_id
     WHERE tl.ticket_id = p_ticket;
$$ LANGUAGE sql;

-- Expect a statement to be refused with a given SQLSTATE (and, optionally, a
-- message containing a given phrase), so a check passes only for the reason
-- it names and not because something else happened to fail.
CREATE FUNCTION pg_temp.refuses(p_label TEXT, p_desc TEXT, p_sql TEXT, p_state TEXT,
                                p_like TEXT DEFAULT NULL) RETURNS VOID AS $$
BEGIN
    EXECUTE p_sql;
    RAISE WARNING 'FAIL % % was accepted', p_label, p_desc;
EXCEPTION WHEN OTHERS THEN
    IF SQLSTATE = p_state AND (p_like IS NULL OR SQLERRM ILIKE p_like) THEN
        RAISE NOTICE 'ok   % refused: %', p_label, p_desc;
    ELSE
        RAISE WARNING 'FAIL % % was refused for another reason (%): %', p_label, p_desc, SQLSTATE, SQLERRM;
    END IF;
END $$ LANGUAGE plpgsql;

CREATE FUNCTION pg_temp.accepts(p_label TEXT, p_desc TEXT, p_sql TEXT) RETURNS VOID AS $$
BEGIN
    EXECUTE p_sql;
    RAISE NOTICE 'ok   % accepted: %', p_label, p_desc;
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'FAIL % % was refused (%): %', p_label, p_desc, SQLSTATE, SQLERRM;
END $$ LANGUAGE plpgsql;

-- The receipt every later check leans on: raised, signed by the whole chain,
-- approved, posted by Finance, its ticket and ledger row written.
DO $$
DECLARE
    fin UUID := pg_temp.holder('FINANCE');
    g   UUID;
    tt  UUID;
BEGIN
    g  := pg_temp.grn_at('_VERIFY-0001', 'POSTED');
    tt := pg_temp.make_ticket(g, fin, '_VERIFY-0001-TT');
    PERFORM pg_temp.move_ticket(tt, fin, kigali_today());
    UPDATE document SET status = 'POSTED', posted_by = fin WHERE id = tt;
    INSERT INTO fx VALUES ('g_ok', g), ('tt_ok', tt);
END $$;

-- ---------------------------------------------------------------------
-- 1. The stock ledger is append-only
-- ---------------------------------------------------------------------
DO $$ BEGIN
  UPDATE stock_movement SET quantity_base_uom = 999 WHERE document_id = pg_temp.fx_id('tt_ok');
  RAISE WARNING 'FAIL  1a  ledger UPDATE was accepted';
EXCEPTION WHEN others THEN RAISE NOTICE 'ok    1a  ledger refuses UPDATE';
END $$;

DO $$ BEGIN
  DELETE FROM stock_movement WHERE document_id = pg_temp.fx_id('tt_ok');
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
  SELECT d.id, b.id, i.id, l.id, 'OUT', 5, 5, 95, kigali_today(), u.id
    FROM document d, branch b, item i, location l, app_user u
   WHERE d.serial_no = '_VERIFY-0001-TT' AND b.code = 'KGL'
     AND i.item_code = '_VERIFY-GLASS' AND l.code = 'KGL-MAIN' AND u.username = '_verify';
  RAISE WARNING 'FAIL  2  OUT with a positive signed quantity was accepted';
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
   WHERE d.serial_no = '_VERIFY-0001-TT' AND b.code = 'KGL'
     AND i.item_code = '_VERIFY-GLASS' AND l.code = 'KGL-MAIN' AND u.username = '_verify';
  RAISE WARNING 'FAIL  3   a movement was backdated into a locked day';
EXCEPTION WHEN restrict_violation THEN RAISE NOTICE 'ok    3   locked business date refuses movements';
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
DO $$ BEGIN
  UPDATE document_approval SET decision = 'REJECTED' WHERE document_id = pg_temp.fx_id('g_ok');
  RAISE WARNING 'FAIL  5a  an approval was rewritten';
EXCEPTION WHEN others THEN RAISE NOTICE 'ok    5a  approvals are immutable';
END $$;

DO $$ BEGIN
  DELETE FROM document_approval WHERE document_id = pg_temp.fx_id('g_ok');
  RAISE WARNING 'FAIL  5c  an approval was deleted';
EXCEPTION WHEN others THEN RAISE NOTICE 'ok    5c  approvals cannot be deleted';
END $$;

-- One person holding the roles of steps 1 and 2 passes every signing rule
-- for both, so what stops the second signature is the one-per-user index.
DO $$
DECLARE d UUID; u UUID;
BEGIN
  d := pg_temp.grn_at('_VERIFY-5B', 'PENDING');
  INSERT INTO app_user (username, full_name, password_hash, is_active, must_change_password)
  VALUES ('_verify_two', 'Verification Two Roles', 'x', TRUE, FALSE) RETURNING id INTO u;
  PERFORM pg_temp.grant_role('_verify_two', pg_temp.step_role(d, 1));
  PERFORM pg_temp.grant_role('_verify_two', pg_temp.step_role(d, 2));
  PERFORM pg_temp.sign(d, 1, u);
  BEGIN
    PERFORM pg_temp.sign(d, 2, u);
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

-- =====================================================================
-- Goods Received (V11). Every check states the rule it proves, and the
-- refusal must carry SQLSTATE 23Z02 (a workflow or document control) or
-- 23514 (a malformed line) with the reason named, so a check cannot pass
-- because something unrelated failed.
-- =====================================================================

-- Receipts in each state the checks below need.
DO $$
DECLARE
    creator UUID;
    d       UUID;
BEGIN
    INSERT INTO fx VALUES
        ('g_draft', pg_temp.grn_at('_VERIFY-G-DRAFT', 'DRAFT')),
        ('g_pend',  pg_temp.grn_at('_VERIFY-G-PEND',  'PENDING')),
        ('g_step1', pg_temp.grn_at('_VERIFY-G-STEP1', 'STEP1')),
        ('g_appr',  pg_temp.grn_at('_VERIFY-G-APPR',  'APPROVED'));

    -- Rejected at step 2 after step 1 approved.
    d := pg_temp.grn_at('_VERIFY-G-REJ', 'PENDING');
    PERFORM pg_temp.sign(d, 1, pg_temp.holder(pg_temp.step_role(d, 1)));
    PERFORM pg_temp.sign(d, 2, pg_temp.holder(pg_temp.step_role(d, 2)), 'REJECTED');
    UPDATE document SET status = 'REJECTED' WHERE id = d;
    INSERT INTO fx VALUES ('g_rej', d);

    -- Raised by the person who holds the role of step 2 (the creator rule).
    creator := pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('g_pend'), 2));
    INSERT INTO fx VALUES ('g_creator2', pg_temp.grn_at('_VERIFY-G-CRE', 'STEP1', creator));
END $$;

-- ---------------------------------------------------------------------
-- 19. Signed content cannot change: the note and its lines are edited only
--     while the document is a draft
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('19a', 'a submitted note''s header was edited',
  $q$UPDATE goods_received_note SET supplier_invoice_no = 'CHANGED' WHERE document_id = pg_temp.fx_id('g_pend')$q$,
  '23Z02', '%PENDING%approvers signed%');
SELECT pg_temp.refuses('19b', 'a line was added after submission',
  $q$INSERT INTO goods_received_line (document_id, line_no, item_id, uom_id, quantity, qty_base_uom, unit_price, measured_thickness_mm)
     SELECT pg_temp.fx_id('g_pend'), 2, i.id, u.id, 1, 1, 1, 6 FROM item i, uom u
      WHERE i.item_code = '_VERIFY-GLASS' AND u.code = 'SHEET'$q$,
  '23Z02', '%PENDING%approvers signed%');
SELECT pg_temp.refuses('19c', 'a line was changed after submission',
  $q$UPDATE goods_received_line SET quantity = 500, qty_base_uom = 500 WHERE document_id = pg_temp.fx_id('g_pend')$q$,
  '23Z02', '%PENDING%approvers signed%');
SELECT pg_temp.refuses('19d', 'a line was deleted after submission',
  $q$DELETE FROM goods_received_line WHERE document_id = pg_temp.fx_id('g_pend')$q$,
  '23Z02', '%PENDING%approvers signed%');
SELECT pg_temp.refuses('19e', 'a note''s header was deleted after submission',
  $q$DELETE FROM goods_received_note WHERE document_id = pg_temp.fx_id('g_pend')$q$,
  '23Z02', '%PENDING%approvers signed%');
SELECT pg_temp.refuses('19f', 'a posted note''s line was changed',
  $q$UPDATE goods_received_line SET unit_price = 1 WHERE document_id = pg_temp.fx_id('g_ok')$q$,
  '23Z02', '%POSTED%approvers signed%');
SELECT pg_temp.accepts('19g', 'a draft is still editable',
  $q$UPDATE goods_received_line SET quantity = 120, qty_base_uom = 120, supplier_quantity_base = 125
      WHERE document_id = pg_temp.fx_id('g_draft')$q$);

-- A glass line is refused without its measured thickness; a non-glass line is not.
SELECT pg_temp.refuses('19h', 'a glass line without a measured thickness was accepted',
  $q$UPDATE goods_received_line SET measured_thickness_mm = NULL WHERE document_id = pg_temp.fx_id('g_draft')$q$,
  '23514', '%thickness%');

-- Bonded receiving needs a customs reference; the reference must not be blank.
DO $$
DECLARE d UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, b.id, '_VERIFY-G-BOND', pg_temp.verify_user()
      FROM document_type dt, branch b WHERE dt.code = 'GRN' AND b.code = 'RBV'
    RETURNING id INTO d;
    INSERT INTO fx VALUES ('g_bond', d);
END $$;
SELECT pg_temp.refuses('19i', 'bonded stock was received with no customs reference',
  $q$INSERT INTO goods_received_note (document_id, supplier_id, location_id)
     SELECT pg_temp.fx_id('g_bond'), s.id, l.id FROM supplier s, location l
      WHERE s.code = '_VERIFY-SUP' AND l.code = 'RBV-BOND'$q$,
  '23514', '%customs reference%');
SELECT pg_temp.refuses('19j', 'a blank customs reference was accepted',
  $q$INSERT INTO goods_received_note (document_id, supplier_id, location_id, customs_reference)
     SELECT pg_temp.fx_id('g_bond'), s.id, l.id, '   ' FROM supplier s, location l
      WHERE s.code = '_VERIFY-SUP' AND l.code = 'RBV-BOND'$q$,
  '23514', '%grn_customs_reference_not_blank%');
SELECT pg_temp.accepts('19k', 'a bonded receipt with its customs reference is accepted',
  $q$INSERT INTO goods_received_note (document_id, supplier_id, location_id, customs_reference)
     SELECT pg_temp.fx_id('g_bond'), s.id, l.id, 'C-2026-001' FROM supplier s, location l
      WHERE s.code = '_VERIFY-SUP' AND l.code = 'RBV-BOND'$q$);

-- The location must be at the document's branch.
DO $$
DECLARE d UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, b.id, '_VERIFY-G-LOC', pg_temp.verify_user()
      FROM document_type dt, branch b WHERE dt.code = 'GRN' AND b.code = 'KGL'
    RETURNING id INTO d;
    INSERT INTO fx VALUES ('g_loc', d);
END $$;
SELECT pg_temp.refuses('19l', 'a receipt at Gahanga was put into a Rubavu location',
  $q$INSERT INTO goods_received_note (document_id, supplier_id, location_id, customs_reference)
     SELECT pg_temp.fx_id('g_loc'), s.id, l.id, 'C-1' FROM supplier s, location l
      WHERE s.code = '_VERIFY-SUP' AND l.code = 'RBV-BOND'$q$,
  '23514', '%not at the branch%');

-- A bin must belong to the receiving location; the base quantity must follow
-- from the entered quantity and the unit.
INSERT INTO storage_bin (location_id, bin_code)
SELECT id, '_VERIFY-BIN' FROM location WHERE code = 'KGL-CUT';
SELECT pg_temp.refuses('19m', 'a bin from another location was accepted',
  $q$UPDATE goods_received_line SET storage_bin_id = (SELECT id FROM storage_bin WHERE bin_code = '_VERIFY-BIN')
      WHERE document_id = pg_temp.fx_id('g_draft')$q$,
  '23514', '%not in the location%');
SELECT pg_temp.refuses('19n', 'a base quantity that does not follow from the entered one was accepted',
  $q$UPDATE goods_received_line SET qty_base_uom = 999 WHERE document_id = pg_temp.fx_id('g_draft')$q$,
  '23514', '%base unit%');
SELECT pg_temp.refuses('19o', 'a note with no lines was submitted',
  $q$UPDATE document SET status = 'PENDING' WHERE id = pg_temp.fx_id('g_bond')$q$,
  '23Z02', '%no lines%');
SELECT pg_temp.refuses('19p', 'a negative landed cost was accepted',
  $q$UPDATE goods_received_note SET freight_rwf = -1 WHERE document_id = pg_temp.fx_id('g_draft')$q$,
  '23514', '%grn_landed_costs_not_negative%');
SELECT pg_temp.refuses('19q', 'an RWF invoice with a rate other than 1 was accepted',
  $q$UPDATE goods_received_note SET exchange_rate = 1.5 WHERE document_id = pg_temp.fx_id('g_draft')$q$,
  '23514', '%grn_rwf_rate_is_one%');

-- ---------------------------------------------------------------------
-- 20. The spine: the date is the creation date, the chain binds to it, the
--     header is fixed
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('20a', 'a document was backdated',
  $q$INSERT INTO document (document_type_id, branch_id, serial_no, created_by, document_date)
     SELECT dt.id, b.id, '_VERIFY-BACKDATED', pg_temp.verify_user(), kigali_today() - 1
       FROM document_type dt, branch b WHERE dt.code = 'GRN' AND b.code = 'KGL'$q$,
  '23Z02', '%dated the day it is created%');
SELECT pg_temp.refuses('20b', 'a document was dated into the future',
  $q$INSERT INTO document (document_type_id, branch_id, serial_no, created_by, document_date)
     SELECT dt.id, b.id, '_VERIFY-FUTURE', pg_temp.verify_user(), DATE '2027-01-01'
       FROM document_type dt, branch b WHERE dt.code = 'GRN' AND b.code = 'KGL'$q$,
  '23Z02', '%dated the day it is created%');

DO $$
DECLARE live UUID; bound UUID;
BEGIN
  SELECT wd.id INTO live FROM workflow_definition wd
    JOIN document_type dt ON dt.id = wd.document_type_id AND dt.code = 'GRN'
   WHERE kigali_today() >= wd.effective_from AND (wd.effective_to IS NULL OR kigali_today() < wd.effective_to);
  SELECT workflow_definition_id INTO bound FROM document WHERE id = pg_temp.fx_id('g_draft');
  IF bound = live THEN
    RAISE NOTICE 'ok   20c  a new document binds to the chain live on its creation date';
  ELSE
    RAISE WARNING 'FAIL 20c  a new document is bound to % and not to %', bound, live;
  END IF;
END $$;

SELECT pg_temp.refuses('20d', 'a document was bound to a chain that is not the one in force',
  $q$INSERT INTO document (document_type_id, branch_id, serial_no, created_by, workflow_definition_id)
     SELECT dt.id, b.id, '_VERIFY-OTHERCHAIN', pg_temp.verify_user(),
            (SELECT wd.id FROM workflow_definition wd
              WHERE wd.document_type_id = dt.id
                AND NOT (kigali_today() >= wd.effective_from AND (wd.effective_to IS NULL OR kigali_today() < wd.effective_to))
              LIMIT 1)
       FROM document_type dt, branch b WHERE dt.code = 'GRN' AND b.code = 'KGL'$q$,
  '23Z02', '%binds to the approval chain in force%');
SELECT pg_temp.refuses('20e', 'a bound chain was swapped for another',
  $q$UPDATE document SET workflow_definition_id = (SELECT id FROM workflow_definition wd
                                                     WHERE wd.document_type_id = document.document_type_id
                                                       AND wd.id <> document.workflow_definition_id LIMIT 1)
      WHERE id = pg_temp.fx_id('g_draft')$q$,
  '23Z02', '%fixed when it is created%');
SELECT pg_temp.refuses('20f', 'a draft''s date was moved',
  $q$UPDATE document SET document_date = document_date - 30 WHERE id = pg_temp.fx_id('g_draft')$q$,
  '23Z02', '%fixed when it is created%');
SELECT pg_temp.refuses('20g', 'a draft''s type was changed',
  $q$UPDATE document SET document_type_id = (SELECT id FROM document_type WHERE code = 'DAO') WHERE id = pg_temp.fx_id('g_draft')$q$,
  '23Z02', '%fixed when it is created%');
SELECT pg_temp.refuses('20h', 'a submitted document''s branch was changed',
  $q$UPDATE document SET branch_id = (SELECT id FROM branch WHERE code = 'RBV') WHERE id = pg_temp.fx_id('g_pend')$q$,
  '23Z02', '%header is fixed%');
SELECT pg_temp.refuses('20i', 'a submitted document''s notes were changed',
  $q$UPDATE document SET notes = 'edited after signing' WHERE id = pg_temp.fx_id('g_pend')$q$,
  '23Z02', '%header is fixed%');
SELECT pg_temp.refuses('20j', 'a document was inserted already posted',
  $q$INSERT INTO document (document_type_id, branch_id, serial_no, status, created_by, posted_at, posted_by)
     SELECT dt.id, b.id, '_VERIFY-INSERTED', 'POSTED', pg_temp.verify_user(), now(), pg_temp.verify_user()
       FROM document_type dt, branch b WHERE dt.code = 'GRN' AND b.code = 'KGL'$q$,
  '23Z02', '%starts as a draft%');
SELECT pg_temp.refuses('20k', 'a document was deleted',
  $q$DELETE FROM document WHERE id = pg_temp.fx_id('g_draft')$q$,
  '23Z02', '%cannot be deleted%');

-- ---------------------------------------------------------------------
-- 21. Signatures: order, role, independence
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('21a', 'step 2 signed before step 1',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('g_pend'), 2, pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('g_pend'), 2)))$q$,
  '23Z02', '%must sign%before step%');
SELECT pg_temp.refuses('21b', 'someone without the step''s role signed it',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('g_pend'), 1, pg_temp.verify_user())$q$,
  '23Z02', '%does not hold%today at this branch%');
SELECT pg_temp.refuses('21c', 'the person holding the wrong role signed a step',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('g_pend'), 1, pg_temp.holder('FINANCE'))$q$,
  '23Z02', '%does not hold%');
SELECT pg_temp.refuses('21d', 'the creator signed a step after the first',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('g_creator2'), 2,
                         (SELECT created_by FROM document WHERE id = pg_temp.fx_id('g_creator2')))$q$,
  '23Z02', '%raised%may not sign a step after the first%');
SELECT pg_temp.refuses('21e', 'a draft was signed',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('g_draft'), 1, pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('g_draft'), 1)))$q$,
  '23Z02', '%is DRAFT%awaits approval%');
SELECT pg_temp.refuses('21f', 'a rejected document was signed again',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('g_rej'), 3, pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('g_rej'), 3)))$q$,
  '23Z02', '%is REJECTED%');
SELECT pg_temp.refuses('21g', 'a step of another chain was signed',
  $q$INSERT INTO document_approval (document_id, workflow_step_id, actor_user_id, actor_name, actor_role_label, decision)
     SELECT pg_temp.fx_id('g_pend'), ws.id, pg_temp.holder('FINANCE'), 'x', 'x', 'APPROVED'
       FROM workflow_step ws JOIN workflow_definition wd ON wd.id = ws.workflow_definition_id
       JOIN document_type dt ON dt.id = wd.document_type_id AND dt.code = 'DAO'
      WHERE ws.sequence_no = 1 LIMIT 1$q$,
  '23Z02', '%not part of the approval chain%');

-- A revoked assignment, an assignment at another branch, and an account
-- that has been switched off sign nothing.
DO $$
DECLARE
    d UUID := pg_temp.fx_id('g_pend');
    r TEXT := pg_temp.step_role(pg_temp.fx_id('g_pend'), 1);
    a UUID;
BEGIN
    INSERT INTO app_user (username, full_name, password_hash, is_active, must_change_password)
    VALUES ('_verify_revoked', 'Verification Revoked', 'x', TRUE, FALSE),
           ('_verify_rbv',     'Verification Other Branch', 'x', TRUE, FALSE),
           ('_verify_off',     'Verification Switched Off', 'x', TRUE, FALSE);
    a := pg_temp.grant_role('_verify_revoked', r);
    UPDATE user_role SET revoked_at = now(), revoked_by = pg_temp.verify_user(), revoke_reason = 'Verification'
     WHERE id = a;
    INSERT INTO user_role (user_id, role_id, branch_id, assigned_by)
    SELECT u.id, rl.id, b.id, pg_temp.verify_user()
      FROM app_user u, role rl, branch b WHERE u.username = '_verify_rbv' AND rl.code = r AND b.code = 'RBV';
    PERFORM pg_temp.grant_role('_verify_off', r);
    UPDATE app_user SET is_active = FALSE, deactivated_at = now() WHERE username = '_verify_off';
END $$;
SELECT pg_temp.refuses('21h', 'a revoked role signed',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('g_pend'), 1, (SELECT id FROM app_user WHERE username = '_verify_revoked'))$q$,
  '23Z02', '%does not hold%');
SELECT pg_temp.refuses('21i', 'a role held only at another branch signed',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('g_pend'), 1, (SELECT id FROM app_user WHERE username = '_verify_rbv'))$q$,
  '23Z02', '%does not hold%');
SELECT pg_temp.refuses('21j', 'a deactivated account signed',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('g_pend'), 1, (SELECT id FROM app_user WHERE username = '_verify_off'))$q$,
  '23Z02', '%deactivated account%');

-- The signer's name and role title come from the database, not the caller.
DO $$
DECLARE n TEXT; l TEXT;
BEGIN
  SELECT actor_name, actor_role_label INTO n, l FROM document_approval
   WHERE document_id = pg_temp.fx_id('g_step1');
  IF n LIKE 'Holder %' AND l = (SELECT r.name FROM role r WHERE r.code = pg_temp.step_role(pg_temp.fx_id('g_step1'), 1)) THEN
    RAISE NOTICE 'ok   21k  a signature records the signer''s name and role from the database';
  ELSE
    RAISE WARNING 'FAIL 21k  signature recorded name % and role % as supplied', n, l;
  END IF;
END $$;

-- A role assignment at every branch (branch NULL) does sign at any branch.
SELECT pg_temp.accepts('21l', 'the step-1 role, held at every branch, signs',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('g_pend'), 1, pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('g_pend'), 1)))$q$);

-- ---------------------------------------------------------------------
-- 22. Status transitions carry their evidence
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('22a', 'a document was approved with a step unsigned',
  $q$UPDATE document SET status = 'APPROVED' WHERE id = pg_temp.fx_id('g_step1')$q$,
  '23Z02', '%not fully approved: step 2%');
SELECT pg_temp.refuses('22b', 'a document was approved with no signatures at all',
  $q$UPDATE document SET status = 'APPROVED' WHERE id = pg_temp.fx_id('g_creator2')$q$,
  '23Z02', '%not fully approved%');
SELECT pg_temp.refuses('22c', 'a document was rejected with no rejecting signature',
  $q$UPDATE document SET status = 'REJECTED' WHERE id = pg_temp.fx_id('g_step1')$q$,
  '23Z02', '%rejecting signature%');
SELECT pg_temp.refuses('22d', 'a pending document was posted',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('FINANCE') WHERE id = pg_temp.fx_id('g_step1')$q$,
  '23Z02', '%cannot move from PENDING to POSTED%');
SELECT pg_temp.refuses('22e', 'a draft skipped submission and approval',
  $q$UPDATE document SET status = 'APPROVED' WHERE id = pg_temp.fx_id('g_draft')$q$,
  '23Z02', '%cannot move from DRAFT to APPROVED%');
SELECT pg_temp.refuses('22f', 'the person who raised a receipt posted it',
  $q$UPDATE document SET status = 'POSTED', posted_by = created_by WHERE id = pg_temp.fx_id('g_appr')$q$,
  '23Z02', '%person who raised it%');
SELECT pg_temp.refuses('22g', 'a signer of the receipt posted it',
  $q$UPDATE document SET status = 'POSTED',
            posted_by = pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('g_appr'), 1))
      WHERE id = pg_temp.fx_id('g_appr')$q$,
  '23Z02', '%signed%cannot also post%');
SELECT pg_temp.refuses('22h', 'a posting named nobody',
  $q$UPDATE document SET status = 'POSTED' WHERE id = pg_temp.fx_id('g_appr')$q$,
  '23Z02', '%must name who posts%');
SELECT pg_temp.refuses('22i', 'a rejected document was revived',
  $q$UPDATE document SET status = 'PENDING' WHERE id = pg_temp.fx_id('g_rej')$q$,
  '23Z02', '%REJECTED and final%');
SELECT pg_temp.refuses('22j', 'a rejected document was re-approved',
  $q$UPDATE document SET status = 'APPROVED' WHERE id = pg_temp.fx_id('g_rej')$q$,
  '23Z02', '%REJECTED and final%');
SELECT pg_temp.refuses('22k', 'a stamp was rewritten without a status change',
  $q$UPDATE document SET posted_at = now() - interval '5 days' WHERE id = pg_temp.fx_id('g_appr')$q$,
  '23Z02', '%change only when its status does%');
SELECT pg_temp.refuses('22l', 'a cancellation named nobody',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'x' WHERE id = pg_temp.fx_id('g_bond')$q$,
  '23Z02', '%must name who cancels%');
SELECT pg_temp.accepts('22m', 'a draft can be cancelled, and keeps its serial',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('g_bond')$q$);
SELECT pg_temp.refuses('22n', 'a cancelled document was revived',
  $q$UPDATE document SET status = 'DRAFT' WHERE id = pg_temp.fx_id('g_bond')$q$,
  '23Z02', '%CANCELLED and final%');
SELECT pg_temp.refuses('22o', 'a cancelled document''s content was edited',
  $q$UPDATE goods_received_note SET supplier_invoice_no = 'x' WHERE document_id = pg_temp.fx_id('g_bond')$q$,
  '23Z02', '%CANCELLED%');


-- A document with no chain of its own (a ticket) cannot be submitted or
-- approved, and is posted only against a fully approved document.
DO $$
DECLARE t UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, b.id, '_VERIFY-TT-SOLO', pg_temp.verify_user()
      FROM document_type dt, branch b WHERE dt.code = 'TT' AND b.code = 'KGL'
    RETURNING id INTO t;
    INSERT INTO fx VALUES ('tt_solo', t);
END $$;
SELECT pg_temp.refuses('22p', 'a ticket with no chain was submitted for approval',
  $q$UPDATE document SET status = 'PENDING' WHERE id = pg_temp.fx_id('tt_solo')$q$,
  '23Z02', '%no approval chain of its own%');
SELECT pg_temp.refuses('22q', 'a ticket that answers to nothing was posted',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('FINANCE') WHERE id = pg_temp.fx_id('tt_solo')$q$,
  '23Z02', '%answers to no document with an approval chain%');

-- ---------------------------------------------------------------------
-- 23. The ledger refuses a movement whose supporting document is not fully
--     approved, and takes exactly what the ticket and the receipt say
-- ---------------------------------------------------------------------
DO $$
DECLARE fin UUID := pg_temp.holder('FINANCE');
BEGIN
    INSERT INTO fx VALUES
        ('tt_pend', pg_temp.make_ticket(pg_temp.fx_id('g_pend'), fin, '_VERIFY-TT-PEND')),
        ('tt_appr', pg_temp.make_ticket(pg_temp.fx_id('g_appr'), fin, '_VERIFY-TT-APPR'));
END $$;

SELECT pg_temp.refuses('23a', 'stock moved against a receipt that is not fully approved',
  $q$SELECT pg_temp.move_ticket(pg_temp.fx_id('tt_pend'), pg_temp.holder('FINANCE'), kigali_today())$q$,
  '23Z02', '%not fully approved%');
SELECT pg_temp.refuses('23b', 'stock moved against an approved receipt that had not been posted',
  $q$SELECT pg_temp.move_ticket(pg_temp.fx_id('tt_appr'), pg_temp.holder('FINANCE'), kigali_today())$q$,
  '23Z02', '%must be posted before its stock moves%');
SELECT pg_temp.refuses('23c', 'stock moved against the receipt itself, with no ticket',
  $q$INSERT INTO stock_movement (document_id, branch_id, item_id, location_id, direction,
                                 quantity_base_uom, signed_quantity, running_balance, business_date, posted_by)
     SELECT d.id, d.branch_id, i.id, l.id, 'IN', 5, 5, 5, kigali_today(), pg_temp.holder('FINANCE')
       FROM document d, item i, location l
      WHERE d.id = pg_temp.fx_id('g_ok') AND i.item_code = '_VERIFY-GLASS' AND l.code = 'KGL-MAIN'$q$,
  '23Z02', '%only against a transaction ticket line%');
SELECT pg_temp.refuses('23d', 'a ticket line moved stock twice',
  $q$SELECT pg_temp.move_ticket(pg_temp.fx_id('tt_ok'), pg_temp.holder('FINANCE'), kigali_today())$q$,
  '23505', '%stock_movement_one_per_ticket_line%');

DO $$
DECLARE
    fin UUID := pg_temp.holder('FINANCE');
    g   UUID;
BEGIN
    g := pg_temp.grn_at('_VERIFY-G-P2', 'POSTED');
    INSERT INTO fx VALUES ('g_p2', g), ('tt_p2', pg_temp.make_ticket(g, fin, '_VERIFY-TT-P2'));
END $$;
SELECT pg_temp.refuses('23e', 'a movement carried a quantity the ticket does not',
  $q$SELECT pg_temp.move_ticket(pg_temp.fx_id('tt_p2'), pg_temp.holder('FINANCE'), kigali_today(), 99)$q$,
  '23Z02', '%differs from line 1%');
SELECT pg_temp.refuses('23f', 'a movement was recorded by someone other than the independent poster',
  $q$SELECT pg_temp.move_ticket(pg_temp.fx_id('tt_p2'), pg_temp.verify_user(), kigali_today())$q$,
  '23Z02', '%posted by someone else%');
SELECT pg_temp.refuses('23g', 'a movement went to a location the ticket does not name',
  $q$INSERT INTO stock_movement (ticket_line_id, document_id, branch_id, item_id, location_id, direction,
                                 quantity_base_uom, signed_quantity, running_balance, business_date, posted_by)
     SELECT tl.id, tl.ticket_id, d.branch_id, tl.item_id, l.id, 'IN', tl.qty_base_uom, tl.qty_base_uom,
            tl.qty_base_uom, kigali_today(), pg_temp.holder('FINANCE')
       FROM ticket_line tl JOIN document d ON d.id = tl.ticket_id, location l
      WHERE tl.ticket_id = pg_temp.fx_id('tt_p2') AND l.code = 'KGL-CUT'$q$,
  '23Z02', '%direction or location does not match%');
SELECT pg_temp.accepts('23h', 'the right movement, for the posted and fully approved receipt, is accepted',
  $q$SELECT pg_temp.move_ticket(pg_temp.fx_id('tt_p2'), pg_temp.holder('FINANCE'), kigali_today())$q$);
UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('FINANCE') WHERE id = pg_temp.fx_id('tt_p2');

-- Every receipt posted so far has moved its stock: the commit-time check passes.
SELECT pg_temp.accepts('23i', 'posted receipts that moved their stock pass the commit check',
  $q$SET CONSTRAINTS document_grn_posted_moved_stock IMMEDIATE$q$);
SET CONSTRAINTS document_grn_posted_moved_stock DEFERRED;
SET CONSTRAINTS stock_movement_ticket_posted DEFERRED;

-- ...and one that did not is refused.
DO $$
BEGIN
    INSERT INTO fx VALUES ('g_p3', pg_temp.grn_at('_VERIFY-G-P3', 'POSTED'));
END $$;
SELECT pg_temp.refuses('23j', 'a receipt was posted without its stock ever reaching the ledger',
  $q$SET CONSTRAINTS document_grn_posted_moved_stock IMMEDIATE$q$,
  '23Z02', '%never reached the ledger%');
SET CONSTRAINTS document_grn_posted_moved_stock DEFERRED;
SET CONSTRAINTS stock_movement_ticket_posted DEFERRED;

-- ---------------------------------------------------------------------
-- 24. The ticket is what was signed: lines match the receipt, once, frozen
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('24a', 'a ticket line was changed to differ from the receipt line',
  $q$UPDATE ticket_line SET quantity = 1, qty_base_uom = 1 WHERE ticket_id = pg_temp.fx_id('tt_appr')$q$,
  '23Z02', '%differs from line 1%');
SELECT pg_temp.refuses('24b', 'a ticket line was added that the receipt does not have',
  $q$INSERT INTO ticket_line (ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom)
     SELECT pg_temp.fx_id('tt_appr'), 2, i.id, 1, u.id, 1 FROM item i, uom u
      WHERE i.item_code = '_VERIFY-GLASS' AND u.code = 'SHEET'$q$,
  '23Z02', '%no line 2%');
SELECT pg_temp.refuses('24c', 'a posted ticket''s line was changed',
  $q$UPDATE ticket_line SET quantity = 1 WHERE ticket_id = pg_temp.fx_id('tt_ok')$q$,
  '23Z02', '%is posted%');
SELECT pg_temp.refuses('24d', 'a posted ticket''s header was changed',
  $q$UPDATE transaction_ticket SET total_value = 1 WHERE document_id = pg_temp.fx_id('tt_ok')$q$,
  '23Z02', '%is posted%');
SELECT pg_temp.refuses('24e', 'a second receipt ticket was raised for one receipt',
  $q$SELECT pg_temp.make_ticket(pg_temp.fx_id('g_ok'), pg_temp.holder('FINANCE'), '_VERIFY-TT-DUP')$q$,
  '23505', '%transaction_ticket_one_receipt_per_source%');
DO $$
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, b.id, '_VERIFY-TT-WRONG', pg_temp.verify_user()
      FROM document_type dt, branch b WHERE dt.code = 'TT' AND b.code = 'KGL';
END $$;
SELECT pg_temp.refuses('24f', 'a receipt ticket named a location the receipt does not',
  $q$INSERT INTO transaction_ticket (document_id, movement_type, direction, to_location_id, source_document_id)
     SELECT t.id, 'RECEIPT', 'IN', l.id, pg_temp.fx_id('g_draft')
       FROM document t, location l WHERE t.serial_no = '_VERIFY-TT-WRONG' AND l.code = 'KGL-CUT'$q$,
  '23Z02', '%must be a RECEIPT into the location%');

-- ---------------------------------------------------------------------
-- 25. One balance row per item, location and bin, NULL bin included
-- ---------------------------------------------------------------------
SELECT pg_temp.accepts('25a', 'a balance row with no bin is accepted',
  $q$INSERT INTO stock_balance (item_id, location_id)
     SELECT i.id, l.id FROM item i, location l WHERE i.item_code = '_VERIFY-GLASS' AND l.code = 'KGL-MAIN'$q$);
SELECT pg_temp.refuses('25b', 'a second balance row for the same item and location with no bin was accepted',
  $q$INSERT INTO stock_balance (item_id, location_id)
     SELECT i.id, l.id FROM item i, location l WHERE i.item_code = '_VERIFY-GLASS' AND l.code = 'KGL-MAIN'$q$,
  '23505', '%stock_balance_one_row_per_place%');

-- ---------------------------------------------------------------------
-- 26. The receiving rights sit on the roles that sign each step, and
--     nobody is left in conflict
-- ---------------------------------------------------------------------
DO $$
DECLARE
    bad TEXT;
    msg TEXT;
BEGIN
    SELECT string_agg(v.role_code || ' has {' || COALESCE(have.perms, '') || '} expected {' || v.want || '}', '; ')
      INTO bad
      FROM (VALUES
            ('ASST_WH_MANAGER',  'receiving.create,receiving.view'),
            ('WH_MANAGER',       'receiving.create,receiving.verify,receiving.view'),
            ('INV_TX_OFFICER',   'receiving.verify,receiving.view'),
            ('DIR_SUPPLY_CHAIN', 'receiving.approve,receiving.view'),
            ('INTERNAL_CTRL',    'receiving.approve,receiving.verify,receiving.view'),
            ('FINANCE',          'receiving.post,receiving.view')
           ) AS v(role_code, want)
      LEFT JOIN LATERAL (
            SELECT string_agg(p.code, ',' ORDER BY p.code) AS perms
              FROM role r JOIN role_permission rp ON rp.role_id = r.id
              JOIN permission p ON p.id = rp.permission_id AND p.module = 'receiving'
             WHERE r.code = v.role_code) have ON TRUE
     WHERE have.perms IS DISTINCT FROM v.want;
    IF bad IS NULL THEN
        RAISE NOTICE 'ok   26a  receiving rights sit on the roles whose steps they serve';
    ELSE
        RAISE WARNING 'FAIL 26a  %', bad;
    END IF;

    IF EXISTS (SELECT 1 FROM permission WHERE code = 'receiving.approve' AND module = 'receiving'
                  AND action = 'APPROVE' AND duty = 'TRANSACT') THEN
        RAISE NOTICE 'ok   26b  receiving.approve is a transactional right';
    ELSE
        RAISE WARNING 'FAIL 26b  receiving.approve is missing or misclassified';
    END IF;

    msg := access_conflict_anywhere();
    IF msg IS NULL THEN
        RAISE NOTICE 'ok   26c  nobody, and no role, is left in conflict';
    ELSE
        RAISE WARNING 'FAIL 26c  %', msg;
    END IF;
END $$;

-- The Internal Controller still cannot be given the right to post, and
-- Finance (which posts) still cannot sit with the Internal Controller.
SELECT pg_temp.refuses('26d', 'the Internal Controller was given receiving.post',
  $q$INSERT INTO role_permission (role_id, permission_id)
     SELECT r.id, p.id FROM role r, permission p WHERE r.code = 'INTERNAL_CTRL' AND p.code = 'receiving.post'$q$,
  '23Z01');
SELECT pg_temp.refuses('26e', 'Finance and the Internal Controller were held by one person',
  $q$INSERT INTO user_role (user_id, role_id, assigned_by)
     SELECT pg_temp.holder('FINANCE'), r.id, pg_temp.verify_user() FROM role r WHERE r.code = 'INTERNAL_CTRL'$q$,
  '23Z01');

-- ---------------------------------------------------------------------
-- 28. A movement is dated the day it is recorded
-- ---------------------------------------------------------------------
DO $$
DECLARE
    fin UUID := pg_temp.holder('FINANCE');
    g   UUID;
BEGIN
    g := pg_temp.grn_at('_VERIFY-G-D1', 'POSTED');
    INSERT INTO fx VALUES ('g_d1', g), ('tt_d1', pg_temp.make_ticket(g, fin, '_VERIFY-TT-D1'));
END $$;
SELECT pg_temp.refuses('28a', 'a movement dated in the past, on an open day, was accepted',
  $q$SELECT pg_temp.move_ticket(pg_temp.fx_id('tt_d1'), pg_temp.holder('FINANCE'), kigali_today() - 2)$q$,
  '23Z02', '%dated the day it is recorded%');
SELECT pg_temp.refuses('28b', 'a movement dated in the future was accepted',
  $q$SELECT pg_temp.move_ticket(pg_temp.fx_id('tt_d1'), pg_temp.holder('FINANCE'), kigali_today() + 1)$q$,
  '23Z02', '%dated the day it is recorded%');
-- The locked day still speaks first for a locked day (check 3's day).
SELECT pg_temp.refuses('28c', 'a movement into a locked day was accepted',
  $q$SELECT pg_temp.move_ticket(pg_temp.fx_id('tt_d1'), pg_temp.holder('FINANCE'), CURRENT_DATE - 1)$q$,
  '23001', '%closed at this branch%');
SELECT pg_temp.accepts('28d', 'a movement dated today (Kigali) is accepted',
  $q$SELECT pg_temp.move_ticket(pg_temp.fx_id('tt_d1'), pg_temp.holder('FINANCE'), kigali_today())$q$);

-- ---------------------------------------------------------------------
-- 29. A ticket is frozen once stock has moved against it, whatever its
--     status (tt_d1 is still a DRAFT here, with its movement written)
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('29a', 'a ticket line was edited after its movement',
  $q$UPDATE ticket_line SET unit_value = 1 WHERE ticket_id = pg_temp.fx_id('tt_d1')$q$,
  '23Z02', '%Stock has already moved%');
SELECT pg_temp.refuses('29b', 'a ticket line was deleted after its movement',
  $q$DELETE FROM ticket_line WHERE ticket_id = pg_temp.fx_id('tt_d1')$q$,
  '23Z02', '%Stock has already moved%');
SELECT pg_temp.refuses('29c', 'a ticket was edited after its movement',
  $q$UPDATE transaction_ticket SET total_value = 1 WHERE document_id = pg_temp.fx_id('tt_d1')$q$,
  '23Z02', '%Stock has already moved%');
SELECT pg_temp.refuses('29d', 'a ticket was deleted after its movement',
  $q$DELETE FROM transaction_ticket WHERE document_id = pg_temp.fx_id('tt_d1')$q$,
  '23Z02', '%Stock has already moved%');
SELECT pg_temp.refuses('29e', 'a line was added to a ticket after stock moved',
  $q$INSERT INTO ticket_line (ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom)
     SELECT pg_temp.fx_id('tt_d1'), 2, i.id, 1, u.id, 1 FROM item i, uom u
      WHERE i.item_code = '_VERIFY-GLASS' AND u.code = 'SHEET'$q$,
  '23Z02', '%Stock has already moved%');

-- ---------------------------------------------------------------------
-- 30. A ticket that has moved stock must be posted by commit
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('30a', 'a ticket was left DRAFT with its movements in the ledger',
  $q$SET CONSTRAINTS stock_movement_ticket_posted IMMEDIATE$q$,
  '23Z02', '%must be posted%');
SET CONSTRAINTS stock_movement_ticket_posted DEFERRED;
UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('FINANCE') WHERE id = pg_temp.fx_id('tt_d1');
SELECT pg_temp.accepts('30b', 'once posted, the ticket passes the commit check',
  $q$SET CONSTRAINTS stock_movement_ticket_posted IMMEDIATE$q$);
SET CONSTRAINTS stock_movement_ticket_posted DEFERRED;

-- ---------------------------------------------------------------------
-- 31. Posted stock is corrected by a reversing document, never cancelled;
--     a posted document that moved no stock may still be cancelled
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('31a', 'a posted goods received note was cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('g_ok')$q$,
  '23Z02', '%corrected by a reversing document%');
SELECT pg_temp.refuses('31b', 'a posted transaction ticket was cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('tt_ok')$q$,
  '23Z02', '%corrected by a reversing document%');

-- A delivery authorization moves no stock itself: raised, signed by its whole
-- chain, approved and posted, it can still be cancelled.
DO $$
DECLARE d UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, b.id, '_VERIFY-DAO-31', pg_temp.verify_user()
      FROM document_type dt, branch b WHERE dt.code = 'DAO' AND b.code = 'KGL'
    RETURNING id INTO d;
    UPDATE document SET status = 'PENDING' WHERE id = d;
    PERFORM pg_temp.sign_upto(d, pg_temp.steps_in(d));
    UPDATE document SET status = 'APPROVED' WHERE id = d;
    UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('SALES') WHERE id = d;
    INSERT INTO fx VALUES ('dao_posted', d);
END $$;
SELECT pg_temp.accepts('31c', 'a posted document of a type that moves no stock (DAO) can still be cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('dao_posted')$q$);

-- ---------------------------------------------------------------------
-- 27. The chain switch is a row, and a document finishes under its chain.
--     Move the 2027 chain to today: a new receipt binds to it, and a
--     receipt already open still signs under the chain it began with.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    old_def UUID; new_def UUID; d UUID; bound UUID;
BEGIN
    IF kigali_today() >= DATE '2027-01-01' THEN
        RAISE NOTICE 'ok   27   (skipped: the 2027 chain is already in force)';
        RETURN;
    END IF;
    SELECT wd.id INTO old_def FROM workflow_definition wd JOIN document_type dt ON dt.id = wd.document_type_id
     WHERE dt.code = 'GRN' AND wd.basis = 'POLICY_2026';
    SELECT wd.id INTO new_def FROM workflow_definition wd JOIN document_type dt ON dt.id = wd.document_type_id
     WHERE dt.code = 'GRN' AND wd.basis = 'RESTRUCTURE_2027';

    UPDATE workflow_definition SET effective_to = kigali_today() WHERE id = old_def;
    UPDATE workflow_definition SET effective_from = kigali_today() WHERE id = new_def;

    d := pg_temp.make_grn('_VERIFY-G-2027', NULL);
    SELECT workflow_definition_id INTO bound FROM document WHERE id = d;
    IF bound = new_def THEN
        RAISE NOTICE 'ok   27a  once the switch date arrives a new receipt binds to the 2027 chain';
    ELSE
        RAISE WARNING 'FAIL 27a  a new receipt bound to % instead of the 2027 chain', bound;
    END IF;

    SELECT workflow_definition_id INTO bound FROM document WHERE id = pg_temp.fx_id('g_step1');
    IF bound = old_def THEN
        RAISE NOTICE 'ok   27b  a receipt opened before the switch stays bound to the 2026 chain';
    ELSE
        RAISE WARNING 'FAIL 27b  an open receipt moved to another chain';
    END IF;

    BEGIN
        PERFORM pg_temp.sign(pg_temp.fx_id('g_step1'), 2,
                             pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('g_step1'), 2)));
        RAISE NOTICE 'ok   27c  an open receipt finishes under the chain it began with';
    EXCEPTION WHEN OTHERS THEN
        RAISE WARNING 'FAIL 27c  an open receipt could not finish under its own chain: %', SQLERRM;
    END;
END $$;

ROLLBACK;

\echo ''
\echo 'Every line above must read ok. A FAIL means a control has been weakened.'
\echo ''
