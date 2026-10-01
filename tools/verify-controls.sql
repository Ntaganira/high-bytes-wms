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
SET CONSTRAINTS document_dn_posted_moved_stock DEFERRED;
SET CONSTRAINTS document_trf_posted_moved_stock DEFERRED;
SET CONSTRAINTS document_trr_posted_moved_stock DEFERRED;
SET CONSTRAINTS document_trf_value_kept DEFERRED;
SET CONSTRAINTS document_trr_value_share DEFERRED;
SET CONSTRAINTS document_dmg_posted_moved_stock DEFERRED;
SET CONSTRAINTS document_dmg_value_rule DEFERRED;
SET CONSTRAINTS document_cnt_posted_moved_stock DEFERRED;
SET CONSTRAINTS document_cnt_value_rule DEFERRED;

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
  -- V12 gave dispatch.verify to the Assistant WH Manager and the Director
  -- Supply Chain as well, so it stops being one side's right only when it
  -- leaves every role permitted alongside Finance.
  DELETE FROM role_permission
   WHERE role_id IN (SELECT id FROM role WHERE code IN ('WH_MANAGER', 'ASST_WH_MANAGER', 'DIR_SUPPLY_CHAIN'))
     AND permission_id = (SELECT id FROM permission WHERE code = 'dispatch.verify');
  PERFORM set_config('highbytes.migration', 'off', true);
  RAISE WARNING 'FAIL 18d  a migration left someone holding what the rules now forbid';
EXCEPTION WHEN SQLSTATE '23Z01' THEN RAISE NOTICE 'ok   18d  a change to a policy role is judged for everyone';
END $$;

DO $$ BEGIN
  INSERT INTO role_permission (role_id, permission_id)
  SELECT r.id, p.id FROM role r, permission p WHERE r.code = '_VERIFY_CLOSER' AND p.code = 'cutting.release';
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
  $q$SELECT pg_temp.move_ticket(pg_temp.fx_id('tt_d1'), pg_temp.holder('FINANCE'), kigali_today() - 5)$q$,
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

-- A document of a type that moves no stock: raised, signed by its whole
-- chain, approved and posted, it can still be cancelled. No such type is
-- postable any more (V12 made a DAO unpostable; V15 made the count sheet a
-- stock-moving document, so a posted count is refused in check 58), so a
-- fixture type with a two-step chain stands in, rolled back with the rest.
DO $$
DECLARE d UUID; t UUID; wd UUID;
BEGIN
    INSERT INTO document_type (code, name, moves_stock) VALUES ('_VFY', 'Verification non-stock type', FALSE)
    RETURNING id INTO t;
    INSERT INTO workflow_definition (document_type_id, version, effective_from, basis)
    VALUES (t, 1, DATE '2026-01-01', 'POLICY_2026') RETURNING id INTO wd;
    INSERT INTO workflow_step (workflow_definition_id, sequence_no, required_role_id, action_label)
    SELECT wd, v.seq, r.id, v.act
      FROM (VALUES (1, 'WH_MANAGER', 'PREPARE'), (2, 'FINANCE', 'APPROVE')) AS v(seq, role_code, act)
      JOIN role r ON r.code = v.role_code;
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT t, b.id, '_VERIFY-NONSTOCK-31', pg_temp.verify_user() FROM branch b WHERE b.code = 'KGL'
    RETURNING id INTO d;
    UPDATE document SET status = 'PENDING' WHERE id = d;
    PERFORM pg_temp.sign_upto(d, pg_temp.steps_in(d));
    UPDATE document SET status = 'APPROVED' WHERE id = d;
    UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('SALES') WHERE id = d;
    INSERT INTO fx VALUES ('nonstock_posted', d);
END $$;
SELECT pg_temp.accepts('31c', 'a posted document of a type that moves no stock can still be cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('nonstock_posted')$q$);

-- =====================================================================
-- Delivery Authorization and Delivery Note (V12). As for Goods Received,
-- every refusal must carry SQLSTATE 23Z02 (a workflow or document control)
-- or 23514 (a malformed line) with the reason named.
-- =====================================================================

INSERT INTO customer (code, name) VALUES ('_VERIFY-CUST', 'Verification Customer');
INSERT INTO customer (code, name, is_blocked) VALUES ('_VERIFY-BLOCKED', 'Verification Blocked Customer', TRUE);
INSERT INTO item (item_code, description, product_type, base_uom_id)
SELECT '_VERIFY-OTHER', 'Verification other item', 'HARDWARE', id FROM uom WHERE code = 'SHEET';
INSERT INTO storage_bin (location_id, bin_code)
SELECT id, '_VERIFY-BIN-MAIN' FROM location WHERE code = 'KGL-MAIN';

-- A draft authorization: header and one glass line of p_qty sheets.
CREATE FUNCTION pg_temp.make_dao(p_serial TEXT, p_qty NUMERIC DEFAULT 40, p_creator UUID DEFAULT NULL,
                                 p_branch TEXT DEFAULT 'KGL', p_loc TEXT DEFAULT 'KGL-MAIN',
                                 p_customs TEXT DEFAULT NULL) RETURNS UUID AS $$
DECLARE d UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, b.id, p_serial, COALESCE(p_creator, pg_temp.verify_user())
      FROM document_type dt, branch b WHERE dt.code = 'DAO' AND b.code = p_branch
    RETURNING id INTO d;
    INSERT INTO delivery_authorization (document_id, customer_id, location_id, customs_reference)
    SELECT d, c.id, l.id, p_customs FROM customer c, location l
     WHERE c.code = '_VERIFY-CUST' AND l.code = p_loc;
    INSERT INTO delivery_authorization_line (document_id, line_no, item_id, uom_id, quantity, qty_base_uom)
    SELECT d, 1, i.id, u.id, p_qty, p_qty
      FROM item i, uom u WHERE i.item_code = '_VERIFY-GLASS' AND u.code = 'SHEET';
    RETURN d;
END $$ LANGUAGE plpgsql;

-- An authorization taken to a state: DRAFT, PENDING, STEP1 (one signature),
-- NORELEASE (every step but the last signed, still PENDING) or APPROVED.
CREATE FUNCTION pg_temp.dao_at(p_serial TEXT, p_state TEXT, p_qty NUMERIC DEFAULT 40,
                               p_customs TEXT DEFAULT NULL) RETURNS UUID AS $$
DECLARE d UUID;
BEGIN
    d := pg_temp.make_dao(p_serial, p_qty, p_customs => p_customs);
    IF p_state = 'DRAFT' THEN RETURN d; END IF;
    UPDATE document SET status = 'PENDING' WHERE id = d;
    IF p_state = 'PENDING' THEN RETURN d; END IF;
    IF p_state = 'STEP1' THEN PERFORM pg_temp.sign_upto(d, 1); RETURN d; END IF;
    IF p_state = 'NORELEASE' THEN PERFORM pg_temp.sign_upto(d, pg_temp.steps_in(d) - 1); RETURN d; END IF;
    PERFORM pg_temp.sign_upto(d, pg_temp.steps_in(d));
    UPDATE document SET status = 'APPROVED' WHERE id = d;
    RETURN d;
END $$ LANGUAGE plpgsql;

-- A draft delivery note loading exactly what the authorization lists.
CREATE FUNCTION pg_temp.make_dn(p_serial TEXT, p_dao UUID, p_creator UUID DEFAULT NULL,
                                p_bin TEXT DEFAULT NULL) RETURNS UUID AS $$
DECLARE n UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, d.branch_id, p_serial, COALESCE(p_creator, pg_temp.verify_user())
      FROM document_type dt, document d WHERE dt.code = 'DN' AND d.id = p_dao
    RETURNING id INTO n;
    INSERT INTO delivery_note (document_id, authorization_id, vehicle_registration, driver_name)
    VALUES (n, p_dao, 'RAB 123 A', 'Verification Driver');
    INSERT INTO delivery_note_line (document_id, line_no, authorization_line_id, item_id, uom_id,
                                    quantity, qty_base_uom, storage_bin_id, measured_thickness_mm)
    SELECT n, al.line_no, al.id, al.item_id, al.uom_id, al.quantity, al.qty_base_uom,
           (SELECT sb.id FROM storage_bin sb WHERE sb.bin_code = p_bin),
           CASE WHEN i.product_type = 'GLASS' THEN 6.0 END
      FROM delivery_authorization_line al JOIN item i ON i.id = al.item_id
     WHERE al.document_id = p_dao;
    RETURN n;
END $$ LANGUAGE plpgsql;

-- The person who may post a note in these fixtures: a signer of the
-- authorization (step 2), which the rules allow.
CREATE FUNCTION pg_temp.gate_poster(p_dao UUID) RETURNS UUID AS $$
    SELECT pg_temp.holder(pg_temp.step_role(p_dao, 2));
$$ LANGUAGE sql;

CREATE FUNCTION pg_temp.make_dn_ticket(p_dn UUID, p_poster UUID, p_serial TEXT) RETURNS UUID AS $$
DECLARE t UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, d.branch_id, p_serial, p_poster
      FROM document_type dt, document d WHERE dt.code = 'TT' AND d.id = p_dn
    RETURNING id INTO t;
    INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id,
                                    source_document_id, customs_reference)
    SELECT t, 'DELIVERY', 'OUT', a.location_id, p_dn, a.customs_reference
      FROM delivery_note n JOIN delivery_authorization a ON a.document_id = n.authorization_id
     WHERE n.document_id = p_dn;
    INSERT INTO ticket_line (ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom, storage_bin_id)
    SELECT t, l.line_no, l.item_id, l.quantity, l.uom_id, l.qty_base_uom, l.storage_bin_id
      FROM delivery_note_line l WHERE l.document_id = p_dn;
    RETURN t;
END $$ LANGUAGE plpgsql;

CREATE FUNCTION pg_temp.move_out(p_ticket UUID, p_poster UUID, p_date DATE) RETURNS VOID AS $$
    INSERT INTO stock_movement (ticket_line_id, document_id, branch_id, item_id, location_id, storage_bin_id,
                                direction, quantity_base_uom, signed_quantity, unit_cost, value,
                                running_balance, business_date, posted_by)
    SELECT tl.id, d.id, d.branch_id, tl.item_id, t.from_location_id, tl.storage_bin_id,
           'OUT', tl.qty_base_uom, -tl.qty_base_uom, 1000, tl.qty_base_uom * 1000, 0, p_date, p_poster
      FROM ticket_line tl
      JOIN transaction_ticket t ON t.document_id = tl.ticket_id
      JOIN document d ON d.id = t.document_id
     WHERE tl.ticket_id = p_ticket;
$$ LANGUAGE sql;

-- The whole gate: approved authorization, note raised, posted by a signer,
-- ticket written, stock moved out, ticket posted.
CREATE FUNCTION pg_temp.deliver(p_serial TEXT, p_qty NUMERIC DEFAULT 40) RETURNS UUID AS $$
DECLARE
    dao UUID; dn UUID; tt UUID; poster UUID;
BEGIN
    dao    := pg_temp.dao_at(p_serial, 'APPROVED', p_qty);
    poster := pg_temp.gate_poster(dao);
    dn     := pg_temp.make_dn(p_serial || '-DN', dao);
    UPDATE document SET status = 'POSTED', posted_by = poster WHERE id = dn;
    tt     := pg_temp.make_dn_ticket(dn, poster, p_serial || '-TT');
    PERFORM pg_temp.move_out(tt, poster, kigali_today());
    UPDATE document SET status = 'POSTED', posted_by = poster WHERE id = tt;
    INSERT INTO fx VALUES (p_serial, dao), (p_serial || '-DN', dn), (p_serial || '-TT', tt);
    RETURN dao;
END $$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- 32. The dispatch rights sit on the roles that sign each step, and
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
            ('ASST_WH_MANAGER',  'dispatch.post,dispatch.verify,dispatch.view'),
            ('WH_MANAGER',       'dispatch.post,dispatch.verify,dispatch.view'),
            ('FINANCE',          'dispatch.approve,dispatch.create,dispatch.view'),
            ('INTERNAL_CTRL',    'dispatch.release,dispatch.verify,dispatch.view'),
            ('INV_TX_OFFICER',   'dispatch.create,dispatch.view'),
            ('DIR_SUPPLY_CHAIN', 'dispatch.verify,dispatch.view'),
            ('DIR_COMMERCIAL',   'dispatch.countersign,dispatch.view')
           ) AS v(role_code, want)
      LEFT JOIN LATERAL (
            SELECT string_agg(p.code, ',' ORDER BY p.code) AS perms
              FROM role r JOIN role_permission rp ON rp.role_id = r.id
              JOIN permission p ON p.id = rp.permission_id AND p.module = 'dispatch'
             WHERE r.code = v.role_code) have ON TRUE
     WHERE have.perms IS DISTINCT FROM v.want;
    IF bad IS NULL THEN
        RAISE NOTICE 'ok   32a  dispatch rights sit on the roles whose steps they serve';
    ELSE
        RAISE WARNING 'FAIL 32a  %', bad;
    END IF;

    -- Every step of both delivery authorization chains has a role carrying the
    -- right its action needs.
    SELECT string_agg(r.code || ' signs ' || ws.action_label || ' without ' || need.code, '; ')
      INTO bad
      FROM workflow_step ws
      JOIN workflow_definition wd ON wd.id = ws.workflow_definition_id
      JOIN document_type dt ON dt.id = wd.document_type_id AND dt.code = 'DAO'
      JOIN role r ON r.id = ws.required_role_id
      CROSS JOIN LATERAL (SELECT CASE ws.action_label
                                   WHEN 'PREPARE' THEN 'dispatch.create'
                                   WHEN 'VERIFY' THEN 'dispatch.verify'
                                   WHEN 'COUNTERSIGN' THEN 'dispatch.countersign'
                                   WHEN 'RELEASE' THEN 'dispatch.release' END AS code) need
     WHERE NOT EXISTS (SELECT 1 FROM role_permission rp JOIN permission p ON p.id = rp.permission_id
                        WHERE rp.role_id = r.id AND p.code = need.code);
    IF bad IS NULL THEN
        RAISE NOTICE 'ok   32b  every delivery chain signer carries the right its step needs';
    ELSE
        RAISE WARNING 'FAIL 32b  %', bad;
    END IF;

    SELECT string_agg(r.code, ',' ORDER BY r.code) INTO bad
      FROM role r JOIN role_permission rp ON rp.role_id = r.id JOIN permission p ON p.id = rp.permission_id
     WHERE p.code = 'dispatch.post';
    IF bad = 'ASST_WH_MANAGER,WH_MANAGER' THEN
        RAISE NOTICE 'ok   32c  only the warehouse roles carry dispatch.post';
    ELSE
        RAISE WARNING 'FAIL 32c  dispatch.post is carried by %', bad;
    END IF;

    IF EXISTS (SELECT 1 FROM permission WHERE code = 'dispatch.post' AND module = 'dispatch' AND action = 'POST' AND duty = 'TRANSACT')
       AND EXISTS (SELECT 1 FROM permission WHERE code = 'dispatch.countersign' AND action = 'APPROVE' AND duty = 'TRANSACT') THEN
        RAISE NOTICE 'ok   32d  dispatch.post and dispatch.countersign are transactional rights';
    ELSE
        RAISE WARNING 'FAIL 32d  the new dispatch rights are missing or misclassified';
    END IF;

    msg := access_conflict_anywhere();
    IF msg IS NULL THEN
        RAISE NOTICE 'ok   32e  nobody, and no role, is left in conflict';
    ELSE
        RAISE WARNING 'FAIL 32e  %', msg;
    END IF;
END $$;

SELECT pg_temp.refuses('32f', 'the Internal Controller was given dispatch.post',
  $q$INSERT INTO role_permission (role_id, permission_id)
     SELECT r.id, p.id FROM role r, permission p WHERE r.code = 'INTERNAL_CTRL' AND p.code = 'dispatch.post'$q$,
  '23Z01');
SELECT pg_temp.refuses('32g', 'the Internal Controller and the warehouse were held by one person',
  $q$INSERT INTO user_role (user_id, role_id, assigned_by)
     SELECT pg_temp.holder('WH_MANAGER'), r.id, pg_temp.verify_user() FROM role r WHERE r.code = 'INTERNAL_CTRL'$q$,
  '23Z01');

-- ---------------------------------------------------------------------
-- 33. The delivery authorization: content frozen at submission, header
--     rules, signing down the chain, never posted
-- ---------------------------------------------------------------------
DO $$
BEGIN
    INSERT INTO fx VALUES
        ('d_draft',  pg_temp.dao_at('_VERIFY-D-DRAFT',  'DRAFT')),
        ('d_pend',   pg_temp.dao_at('_VERIFY-D-PEND',   'PENDING')),
        ('d_step1',  pg_temp.dao_at('_VERIFY-D-STEP1',  'STEP1')),
        ('d_norel',  pg_temp.dao_at('_VERIFY-D-NOREL',  'NORELEASE')),
        ('d_appr',   pg_temp.dao_at('_VERIFY-D-APPR',   'APPROVED'));
END $$;

SELECT pg_temp.refuses('33a', 'a submitted authorization''s header was edited',
  $q$UPDATE delivery_authorization SET customer_reference = 'CHANGED' WHERE document_id = pg_temp.fx_id('d_pend')$q$,
  '23Z02', '%PENDING%cannot change%');
SELECT pg_temp.refuses('33b', 'a line was added after submission',
  $q$INSERT INTO delivery_authorization_line (document_id, line_no, item_id, uom_id, quantity, qty_base_uom)
     SELECT pg_temp.fx_id('d_pend'), 2, i.id, u.id, 1, 1 FROM item i, uom u
      WHERE i.item_code = '_VERIFY-GLASS' AND u.code = 'SHEET'$q$,
  '23Z02', '%PENDING%cannot change%');
SELECT pg_temp.refuses('33c', 'a line was changed after submission',
  $q$UPDATE delivery_authorization_line SET quantity = 500, qty_base_uom = 500 WHERE document_id = pg_temp.fx_id('d_pend')$q$,
  '23Z02', '%PENDING%cannot change%');
SELECT pg_temp.refuses('33d', 'a line was deleted after submission',
  $q$DELETE FROM delivery_authorization_line WHERE document_id = pg_temp.fx_id('d_appr')$q$,
  '23Z02', '%APPROVED%cannot change%');
SELECT pg_temp.accepts('33e', 'a draft authorization is still editable',
  $q$UPDATE delivery_authorization_line SET quantity = 50, qty_base_uom = 50 WHERE document_id = pg_temp.fx_id('d_draft')$q$);

DO $$
DECLARE d UUID; c UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, b.id, '_VERIFY-D-BOND', pg_temp.verify_user()
      FROM document_type dt, branch b WHERE dt.code = 'DAO' AND b.code = 'RBV'
    RETURNING id INTO d;
    INSERT INTO fx VALUES ('d_bond', d);
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, b.id, '_VERIFY-D-EMPTY', pg_temp.verify_user()
      FROM document_type dt, branch b WHERE dt.code = 'DAO' AND b.code = 'KGL'
    RETURNING id INTO c;
    INSERT INTO fx VALUES ('d_empty', c);
    INSERT INTO delivery_authorization (document_id, customer_id, location_id)
    SELECT c, cu.id, l.id FROM customer cu, location l WHERE cu.code = '_VERIFY-CUST' AND l.code = 'KGL-MAIN';
END $$;
SELECT pg_temp.refuses('33f', 'bonded stock was authorized to leave with no customs reference',
  $q$INSERT INTO delivery_authorization (document_id, customer_id, location_id)
     SELECT pg_temp.fx_id('d_bond'), c.id, l.id FROM customer c, location l
      WHERE c.code = '_VERIFY-CUST' AND l.code = 'RBV-BOND'$q$,
  '23514', '%customs reference%');
SELECT pg_temp.refuses('33g', 'a blank customs reference was accepted',
  $q$INSERT INTO delivery_authorization (document_id, customer_id, location_id, customs_reference)
     SELECT pg_temp.fx_id('d_bond'), c.id, l.id, '  ' FROM customer c, location l
      WHERE c.code = '_VERIFY-CUST' AND l.code = 'RBV-BOND'$q$,
  '23514', '%dao_customs_reference_not_blank%');
SELECT pg_temp.accepts('33h', 'a bonded authorization with its customs reference is accepted',
  $q$INSERT INTO delivery_authorization (document_id, customer_id, location_id, customs_reference)
     SELECT pg_temp.fx_id('d_bond'), c.id, l.id, 'C-2026-DAO' FROM customer c, location l
      WHERE c.code = '_VERIFY-CUST' AND l.code = 'RBV-BOND'$q$);
SELECT pg_temp.refuses('33i', 'an authorization for a blocked customer was accepted',
  $q$UPDATE delivery_authorization SET customer_id = (SELECT id FROM customer WHERE code = '_VERIFY-BLOCKED')
      WHERE document_id = pg_temp.fx_id('d_draft')$q$,
  '23514', '%blocked%');
SELECT pg_temp.refuses('33j', 'an authorization at Gahanga released from a Rubavu location',
  $q$UPDATE delivery_authorization SET location_id = (SELECT id FROM location WHERE code = 'RBV-BOND'),
                                       customs_reference = 'C-1'
      WHERE document_id = pg_temp.fx_id('d_draft')$q$,
  '23514', '%not at the branch%');
SELECT pg_temp.refuses('33k', 'a base quantity that does not follow from the entered one was accepted',
  $q$UPDATE delivery_authorization_line SET qty_base_uom = 999 WHERE document_id = pg_temp.fx_id('d_draft')$q$,
  '23514', '%base unit%');
SELECT pg_temp.refuses('33l', 'an authorization with no lines was submitted',
  $q$UPDATE document SET status = 'PENDING' WHERE id = pg_temp.fx_id('d_empty')$q$,
  '23Z02', '%no lines%');
SELECT pg_temp.refuses('33m', 'the Internal Controller''s release was signed out of order',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('d_pend'), pg_temp.steps_in(pg_temp.fx_id('d_pend')),
                         pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('d_pend'), pg_temp.steps_in(pg_temp.fx_id('d_pend')))))$q$,
  '23Z02', '%must sign%before step%');
SELECT pg_temp.refuses('33n', 'someone without the step''s role signed the authorization',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('d_pend'), 1, pg_temp.holder('SALES'))$q$,
  '23Z02', '%does not hold%');
SELECT pg_temp.refuses('33o', 'an approved authorization was posted',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('SALES') WHERE id = pg_temp.fx_id('d_appr')$q$,
  '23Z02', '%never posted%');
SELECT pg_temp.accepts('33p', 'the whole chain signs an authorization in order, each in its own role',
  $q$SELECT pg_temp.dao_at('_VERIFY-D-CHAIN', 'APPROVED')$q$);

-- ---------------------------------------------------------------------
-- 34. The delivery note is raised only against a fully signed
--     authorization, once, and matches it
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('34a', 'a note was raised against an authorization still pending',
  $q$SELECT pg_temp.make_dn('_VERIFY-DN-PEND', pg_temp.fx_id('d_pend'))$q$,
  '23Z02', '%is PENDING%fully signed%');
SELECT pg_temp.refuses('34b', 'a note was raised against an authorization missing the release',
  $q$SELECT pg_temp.make_dn('_VERIFY-DN-NOREL', pg_temp.fx_id('d_norel'))$q$,
  '23Z02', '%is PENDING%');
SELECT pg_temp.refuses('34c', 'a note was raised against a draft authorization',
  $q$SELECT pg_temp.make_dn('_VERIFY-DN-DRAFT', pg_temp.fx_id('d_draft'))$q$,
  '23Z02', '%is DRAFT%');

DO $$
DECLARE dn UUID;
BEGIN
    dn := pg_temp.make_dn('_VERIFY-DN-A', pg_temp.fx_id('d_appr'));
    INSERT INTO fx VALUES ('dn_a', dn);
END $$;
SELECT pg_temp.refuses('34d', 'a second live note was raised for one authorization',
  $q$SELECT pg_temp.make_dn('_VERIFY-DN-A2', pg_temp.fx_id('d_appr'))$q$,
  '23Z02', '%already has a live delivery note%');

-- Glass needs its gate thickness; the line must serve its own authorization
-- and match it; bins belong to the authorization's location.
SELECT pg_temp.refuses('34e', 'a glass line without a gate measurement was accepted',
  $q$UPDATE delivery_note_line SET measured_thickness_mm = NULL WHERE document_id = pg_temp.fx_id('dn_a')$q$,
  '23514', '%thickness%');
SELECT pg_temp.refuses('34f', 'a bin from another location was accepted',
  $q$UPDATE delivery_note_line SET storage_bin_id = (SELECT id FROM storage_bin WHERE bin_code = '_VERIFY-BIN')
      WHERE document_id = pg_temp.fx_id('dn_a')$q$,
  '23514', '%not in the location%');
SELECT pg_temp.refuses('34g', 'a note line served a line of another authorization',
  $q$UPDATE delivery_note_line SET authorization_line_id =
        (SELECT id FROM delivery_authorization_line WHERE document_id = pg_temp.fx_id('d_draft'))
      WHERE document_id = pg_temp.fx_id('dn_a')$q$,
  '23514', '%different authorization%');
SELECT pg_temp.refuses('34h', 'a note line loaded another item than authorized',
  $q$UPDATE delivery_note_line SET item_id = (SELECT id FROM item WHERE item_code = '_VERIFY-OTHER')
      WHERE document_id = pg_temp.fx_id('dn_a')$q$,
  '23514', '%differs from authorization line%');
SELECT pg_temp.refuses('34i', 'a note was re-pointed at another authorization',
  $q$UPDATE delivery_note SET authorization_id = pg_temp.fx_id('d_bond') WHERE document_id = pg_temp.fx_id('dn_a')$q$,
  '23Z02', '%bound to one authorization for life%');
SELECT pg_temp.refuses('34j', 'a note with a blank vehicle was accepted',
  $q$UPDATE delivery_note SET vehicle_registration = '  ' WHERE document_id = pg_temp.fx_id('dn_a')$q$,
  '23514', '%dn_vehicle_not_blank%');

-- A cancelled note frees the authorization for another load.
SELECT pg_temp.accepts('34k', 'a draft note can be cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('dn_a')$q$);
SELECT pg_temp.accepts('34l', 'with the first note cancelled, a new note is raised for the authorization',
  $q$SELECT pg_temp.make_dn('_VERIFY-DN-A3', pg_temp.fx_id('d_appr'))$q$);

-- ---------------------------------------------------------------------
-- 35. Posting the note is the gate: who, how much, and the ledger
-- ---------------------------------------------------------------------
DO $$
DECLARE
    dao UUID := pg_temp.dao_at('_VERIFY-D-GATE', 'APPROVED', 40);
BEGIN
    INSERT INTO fx VALUES ('d_gate', dao), ('dn_gate', pg_temp.make_dn('_VERIFY-DN-GATE', dao));
END $$;

SELECT pg_temp.refuses('35a', 'the person who raised the authorization posted its note',
  $q$UPDATE document SET status = 'POSTED', posted_by = (SELECT created_by FROM document WHERE id = pg_temp.fx_id('d_gate'))
      WHERE id = pg_temp.fx_id('dn_gate')$q$,
  '23Z02', '%person who raised authorization%');
SELECT pg_temp.refuses('35b', 'a note was posted naming nobody',
  $q$UPDATE document SET status = 'POSTED' WHERE id = pg_temp.fx_id('dn_gate')$q$,
  '23Z02', '%must name who records%');
UPDATE delivery_note_line SET quantity = 30, qty_base_uom = 30 WHERE document_id = pg_temp.fx_id('dn_gate');
SELECT pg_temp.refuses('35c', 'a short load was posted',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.gate_poster(pg_temp.fx_id('d_gate'))
      WHERE id = pg_temp.fx_id('dn_gate')$q$,
  '23Z02', '%allows 40.000 and%loads 30.000%');
UPDATE delivery_note_line SET quantity = 50, qty_base_uom = 50 WHERE document_id = pg_temp.fx_id('dn_gate');
SELECT pg_temp.refuses('35d', 'an over load was posted',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.gate_poster(pg_temp.fx_id('d_gate'))
      WHERE id = pg_temp.fx_id('dn_gate')$q$,
  '23Z02', '%allows 40.000 and%loads 50.000%');

-- Two lines summing to the authorized quantity are an exact load.
UPDATE delivery_note_line SET quantity = 15, qty_base_uom = 15 WHERE document_id = pg_temp.fx_id('dn_gate');
INSERT INTO delivery_note_line (document_id, line_no, authorization_line_id, item_id, uom_id,
                                quantity, qty_base_uom, storage_bin_id, measured_thickness_mm)
SELECT n.document_id, 2, l.authorization_line_id, l.item_id, l.uom_id, 25, 25,
       NULL, 6.0
  FROM delivery_note_line l JOIN delivery_note n ON n.document_id = l.document_id
 WHERE l.document_id = pg_temp.fx_id('dn_gate') AND l.line_no = 1;
SELECT pg_temp.accepts('35e', 'an exact load, split over two lines, is posted by a signer of the authorization',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.gate_poster(pg_temp.fx_id('d_gate'))
      WHERE id = pg_temp.fx_id('dn_gate')$q$);
SELECT pg_temp.refuses('35f', 'a posted note''s load was changed',
  $q$UPDATE delivery_note_line SET quantity = 1 WHERE document_id = pg_temp.fx_id('dn_gate')$q$,
  '23Z02', '%POSTED%cannot change%');
SELECT pg_temp.refuses('35g', 'a posted note''s details were changed',
  $q$UPDATE delivery_note SET driver_name = 'Someone Else' WHERE document_id = pg_temp.fx_id('dn_gate')$q$,
  '23Z02', '%POSTED%cannot change%');

-- The ledger: the ticket for the posted note, stock out.
DO $$
DECLARE
    poster UUID := pg_temp.gate_poster(pg_temp.fx_id('d_gate'));
BEGIN
    INSERT INTO fx VALUES ('tt_gate', pg_temp.make_dn_ticket(pg_temp.fx_id('dn_gate'), poster, '_VERIFY-TT-GATE'));
END $$;
SELECT pg_temp.refuses('35h', 'stock left the ledger recorded by someone other than the person who posted the note',
  $q$SELECT pg_temp.move_out(pg_temp.fx_id('tt_gate'), pg_temp.verify_user(), kigali_today())$q$,
  '23Z02', '%posted by someone else%');
SELECT pg_temp.accepts('35i', 'the movements OUT for the posted note, against the released authorization, are accepted',
  $q$SELECT pg_temp.move_out(pg_temp.fx_id('tt_gate'), pg_temp.gate_poster(pg_temp.fx_id('d_gate')), kigali_today())$q$);
UPDATE document SET status = 'POSTED', posted_by = pg_temp.gate_poster(pg_temp.fx_id('d_gate'))
 WHERE id = pg_temp.fx_id('tt_gate');
SELECT pg_temp.refuses('35j', 'a delivery ticket line was moved twice',
  $q$SELECT pg_temp.move_out(pg_temp.fx_id('tt_gate'), pg_temp.gate_poster(pg_temp.fx_id('d_gate')), kigali_today())$q$,
  '23505', '%stock_movement_one_per_ticket_line%');
SELECT pg_temp.refuses('35k', 'a second delivery ticket was raised for one note',
  $q$SELECT pg_temp.make_dn_ticket(pg_temp.fx_id('dn_gate'), pg_temp.holder('FINANCE'), '_VERIFY-TT-GATE2')$q$,
  '23505', '%transaction_ticket_one_delivery_per_source%');

-- A whole delivery through the gate, for the checks that follow.
SELECT pg_temp.accepts('35l', 'a complete delivery (authorize, sign, raise, post, move, post ticket) passes every guard',
  $q$SELECT pg_temp.deliver('_VERIFY-D-OK', 10)$q$);

-- Delivered notes and tickets moved their stock: the commit-time check passes.
SELECT pg_temp.accepts('35m', 'posted notes that moved their stock pass the commit check',
  $q$SET CONSTRAINTS document_dn_posted_moved_stock, stock_movement_ticket_posted IMMEDIATE$q$);
SET CONSTRAINTS document_dn_posted_moved_stock, stock_movement_ticket_posted DEFERRED;

-- A note that reaches the ledger before it is posted moves nothing.
DO $$
DECLARE
    dn  UUID := pg_temp.make_dn('_VERIFY-DN-EARLY', pg_temp.dao_at('_VERIFY-D-EARLY', 'APPROVED', 5));
BEGIN
    INSERT INTO fx VALUES ('tt_early', pg_temp.make_dn_ticket(dn, pg_temp.holder('FINANCE'), '_VERIFY-TT-EARLY'));
END $$;
SELECT pg_temp.refuses('35n', 'stock left against a delivery note that had not been posted',
  $q$SELECT pg_temp.move_out(pg_temp.fx_id('tt_early'), pg_temp.holder('FINANCE'), kigali_today())$q$,
  '23Z02', '%must be posted before its stock moves%');

-- A ticket cannot carry lines the note did not load.
SELECT pg_temp.refuses('35o', 'a delivery ticket line differed from the note line',
  $q$UPDATE ticket_line SET quantity = 1, qty_base_uom = 1 WHERE ticket_id = pg_temp.fx_id('tt_early')$q$,
  '23Z02', '%differs from line 1%');
-- ---------------------------------------------------------------------
-- 36. An authorization acted on can never read CANCELLED
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('36a', 'a delivered authorization was cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('_VERIFY-D-OK')$q$,
  '23Z02', '%stock has already moved%');
SELECT pg_temp.refuses('36b', 'an authorization was cancelled under a live note',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('d_appr')$q$,
  '23Z02', '%still answers to it%');
SELECT pg_temp.refuses('36c', 'a delivered note was cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('_VERIFY-D-OK-DN')$q$,
  '23Z02', '%corrected by a reversing document%');
SELECT pg_temp.refuses('36d', 'a delivered ticket was cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('_VERIFY-D-OK-TT')$q$,
  '23Z02', '%corrected by a reversing document%');
DO $$
DECLARE d UUID;
BEGIN
    d := pg_temp.dao_at('_VERIFY-D-CANCEL', 'APPROVED');
    INSERT INTO fx VALUES ('d_cancel', d);
END $$;
SELECT pg_temp.accepts('36e', 'an approved authorization nothing has acted on can be cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('d_cancel')$q$);
SELECT pg_temp.refuses('36f', 'a pending authorization with an unsigned chain was cancelled without naming who',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'x' WHERE id = pg_temp.fx_id('d_pend')$q$,
  '23Z02', '%must name who cancels%');
SELECT pg_temp.accepts('36g', 'an authorization whose only note was cancelled can be cancelled too',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = (SELECT id FROM document WHERE serial_no = '_VERIFY-DN-A3')$q$);
SELECT pg_temp.accepts('36h', 'and then the authorization itself',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('d_appr')$q$);

-- ---------------------------------------------------------------------
-- 37. Stock never goes negative
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('37a', 'a balance row with a negative quantity on hand was accepted',
  $q$INSERT INTO stock_balance (item_id, location_id, qty_on_hand)
     SELECT i.id, l.id, -1 FROM item i, location l WHERE i.item_code = '_VERIFY-OTHER' AND l.code = 'KGL-MAIN'$q$,
  '23514', '%stock_balance_never_negative%');

-- More than is on hand: authorized and loaded, but the ledger refuses.
DO $$
DECLARE
    dao UUID := pg_temp.dao_at('_VERIFY-D-HUGE', 'APPROVED', 100000);
    dn  UUID := pg_temp.make_dn('_VERIFY-DN-HUGE', dao);
    p   UUID := pg_temp.gate_poster(dao);
BEGIN
    UPDATE document SET status = 'POSTED', posted_by = p WHERE id = dn;
    INSERT INTO fx VALUES ('tt_huge', pg_temp.make_dn_ticket(dn, p, '_VERIFY-TT-HUGE')), ('p_huge', p);
END $$;
SELECT pg_temp.refuses('37b', 'stock left that was not there',
  $q$SELECT pg_temp.move_out(pg_temp.fx_id('tt_huge'), pg_temp.fx_id('p_huge'), kigali_today())$q$,
  '23Z02', '%Not enough stock%short by%');

-- A named bin must hold what leaves from it.
DO $$
DECLARE
    dao UUID := pg_temp.dao_at('_VERIFY-D-BIN', 'APPROVED', 5);
    dn  UUID := pg_temp.make_dn('_VERIFY-DN-BIN', dao, NULL, '_VERIFY-BIN-MAIN');
    p   UUID := pg_temp.gate_poster(dao);
BEGIN
    UPDATE document SET status = 'POSTED', posted_by = p WHERE id = dn;
    INSERT INTO fx VALUES ('tt_bin', pg_temp.make_dn_ticket(dn, p, '_VERIFY-TT-BIN')), ('p_bin', p);
END $$;
SELECT pg_temp.refuses('37c', 'stock left a bin that held none of it',
  $q$SELECT pg_temp.move_out(pg_temp.fx_id('tt_bin'), pg_temp.fx_id('p_bin'), kigali_today())$q$,
  '23Z02', '%Not enough stock in bin%');

-- Nothing moved in those two: the notes stand posted without their stock, which
-- the commit check refuses.
SELECT pg_temp.refuses('37d', 'a posted delivery note whose stock never left was accepted at commit',
  $q$SET CONSTRAINTS document_dn_posted_moved_stock IMMEDIATE$q$,
  '23Z02', '%never left the ledger%');
SET CONSTRAINTS document_dn_posted_moved_stock DEFERRED;

-- ---------------------------------------------------------------------
-- 39. What the control audit found in V12
-- ---------------------------------------------------------------------

-- A ticket answers only to a goods received note or a delivery note. An
-- approved authorization must not, on its own, let stock leave.
DO $$
DECLARE n INT;
BEGIN
    FOR n IN 1..4 LOOP
        WITH d AS (
            INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
            SELECT dt.id, b.id, '_VERIFY-TT-S' || n, pg_temp.verify_user()
              FROM document_type dt, branch b WHERE dt.code = 'TT' AND b.code = 'KGL'
            RETURNING id)
        INSERT INTO fx SELECT '_VERIFY-TT-S' || n, id FROM d;
    END LOOP;
END $$;

SELECT pg_temp.refuses('39a', 'a ticket sourced from an approved delivery authorization was accepted',
  $q$INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id, source_document_id)
     SELECT pg_temp.fx_id('_VERIFY-TT-S1'), 'DELIVERY', 'OUT', l.id, pg_temp.fx_id('d_gate')
       FROM location l WHERE l.code = 'KGL-MAIN'$q$,
  '23Z02', '%answers only to a goods received note%');
-- (A count sheet was the example here until V15 made a count's ADJUSTMENT a
-- ticket the ledger handles; check 59 covers those. The posted document of
-- check 31's non-stock fixture type stands in for any other source.)
SELECT pg_temp.refuses('39b', 'a ticket sourced from a document type the ledger does not handle was accepted',
  $q$INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id, source_document_id)
     SELECT pg_temp.fx_id('_VERIFY-TT-S2'), 'ADJUSTMENT', 'OUT', l.id, pg_temp.fx_id('nonstock_posted')
       FROM location l WHERE l.code = 'KGL-MAIN'$q$,
  '23Z02', '%answers only to a goods received note%');
SELECT pg_temp.refuses('39c', 'a ticket with no source document was accepted',
  $q$INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id)
     SELECT pg_temp.fx_id('_VERIFY-TT-S3'), 'ADJUSTMENT', 'OUT', l.id
       FROM location l WHERE l.code = 'KGL-MAIN'$q$,
  '23Z02', '%names no supporting document%');

-- Even if such a ticket row somehow exists (its own trigger switched off for
-- this test), the ledger refuses the movement against it.
ALTER TABLE transaction_ticket DISABLE TRIGGER transaction_ticket_rules;
ALTER TABLE ticket_line DISABLE TRIGGER ticket_line_rules;
INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id, source_document_id)
SELECT pg_temp.fx_id('_VERIFY-TT-S1'), 'DELIVERY', 'OUT', l.id, pg_temp.fx_id('d_gate')
  FROM location l WHERE l.code = 'KGL-MAIN';
INSERT INTO ticket_line (ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom)
SELECT pg_temp.fx_id('_VERIFY-TT-S1'), 1, i.id, 5, u.id, 5
  FROM item i, uom u WHERE i.item_code = '_VERIFY-GLASS' AND u.code = 'SHEET';
INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id)
SELECT pg_temp.fx_id('_VERIFY-TT-S4'), 'ADJUSTMENT', 'OUT', l.id
  FROM location l WHERE l.code = 'KGL-MAIN';
INSERT INTO ticket_line (ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom)
SELECT pg_temp.fx_id('_VERIFY-TT-S4'), 1, i.id, 5, u.id, 5
  FROM item i, uom u WHERE i.item_code = '_VERIFY-GLASS' AND u.code = 'SHEET';
ALTER TABLE transaction_ticket ENABLE TRIGGER transaction_ticket_rules;
ALTER TABLE ticket_line ENABLE TRIGGER ticket_line_rules;

SELECT pg_temp.refuses('39d', 'stock left against a ticket sourced from an authorization',
  $q$SELECT pg_temp.move_out(pg_temp.fx_id('_VERIFY-TT-S1'), pg_temp.holder('FINANCE'), kigali_today())$q$,
  '23Z02', '%answers to no goods received note%');
SELECT pg_temp.refuses('39e', 'stock left against a ticket with no source',
  $q$SELECT pg_temp.move_out(pg_temp.fx_id('_VERIFY-TT-S4'), pg_temp.holder('FINANCE'), kigali_today())$q$,
  '23Z02', '%answers to no goods received note%');

-- The unbinned bucket is a bucket: 10 in a bin does not cover 10 unbinned.
-- KGL-CUT holds 10 sheets in bin _VERIFY-BIN and nothing unbinned.
CREATE FUNCTION pg_temp.receive_into_bin(p_serial TEXT, p_qty NUMERIC) RETURNS VOID AS $$
DECLARE
    fin UUID := pg_temp.holder('FINANCE');
    g   UUID;
    tt  UUID;
BEGIN
    g := pg_temp.make_grn(p_serial, NULL, 'KGL', 'KGL-CUT');
    UPDATE goods_received_line SET quantity = p_qty, qty_base_uom = p_qty,
           storage_bin_id = (SELECT id FROM storage_bin WHERE bin_code = '_VERIFY-BIN')
     WHERE document_id = g;
    UPDATE document SET status = 'PENDING' WHERE id = g;
    PERFORM pg_temp.sign_upto(g, pg_temp.steps_in(g));
    UPDATE document SET status = 'APPROVED' WHERE id = g;
    UPDATE document SET status = 'POSTED', posted_by = fin WHERE id = g;
    tt := pg_temp.make_ticket(g, fin, p_serial || '-TT');
    PERFORM pg_temp.move_ticket(tt, fin, kigali_today());
    UPDATE document SET status = 'POSTED', posted_by = fin WHERE id = tt;
END $$ LANGUAGE plpgsql;

-- An authorization, note and (unmoved) ticket at a given location and bin.
CREATE FUNCTION pg_temp.gate_at(p_serial TEXT, p_qty NUMERIC, p_loc TEXT, p_bin TEXT) RETURNS UUID AS $$
DECLARE
    dao UUID; dn UUID; poster UUID;
BEGIN
    dao := pg_temp.make_dao(p_serial, p_qty, NULL, 'KGL', p_loc);
    UPDATE document SET status = 'PENDING' WHERE id = dao;
    PERFORM pg_temp.sign_upto(dao, pg_temp.steps_in(dao));
    UPDATE document SET status = 'APPROVED' WHERE id = dao;
    poster := pg_temp.gate_poster(dao);
    dn := pg_temp.make_dn(p_serial || '-DN', dao, NULL, p_bin);
    UPDATE document SET status = 'POSTED', posted_by = poster WHERE id = dn;
    INSERT INTO fx VALUES (p_serial || '-P', poster);
    RETURN pg_temp.make_dn_ticket(dn, poster, p_serial || '-TT');
END $$ LANGUAGE plpgsql;

DO $$
BEGIN
    PERFORM pg_temp.receive_into_bin('_VERIFY-G-BIN10', 10);
    INSERT INTO fx VALUES ('tt_unb', pg_temp.gate_at('_VERIFY-D-UNB', 10, 'KGL-CUT', NULL));
    INSERT INTO fx VALUES ('tt_bin_ok', pg_temp.gate_at('_VERIFY-D-BINOK', 4, 'KGL-CUT', '_VERIFY-BIN'));
END $$;
SELECT pg_temp.refuses('39f', 'an unbinned OUT beyond the unbinned stock, while the stock sits in a bin, was accepted',
  $q$SELECT pg_temp.move_out(pg_temp.fx_id('tt_unb'), pg_temp.fx_id('_VERIFY-D-UNB-P'), kigali_today())$q$,
  '23Z02', '%Not enough unbinned stock%');
SELECT pg_temp.accepts('39g', 'an OUT from the bin that holds the stock is accepted',
  $q$SELECT pg_temp.move_out(pg_temp.fx_id('tt_bin_ok'), pg_temp.fx_id('_VERIFY-D-BINOK-P'), kigali_today())$q$);
UPDATE document SET status = 'POSTED', posted_by = pg_temp.fx_id('_VERIFY-D-BINOK-P')
 WHERE id = pg_temp.fx_id('tt_bin_ok');
SELECT pg_temp.accepts('39h', 'an unbinned OUT within the unbinned stock is accepted',
  $q$SELECT pg_temp.deliver('_VERIFY-D-UNB2', 3)$q$);

-- The Internal Controller does not let out what it released. The IC holder
-- signed the RELEASE step of this authorization; a verifier may post (35e),
-- a releaser may not. The database checks the person, not the right the
-- service checks, so the test names the IC holder as poster.
DO $$
DECLARE dao UUID := pg_temp.dao_at('_VERIFY-D-IC', 'APPROVED');
BEGIN
    INSERT INTO fx VALUES ('d_ic', dao), ('dn_ic', pg_temp.make_dn('_VERIFY-DN-IC', dao));
END $$;
SELECT pg_temp.refuses('39i', 'the Internal Controller, who signed the release, posted the delivery note',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('INTERNAL_CTRL')
      WHERE id = pg_temp.fx_id('dn_ic')$q$,
  '23Z02', '%signed the release of authorization%');
SELECT pg_temp.accepts('39j', 'the same note is posted by a signer of a verifying step',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.gate_poster(pg_temp.fx_id('d_ic'))
      WHERE id = pg_temp.fx_id('dn_ic')$q$);

-- Cancelling a DAO takes the lock a DN insert takes, so the two serialise.
-- Functionally the cancel still works on an untouched authorization and is
-- still refused under a live note (36b); here the lock is seen held.
DO $$
DECLARE d UUID := pg_temp.dao_at('_VERIFY-D-LOCK', 'APPROVED');
BEGIN
    INSERT INTO fx VALUES ('d_lock', d);
END $$;
SELECT pg_temp.accepts('39k', 'an untouched authorization is still cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('d_lock')$q$);
DO $$
DECLARE key BIGINT := hashtextextended('dn:' || pg_temp.fx_id('d_lock')::text, 0);
BEGIN
    IF EXISTS (SELECT 1 FROM pg_locks
                WHERE locktype = 'advisory' AND pid = pg_backend_pid()
                  AND ((classid::bigint << 32) | objid::bigint) = key) THEN
        RAISE NOTICE 'ok   39l  cancelling a delivery authorization takes the lock a delivery note insert takes';
    ELSE
        RAISE WARNING 'FAIL 39l  the cancellation did not take the delivery note lock';
    END IF;
END $$;
-- The authorization whose note is posted (39j) cannot now be cancelled.
SELECT pg_temp.refuses('39m', 'an authorization was cancelled under a live, posted note',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('d_ic')$q$,
  '23Z02', '%still answers to it%');

-- =====================================================================
-- Transfers (V13): dispatch at the source gate, receipt at the
-- destination, the shortfall left in transit. As before, every refusal
-- must carry SQLSTATE 23Z02 (a workflow or document control) or 23514 (a
-- malformed line) with the reason named.
-- =====================================================================

INSERT INTO location (branch_id, code, name, location_type)
SELECT id, '_VERIFY-WH2', 'Verification second warehouse', 'WAREHOUSE' FROM branch WHERE code = 'KGL';

-- The independent receiver: holds the warehouse role (so may receive) but
-- raised, dispatched and signed nothing on any fixture transfer.
CREATE FUNCTION pg_temp.receiver() RETURNS UUID AS $$
DECLARE u UUID;
BEGIN
    SELECT id INTO u FROM app_user WHERE username = '_verify_receiver';
    IF u IS NULL THEN
        INSERT INTO app_user (username, full_name, password_hash, is_active, must_change_password)
        VALUES ('_verify_receiver', 'Verification Receiver', 'x', TRUE, FALSE) RETURNING id INTO u;
        PERFORM pg_temp.grant_role('_verify_receiver', 'WH_MANAGER');
    END IF;
    RETURN u;
END $$ LANGUAGE plpgsql;

CREATE FUNCTION pg_temp.glass_at(p_loc TEXT) RETURNS NUMERIC AS $$
    SELECT COALESCE(SUM(m.signed_quantity), 0)
      FROM stock_movement m
      JOIN location l ON l.id = m.location_id
      JOIN item i ON i.id = m.item_id
     WHERE l.code = p_loc AND i.item_code = '_VERIFY-GLASS';
$$ LANGUAGE sql;

-- A draft transfer of p_qty glass sheets, source branch KGL.
CREATE FUNCTION pg_temp.make_trf(p_serial TEXT, p_qty NUMERIC DEFAULT 20, p_creator UUID DEFAULT NULL,
                                 p_from TEXT DEFAULT 'KGL-MAIN', p_to TEXT DEFAULT 'RBV-BOND',
                                 p_customs TEXT DEFAULT 'C-TRF-1', p_bin TEXT DEFAULT NULL) RETURNS UUID AS $$
DECLARE d UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, l.branch_id, p_serial, COALESCE(p_creator, pg_temp.verify_user())
      FROM document_type dt, location l WHERE dt.code = 'TRF' AND l.code = p_from
    RETURNING id INTO d;
    INSERT INTO transfer_order (document_id, from_location_id, to_location_id, customs_reference)
    SELECT d, f.id, t.id, p_customs FROM location f, location t WHERE f.code = p_from AND t.code = p_to;
    INSERT INTO transfer_order_line (document_id, line_no, item_id, uom_id, quantity, qty_base_uom, storage_bin_id)
    SELECT d, 1, i.id, u.id, p_qty, p_qty, (SELECT sb.id FROM storage_bin sb WHERE sb.bin_code = p_bin)
      FROM item i, uom u WHERE i.item_code = '_VERIFY-GLASS' AND u.code = 'SHEET';
    RETURN d;
END $$ LANGUAGE plpgsql;

-- A transfer taken to a state: DRAFT, PENDING, STEP1 or APPROVED.
CREATE FUNCTION pg_temp.trf_at(p_serial TEXT, p_state TEXT, p_qty NUMERIC DEFAULT 20) RETURNS UUID AS $$
DECLARE d UUID;
BEGIN
    d := pg_temp.make_trf(p_serial, p_qty);
    IF p_state = 'DRAFT' THEN RETURN d; END IF;
    UPDATE document SET status = 'PENDING' WHERE id = d;
    IF p_state = 'PENDING' THEN RETURN d; END IF;
    IF p_state = 'STEP1' THEN PERFORM pg_temp.sign_upto(d, 1); RETURN d; END IF;
    PERFORM pg_temp.sign_upto(d, pg_temp.steps_in(d));
    UPDATE document SET status = 'APPROVED' WHERE id = d;
    RETURN d;
END $$ LANGUAGE plpgsql;

-- One dispatch ticket: leg 'OUT' (source location) or 'IN' (source transit).
CREATE FUNCTION pg_temp.trf_leg_ticket(p_trf UUID, p_poster UUID, p_serial TEXT, p_leg TEXT) RETURNS UUID AS $$
DECLARE t UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, d.branch_id, p_serial, p_poster
      FROM document_type dt, document d WHERE dt.code = 'TT' AND d.id = p_trf
    RETURNING id INTO t;
    INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id, to_location_id,
                                    source_document_id, customs_reference)
    SELECT t,
           CASE p_leg WHEN 'OUT' THEN 'TRANSFER_OUT' ELSE 'TRANSFER_IN' END,
           p_leg,
           CASE p_leg WHEN 'OUT' THEN x.from_location_id END,
           CASE p_leg WHEN 'IN' THEN x.transit_location_id END,
           p_trf, x.customs_reference
      FROM transfer_order x WHERE x.document_id = p_trf;
    INSERT INTO ticket_line (ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom, storage_bin_id)
    SELECT t, l.line_no, l.item_id, l.quantity, l.uom_id, l.qty_base_uom,
           CASE p_leg WHEN 'OUT' THEN l.storage_bin_id END
      FROM transfer_order_line l WHERE l.document_id = p_trf;
    RETURN t;
END $$ LANGUAGE plpgsql;

-- One leg of a receipt. The OUT leg moves the SOURCE branch's transit location
-- and sits on that branch's register; the IN leg is at the destination branch.
CREATE FUNCTION pg_temp.trr_leg_ticket(p_trr UUID, p_poster UUID, p_serial TEXT, p_leg TEXT) RETURNS UUID AS $$
DECLARE t UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, CASE p_leg WHEN 'OUT' THEN td.branch_id ELSE rd.branch_id END, p_serial, p_poster
      FROM document_type dt, document rd, transfer_receipt r, document td
     WHERE dt.code = 'TT' AND rd.id = p_trr AND r.document_id = rd.id AND td.id = r.transfer_id
    RETURNING id INTO t;
    INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id, to_location_id,
                                    source_document_id, customs_reference)
    SELECT t,
           CASE p_leg WHEN 'OUT' THEN 'TRANSFER_OUT' ELSE 'TRANSFER_IN' END,
           p_leg,
           CASE p_leg WHEN 'OUT' THEN x.transit_location_id END,
           CASE p_leg WHEN 'IN' THEN x.to_location_id END,
           p_trr, x.customs_reference
      FROM transfer_receipt r JOIN transfer_order x ON x.document_id = r.transfer_id
     WHERE r.document_id = p_trr;
    INSERT INTO ticket_line (ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom, storage_bin_id)
    SELECT t, l.line_no, l.item_id, l.quantity, l.uom_id, l.qty_base_uom,
           CASE p_leg WHEN 'IN' THEN l.storage_bin_id END
      FROM transfer_receipt_line l WHERE l.document_id = p_trr;
    RETURN t;
END $$ LANGUAGE plpgsql;

-- The movements of one ticket, in the ticket's own direction.
CREATE FUNCTION pg_temp.move_leg(p_ticket UUID, p_poster UUID, p_date DATE, p_value NUMERIC DEFAULT NULL,
                                 p_values NUMERIC[] DEFAULT NULL) RETURNS VOID AS $$
    INSERT INTO stock_movement (ticket_line_id, document_id, branch_id, item_id, location_id, storage_bin_id,
                                direction, quantity_base_uom, signed_quantity, unit_cost, value,
                                running_balance, business_date, posted_by)
    SELECT tl.id, d.id, d.branch_id, tl.item_id,
           CASE t.direction WHEN 'IN' THEN t.to_location_id ELSE t.from_location_id END,
           tl.storage_bin_id, t.direction, tl.qty_base_uom,
           CASE t.direction WHEN 'IN' THEN tl.qty_base_uom ELSE -tl.qty_base_uom END,
           1000, COALESCE(p_values[tl.line_no], p_value, tl.qty_base_uom * 1000), 0, p_date, p_poster
      FROM ticket_line tl
      JOIN transaction_ticket t ON t.document_id = tl.ticket_id
      JOIN document d ON d.id = t.document_id
     WHERE tl.ticket_id = p_ticket
     ORDER BY tl.line_no;
$$ LANGUAGE sql;

-- The whole dispatch: approved transfer, posted by the Assistant WH Manager
-- (who signs nothing on a transfer), both tickets, both movements, both
-- tickets posted.
CREATE FUNCTION pg_temp.dispatch(p_serial TEXT, p_qty NUMERIC DEFAULT 20) RETURNS UUID AS $$
DECLARE
    t UUID; a UUID; b UUID;
    poster UUID := pg_temp.holder('ASST_WH_MANAGER');
BEGIN
    t := pg_temp.trf_at(p_serial, 'APPROVED', p_qty);
    UPDATE document SET status = 'POSTED', posted_by = poster WHERE id = t;
    a := pg_temp.trf_leg_ticket(t, poster, p_serial || '-OUT', 'OUT');
    b := pg_temp.trf_leg_ticket(t, poster, p_serial || '-IN', 'IN');
    PERFORM pg_temp.move_leg(a, poster, kigali_today());
    PERFORM pg_temp.move_leg(b, poster, kigali_today());
    UPDATE document SET status = 'POSTED', posted_by = poster WHERE id IN (a, b);
    INSERT INTO fx VALUES (p_serial, t), (p_serial || '-OUT', a), (p_serial || '-IN', b);
    RETURN t;
END $$ LANGUAGE plpgsql;

-- A draft receipt at the destination branch; p_qty NULL receives in full.
CREATE FUNCTION pg_temp.make_trr(p_serial TEXT, p_trf UUID, p_creator UUID DEFAULT NULL,
                                 p_branch TEXT DEFAULT 'RBV', p_qty NUMERIC DEFAULT NULL,
                                 p_bin TEXT DEFAULT NULL) RETURNS UUID AS $$
DECLARE r UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, b.id, p_serial, COALESCE(p_creator, pg_temp.receiver())
      FROM document_type dt, branch b WHERE dt.code = 'TRR' AND b.code = p_branch
    RETURNING id INTO r;
    INSERT INTO transfer_receipt (document_id, transfer_id) VALUES (r, p_trf);
    INSERT INTO transfer_receipt_line (document_id, line_no, transfer_line_id, item_id, uom_id,
                                       quantity, qty_base_uom, storage_bin_id, entered_by)
    SELECT r, tl.line_no, tl.id, tl.item_id, tl.uom_id,
           COALESCE(p_qty, tl.quantity), COALESCE(p_qty, tl.quantity),
           (SELECT sb.id FROM storage_bin sb WHERE sb.bin_code = p_bin),
           COALESCE(p_creator, pg_temp.receiver())
      FROM transfer_order_line tl WHERE tl.document_id = p_trf;
    RETURN r;
END $$ LANGUAGE plpgsql;

-- Post a drafted receipt by the independent receiver, write both tickets and
-- their movements (values default to the proportional cost), post the tickets.
CREATE FUNCTION pg_temp.finish_receipt(p_trr UUID, p_serial TEXT, p_out_value NUMERIC DEFAULT NULL,
                                       p_in_value NUMERIC DEFAULT NULL,
                                       p_out_values NUMERIC[] DEFAULT NULL,
                                       p_in_values NUMERIC[] DEFAULT NULL) RETURNS UUID AS $$
DECLARE
    a UUID; b UUID;
    poster UUID := pg_temp.receiver();
BEGIN
    UPDATE document SET status = 'POSTED', posted_by = poster WHERE id = p_trr;
    a := pg_temp.trr_leg_ticket(p_trr, poster, p_serial || '-OUT', 'OUT');
    b := pg_temp.trr_leg_ticket(p_trr, poster, p_serial || '-IN', 'IN');
    PERFORM pg_temp.move_leg(a, poster, kigali_today(), p_out_value, p_out_values);
    PERFORM pg_temp.move_leg(b, poster, kigali_today(), p_in_value, p_in_values);
    UPDATE document SET status = 'POSTED', posted_by = poster WHERE id IN (a, b);
    INSERT INTO fx VALUES (p_serial, p_trr), (p_serial || '-OUT', a), (p_serial || '-IN', b);
    RETURN p_trr;
END $$ LANGUAGE plpgsql;

CREATE FUNCTION pg_temp.receive(p_serial TEXT, p_trf UUID, p_qty NUMERIC DEFAULT NULL) RETURNS UUID AS $$
    SELECT pg_temp.finish_receipt(pg_temp.make_trr(p_serial, p_trf, NULL, 'RBV', p_qty), p_serial);
$$ LANGUAGE sql;

-- A dispatch with stated values on its two legs (total value of the line).
CREATE FUNCTION pg_temp.dispatch_v(p_serial TEXT, p_qty NUMERIC, p_out_value NUMERIC, p_in_value NUMERIC) RETURNS UUID AS $$
DECLARE
    t UUID; a UUID; b UUID;
    poster UUID := pg_temp.holder('ASST_WH_MANAGER');
BEGIN
    t := pg_temp.trf_at(p_serial, 'APPROVED', p_qty);
    UPDATE document SET status = 'POSTED', posted_by = poster WHERE id = t;
    a := pg_temp.trf_leg_ticket(t, poster, p_serial || '-OUT', 'OUT');
    b := pg_temp.trf_leg_ticket(t, poster, p_serial || '-IN', 'IN');
    PERFORM pg_temp.move_leg(a, poster, kigali_today(), p_out_value);
    PERFORM pg_temp.move_leg(b, poster, kigali_today(), p_in_value);
    UPDATE document SET status = 'POSTED', posted_by = poster WHERE id IN (a, b);
    INSERT INTO fx VALUES (p_serial, t), (p_serial || '-OUT', a), (p_serial || '-IN', b);
    RETURN t;
END $$ LANGUAGE plpgsql;

-- A scenario whose commit-time check is asked for at its end, inside a
-- subtransaction: a refusal rolls the whole scenario back, so a broken
-- posting leaves nothing pending for the checks that follow.
CREATE FUNCTION pg_temp.refuses_at_commit(p_label TEXT, p_desc TEXT, p_sql TEXT, p_trigger TEXT, p_like TEXT) RETURNS VOID AS $$
BEGIN
    EXECUTE p_sql;
    EXECUTE 'SET CONSTRAINTS ' || p_trigger || ' IMMEDIATE';
    RAISE WARNING 'FAIL % % was accepted at commit', p_label, p_desc;
EXCEPTION WHEN OTHERS THEN
    IF SQLSTATE = '23Z02' AND SQLERRM ILIKE p_like THEN
        RAISE NOTICE 'ok   % refused at commit: %', p_label, p_desc;
    ELSE
        RAISE WARNING 'FAIL % % was refused for another reason (%): %', p_label, p_desc, SQLSTATE, SQLERRM;
    END IF;
END $$ LANGUAGE plpgsql;

CREATE FUNCTION pg_temp.accepts_at_commit(p_label TEXT, p_desc TEXT, p_sql TEXT, p_trigger TEXT) RETURNS VOID AS $$
BEGIN
    EXECUTE p_sql;
    EXECUTE 'SET CONSTRAINTS ' || p_trigger || ' IMMEDIATE';
    RAISE NOTICE 'ok   % accepted at commit: %', p_label, p_desc;
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'FAIL % % was refused (%): %', p_label, p_desc, SQLSTATE, SQLERRM;
END $$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- 40. The transfer rights sit on the roles whose steps they serve; both
--     chains; nobody is left in conflict
-- ---------------------------------------------------------------------
DO $$
DECLARE
    bad TEXT;
    msg TEXT;
BEGIN
    SELECT string_agg(v.role_code || ' has {' || COALESCE(have.perms, '') || '} expected {' || v.want || '}', '; ')
      INTO bad
      FROM (VALUES
            ('WH_MANAGER',      'transfer.create,transfer.dispatch,transfer.receive,transfer.view'),
            ('ASST_WH_MANAGER', 'transfer.dispatch,transfer.receive,transfer.view'),
            ('HEAD_INVENTORY',  'transfer.approve,transfer.view'),
            ('MANAGING_DIR',    'transfer.approve,transfer.view'),
            ('INTERNAL_CTRL',   'transfer.verify,transfer.view')
           ) AS v(role_code, want)
      LEFT JOIN LATERAL (
            SELECT string_agg(p.code, ',' ORDER BY p.code) AS perms
              FROM role r JOIN role_permission rp ON rp.role_id = r.id
              JOIN permission p ON p.id = rp.permission_id AND p.module = 'transfer'
             WHERE r.code = v.role_code) have ON TRUE
     WHERE have.perms IS DISTINCT FROM v.want;
    IF bad IS NULL THEN
        RAISE NOTICE 'ok   40a  transfer rights sit on the roles whose steps they serve';
    ELSE
        RAISE WARNING 'FAIL 40a  %', bad;
    END IF;

    SELECT string_agg(r.code || ' signs ' || ws.action_label || ' without ' || need.code, '; ')
      INTO bad
      FROM workflow_step ws
      JOIN workflow_definition wd ON wd.id = ws.workflow_definition_id
      JOIN document_type dt ON dt.id = wd.document_type_id AND dt.code = 'TRF'
      JOIN role r ON r.id = ws.required_role_id
      CROSS JOIN LATERAL (SELECT CASE ws.action_label
                                   WHEN 'PREPARE' THEN 'transfer.create'
                                   WHEN 'APPROVE' THEN 'transfer.approve'
                                   WHEN 'VERIFY'  THEN 'transfer.verify' END AS code) need
     WHERE NOT EXISTS (SELECT 1 FROM role_permission rp JOIN permission p ON p.id = rp.permission_id
                        WHERE rp.role_id = r.id AND p.code = need.code);
    IF bad IS NULL THEN
        RAISE NOTICE 'ok   40b  every transfer chain signer carries the right its step needs';
    ELSE
        RAISE WARNING 'FAIL 40b  %', bad;
    END IF;

    SELECT string_agg(p.code || ':' || r.code, ',' ORDER BY p.code, r.code) INTO bad
      FROM role r JOIN role_permission rp ON rp.role_id = r.id JOIN permission p ON p.id = rp.permission_id
     WHERE p.code IN ('transfer.dispatch', 'transfer.receive');
    IF bad = 'transfer.dispatch:ASST_WH_MANAGER,transfer.dispatch:WH_MANAGER,transfer.receive:ASST_WH_MANAGER,transfer.receive:WH_MANAGER' THEN
        RAISE NOTICE 'ok   40c  only the warehouse roles carry dispatch and receipt';
    ELSE
        RAISE WARNING 'FAIL 40c  dispatch and receipt are carried by %', bad;
    END IF;

    -- Both definitions: the 2026 chain ends on 1 January 2027 where the 2027 one begins.
    SELECT string_agg(wd.version || ':' || wd.effective_from || '..' || COALESCE(wd.effective_to::text, 'open') || ':' ||
                      (SELECT string_agg(ws.sequence_no || r.code || '/' || ws.action_label, ',' ORDER BY ws.sequence_no)
                         FROM workflow_step ws JOIN role r ON r.id = ws.required_role_id
                        WHERE ws.workflow_definition_id = wd.id), ' | ' ORDER BY wd.version)
      INTO bad
      FROM workflow_definition wd JOIN document_type dt ON dt.id = wd.document_type_id AND dt.code = 'TRF';
    IF bad = '1:2026-07-01..2027-01-01:1WH_MANAGER/PREPARE,2HEAD_INVENTORY/APPROVE,3INTERNAL_CTRL/VERIFY | 2:2027-01-01..open:1WH_MANAGER/PREPARE,2MANAGING_DIR/APPROVE,3INTERNAL_CTRL/VERIFY' THEN
        RAISE NOTICE 'ok   40d  the 2026 chain (Head of Inventory) ends where the 2027 chain (Managing Director) begins';
    ELSE
        RAISE WARNING 'FAIL 40d  transfer chains read %', bad;
    END IF;

    msg := access_conflict_anywhere();
    IF msg IS NULL THEN
        RAISE NOTICE 'ok   40e  nobody, and no role, is left in conflict';
    ELSE
        RAISE WARNING 'FAIL 40e  %', msg;
    END IF;
END $$;

SELECT pg_temp.refuses('40f', 'the Internal Controller was given transfer.dispatch',
  $q$INSERT INTO role_permission (role_id, permission_id)
     SELECT r.id, p.id FROM role r, permission p WHERE r.code = 'INTERNAL_CTRL' AND p.code = 'transfer.dispatch'$q$,
  '23Z01');
SELECT pg_temp.refuses('40g', 'the Internal Controller was given transfer.receive',
  $q$INSERT INTO role_permission (role_id, permission_id)
     SELECT r.id, p.id FROM role r, permission p WHERE r.code = 'INTERNAL_CTRL' AND p.code = 'transfer.receive'$q$,
  '23Z01');
SELECT pg_temp.refuses('40h', 'the Internal Controller and the dispatching warehouse were held by one person',
  $q$INSERT INTO user_role (user_id, role_id, assigned_by)
     SELECT pg_temp.holder('ASST_WH_MANAGER'), r.id, pg_temp.verify_user() FROM role r WHERE r.code = 'INTERNAL_CTRL'$q$,
  '23Z01');

-- ---------------------------------------------------------------------
-- 41. The transfer: between branches only, bonded rules, frozen at
--     submission, signed down its chain
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('41a', 'a transfer between two locations of one branch was accepted',
  $q$SELECT pg_temp.make_trf('_VERIFY-T-SAME', 20, NULL, 'KGL-MAIN', '_VERIFY-WH2', NULL)$q$,
  '23514', '%same branch%');
SELECT pg_temp.refuses('41b', 'a transfer into a bonded location without a customs reference was accepted',
  $q$SELECT pg_temp.make_trf('_VERIFY-T-NOCUST', 20, NULL, 'KGL-MAIN', 'RBV-BOND', NULL)$q$,
  '23514', '%customs reference%');
SELECT pg_temp.refuses('41c', 'a blank customs reference was accepted',
  $q$SELECT pg_temp.make_trf('_VERIFY-T-BLANK', 20, NULL, 'KGL-MAIN', 'RBV-BOND', '  ')$q$,
  '23514', '%transfer_customs_reference_not_blank%');
SELECT pg_temp.refuses('41d', 'a transfer out of a cutting floor was accepted',
  $q$SELECT pg_temp.make_trf('_VERIFY-T-CUT', 20, NULL, 'KGL-CUT', 'RBV-BOND')$q$,
  '23514', '%warehouse or bonded%');

INSERT INTO location (branch_id, code, name, location_type)
SELECT id, '_VERIFY-TRAN2', 'Verification second transit', 'TRANSIT' FROM branch WHERE code = 'KGL';
SELECT pg_temp.refuses('41e', 'a transfer was raised where the source branch has two transit locations',
  $q$SELECT pg_temp.make_trf('_VERIFY-T-TWOTRAN')$q$,
  '23514', '%exactly one active transit location%');
UPDATE location SET is_active = FALSE WHERE code = '_VERIFY-TRAN2';

DO $$
BEGIN
    INSERT INTO fx VALUES
        ('t_draft', pg_temp.trf_at('_VERIFY-T-DRAFT', 'DRAFT')),
        ('t_pend',  pg_temp.trf_at('_VERIFY-T-PEND',  'PENDING')),
        ('t_step1', pg_temp.trf_at('_VERIFY-T-STEP1', 'STEP1')),
        ('t_appr',  pg_temp.trf_at('_VERIFY-T-APPR',  'APPROVED'));
END $$;

DO $$
DECLARE tr UUID; tn UUID;
BEGIN
    SELECT transit_location_id INTO tr FROM transfer_order WHERE document_id = pg_temp.fx_id('t_draft');
    SELECT id INTO tn FROM location WHERE code = 'KGL-TRAN';
    IF tr = tn THEN
        RAISE NOTICE 'ok   41f  the database assigns the source branch''s transit location';
    ELSE
        RAISE WARNING 'FAIL 41f  the transit location is %', tr;
    END IF;
END $$;
SELECT pg_temp.refuses('41g', 'another transit location was chosen',
  $q$UPDATE transfer_order SET transit_location_id = (SELECT id FROM location WHERE code = 'KGL-CUT')
      WHERE document_id = pg_temp.fx_id('t_draft')$q$,
  '23514', '%another cannot be chosen%');
SELECT pg_temp.refuses('41h', 'a submitted transfer''s header was edited',
  $q$UPDATE transfer_order SET note = 'CHANGED' WHERE document_id = pg_temp.fx_id('t_pend')$q$,
  '23Z02', '%PENDING%cannot change%');
SELECT pg_temp.refuses('41i', 'a line was added after submission',
  $q$INSERT INTO transfer_order_line (document_id, line_no, item_id, uom_id, quantity, qty_base_uom)
     SELECT pg_temp.fx_id('t_pend'), 2, i.id, u.id, 1, 1 FROM item i, uom u
      WHERE i.item_code = '_VERIFY-GLASS' AND u.code = 'SHEET'$q$,
  '23Z02', '%PENDING%cannot change%');
SELECT pg_temp.refuses('41j', 'a line was changed after approval',
  $q$UPDATE transfer_order_line SET quantity = 500, qty_base_uom = 500 WHERE document_id = pg_temp.fx_id('t_appr')$q$,
  '23Z02', '%APPROVED%cannot change%');
SELECT pg_temp.refuses('41k', 'a line was deleted after submission',
  $q$DELETE FROM transfer_order_line WHERE document_id = pg_temp.fx_id('t_pend')$q$,
  '23Z02', '%PENDING%cannot change%');
SELECT pg_temp.accepts('41l', 'a draft transfer is still editable',
  $q$UPDATE transfer_order_line SET quantity = 25, qty_base_uom = 25 WHERE document_id = pg_temp.fx_id('t_draft')$q$);
SELECT pg_temp.refuses('41m', 'a base quantity that does not follow from the entered one was accepted',
  $q$UPDATE transfer_order_line SET qty_base_uom = 999 WHERE document_id = pg_temp.fx_id('t_draft')$q$,
  '23514', '%base unit%');
SELECT pg_temp.refuses('41n', 'a bin from another location was accepted',
  $q$UPDATE transfer_order_line SET storage_bin_id = (SELECT id FROM storage_bin WHERE bin_code = '_VERIFY-BIN')
      WHERE document_id = pg_temp.fx_id('t_draft')$q$,
  '23514', '%not in the location%');

DO $$
DECLARE d UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, b.id, '_VERIFY-T-EMPTY', pg_temp.verify_user()
      FROM document_type dt, branch b WHERE dt.code = 'TRF' AND b.code = 'KGL'
    RETURNING id INTO d;
    INSERT INTO transfer_order (document_id, from_location_id, to_location_id, customs_reference)
    SELECT d, f.id, t.id, 'C-1' FROM location f, location t WHERE f.code = 'KGL-MAIN' AND t.code = 'RBV-BOND';
    INSERT INTO fx VALUES ('t_empty', d);
END $$;
SELECT pg_temp.refuses('41o', 'a transfer with no lines was submitted',
  $q$UPDATE document SET status = 'PENDING' WHERE id = pg_temp.fx_id('t_empty')$q$,
  '23Z02', '%no lines%');
SELECT pg_temp.refuses('41p', 'the Managing Director signed the 2026 chain''s approval step out of order',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('t_pend'), 3, pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('t_pend'), 3)))$q$,
  '23Z02', '%must sign%before step%');
SELECT pg_temp.refuses('41q', 'someone without the step''s role signed the transfer',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('t_pend'), 1, pg_temp.holder('SALES'))$q$,
  '23Z02', '%does not hold%');
SELECT pg_temp.accepts('41r', 'the whole chain signs a transfer in order, each in its own role',
  $q$SELECT pg_temp.trf_at('_VERIFY-T-CHAIN', 'APPROVED')$q$);

-- ---------------------------------------------------------------------
-- 42. Dispatch at the source gate
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('42a', 'a transfer still pending was dispatched',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('ASST_WH_MANAGER') WHERE id = pg_temp.fx_id('t_pend')$q$,
  '23Z02', '%cannot move from PENDING to POSTED%');
SELECT pg_temp.refuses('42b', 'a transfer with only its first signature was dispatched',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('ASST_WH_MANAGER') WHERE id = pg_temp.fx_id('t_step1')$q$,
  '23Z02', '%cannot move from PENDING to POSTED%');
SELECT pg_temp.refuses('42c', 'the person who raised the transfer dispatched it',
  $q$UPDATE document SET status = 'POSTED', posted_by = created_by WHERE id = pg_temp.fx_id('t_appr')$q$,
  '23Z02', '%person who raised it%');
SELECT pg_temp.refuses('42d', 'the Internal Controller, who verified the transfer, dispatched it',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('INTERNAL_CTRL') WHERE id = pg_temp.fx_id('t_appr')$q$,
  '23Z02', '%signed%cannot also post%');
SELECT pg_temp.refuses('42e', 'the Head of Inventory, who approved the transfer, dispatched it',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('HEAD_INVENTORY') WHERE id = pg_temp.fx_id('t_appr')$q$,
  '23Z02', '%signed%cannot also post%');
SELECT pg_temp.refuses('42f', 'a dispatch named nobody',
  $q$UPDATE document SET status = 'POSTED' WHERE id = pg_temp.fx_id('t_appr')$q$,
  '23Z02', '%must name who posts%');

-- Stock does not move for a transfer that has not been dispatched.
DO $$
DECLARE p UUID := pg_temp.holder('ASST_WH_MANAGER');
BEGIN
    INSERT INTO fx VALUES ('tt_early_out', pg_temp.trf_leg_ticket(pg_temp.fx_id('t_appr'), p, '_VERIFY-TT-EARLY-OUT', 'OUT'));
END $$;
SELECT pg_temp.refuses('42g', 'stock left against an approved transfer that had not been dispatched',
  $q$SELECT pg_temp.move_leg(pg_temp.fx_id('tt_early_out'), pg_temp.holder('ASST_WH_MANAGER'), kigali_today())$q$,
  '23Z02', '%must be posted before its stock moves%');

-- The exact dispatch: out of the source location, into the source branch's transit.
DO $$
DECLARE
    src_before NUMERIC := pg_temp.glass_at('KGL-MAIN');
    tr_before  NUMERIC := pg_temp.glass_at('KGL-TRAN');
    dst_before NUMERIC := pg_temp.glass_at('RBV-BOND');
BEGIN
    PERFORM pg_temp.dispatch('_VERIFY-T-R1', 20);
    IF pg_temp.glass_at('KGL-MAIN') = src_before - 20
       AND pg_temp.glass_at('KGL-TRAN') = tr_before + 20
       AND pg_temp.glass_at('RBV-BOND') = dst_before THEN
        RAISE NOTICE 'ok   42h  the exact dispatch moves 20 from the source location into source transit, and nothing reaches the destination';
    ELSE
        RAISE WARNING 'FAIL 42h  dispatch moved source % -> %, transit % -> %, destination % -> %',
            src_before, pg_temp.glass_at('KGL-MAIN'), tr_before, pg_temp.glass_at('KGL-TRAN'),
            dst_before, pg_temp.glass_at('RBV-BOND');
    END IF;
END $$;
SELECT pg_temp.refuses('42i', 'a second dispatch ticket was raised for one transfer leg',
  $q$SELECT pg_temp.trf_leg_ticket(pg_temp.fx_id('_VERIFY-T-R1'), pg_temp.holder('ASST_WH_MANAGER'), '_VERIFY-T-R1-OUT2', 'OUT')$q$,
  '23505', '%transaction_ticket_one_transfer_leg_per_source%');
SELECT pg_temp.refuses('42j', 'a dispatched transfer''s line was changed',
  $q$UPDATE transfer_order_line SET quantity = 1, qty_base_uom = 1 WHERE document_id = pg_temp.fx_id('_VERIFY-T-R1')$q$,
  '23Z02', '%POSTED%cannot change%');

-- ---------------------------------------------------------------------
-- 43. Receipt at the destination: only after dispatch, once, by someone
--     who did not dispatch, never more than was sent; a shortfall stays
--     in transit
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('43a', 'a receipt was raised before the transfer was dispatched',
  $q$SELECT pg_temp.make_trr('_VERIFY-R-EARLY', pg_temp.fx_id('t_appr'))$q$,
  '23Z02', '%not dispatched%');
SELECT pg_temp.refuses('43b', 'a receipt was raised at the source branch instead of the destination',
  $q$SELECT pg_temp.make_trr('_VERIFY-R-WRONG', pg_temp.fx_id('_VERIFY-T-R1'), NULL, 'KGL')$q$,
  '23Z02', '%wrong branch%');

DO $$
BEGIN
    INSERT INTO fx VALUES ('trr1', pg_temp.make_trr('_VERIFY-R-1', pg_temp.fx_id('_VERIFY-T-R1'), NULL, 'RBV', 25));
END $$;
SELECT pg_temp.refuses('43c', 'a second live receipt was raised for one transfer',
  $q$SELECT pg_temp.make_trr('_VERIFY-R-1B', pg_temp.fx_id('_VERIFY-T-R1'))$q$,
  '23Z02', '%already has a live receipt%');
SELECT pg_temp.refuses('43d', 'a receipt of 25 against 20 dispatched was posted',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.receiver() WHERE id = pg_temp.fx_id('trr1')$q$,
  '23Z02', '%dispatched 20.000 and%receives 25.000%');
UPDATE transfer_receipt_line SET quantity = 15, qty_base_uom = 15 WHERE document_id = pg_temp.fx_id('trr1');
SELECT pg_temp.refuses('43e', 'the person who dispatched the transfer posted its receipt',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('ASST_WH_MANAGER') WHERE id = pg_temp.fx_id('trr1')$q$,
  '23Z02', '%person who dispatched%');
SELECT pg_temp.refuses('43f', 'a receipt was posted naming nobody',
  $q$UPDATE document SET status = 'POSTED' WHERE id = pg_temp.fx_id('trr1')$q$,
  '23Z02', '%must name who%');
SELECT pg_temp.refuses('43g', 'a receipt bin was taken from another location',
  $q$UPDATE transfer_receipt_line SET storage_bin_id = (SELECT id FROM storage_bin WHERE bin_code = '_VERIFY-BIN-MAIN')
      WHERE document_id = pg_temp.fx_id('trr1')$q$,
  '23514', '%not in the destination location%');
SELECT pg_temp.refuses('43h', 'a receipt line served a line of another transfer',
  $q$UPDATE transfer_receipt_line SET transfer_line_id = (SELECT id FROM transfer_order_line WHERE document_id = pg_temp.fx_id('t_appr'))
      WHERE document_id = pg_temp.fx_id('trr1')$q$,
  '23514', '%different transfer%');

-- Posted by the receiver, the short receipt: 15 of 20 arrive, 5 stay in transit.
DO $$
DECLARE
    poster UUID := pg_temp.receiver();
    r UUID := pg_temp.fx_id('trr1');
    a UUID; b UUID;
BEGIN
    UPDATE document SET status = 'POSTED', posted_by = poster WHERE id = r;
    a := pg_temp.trr_leg_ticket(r, poster, '_VERIFY-R-1-OUT', 'OUT');
    b := pg_temp.trr_leg_ticket(r, poster, '_VERIFY-R-1-IN', 'IN');
    INSERT INTO fx VALUES ('trr1_out', a), ('trr1_in', b);
END $$;
SELECT pg_temp.refuses('43i', 'the receipt''s stock was recorded by someone other than the receiver',
  $q$SELECT pg_temp.move_leg(pg_temp.fx_id('trr1_out'), pg_temp.verify_user(), kigali_today())$q$,
  '23Z02', '%posted by someone else%');
DO $$
DECLARE
    tr_before  NUMERIC := pg_temp.glass_at('KGL-TRAN');
    dst_before NUMERIC := pg_temp.glass_at('RBV-BOND');
    src_before NUMERIC := pg_temp.glass_at('KGL-MAIN');
    poster UUID := pg_temp.receiver();
    pos RECORD;
BEGIN
    PERFORM pg_temp.move_leg(pg_temp.fx_id('trr1_out'), poster, kigali_today());
    PERFORM pg_temp.move_leg(pg_temp.fx_id('trr1_in'), poster, kigali_today());
    UPDATE document SET status = 'POSTED', posted_by = poster
     WHERE id IN (pg_temp.fx_id('trr1_out'), pg_temp.fx_id('trr1_in'));
    IF pg_temp.glass_at('RBV-BOND') = dst_before + 15
       AND pg_temp.glass_at('KGL-TRAN') = tr_before - 15
       AND pg_temp.glass_at('KGL-MAIN') = src_before THEN
        RAISE NOTICE 'ok   43j  a short receipt moves the 15 that arrived out of transit and into the destination';
    ELSE
        RAISE WARNING 'FAIL 43j  receipt moved destination % -> %, transit % -> %', dst_before,
            pg_temp.glass_at('RBV-BOND'), tr_before, pg_temp.glass_at('KGL-TRAN');
    END IF;
    SELECT * INTO pos FROM transfer_line_position WHERE transfer_id = pg_temp.fx_id('_VERIFY-T-R1');
    IF pos.dispatched_base = 20 AND pos.received_base = 15 AND pos.in_transit_base = 5 THEN
        RAISE NOTICE 'ok   43k  the shortfall of 5 is a visible balance: dispatched 20, received 15, in transit 5';
    ELSE
        RAISE WARNING 'FAIL 43k  position reads dispatched %, received %, in transit %',
            pos.dispatched_base, pos.received_base, pos.in_transit_base;
    END IF;
END $$;
SELECT pg_temp.refuses('43l', 'a second receipt was raised after the first was posted',
  $q$SELECT pg_temp.make_trr('_VERIFY-R-1C', pg_temp.fx_id('_VERIFY-T-R1'))$q$,
  '23Z02', '%already has a live receipt%');
SELECT pg_temp.refuses('43m', 'a posted receipt''s line was changed',
  $q$UPDATE transfer_receipt_line SET quantity = 20, qty_base_uom = 20 WHERE document_id = pg_temp.fx_id('trr1')$q$,
  '23Z02', '%POSTED%cannot change%');
SELECT pg_temp.refuses('43n', 'a receipt''s out-of-transit ticket was put on the destination branch''s register',
  $q$INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
     SELECT dt.id, b.id, '_VERIFY-TT-BADBR', pg_temp.verify_user() FROM document_type dt, branch b
      WHERE dt.code = 'TT' AND b.code = 'RBV';
     INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id, source_document_id, customs_reference)
     SELECT d.id, 'TRANSFER_OUT', 'OUT', x.transit_location_id, pg_temp.fx_id('trr1'), x.customs_reference
       FROM document d, transfer_order x
      WHERE d.serial_no = '_VERIFY-TT-BADBR' AND x.document_id = pg_temp.fx_id('_VERIFY-T-R1')$q$,
  '23Z02', '%must be a receipt leg%');

-- A full receipt of a second transfer leaves nothing in transit.
DO $$
DECLARE
    t UUID := pg_temp.dispatch('_VERIFY-T-R2', 10);
    pos RECORD;
BEGIN
    PERFORM pg_temp.receive('_VERIFY-R-2', t);
    SELECT * INTO pos FROM transfer_line_position WHERE transfer_id = t;
    IF pos.dispatched_base = 10 AND pos.received_base = 10 AND pos.in_transit_base = 0 THEN
        RAISE NOTICE 'ok   43o  a full receipt, recorded by someone other than the dispatcher, leaves nothing in transit';
    ELSE
        RAISE WARNING 'FAIL 43o  position reads dispatched %, received %, in transit %',
            pos.dispatched_base, pos.received_base, pos.in_transit_base;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 44. A dispatched transfer can never read CANCELLED
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('44a', 'a dispatched transfer was cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('_VERIFY-T-R1')$q$,
  '23Z02', '%reversing document%');
SELECT pg_temp.refuses('44b', 'a transfer with a live receipt was cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('_VERIFY-T-R2')$q$,
  '23Z02', '%reversing document%');
SELECT pg_temp.refuses('44c', 'a posted receipt was cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('_VERIFY-R-2')$q$,
  '23Z02', '%reversing document%');
SELECT pg_temp.refuses('44d', 'a dispatch ticket was cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('_VERIFY-T-R1-OUT')$q$,
  '23Z02', '%reversing document%');

-- A draft receipt can be cancelled and another raised in its place.
DO $$
DECLARE t UUID := pg_temp.dispatch('_VERIFY-T-R3', 7);
BEGIN
    INSERT INTO fx VALUES ('trr3a', pg_temp.make_trr('_VERIFY-R-3A', t));
END $$;
SELECT pg_temp.accepts('44e', 'a draft receipt can be cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('trr3a')$q$);
SELECT pg_temp.accepts('44f', 'with the first receipt cancelled, a new one is raised for the transfer',
  $q$SELECT pg_temp.make_trr('_VERIFY-R-3B', pg_temp.fx_id('_VERIFY-T-R3'))$q$);

-- Everything posted so far moved both legs of every line: the commit checks pass.
SELECT pg_temp.accepts('44g', 'posted transfers and receipts that moved their stock pass the commit check',
  $q$SET CONSTRAINTS document_trf_posted_moved_stock, document_trr_posted_moved_stock, stock_movement_ticket_posted,
                     document_trf_value_kept, document_trr_value_share IMMEDIATE$q$);
SET CONSTRAINTS document_trf_posted_moved_stock, document_trr_posted_moved_stock, stock_movement_ticket_posted,
                document_trf_value_kept, document_trr_value_share DEFERRED;

-- ---------------------------------------------------------------------
-- 45. What must not be left half-done (these leave posted documents whose
--     stock never moved, so they run last, ending in the commit check)
-- ---------------------------------------------------------------------
DO $$
DECLARE
    p UUID := pg_temp.holder('ASST_WH_MANAGER');
    t UUID := pg_temp.trf_at('_VERIFY-T-BAD', 'APPROVED', 20);
BEGIN
    UPDATE document SET status = 'POSTED', posted_by = p WHERE id = t;
    INSERT INTO fx VALUES ('t_bad', t),
        ('t_bad_out', pg_temp.trf_leg_ticket(t, p, '_VERIFY-T-BAD-OUT', 'OUT')),
        ('t_bad_in',  pg_temp.trf_leg_ticket(t, p, '_VERIFY-T-BAD-IN',  'IN'));
END $$;
SELECT pg_temp.refuses('45a', 'a short dispatch load was ticketed',
  $q$UPDATE ticket_line SET quantity = 15, qty_base_uom = 15 WHERE ticket_id = pg_temp.fx_id('t_bad_out')$q$,
  '23Z02', '%exactly the approved quantity%');
SELECT pg_temp.refuses('45b', 'an over dispatch load was ticketed',
  $q$UPDATE ticket_line SET quantity = 25, qty_base_uom = 25 WHERE ticket_id = pg_temp.fx_id('t_bad_in')$q$,
  '23Z02', '%exactly the approved quantity%');
SELECT pg_temp.refuses('45c', 'a dispatch ticket named a source location the transfer does not',
  $q$INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
     SELECT dt.id, b.id, '_VERIFY-TT-BADFROM', pg_temp.verify_user() FROM document_type dt, branch b
      WHERE dt.code = 'TT' AND b.code = 'KGL';
     INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id, source_document_id, customs_reference)
     SELECT d.id, 'TRANSFER_OUT', 'OUT', l.id, pg_temp.fx_id('t_bad'), 'C-TRF-1'
       FROM document d, location l WHERE d.serial_no = '_VERIFY-TT-BADFROM' AND l.code = '_VERIFY-WH2'$q$,
  '23Z02', '%must be a dispatch leg%');
SELECT pg_temp.refuses('45d', 'a dispatch ticket put the goods somewhere other than transit',
  $q$INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
     SELECT dt.id, b.id, '_VERIFY-TT-BADTO', pg_temp.verify_user() FROM document_type dt, branch b
      WHERE dt.code = 'TT' AND b.code = 'KGL';
     INSERT INTO transaction_ticket (document_id, movement_type, direction, to_location_id, source_document_id, customs_reference)
     SELECT d.id, 'TRANSFER_IN', 'IN', l.id, pg_temp.fx_id('t_bad'), 'C-TRF-1'
       FROM document d, location l WHERE d.serial_no = '_VERIFY-TT-BADTO' AND l.code = '_VERIFY-WH2'$q$,
  '23Z02', '%must be a dispatch leg%');

-- More than is on hand.
DO $$
DECLARE
    p UUID := pg_temp.holder('ASST_WH_MANAGER');
    t UUID := pg_temp.trf_at('_VERIFY-T-HUGE', 'APPROVED', 100000);
BEGIN
    UPDATE document SET status = 'POSTED', posted_by = p WHERE id = t;
    INSERT INTO fx VALUES ('t_huge_out', pg_temp.trf_leg_ticket(t, p, '_VERIFY-T-HUGE-OUT', 'OUT'));
END $$;
SELECT pg_temp.refuses('45e', 'a dispatch took more stock than the source location holds',
  $q$SELECT pg_temp.move_leg(pg_temp.fx_id('t_huge_out'), pg_temp.holder('ASST_WH_MANAGER'), kigali_today())$q$,
  '23Z02', '%Not enough stock%');

-- A receipt cannot take more out of transit than is there. The receipt's own
-- posting guard forbids receiving more than was sent, so that guard is switched
-- off (session_replication_role) for that one posting, to reach the ledger's own refusal.
DO $$
DECLARE
    p UUID := pg_temp.receiver();
    r UUID := pg_temp.make_trr('_VERIFY-R-4', pg_temp.dispatch('_VERIFY-T-R4', 7), NULL, 'RBV', 100000);
BEGIN
    SET LOCAL session_replication_role = replica;
    UPDATE document SET status = 'POSTED', posted_by = p, posted_at = now() WHERE id = r;
    SET LOCAL session_replication_role = origin;
    INSERT INTO fx VALUES ('trr4_out', pg_temp.trr_leg_ticket(r, p, '_VERIFY-R-4-OUT', 'OUT'));
END $$;
SELECT pg_temp.refuses('45f', 'a receipt took more out of transit than transit holds',
  $q$SELECT pg_temp.move_leg(pg_temp.fx_id('trr4_out'), pg_temp.receiver(), kigali_today())$q$,
  '23Z02', '%Not enough stock%');

-- A receipt posted by the book but whose stock never moved.
DO $$
DECLARE r UUID := pg_temp.make_trr('_VERIFY-R-5', pg_temp.dispatch('_VERIFY-T-R5', 7));
BEGIN
    UPDATE document SET status = 'POSTED', posted_by = pg_temp.receiver() WHERE id = r;
END $$;

SELECT pg_temp.refuses('45g', 'a posted transfer whose stock never moved was accepted at commit',
  $q$SET CONSTRAINTS document_trf_posted_moved_stock IMMEDIATE$q$,
  '23Z02', '%never completed its%leg%');
SET CONSTRAINTS document_trf_posted_moved_stock DEFERRED;
SELECT pg_temp.refuses('45h', 'a posted receipt whose stock never moved was accepted at commit',
  $q$SET CONSTRAINTS document_trr_posted_moved_stock IMMEDIATE$q$,
  '23Z02', '%never completed its%leg%');
SET CONSTRAINTS document_trr_posted_moved_stock DEFERRED;

CREATE FUNCTION pg_temp.receive_v_out_in(p_serial TEXT, p_trf UUID, p_qty NUMERIC, p_out NUMERIC, p_in NUMERIC) RETURNS UUID AS $$
    SELECT pg_temp.finish_receipt(pg_temp.make_trr(p_serial, p_trf, NULL, 'RBV', p_qty), p_serial, p_out, p_in);
$$ LANGUAGE sql;

-- ---------------------------------------------------------------------
-- 47. A receipt is independent of the transfer: not raised, dispatched or
--     signed by the same person, at either end of the receipt
-- ---------------------------------------------------------------------
DO $$
BEGIN
    PERFORM pg_temp.dispatch('_VERIFY-T-I1', 10);
END $$;
SELECT pg_temp.refuses('47a', 'a receipt was raised by the person who raised the transfer',
  $q$SELECT pg_temp.make_trr('_VERIFY-R-I1A', pg_temp.fx_id('_VERIFY-T-I1'), pg_temp.verify_user())$q$,
  '23Z02', '%cannot be raised by the person who raised transfer%');
SELECT pg_temp.refuses('47b', 'a receipt was raised by a signer of the transfer',
  $q$SELECT pg_temp.make_trr('_VERIFY-R-I1B', pg_temp.fx_id('_VERIFY-T-I1'), pg_temp.holder('WH_MANAGER'))$q$,
  '23Z02', '%cannot be raised by a person who signed transfer%');
SELECT pg_temp.refuses('47c', 'a receipt was authored by the dispatcher',
  $q$SELECT pg_temp.make_trr('_VERIFY-R-I1C', pg_temp.fx_id('_VERIFY-T-I1'), pg_temp.holder('ASST_WH_MANAGER'))$q$,
  '23Z02', '%cannot be raised by the person who dispatched transfer%');
DO $$
BEGIN
    INSERT INTO fx VALUES ('trr_i1', pg_temp.make_trr('_VERIFY-R-I1', pg_temp.fx_id('_VERIFY-T-I1')));
END $$;
SELECT pg_temp.accepts('47d', 'a receipt raised by an independent receiver is accepted',
  $q$SELECT 1 FROM document WHERE id = pg_temp.fx_id('trr_i1')$q$);
SELECT pg_temp.refuses('47e', 'the person who raised the transfer posted its receipt',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.verify_user() WHERE id = pg_temp.fx_id('trr_i1')$q$,
  '23Z02', '%cannot be posted by the person who raised transfer%');
SELECT pg_temp.refuses('47f', 'a signer of the transfer posted its receipt',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('HEAD_INVENTORY') WHERE id = pg_temp.fx_id('trr_i1')$q$,
  '23Z02', '%cannot be posted by a person who signed transfer%');
SELECT pg_temp.refuses('47g', 'the dispatcher posted the receipt a colleague raised',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('ASST_WH_MANAGER') WHERE id = pg_temp.fx_id('trr_i1')$q$,
  '23Z02', '%cannot be posted by the person who dispatched transfer%');
SELECT pg_temp.accepts('47h', 'the independent receiver posts the receipt and moves its stock',
  $q$SELECT pg_temp.finish_receipt(pg_temp.fx_id('trr_i1'), '_VERIFY-R-I1')$q$);

-- ---------------------------------------------------------------------
-- 48. A transfer creates and moves no value: what enters transit is what
--     left the source, and a receipt takes its exact share out of transit
-- ---------------------------------------------------------------------
INSERT INTO storage_bin (location_id, bin_code)
SELECT id, '_VERIFY-BIN-RBV' FROM location WHERE code = 'RBV-BOND';

SELECT pg_temp.refuses_at_commit('48a', 'a dispatch whose transit value differs from its source value by a cent',
  $q$SELECT pg_temp.dispatch_v('_VERIFY-T-V1', 10, 10000.00, 9999.99)$q$,
  'document_trf_value_kept', '%left the source at value 10000.00 but entered transit at 9999.99%');
SET CONSTRAINTS document_trf_value_kept DEFERRED;
SELECT pg_temp.accepts_at_commit('48b', 'a dispatch whose transit value equals its source value',
  $q$SELECT pg_temp.dispatch_v('_VERIFY-T-V2', 10, 12345.67, 12345.67)$q$,
  'document_trf_value_kept');
SET CONSTRAINTS document_trf_value_kept DEFERRED;

-- 3 sheets dispatched at 100.00: one sheet carries round(100 x 1 / 3, 2) = 33.33,
-- two carry round(100 x 2 / 3, 2) = 66.67, and the residual cents stay in transit.
DO $$
BEGIN
    PERFORM pg_temp.dispatch_v('_VERIFY-T-V3', 3, 100.00, 100.00);
    PERFORM pg_temp.dispatch_v('_VERIFY-T-V4', 3, 100.00, 100.00);
END $$;
SELECT pg_temp.refuses_at_commit('48c', 'a receipt took a cent too much out of transit',
  $q$SELECT pg_temp.receive_v_out_in('_VERIFY-R-V1', pg_temp.fx_id('_VERIFY-T-V3'), 1, 33.34, 33.33)$q$,
  'document_trr_value_share', '%takes 33.34 out of transit, but its share%is 33.33%');
SET CONSTRAINTS document_trr_value_share DEFERRED;
SELECT pg_temp.refuses_at_commit('48d', 'a receipt took a cent too little out of transit',
  $q$SELECT pg_temp.receive_v_out_in('_VERIFY-R-V2', pg_temp.fx_id('_VERIFY-T-V3'), 1, 33.32, 33.32)$q$,
  'document_trr_value_share', '%takes 33.32 out of transit, but its share%is 33.33%');
SET CONSTRAINTS document_trr_value_share DEFERRED;
SELECT pg_temp.refuses_at_commit('48e', 'a receipt entered the destination at a cent more than it left transit',
  $q$SELECT pg_temp.receive_v_out_in('_VERIFY-R-V3', pg_temp.fx_id('_VERIFY-T-V3'), 1, 33.33, 33.34)$q$,
  'document_trr_value_share', '%enters the destination at 33.34 but left transit at 33.33%');
SET CONSTRAINTS document_trr_value_share DEFERRED;
SELECT pg_temp.accepts_at_commit('48f', 'two of three sheets receive exactly 66.67 out of transit and into the destination',
  $q$SELECT pg_temp.receive_v_out_in('_VERIFY-R-V4', pg_temp.fx_id('_VERIFY-T-V3'), 2, 66.67, 66.67)$q$,
  'document_trr_value_share');
SET CONSTRAINTS document_trr_value_share DEFERRED;

-- A receipt of one transfer line split over several receipt lines (destination
-- bins). Each line takes its share of the cost by CUMULATIVE rounding:
--     share_i = round(v x cum_i / Q, 2) - round(v x cum_(i-1) / Q, 2)
-- so the shares always sum exactly; per-line rounding strands or over-takes cents.
CREATE FUNCTION pg_temp.receive_lines(p_serial TEXT, p_trf UUID, p_qtys NUMERIC[],
                                      p_out NUMERIC[], p_in NUMERIC[]) RETURNS UUID AS $$
DECLARE
    r UUID;
    i INT;
BEGIN
    r := pg_temp.make_trr(p_serial, p_trf, NULL, 'RBV', p_qtys[1]);
    FOR i IN 2..array_length(p_qtys, 1) LOOP
        INSERT INTO transfer_receipt_line (document_id, line_no, transfer_line_id, item_id, uom_id,
                                           quantity, qty_base_uom, storage_bin_id, entered_by)
        SELECT r, i, l.transfer_line_id, l.item_id, l.uom_id, p_qtys[i], p_qtys[i],
               CASE WHEN i = 2 THEN (SELECT id FROM storage_bin WHERE bin_code = '_VERIFY-BIN-RBV') END,
               pg_temp.receiver()
          FROM transfer_receipt_line l WHERE l.document_id = r AND l.line_no = 1;
    END LOOP;
    RETURN pg_temp.finish_receipt(r, p_serial, NULL, NULL, p_out, p_in);
END $$ LANGUAGE plpgsql;

-- Value still in transit for a transfer: what entered transit, less what its
-- receipt took out.
CREATE FUNCTION pg_temp.transit_value(p_trf UUID) RETURNS NUMERIC AS $$
    SELECT COALESCE(SUM(CASE WHEN t.source_document_id = p_trf AND t.movement_type = 'TRANSFER_IN' THEN m.value END), 0)
         - COALESCE(SUM(CASE WHEN t.movement_type = 'TRANSFER_OUT'
                              AND t.source_document_id IN (SELECT r.document_id FROM transfer_receipt r WHERE r.transfer_id = p_trf)
                             THEN m.value END), 0)
      FROM stock_movement m JOIN transaction_ticket t ON t.document_id = m.document_id;
$$ LANGUAGE sql STABLE;

SELECT pg_temp.refuses_at_commit('48g', 'a two-line receipt with each line rounded on its own (33.33 + 33.33) instead of cumulatively',
  $q$SELECT pg_temp.receive_lines('_VERIFY-R-V5', pg_temp.fx_id('_VERIFY-T-V4'), ARRAY[1,1], ARRAY[33.33,33.33], ARRAY[33.33,33.33])$q$,
  'document_trr_value_share', '%line 2 takes 33.33 out of transit, but its share%is 33.34%');
SET CONSTRAINTS document_trr_value_share DEFERRED;
SELECT pg_temp.accepts_at_commit('48h', 'a two-line bin-split receipt taking 33.33 then 33.34 (cumulative 66.67) is accepted',
  $q$SELECT pg_temp.receive_lines('_VERIFY-R-V6', pg_temp.fx_id('_VERIFY-T-V4'), ARRAY[1,1], ARRAY[33.33,33.34], ARRAY[33.33,33.34])$q$,
  'document_trr_value_share');
SET CONSTRAINTS document_trr_value_share DEFERRED;
DO $$
DECLARE pos RECORD;
BEGIN
    SELECT * INTO pos FROM transfer_line_position WHERE transfer_id = pg_temp.fx_id('_VERIFY-T-V4');
    IF pos.dispatched_base = 3 AND pos.received_base = 2 AND pos.in_transit_base = 1
       AND pg_temp.transit_value(pg_temp.fx_id('_VERIFY-T-V4')) = 33.33 THEN
        RAISE NOTICE 'ok   48i  the partial receipt leaves the missing sheet, and its 33.33 of cost, in transit';
    ELSE
        RAISE WARNING 'FAIL 48i  position reads dispatched %, received %, in transit %, transit value %',
            pos.dispatched_base, pos.received_base, pos.in_transit_base,
            pg_temp.transit_value(pg_temp.fx_id('_VERIFY-T-V4'));
    END IF;
END $$;

-- 10.00 over 3 sheets received as 1 + 1 + 1: per-line rounding gives 3.33 x 3 and
-- strands 0.01 at zero quantity forever; cumulative gives 3.33, 3.34, 3.33.
DO $$
BEGIN
    PERFORM pg_temp.dispatch_v('_VERIFY-T-V5', 3, 10.00, 10.00);
    PERFORM pg_temp.dispatch_v('_VERIFY-T-V6', 6, 1.00, 1.00);
END $$;
SELECT pg_temp.refuses_at_commit('48j', 'a 1+1+1 receipt of 10.00 rounded per line (3.33 x 3) was accepted',
  $q$SELECT pg_temp.receive_lines('_VERIFY-R-V7', pg_temp.fx_id('_VERIFY-T-V5'), ARRAY[1,1,1],
                                  ARRAY[3.33,3.33,3.33], ARRAY[3.33,3.33,3.33])$q$,
  'document_trr_value_share', '%line 2 takes 3.33 out of transit, but its share%is 3.34%');
SET CONSTRAINTS document_trr_value_share DEFERRED;
SELECT pg_temp.accepts_at_commit('48k', 'a 1+1+1 receipt of 10.00 taking 3.33, 3.34, 3.33 is accepted',
  $q$SELECT pg_temp.receive_lines('_VERIFY-R-V8', pg_temp.fx_id('_VERIFY-T-V5'), ARRAY[1,1,1],
                                  ARRAY[3.33,3.34,3.33], ARRAY[3.33,3.34,3.33])$q$,
  'document_trr_value_share');
SET CONSTRAINTS document_trr_value_share DEFERRED;
DO $$
DECLARE pos RECORD;
BEGIN
    SELECT * INTO pos FROM transfer_line_position WHERE transfer_id = pg_temp.fx_id('_VERIFY-T-V5');
    IF pos.in_transit_base = 0 AND pg_temp.transit_value(pg_temp.fx_id('_VERIFY-T-V5')) = 0 THEN
        RAISE NOTICE 'ok   48l  the full 3-way split takes exactly 10.00 and leaves no quantity and no value in transit';
    ELSE
        RAISE WARNING 'FAIL 48l  after the full split: in transit % sheets, value %',
            pos.in_transit_base, pg_temp.transit_value(pg_temp.fx_id('_VERIFY-T-V5'));
    END IF;
END $$;

-- 1.00 over 6 sheets as six lines: per-line rounding over-takes (0.17 x 6 = 1.02).
SELECT pg_temp.refuses_at_commit('48m', 'a six-line receipt of 1.00 rounded per line (0.17 x 6, summing 1.02) was accepted',
  $q$SELECT pg_temp.receive_lines('_VERIFY-R-V9', pg_temp.fx_id('_VERIFY-T-V6'), ARRAY[1,1,1,1,1,1],
                                  ARRAY[0.17,0.17,0.17,0.17,0.17,0.17], ARRAY[0.17,0.17,0.17,0.17,0.17,0.17])$q$,
  'document_trr_value_share', '%line 2 takes 0.17 out of transit, but its share%is 0.16%');
SET CONSTRAINTS document_trr_value_share DEFERRED;
SELECT pg_temp.accepts_at_commit('48n', 'a six-line receipt of 1.00 taking 0.17, 0.16, 0.17, 0.17, 0.16, 0.17 is accepted',
  $q$SELECT pg_temp.receive_lines('_VERIFY-R-V10', pg_temp.fx_id('_VERIFY-T-V6'), ARRAY[1,1,1,1,1,1],
                                  ARRAY[0.17,0.16,0.17,0.17,0.16,0.17], ARRAY[0.17,0.16,0.17,0.17,0.16,0.17])$q$,
  'document_trr_value_share');
SET CONSTRAINTS document_trr_value_share DEFERRED;
DO $$
BEGIN
    IF pg_temp.transit_value(pg_temp.fx_id('_VERIFY-T-V6')) = 0 THEN
        RAISE NOTICE 'ok   48o  the six shares sum to exactly 1.00: nothing over-taken, nothing stranded';
    ELSE
        RAISE WARNING 'FAIL 48o  value left in transit after the full 6-way split: %',
            pg_temp.transit_value(pg_temp.fx_id('_VERIFY-T-V6'));
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 49. Receipt lines carry their author, and the author is independent
-- ---------------------------------------------------------------------
DO $$
BEGIN
    PERFORM pg_temp.dispatch('_VERIFY-T-E1', 10);
    INSERT INTO fx VALUES ('trr_e1', pg_temp.make_trr('_VERIFY-R-E1', pg_temp.fx_id('_VERIFY-T-E1')));
END $$;
SELECT pg_temp.refuses('49a', 'the dispatcher rewrote a line of a draft receipt',
  $q$UPDATE transfer_receipt_line SET entered_by = pg_temp.holder('ASST_WH_MANAGER') WHERE document_id = pg_temp.fx_id('trr_e1')$q$,
  '23Z02', '%cannot be entered by the person who dispatched the transfer%');
SELECT pg_temp.refuses('49b', 'the person who raised the transfer entered a receipt line',
  $q$UPDATE transfer_receipt_line SET entered_by = pg_temp.verify_user() WHERE document_id = pg_temp.fx_id('trr_e1')$q$,
  '23Z02', '%cannot be entered by the person who raised the transfer%');
SELECT pg_temp.refuses('49c', 'a signer of the transfer entered a receipt line',
  $q$UPDATE transfer_receipt_line SET entered_by = pg_temp.holder('HEAD_INVENTORY') WHERE document_id = pg_temp.fx_id('trr_e1')$q$,
  '23Z02', '%cannot be entered by a person who signed the transfer%');
SELECT pg_temp.refuses('49d', 'the dispatcher added a line to a draft receipt',
  $q$INSERT INTO transfer_receipt_line (document_id, line_no, transfer_line_id, item_id, uom_id, quantity, qty_base_uom, entered_by)
     SELECT l.document_id, 2, l.transfer_line_id, l.item_id, l.uom_id, 1, 1, pg_temp.holder('ASST_WH_MANAGER')
       FROM transfer_receipt_line l WHERE l.document_id = pg_temp.fx_id('trr_e1') AND l.line_no = 1$q$,
  '23Z02', '%cannot be entered by the person who dispatched the transfer%');
SELECT pg_temp.accepts('49e', 'a line entered by an independent receiver is accepted',
  $q$UPDATE transfer_receipt_line SET note = 'counted at the gate', entered_by = pg_temp.receiver()
      WHERE document_id = pg_temp.fx_id('trr_e1')$q$);

-- Even past the line guard (switched off for this one edit), posting judges every line.
ALTER TABLE transfer_receipt_line DISABLE TRIGGER transfer_receipt_line_content;
UPDATE transfer_receipt_line SET entered_by = pg_temp.holder('ASST_WH_MANAGER')
 WHERE document_id = pg_temp.fx_id('trr_e1');
ALTER TABLE transfer_receipt_line ENABLE TRIGGER transfer_receipt_line_content;
SELECT pg_temp.refuses('49f', 'a receipt was posted carrying a line the dispatcher entered',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.receiver() WHERE id = pg_temp.fx_id('trr_e1')$q$,
  '23Z02', '%was entered by someone who raised, dispatched or signed transfer%');
UPDATE transfer_receipt_line SET entered_by = pg_temp.receiver() WHERE document_id = pg_temp.fx_id('trr_e1');
SELECT pg_temp.accepts('49g', 'with every line independently entered, the receipt posts and moves its stock',
  $q$SELECT pg_temp.finish_receipt(pg_temp.fx_id('trr_e1'), '_VERIFY-R-E1')$q$);

-- ---------------------------------------------------------------------
-- 50. A movement never carries a negative value; zero is allowed
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('50a', 'a movement with a negative value was accepted',
  $q$SELECT pg_temp.dispatch_v('_VERIFY-T-N1', 2, -1.00, -1.00)$q$,
  '23514', '%stock_movement_value_not_negative%');
SELECT pg_temp.accepts_at_commit('50b', 'zero-cost stock may move at zero value on both legs',
  $q$SELECT pg_temp.dispatch_v('_VERIFY-T-N2', 2, 0.00, 0.00)$q$,
  'document_trf_value_kept');
SET CONSTRAINTS document_trf_value_kept DEFERRED;

-- =====================================================================
-- Returns and damage (V14): four kinds of report, each end to end. As
-- before, every refusal must carry SQLSTATE 23Z02 (a workflow or document
-- control) or 23514 (a malformed report) with the reason named.
-- =====================================================================

INSERT INTO app_user (username, full_name, password_hash, is_active, must_change_password)
VALUES ('_verify_dmg', 'Verification Report Writer', 'x', TRUE, FALSE),
       -- Posts returns: the Finance holder signs the 2026 authorization's
       -- PREPARE step, and nobody who signed an authorization takes back
       -- what it let out.
       ('_verify_ret_post', 'Verification Return Poster', 'x', TRUE, FALSE),
       -- Draws up a delivery note and nothing else.
       ('_verify_loader', 'Verification Loader', 'x', TRUE, FALSE);
INSERT INTO location (branch_id, code, name, location_type, is_sellable)
SELECT id, '_VERIFY-NOSELL', 'Verification non-sellable warehouse', 'WAREHOUSE', FALSE FROM branch WHERE code = 'KGL';

CREATE FUNCTION pg_temp.dmg_creator() RETURNS UUID AS $$
    SELECT id FROM app_user WHERE username = '_verify_dmg';
$$ LANGUAGE sql;

CREATE FUNCTION pg_temp.return_poster() RETURNS UUID AS $$
    SELECT id FROM app_user WHERE username = '_verify_ret_post';
$$ LANGUAGE sql;

-- A draft report of one kind with one glass line. Branch is worked out from what
-- the report is about unless overridden (for the wrong-branch checks).
CREATE FUNCTION pg_temp.make_dmg(p_serial TEXT, p_kind TEXT, p_qty NUMERIC,
                                 p_from TEXT DEFAULT NULL, p_to TEXT DEFAULT NULL,
                                 p_transfer UUID DEFAULT NULL, p_dn UUID DEFAULT NULL,
                                 p_creator UUID DEFAULT NULL, p_branch TEXT DEFAULT NULL,
                                 p_customs TEXT DEFAULT NULL, p_bin TEXT DEFAULT NULL,
                                 p_to_bin TEXT DEFAULT NULL) RETURNS UUID AS $$
DECLARE
    d UUID;
    v_branch UUID;
BEGIN
    v_branch := COALESCE((SELECT id FROM branch WHERE code = p_branch),
                         CASE WHEN p_transfer IS NOT NULL THEN (SELECT branch_id FROM document WHERE id = p_transfer)
                              WHEN p_dn IS NOT NULL THEN (SELECT branch_id FROM document WHERE id = p_dn)
                              ELSE (SELECT branch_id FROM location WHERE code = p_from) END);
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, v_branch, p_serial, COALESCE(p_creator, pg_temp.dmg_creator())
      FROM document_type dt WHERE dt.code = 'DMG'
    RETURNING id INTO d;
    INSERT INTO damage_report (document_id, kind, reason_code, reason, from_location_id, to_location_id,
                               transfer_id, delivery_note_id, customs_reference)
    VALUES (d, p_kind,
            CASE p_kind WHEN 'TRANSIT_LOSS' THEN 'LOST_IN_TRANSIT' WHEN 'CUSTOMER_RETURN' THEN 'CUSTOMER_RETURN'
                        WHEN 'QUARANTINE_RELEASE' THEN 'INSPECTION_PASSED' ELSE 'DAMAGED' END,
            'Verification',
            (SELECT id FROM location WHERE code = p_from), (SELECT id FROM location WHERE code = p_to),
            p_transfer, p_dn, p_customs);
    INSERT INTO damage_report_line (document_id, line_no, item_id, uom_id, quantity, qty_base_uom,
                                    storage_bin_id, to_storage_bin_id, transfer_line_id, delivery_note_line_id, entered_by)
    SELECT d, 1, i.id, u.id, p_qty, p_qty,
           (SELECT id FROM storage_bin WHERE bin_code = p_bin),
           (SELECT id FROM storage_bin WHERE bin_code = p_to_bin),
           (SELECT tl.id FROM transfer_order_line tl WHERE tl.document_id = p_transfer AND tl.line_no = 1),
           (SELECT nl.id FROM delivery_note_line nl WHERE nl.document_id = p_dn AND nl.line_no = 1),
           COALESCE(p_creator, pg_temp.dmg_creator())
      FROM item i, uom u WHERE i.item_code = '_VERIFY-GLASS' AND u.code = 'SHEET';
    RETURN d;
END $$ LANGUAGE plpgsql;

-- A report taken to a state: DRAFT, PENDING, STEP1 or APPROVED.
CREATE FUNCTION pg_temp.dmg_at(p_dmg UUID, p_state TEXT) RETURNS UUID AS $$
BEGIN
    IF p_state = 'DRAFT' THEN RETURN p_dmg; END IF;
    UPDATE document SET status = 'PENDING' WHERE id = p_dmg;
    IF p_state = 'PENDING' THEN RETURN p_dmg; END IF;
    IF p_state = 'STEP1' THEN PERFORM pg_temp.sign_upto(p_dmg, 1); RETURN p_dmg; END IF;
    PERFORM pg_temp.sign_upto(p_dmg, pg_temp.steps_in(p_dmg));
    UPDATE document SET status = 'APPROVED' WHERE id = p_dmg;
    RETURN p_dmg;
END $$ LANGUAGE plpgsql;

-- One ticket of a report: a write-off or loss is DAMAGE out; a return is RETURN
-- in; a release is TRANSFER_OUT (leg 'OUT') and TRANSFER_IN (leg 'IN').
CREATE FUNCTION pg_temp.dmg_ticket(p_dmg UUID, p_poster UUID, p_serial TEXT, p_leg TEXT DEFAULT NULL) RETURNS UUID AS $$
DECLARE
    t UUID;
    h RECORD;
    dir TEXT;
    mtype TEXT;
BEGIN
    SELECT r.kind, r.from_location_id, r.to_location_id, r.customs_reference INTO h
      FROM damage_report r WHERE r.document_id = p_dmg;
    dir := CASE WHEN h.kind = 'CUSTOMER_RETURN' THEN 'IN'
                WHEN h.kind = 'QUARANTINE_RELEASE' THEN p_leg ELSE 'OUT' END;
    mtype := CASE WHEN h.kind = 'CUSTOMER_RETURN' THEN 'RETURN'
                  WHEN h.kind = 'QUARANTINE_RELEASE' THEN CASE dir WHEN 'OUT' THEN 'TRANSFER_OUT' ELSE 'TRANSFER_IN' END
                  ELSE 'DAMAGE' END;
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, d.branch_id, p_serial, p_poster
      FROM document_type dt, document d WHERE dt.code = 'TT' AND d.id = p_dmg
    RETURNING id INTO t;
    INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id, to_location_id,
                                    source_document_id, customs_reference)
    VALUES (t, mtype, dir, CASE WHEN dir = 'OUT' THEN h.from_location_id END,
            CASE WHEN dir = 'IN' THEN h.to_location_id END, p_dmg, h.customs_reference);
    INSERT INTO ticket_line (ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom, storage_bin_id)
    SELECT t, l.line_no, l.item_id, l.quantity, l.uom_id, l.qty_base_uom,
           CASE WHEN dir = 'OUT' THEN l.storage_bin_id ELSE l.to_storage_bin_id END
      FROM damage_report_line l WHERE l.document_id = p_dmg;
    RETURN t;
END $$ LANGUAGE plpgsql;

-- Post a report (by the Finance holder, who is in no step), write its ticket(s)
-- and movements, post the tickets. p_values are the movement values of the
-- leaving/returning leg, by line_no; p_in_values the arriving leg of a release.
CREATE FUNCTION pg_temp.post_dmg(p_dmg UUID, p_serial TEXT, p_values NUMERIC[] DEFAULT NULL,
                                 p_in_values NUMERIC[] DEFAULT NULL) RETURNS UUID AS $$
DECLARE
    kind TEXT;
    poster UUID;
    a UUID; b UUID;
BEGIN
    SELECT r.kind INTO kind FROM damage_report r WHERE r.document_id = p_dmg;
    poster := CASE WHEN kind = 'CUSTOMER_RETURN' THEN pg_temp.return_poster() ELSE pg_temp.holder('FINANCE') END;
    UPDATE document SET status = 'POSTED', posted_by = poster WHERE id = p_dmg;
    IF kind = 'QUARANTINE_RELEASE' THEN
        a := pg_temp.dmg_ticket(p_dmg, poster, p_serial || '-OUT', 'OUT');
        b := pg_temp.dmg_ticket(p_dmg, poster, p_serial || '-IN', 'IN');
        PERFORM pg_temp.move_leg(a, poster, kigali_today(), NULL, p_values);
        PERFORM pg_temp.move_leg(b, poster, kigali_today(), NULL, COALESCE(p_in_values, p_values));
        UPDATE document SET status = 'POSTED', posted_by = poster WHERE id IN (a, b);
    ELSE
        a := pg_temp.dmg_ticket(p_dmg, poster, p_serial || '-T');
        PERFORM pg_temp.move_leg(a, poster, kigali_today(), NULL, p_values);
        UPDATE document SET status = 'POSTED', posted_by = poster WHERE id = a;
    END IF;
    INSERT INTO fx VALUES (p_serial, p_dmg);
    RETURN p_dmg;
END $$ LANGUAGE plpgsql;

-- A delivery at a stated value: approved authorization, note posted by a signer of
-- it, ticket, the OUT movement at p_value, ticket posted. Returns the note.
CREATE FUNCTION pg_temp.move_out_v(p_ticket UUID, p_poster UUID, p_date DATE, p_value NUMERIC) RETURNS VOID AS $$
    INSERT INTO stock_movement (ticket_line_id, document_id, branch_id, item_id, location_id, storage_bin_id,
                                direction, quantity_base_uom, signed_quantity, unit_cost, value,
                                running_balance, business_date, posted_by)
    SELECT tl.id, d.id, d.branch_id, tl.item_id, t.from_location_id, tl.storage_bin_id,
           'OUT', tl.qty_base_uom, -tl.qty_base_uom, 1000, p_value, 0, p_date, p_poster
      FROM ticket_line tl
      JOIN transaction_ticket t ON t.document_id = tl.ticket_id
      JOIN document d ON d.id = t.document_id
     WHERE tl.ticket_id = p_ticket;
$$ LANGUAGE sql;

CREATE FUNCTION pg_temp.deliver_v(p_serial TEXT, p_qty NUMERIC, p_value NUMERIC,
                                  p_customs TEXT DEFAULT NULL, p_dn_creator UUID DEFAULT NULL) RETURNS UUID AS $$
DECLARE
    dao UUID; dn UUID; tt UUID; poster UUID;
BEGIN
    dao    := pg_temp.dao_at(p_serial, 'APPROVED', p_qty, p_customs);
    poster := pg_temp.gate_poster(dao);
    dn     := pg_temp.make_dn(p_serial || '-DN', dao, p_dn_creator);
    UPDATE document SET status = 'POSTED', posted_by = poster WHERE id = dn;
    tt     := pg_temp.make_dn_ticket(dn, poster, p_serial || '-TT');
    PERFORM pg_temp.move_out_v(tt, poster, kigali_today(), p_value);
    UPDATE document SET status = 'POSTED', posted_by = poster WHERE id = tt;
    INSERT INTO fx VALUES (p_serial || '-DN', dn), (p_serial, dao);
    RETURN dn;
END $$ LANGUAGE plpgsql;

-- Value still in transit for a transfer, counting losses as well as receipts.
CREATE FUNCTION pg_temp.transit_value2(p_trf UUID) RETURNS NUMERIC AS $$
    SELECT COALESCE(SUM(CASE WHEN t.source_document_id = p_trf AND t.movement_type = 'TRANSFER_IN' THEN m.value END), 0)
         - COALESCE(SUM(CASE WHEN t.movement_type = 'TRANSFER_OUT'
                              AND t.source_document_id IN (SELECT r.document_id FROM transfer_receipt r WHERE r.transfer_id = p_trf)
                             THEN m.value END), 0)
         - COALESCE(SUM(CASE WHEN t.movement_type = 'DAMAGE'
                              AND t.source_document_id IN (SELECT r.document_id FROM damage_report r WHERE r.transfer_id = p_trf)
                             THEN m.value END), 0)
      FROM stock_movement m JOIN transaction_ticket t ON t.document_id = m.document_id;
$$ LANGUAGE sql STABLE;

-- A write-off taken all the way to the ledger; used to prove the stock guard.
CREATE FUNCTION pg_temp.wo_to_ledger(p_serial TEXT, p_qty NUMERIC, p_loc TEXT) RETURNS UUID AS $$
    SELECT pg_temp.post_dmg(pg_temp.dmg_at(pg_temp.make_dmg(p_serial, 'WRITE_OFF', p_qty, p_loc), 'APPROVED'), p_serial || '-P');
$$ LANGUAGE sql;

-- ---------------------------------------------------------------------
-- 51. The damage rights sit on the roles whose steps they serve, and
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
            ('WH_MANAGER',    'damage.create,damage.view'),
            ('INTERNAL_CTRL', 'damage.verify,damage.view'),
            ('MANAGING_DIR',  'damage.approve,damage.view'),
            ('FINANCE',       'damage.post,damage.view')
           ) AS v(role_code, want)
      LEFT JOIN LATERAL (
            SELECT string_agg(p.code, ',' ORDER BY p.code) AS perms
              FROM role r JOIN role_permission rp ON rp.role_id = r.id
              JOIN permission p ON p.id = rp.permission_id AND p.module = 'damage'
             WHERE r.code = v.role_code) have ON TRUE
     WHERE have.perms IS DISTINCT FROM v.want;
    IF bad IS NULL THEN
        RAISE NOTICE 'ok   51a  damage rights sit on the roles whose steps they serve';
    ELSE
        RAISE WARNING 'FAIL 51a  %', bad;
    END IF;

    SELECT string_agg(r.code || ' signs ' || ws.action_label || ' without ' || need.code, '; ')
      INTO bad
      FROM workflow_step ws
      JOIN workflow_definition wd ON wd.id = ws.workflow_definition_id
      JOIN document_type dt ON dt.id = wd.document_type_id AND dt.code = 'DMG'
      JOIN role r ON r.id = ws.required_role_id
      CROSS JOIN LATERAL (SELECT CASE ws.action_label
                                   WHEN 'PREPARE' THEN 'damage.create'
                                   WHEN 'VERIFY'  THEN 'damage.verify'
                                   WHEN 'APPROVE' THEN 'damage.approve' END AS code) need
     WHERE NOT EXISTS (SELECT 1 FROM role_permission rp JOIN permission p ON p.id = rp.permission_id
                        WHERE rp.role_id = r.id AND p.code = need.code);
    IF bad IS NULL THEN
        RAISE NOTICE 'ok   51b  every damage chain signer carries the right its step needs';
    ELSE
        RAISE WARNING 'FAIL 51b  %', bad;
    END IF;

    SELECT string_agg(r.code, ',' ORDER BY r.code) INTO bad
      FROM role r JOIN role_permission rp ON rp.role_id = r.id JOIN permission p ON p.id = rp.permission_id
     WHERE p.code = 'damage.post';
    IF bad = 'FINANCE' THEN
        RAISE NOTICE 'ok   51c  only Finance, who signs no step, carries damage.post';
    ELSE
        RAISE WARNING 'FAIL 51c  damage.post is carried by %', bad;
    END IF;

    msg := access_conflict_anywhere();
    IF msg IS NULL THEN
        RAISE NOTICE 'ok   51d  nobody, and no role, is left in conflict';
    ELSE
        RAISE WARNING 'FAIL 51d  %', msg;
    END IF;
END $$;
SELECT pg_temp.refuses('51e', 'the Internal Controller was given damage.post',
  $q$INSERT INTO role_permission (role_id, permission_id)
     SELECT r.id, p.id FROM role r, permission p WHERE r.code = 'INTERNAL_CTRL' AND p.code = 'damage.post'$q$,
  '23Z01');
SELECT pg_temp.refuses('51f', 'Finance and the Internal Controller were held by one person',
  $q$INSERT INTO user_role (user_id, role_id, assigned_by)
     SELECT pg_temp.holder('FINANCE'), r.id, pg_temp.verify_user() FROM role r WHERE r.code = 'INTERNAL_CTRL'$q$,
  '23Z01');

-- ---------------------------------------------------------------------
-- 52. The report: one kind, its shape, its locations, frozen at submission
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('52a', 'a write-off from a transit location was accepted',
  $q$SELECT pg_temp.make_dmg('_VERIFY-W-TRAN', 'WRITE_OFF', 1, 'KGL-TRAN')$q$,
  '23514', '%warehouse, bonded or quarantine%');
SELECT pg_temp.refuses('52b', 'a write-off at Gahanga named a Rubavu location',
  $q$SELECT pg_temp.make_dmg('_VERIFY-W-WRONGBR', 'WRITE_OFF', 1, 'KGL-MAIN', NULL, NULL, NULL, NULL, 'RBV')$q$,
  '23514', '%not at the branch%');
SELECT pg_temp.refuses('52c', 'a write-off of bonded stock without a customs reference was accepted',
  $q$SELECT pg_temp.make_dmg('_VERIFY-W-BOND', 'WRITE_OFF', 1, 'RBV-BOND')$q$,
  '23514', '%customs reference%');
SELECT pg_temp.refuses('52d', 'a blank customs reference was accepted',
  $q$SELECT pg_temp.make_dmg('_VERIFY-W-BLANK', 'WRITE_OFF', 1, 'RBV-BOND', NULL, NULL, NULL, NULL, NULL, '  ')$q$,
  '23514', '%dmg_customs_reference_not_blank%');
SELECT pg_temp.accepts('52e', 'a bonded write-off with its customs reference is accepted as a draft',
  $q$SELECT pg_temp.make_dmg('_VERIFY-W-BONDOK', 'WRITE_OFF', 1, 'RBV-BOND', NULL, NULL, NULL, NULL, NULL, 'C-DMG-1')$q$);

DO $$
BEGIN
    INSERT INTO fx VALUES
        ('w_draft', pg_temp.make_dmg('_VERIFY-W-DRAFT', 'WRITE_OFF', 2, 'KGL-MAIN')),
        ('w_pend',  pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-W-PEND', 'WRITE_OFF', 2, 'KGL-MAIN'), 'PENDING')),
        ('w_step1', pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-W-STEP1', 'WRITE_OFF', 2, 'KGL-MAIN'), 'STEP1')),
        ('w_appr',  pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-W-APPR', 'WRITE_OFF', 2, 'KGL-MAIN'), 'APPROVED'));
END $$;
SELECT pg_temp.refuses('52f', 'a report''s kind was changed',
  $q$UPDATE damage_report SET kind = 'CUSTOMER_RETURN' WHERE document_id = pg_temp.fx_id('w_draft')$q$,
  '23Z02', '%are fixed when it is created%');
SELECT pg_temp.refuses('52g', 'a write-off named a transfer',
  $q$UPDATE damage_report SET transfer_id = (SELECT document_id FROM transfer_order LIMIT 1) WHERE document_id = pg_temp.fx_id('w_draft')$q$,
  '23Z02', '%are fixed when it is created%');
SELECT pg_temp.refuses('52h', 'a write-off line named a transfer line',
  $q$UPDATE damage_report_line SET transfer_line_id = (SELECT id FROM transfer_order_line LIMIT 1) WHERE document_id = pg_temp.fx_id('w_draft')$q$,
  '23514', '%names a transfer line%');
SELECT pg_temp.refuses('52i', 'a submitted report''s header was edited',
  $q$UPDATE damage_report SET reason = 'CHANGED' WHERE document_id = pg_temp.fx_id('w_pend')$q$,
  '23Z02', '%PENDING%cannot change%');
SELECT pg_temp.refuses('52j', 'a line was added after submission',
  $q$INSERT INTO damage_report_line (document_id, line_no, item_id, uom_id, quantity, qty_base_uom, entered_by)
     SELECT pg_temp.fx_id('w_pend'), 2, i.id, u.id, 1, 1, pg_temp.dmg_creator() FROM item i, uom u
      WHERE i.item_code = '_VERIFY-GLASS' AND u.code = 'SHEET'$q$,
  '23Z02', '%PENDING%cannot change%');
SELECT pg_temp.refuses('52k', 'a line was changed after approval',
  $q$UPDATE damage_report_line SET quantity = 50, qty_base_uom = 50 WHERE document_id = pg_temp.fx_id('w_appr')$q$,
  '23Z02', '%APPROVED%cannot change%');
SELECT pg_temp.refuses('52l', 'a line was deleted after submission',
  $q$DELETE FROM damage_report_line WHERE document_id = pg_temp.fx_id('w_pend')$q$,
  '23Z02', '%PENDING%cannot change%');
SELECT pg_temp.accepts('52m', 'a draft report is still editable',
  $q$UPDATE damage_report_line SET quantity = 3, qty_base_uom = 3 WHERE document_id = pg_temp.fx_id('w_draft')$q$);
SELECT pg_temp.refuses('52n', 'a base quantity that does not follow from the entered one was accepted',
  $q$UPDATE damage_report_line SET qty_base_uom = 999 WHERE document_id = pg_temp.fx_id('w_draft')$q$,
  '23514', '%base unit%');
DO $$
DECLARE d UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, b.id, '_VERIFY-W-EMPTY', pg_temp.dmg_creator() FROM document_type dt, branch b
     WHERE dt.code = 'DMG' AND b.code = 'KGL' RETURNING id INTO d;
    INSERT INTO damage_report (document_id, kind, reason_code, reason, from_location_id)
    SELECT d, 'WRITE_OFF', 'DAMAGED', 'x', id FROM location WHERE code = 'KGL-MAIN';
    INSERT INTO fx VALUES ('w_empty', d);
END $$;
SELECT pg_temp.refuses('52o', 'a report with no lines was submitted',
  $q$UPDATE document SET status = 'PENDING' WHERE id = pg_temp.fx_id('w_empty')$q$,
  '23Z02', '%no lines%');
SELECT pg_temp.refuses('52p', 'a release into a non-sellable location was accepted',
  $q$SELECT pg_temp.make_dmg('_VERIFY-Q-NOSELL', 'QUARANTINE_RELEASE', 1, 'KGL-QUAR', '_VERIFY-NOSELL')$q$,
  '23514', '%not sellable stock%');
SELECT pg_temp.refuses('52q', 'a release out of a warehouse instead of quarantine was accepted',
  $q$SELECT pg_temp.make_dmg('_VERIFY-Q-NOTQ', 'QUARANTINE_RELEASE', 1, 'KGL-MAIN', '_VERIFY-WH2')$q$,
  '23514', '%released FROM quarantine%');
SELECT pg_temp.refuses('52r', 'a release into another branch was accepted',
  $q$SELECT pg_temp.make_dmg('_VERIFY-Q-OTHERBR', 'QUARANTINE_RELEASE', 1, 'KGL-QUAR', 'RBV-BOND', NULL, NULL, NULL, NULL, 'C-1')$q$,
  '23514', '%not at the branch%');
SELECT pg_temp.refuses('52s', 'the third step signed before the first',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('w_pend'), 3, pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('w_pend'), 3)))$q$,
  '23Z02', '%must sign%before step%');
SELECT pg_temp.accepts('52t', 'the whole chain signs a report in order, each in its own role',
  $q$SELECT pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-W-CHAIN', 'WRITE_OFF', 1, 'KGL-MAIN'), 'APPROVED')$q$);

-- ---------------------------------------------------------------------
-- 53. Write-off, end to end
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('53a', 'a write-off still pending was posted',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('FINANCE') WHERE id = pg_temp.fx_id('w_pend')$q$,
  '23Z02', '%cannot move from PENDING to POSTED%');
SELECT pg_temp.refuses('53b', 'a write-off with only its first signature was posted',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('FINANCE') WHERE id = pg_temp.fx_id('w_step1')$q$,
  '23Z02', '%cannot move from PENDING to POSTED%');
SELECT pg_temp.refuses('53c', 'the person who raised the write-off posted it',
  $q$UPDATE document SET status = 'POSTED', posted_by = created_by WHERE id = pg_temp.fx_id('w_appr')$q$,
  '23Z02', '%person who raised it%');
SELECT pg_temp.refuses('53d', 'the Internal Controller, who verified the write-off, posted it',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('INTERNAL_CTRL') WHERE id = pg_temp.fx_id('w_appr')$q$,
  '23Z02', '%signed%cannot also post%');
SELECT pg_temp.refuses('53e', 'the Managing Director, who approved the write-off, posted it',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('MANAGING_DIR') WHERE id = pg_temp.fx_id('w_appr')$q$,
  '23Z02', '%signed%cannot also post%');
DO $$
DECLARE before_q NUMERIC := pg_temp.glass_at('KGL-MAIN');
BEGIN
    PERFORM pg_temp.post_dmg(pg_temp.fx_id('w_appr'), '_VERIFY-W-APPR-POSTED');
    IF pg_temp.glass_at('KGL-MAIN') = before_q - 2 THEN
        RAISE NOTICE 'ok   53f  the approved write-off, posted by Finance, takes 2 sheets out of the location';
    ELSE
        RAISE WARNING 'FAIL 53f  stock moved from % to %', before_q, pg_temp.glass_at('KGL-MAIN');
    END IF;
END $$;
SELECT pg_temp.refuses('53g', 'a posted write-off was cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('w_appr')$q$,
  '23Z02', '%reversing document%');
SELECT pg_temp.refuses('53h', 'a write-off took more than the location holds',
  $q$SELECT pg_temp.wo_to_ledger('_VERIFY-W-HUGE', 100000, 'KGL-MAIN')$q$,
  '23Z02', '%Not enough stock%');
SELECT pg_temp.refuses('53i', 'a posted write-off''s line was changed',
  $q$UPDATE damage_report_line SET quantity = 1, qty_base_uom = 1 WHERE document_id = pg_temp.fx_id('w_appr')$q$,
  '23Z02', '%POSTED%cannot change%');
SELECT pg_temp.accepts('53j', 'a draft report can be cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('w_draft')$q$);

-- ---------------------------------------------------------------------
-- 54. Transit loss: one consumption rule, one value rule, and the people
--     who sent or received the goods stay out
-- ---------------------------------------------------------------------
DO $$
BEGIN
    PERFORM pg_temp.dispatch('_VERIFY-T-L1', 20);
    PERFORM pg_temp.dispatch('_VERIFY-T-L2', 20);
    PERFORM pg_temp.receive('_VERIFY-R-L2', pg_temp.fx_id('_VERIFY-T-L2'), 15);
END $$;
SELECT pg_temp.refuses('54a', 'a transit loss was raised by the person who raised the transfer',
  $q$SELECT pg_temp.make_dmg('_VERIFY-L-X1', 'TRANSIT_LOSS', 5, NULL, NULL, pg_temp.fx_id('_VERIFY-T-L2'), NULL, pg_temp.verify_user())$q$,
  '23Z02', '%cannot be raised by the person who raised transfer%');
SELECT pg_temp.refuses('54b', 'a transit loss was raised by the dispatcher',
  $q$SELECT pg_temp.make_dmg('_VERIFY-L-X2', 'TRANSIT_LOSS', 5, NULL, NULL, pg_temp.fx_id('_VERIFY-T-L2'), NULL, pg_temp.holder('ASST_WH_MANAGER'))$q$,
  '23Z02', '%the person who dispatched transfer%');
SELECT pg_temp.refuses('54c', 'a transit loss was raised by a signer of the transfer',
  $q$SELECT pg_temp.make_dmg('_VERIFY-L-X3', 'TRANSIT_LOSS', 5, NULL, NULL, pg_temp.fx_id('_VERIFY-T-L2'), NULL, pg_temp.holder('HEAD_INVENTORY'))$q$,
  '23Z02', '%a person who signed transfer%');
SELECT pg_temp.refuses('54d', 'a transit loss was raised by the person who received the goods',
  $q$SELECT pg_temp.make_dmg('_VERIFY-L-X4', 'TRANSIT_LOSS', 5, NULL, NULL, pg_temp.fx_id('_VERIFY-T-L2'), NULL, pg_temp.receiver())$q$,
  '23Z02', '%the person who received transfer%');
SELECT pg_temp.refuses('54e', 'a transit loss was raised at the destination branch',
  $q$SELECT pg_temp.make_dmg('_VERIFY-L-X5', 'TRANSIT_LOSS', 5, NULL, NULL, pg_temp.fx_id('_VERIFY-T-L2'), NULL, NULL, 'RBV')$q$,
  '23Z02', '%wrong branch%');

DO $$
BEGIN
    INSERT INTO fx VALUES
        ('l_over',  pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-L-OVER', 'TRANSIT_LOSS', 6, NULL, NULL, pg_temp.fx_id('_VERIFY-T-L2')), 'APPROVED')),
        ('l_party', pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-L-PARTY', 'TRANSIT_LOSS', 5, NULL, NULL, pg_temp.fx_id('_VERIFY-T-L2')), 'APPROVED'));
END $$;
SELECT pg_temp.refuses('54f', 'the dispatcher posted the transit loss',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('ASST_WH_MANAGER') WHERE id = pg_temp.fx_id('l_party')$q$,
  '23Z02', '%cannot be posted by the person who dispatched transfer%');
SELECT pg_temp.refuses('54g', 'the person who received the goods posted the transit loss',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.receiver() WHERE id = pg_temp.fx_id('l_party')$q$,
  '23Z02', '%cannot be posted by the person who received transfer%');
SELECT pg_temp.refuses('54h', 'a loss of 6 was posted when only 5 remain in transit',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('FINANCE') WHERE id = pg_temp.fx_id('l_over')$q$,
  '23Z02', '%only 5.000 remains in transit%');

-- The remainder of a partly received transfer, written off: transit ends at zero.
DO $$
DECLARE
    tr_before NUMERIC := pg_temp.glass_at('KGL-TRAN');
BEGIN
    PERFORM pg_temp.post_dmg(pg_temp.fx_id('l_party'), '_VERIFY-L-PARTY-P');
    IF pg_temp.glass_at('KGL-TRAN') = tr_before - 5 THEN
        RAISE NOTICE 'ok   54i  the exact remainder (5 of 20, after 15 received) is written off out of transit';
    ELSE
        RAISE WARNING 'FAIL 54i  transit moved from % to %', tr_before, pg_temp.glass_at('KGL-TRAN');
    END IF;
END $$;
DO $$
DECLARE pos RECORD;
BEGIN
    SELECT * INTO pos FROM transfer_line_position WHERE transfer_id = pg_temp.fx_id('_VERIFY-T-L2');
    IF pos.dispatched_base = 20 AND pos.received_base = 15 AND pos.written_off_base = 5 AND pos.in_transit_base = 0
       AND pg_temp.transit_value2(pg_temp.fx_id('_VERIFY-T-L2')) = 0 THEN
        RAISE NOTICE 'ok   54j  a receipt followed by a loss of the remainder leaves transit at zero quantity and zero value';
    ELSE
        RAISE WARNING 'FAIL 54j  position reads dispatched %, received %, written off %, in transit %, value %',
            pos.dispatched_base, pos.received_base, pos.written_off_base, pos.in_transit_base,
            pg_temp.transit_value2(pg_temp.fx_id('_VERIFY-T-L2'));
    END IF;
END $$;

-- A total loss with no receipt at all, and a later receipt refused.
DO $$
BEGIN
    INSERT INTO fx VALUES ('l_total', pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-L-TOTAL', 'TRANSIT_LOSS', 20, NULL, NULL, pg_temp.fx_id('_VERIFY-T-L1')), 'APPROVED'));
    INSERT INTO fx VALUES ('trr_late', pg_temp.make_trr('_VERIFY-R-LATE', pg_temp.fx_id('_VERIFY-T-L1')));
END $$;
SELECT pg_temp.accepts('54k', 'a consignment that never arrived is written off in total, with no receipt at all',
  $q$SELECT pg_temp.post_dmg(pg_temp.fx_id('l_total'), '_VERIFY-L-TOTAL-P')$q$);
DO $$
DECLARE pos RECORD;
BEGIN
    SELECT * INTO pos FROM transfer_line_position WHERE transfer_id = pg_temp.fx_id('_VERIFY-T-L1');
    IF pos.received_base = 0 AND pos.written_off_base = 20 AND pos.in_transit_base = 0
       AND pg_temp.transit_value2(pg_temp.fx_id('_VERIFY-T-L1')) = 0 THEN
        RAISE NOTICE 'ok   54l  the total loss leaves nothing in transit, in quantity or in value';
    ELSE
        RAISE WARNING 'FAIL 54l  position reads received %, written off %, in transit %, value %',
            pos.received_base, pos.written_off_base, pos.in_transit_base, pg_temp.transit_value2(pg_temp.fx_id('_VERIFY-T-L1'));
    END IF;
END $$;
SELECT pg_temp.refuses('54m', 'a receipt was posted for a consignment already written off in total',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.receiver() WHERE id = pg_temp.fx_id('trr_late')$q$,
  '23Z02', '%already been written off as lost in transit, so only 0.000 remains%');

-- The unified value rule, loss after receipt and receipt after loss.
DO $$
BEGIN
    PERFORM pg_temp.dispatch('_VERIFY-T-L3', 20);
    PERFORM pg_temp.receive('_VERIFY-R-L3', pg_temp.fx_id('_VERIFY-T-L3'), 15);
    INSERT INTO fx VALUES ('l_off', pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-L-OFF', 'TRANSIT_LOSS', 5, NULL, NULL, pg_temp.fx_id('_VERIFY-T-L3')), 'APPROVED'));
END $$;
SELECT pg_temp.refuses_at_commit('54n', 'a loss written off at a cent more than its share of the consignment cost',
  $q$SELECT pg_temp.post_dmg(pg_temp.fx_id('l_off'), '_VERIFY-L-OFF-P', ARRAY[5000.01])$q$,
  'document_dmg_value_rule', '%writes off 5000.01 out of transit, but its share%is 5000.00%');
SET CONSTRAINTS document_dmg_value_rule DEFERRED;
SELECT pg_temp.refuses_at_commit('54o', 'a loss written off at a cent less than its share of the consignment cost',
  $q$SELECT pg_temp.post_dmg(pg_temp.fx_id('l_off'), '_VERIFY-L-OFF-P', ARRAY[4999.99])$q$,
  'document_dmg_value_rule', '%writes off 4999.99 out of transit, but its share%is 5000.00%');
SET CONSTRAINTS document_dmg_value_rule DEFERRED;
SELECT pg_temp.accepts_at_commit('54p', 'the same loss at exactly its share is accepted',
  $q$SELECT pg_temp.post_dmg(pg_temp.fx_id('l_off'), '_VERIFY-L-OFF-P', ARRAY[5000.00])$q$,
  'document_dmg_value_rule, document_dmg_posted_moved_stock');
SET CONSTRAINTS document_dmg_value_rule, document_dmg_posted_moved_stock DEFERRED;

-- A loss first, then the receipt: the receipt's share is what the loss left.
DO $$
BEGIN
    PERFORM pg_temp.dispatch('_VERIFY-T-L5', 20);
    PERFORM pg_temp.post_dmg(pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-L-FIRST', 'TRANSIT_LOSS', 5, NULL, NULL, pg_temp.fx_id('_VERIFY-T-L5')), 'APPROVED'), '_VERIFY-L-FIRST-P');
    INSERT INTO fx VALUES ('trr_full', pg_temp.make_trr('_VERIFY-R-L5FULL', pg_temp.fx_id('_VERIFY-T-L5')));
END $$;
SELECT pg_temp.refuses('54q', 'a receipt of 20 was posted when 5 of the 20 had already been written off',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.receiver() WHERE id = pg_temp.fx_id('trr_full')$q$,
  '23Z02', '%already been written off as lost in transit, so only 15.000 remains%');
UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
 WHERE id = pg_temp.fx_id('trr_full');
SELECT pg_temp.refuses_at_commit('54r', 'a receipt after a loss took a cent more out of transit than the remainder of the cost',
  $q$SELECT pg_temp.receive_v_out_in('_VERIFY-R-L5A', pg_temp.fx_id('_VERIFY-T-L5'), 15, 15000.01, 15000.01)$q$,
  'document_trr_value_share', '%takes 15000.01 out of transit, but its share%is 15000.00%');
SET CONSTRAINTS document_trr_value_share DEFERRED;
SELECT pg_temp.accepts_at_commit('54s', 'the receipt of the 15 that remain, at the cost that remains, is accepted',
  $q$SELECT pg_temp.receive_v_out_in('_VERIFY-R-L5B', pg_temp.fx_id('_VERIFY-T-L5'), 15, 15000.00, 15000.00)$q$,
  'document_trr_value_share');
SET CONSTRAINTS document_trr_value_share DEFERRED;
DO $$
BEGIN
    IF pg_temp.transit_value2(pg_temp.fx_id('_VERIFY-T-L5')) = 0
       AND (SELECT in_transit_base FROM transfer_line_position WHERE transfer_id = pg_temp.fx_id('_VERIFY-T-L5')) = 0 THEN
        RAISE NOTICE 'ok   54t  loss then receipt consume exactly the dispatched quantity and value';
    ELSE
        RAISE WARNING 'FAIL 54t  value left in transit %', pg_temp.transit_value2(pg_temp.fx_id('_VERIFY-T-L5'));
    END IF;
END $$;

-- Whoever recorded what arrived does not also write off what did not: the
-- author of a (non-cancelled) receipt, and every enterer of its lines, are
-- parties to the transfer, though a different person posted the receipt.
INSERT INTO app_user (username, full_name, password_hash, is_active, must_change_password)
VALUES ('_verify_rec1', 'Verification Receipt Author', 'x', TRUE, FALSE),
       ('_verify_rec2', 'Verification Line Enterer', 'x', TRUE, FALSE);
DO $$
DECLARE
    rec1 UUID := (SELECT id FROM app_user WHERE username = '_verify_rec1');
    rec2 UUID := (SELECT id FROM app_user WHERE username = '_verify_rec2');
    r UUID;
BEGIN
    PERFORM pg_temp.dispatch('_VERIFY-T-L6', 20);
    -- authored by rec1, one line entered by rec2, posted by the receiver
    r := pg_temp.make_trr('_VERIFY-R-L6', pg_temp.fx_id('_VERIFY-T-L6'), rec1, 'RBV', 15);
    UPDATE transfer_receipt_line SET entered_by = rec2 WHERE document_id = r;
    PERFORM pg_temp.finish_receipt(r, '_VERIFY-R-L6');
    -- a draft receipt authored by rec1
    PERFORM pg_temp.dispatch('_VERIFY-T-L7', 20);
    INSERT INTO fx VALUES ('trr_l7', pg_temp.make_trr('_VERIFY-R-L7', pg_temp.fx_id('_VERIFY-T-L7'), rec1));
END $$;
SELECT pg_temp.refuses('54u', 'a loss was raised by the author of the transfer''s receipt',
  $q$SELECT pg_temp.make_dmg('_VERIFY-L-REC1', 'TRANSIT_LOSS', 5, NULL, NULL, pg_temp.fx_id('_VERIFY-T-L6'), NULL,
                             (SELECT id FROM app_user WHERE username = '_verify_rec1'))$q$,
  '23Z02', '%cannot be raised by the person who recorded the arrival of transfer%');
SELECT pg_temp.refuses('54v', 'a loss was raised by someone who entered a line of the transfer''s receipt',
  $q$SELECT pg_temp.make_dmg('_VERIFY-L-REC2', 'TRANSIT_LOSS', 5, NULL, NULL, pg_temp.fx_id('_VERIFY-T-L6'), NULL,
                             (SELECT id FROM app_user WHERE username = '_verify_rec2'))$q$,
  '23Z02', '%cannot be raised by the person who recorded the arrival of transfer%');
DO $$
BEGIN
    INSERT INTO fx VALUES ('l_rec', pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-L-REC', 'TRANSIT_LOSS', 5, NULL, NULL, pg_temp.fx_id('_VERIFY-T-L6')), 'APPROVED'));
END $$;
SELECT pg_temp.refuses('54w', 'a loss was posted by someone who entered a line of the transfer''s receipt',
  $q$UPDATE document SET status = 'POSTED', posted_by = (SELECT id FROM app_user WHERE username = '_verify_rec2')
      WHERE id = pg_temp.fx_id('l_rec')$q$,
  '23Z02', '%cannot be posted by the person who recorded the arrival of transfer%');
SELECT pg_temp.refuses('54x', 'a loss was raised by the author of a transfer''s draft receipt',
  $q$SELECT pg_temp.make_dmg('_VERIFY-L-REC3', 'TRANSIT_LOSS', 5, NULL, NULL, pg_temp.fx_id('_VERIFY-T-L7'), NULL,
                             (SELECT id FROM app_user WHERE username = '_verify_rec1'))$q$,
  '23Z02', '%cannot be raised by the person who recorded the arrival of transfer%');
UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
 WHERE id = pg_temp.fx_id('trr_l7');
SELECT pg_temp.accepts('54y', 'once the draft receipt is cancelled its author is no longer a party',
  $q$SELECT pg_temp.make_dmg('_VERIFY-L-REC4', 'TRANSIT_LOSS', 5, NULL, NULL, pg_temp.fx_id('_VERIFY-T-L7'), NULL,
                             (SELECT id FROM app_user WHERE username = '_verify_rec1'))$q$);
SELECT pg_temp.accepts('54z', 'an independent person writes off the remainder of a transfer whose receipt others recorded',
  $q$SELECT pg_temp.post_dmg(pg_temp.fx_id('l_rec'), '_VERIFY-L-REC-P')$q$);

-- ---------------------------------------------------------------------
-- 55. Customer returns: into quarantine, never more than delivered less
--     returned, valued at the cost they left with
-- ---------------------------------------------------------------------
DO $$
BEGIN
    PERFORM pg_temp.deliver_v('_VERIFY-D-RT1', 3, 100.00);
END $$;
SELECT pg_temp.refuses('55a', 'a return was raised by the person who raised the authorization behind the delivery',
  $q$SELECT pg_temp.make_dmg('_VERIFY-C-X1', 'CUSTOMER_RETURN', 1, NULL, NULL, NULL, pg_temp.fx_id('_VERIFY-D-RT1-DN'), pg_temp.verify_user())$q$,
  '23Z02', '%the person who authorized delivery note%');
SELECT pg_temp.refuses('55b', 'a return was raised by the person who let the goods out',
  $q$SELECT pg_temp.make_dmg('_VERIFY-C-X2', 'CUSTOMER_RETURN', 1, NULL, NULL, NULL, pg_temp.fx_id('_VERIFY-D-RT1-DN'), pg_temp.gate_poster(pg_temp.fx_id('_VERIFY-D-RT1')))$q$,
  '23Z02', '%the person who let out delivery note%');
-- Everyone behind the delivery, not only the releaser: whoever drew up the
-- note, and whoever signed any step of its authorization.
DO $$
BEGIN
    PERFORM pg_temp.deliver_v('_VERIFY-D-RT2', 3, 100.00,
                              p_dn_creator => (SELECT id FROM app_user WHERE username = '_verify_loader'));
END $$;
SELECT pg_temp.refuses('55m', 'a return was raised by the person who drew up the delivery note',
  $q$SELECT pg_temp.make_dmg('_VERIFY-C-X4', 'CUSTOMER_RETURN', 1, NULL, NULL, NULL, pg_temp.fx_id('_VERIFY-D-RT2-DN'),
                             (SELECT id FROM app_user WHERE username = '_verify_loader'))$q$,
  '23Z02', '%cannot be raised by the person who drew up delivery note%');
SELECT pg_temp.refuses('55n', 'a return was raised by a verifier of the authorization behind the delivery',
  $q$SELECT pg_temp.make_dmg('_VERIFY-C-X5', 'CUSTOMER_RETURN', 1, NULL, NULL, NULL, pg_temp.fx_id('_VERIFY-D-RT2-DN'),
                             pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('_VERIFY-D-RT2'), 3)))$q$,
  '23Z02', '%cannot be raised by a person who signed the authorization behind delivery note%');
DO $$
BEGIN
    INSERT INTO fx VALUES
        ('c_rt2', pg_temp.make_dmg('_VERIFY-C-RT2', 'CUSTOMER_RETURN', 1, NULL, NULL, NULL, pg_temp.fx_id('_VERIFY-D-RT2-DN')));
END $$;
SELECT pg_temp.refuses('55o', 'a line of a return was entered by the person who prepared the authorization behind it',
  $q$UPDATE damage_report_line SET entered_by = pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('_VERIFY-D-RT2'), 1))
      WHERE document_id = pg_temp.fx_id('c_rt2')$q$,
  '23Z02', '%cannot be entered by a person who signed the authorization behind delivery note%');
DO $$ BEGIN PERFORM pg_temp.dmg_at(pg_temp.fx_id('c_rt2'), 'APPROVED'); END $$;
SELECT pg_temp.refuses('55p', 'a return was posted by a signer of the authorization behind it',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('_VERIFY-D-RT2'), 1))
      WHERE id = pg_temp.fx_id('c_rt2')$q$,
  '23Z02', '%cannot be posted by a person who signed the authorization behind delivery note%');
SELECT pg_temp.accepts_at_commit('55q', 'a return written and posted by people independent of the delivery',
  $q$SELECT pg_temp.post_dmg(pg_temp.fx_id('c_rt2'), '_VERIFY-C-RT2-P', ARRAY[33.33])$q$,
  'document_dmg_value_rule, document_dmg_posted_moved_stock');
SET CONSTRAINTS document_dmg_value_rule, document_dmg_posted_moved_stock DEFERRED;
SELECT pg_temp.refuses('55c', 'a return was raised at the wrong branch',
  $q$SELECT pg_temp.make_dmg('_VERIFY-C-X3', 'CUSTOMER_RETURN', 1, NULL, NULL, NULL, pg_temp.fx_id('_VERIFY-D-RT1-DN'), NULL, 'RBV')$q$,
  '23Z02', '%wrong branch%');
DO $$
BEGIN
    INSERT INTO fx VALUES
        ('c_over',  pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-C-OVER', 'CUSTOMER_RETURN', 4, NULL, NULL, NULL, pg_temp.fx_id('_VERIFY-D-RT1-DN')), 'APPROVED')),
        ('c_party', pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-C-PARTY', 'CUSTOMER_RETURN', 1, NULL, NULL, NULL, pg_temp.fx_id('_VERIFY-D-RT1-DN')), 'APPROVED')),
        ('c_one',   pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-C-ONE', 'CUSTOMER_RETURN', 1, NULL, NULL, NULL, pg_temp.fx_id('_VERIFY-D-RT1-DN')), 'APPROVED'));
END $$;
SELECT pg_temp.refuses('55d', 'the person who let the goods out posted their return',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.gate_poster(pg_temp.fx_id('_VERIFY-D-RT1')) WHERE id = pg_temp.fx_id('c_party')$q$,
  '23Z02', '%cannot be posted by the person who let out delivery note%');
SELECT pg_temp.refuses('55e', 'a return of 4 against 3 delivered was posted',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.return_poster() WHERE id = pg_temp.fx_id('c_over')$q$,
  '23Z02', '%at most 3.000 can be returned%');
SELECT pg_temp.refuses_at_commit('55f', 'a return valued a cent off the cost the goods left with',
  $q$SELECT pg_temp.post_dmg(pg_temp.fx_id('c_one'), '_VERIFY-C-ONE-P', ARRAY[33.34])$q$,
  'document_dmg_value_rule', '%brings back 33.34%share%is 33.33%');
SET CONSTRAINTS document_dmg_value_rule DEFERRED;
DO $$
DECLARE q_before NUMERIC := pg_temp.glass_at('KGL-QUAR');
BEGIN
    PERFORM pg_temp.post_dmg(pg_temp.fx_id('c_one'), '_VERIFY-C-ONE-P', ARRAY[33.33]);
    IF pg_temp.glass_at('KGL-QUAR') = q_before + 1 THEN
        RAISE NOTICE 'ok   55g  a first return of 1 of 3 comes into the delivering branch''s quarantine at 33.33';
    ELSE
        RAISE WARNING 'FAIL 55g  quarantine moved from % to %', q_before, pg_temp.glass_at('KGL-QUAR');
    END IF;
END $$;
DO $$
BEGIN
    INSERT INTO fx VALUES
        ('c_two',   pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-C-TWO', 'CUSTOMER_RETURN', 2, NULL, NULL, NULL, pg_temp.fx_id('_VERIFY-D-RT1-DN')), 'APPROVED')),
        ('c_three', pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-C-THREE', 'CUSTOMER_RETURN', 3, NULL, NULL, NULL, pg_temp.fx_id('_VERIFY-D-RT1-DN')), 'APPROVED'));
END $$;
SELECT pg_temp.refuses('55h', 'a return of 3 when 1 has already come back was posted',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.return_poster() WHERE id = pg_temp.fx_id('c_three')$q$,
  '23Z02', '%at most 2.000 can be returned%');
SELECT pg_temp.accepts_at_commit('55i', 'the second partial return of 2 is valued at the remaining 66.67, exactly',
  $q$SELECT pg_temp.post_dmg(pg_temp.fx_id('c_two'), '_VERIFY-C-TWO-P', ARRAY[66.67])$q$,
  'document_dmg_value_rule, document_dmg_posted_moved_stock');
SET CONSTRAINTS document_dmg_value_rule, document_dmg_posted_moved_stock DEFERRED;
DO $$
DECLARE total NUMERIC;
BEGIN
    SELECT SUM(m.value) INTO total FROM stock_movement m JOIN transaction_ticket t ON t.document_id = m.document_id
     WHERE t.movement_type = 'RETURN'
       AND t.source_document_id IN (SELECT r.document_id FROM damage_report r WHERE r.delivery_note_id = pg_temp.fx_id('_VERIFY-D-RT1-DN'));
    IF total = 100.00 THEN
        RAISE NOTICE 'ok   55j  two partial returns (33.33 + 66.67) bring back exactly the 100.00 that left';
    ELSE
        RAISE WARNING 'FAIL 55j  returns total %', total;
    END IF;
END $$;
DO $$
BEGIN
    INSERT INTO fx VALUES ('c_more', pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-C-MORE', 'CUSTOMER_RETURN', 1, NULL, NULL, NULL, pg_temp.fx_id('_VERIFY-D-RT1-DN')), 'APPROVED'));
END $$;
SELECT pg_temp.refuses('55k', 'a return was posted after the whole delivery had come back',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.return_poster() WHERE id = pg_temp.fx_id('c_more')$q$,
  '23Z02', '%at most 0.000 can be returned%');
SELECT pg_temp.refuses('55l', 'a posted return was cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('c_two')$q$,
  '23Z02', '%reversing document%');

-- ---------------------------------------------------------------------
-- 56. Quarantine release: out of quarantine, into sellable stock, at no
--     change of value
-- ---------------------------------------------------------------------
DO $$
BEGIN
    INSERT INTO fx VALUES
        ('q_ok',  pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-Q-OK', 'QUARANTINE_RELEASE', 2, 'KGL-QUAR', 'KGL-MAIN'), 'APPROVED')),
        ('q_big', pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-Q-BIG', 'QUARANTINE_RELEASE', 100000, 'KGL-QUAR', 'KGL-MAIN'), 'APPROVED'));
END $$;
SELECT pg_temp.refuses_at_commit('56a', 'a release entered the sellable location at a value different from what left quarantine',
  $q$SELECT pg_temp.post_dmg(pg_temp.fx_id('q_ok'), '_VERIFY-Q-OK-P', ARRAY[2000.00], ARRAY[2000.01])$q$,
  'document_dmg_value_rule', '%left quarantine at 2000.00 but enters the sellable location at 2000.01%');
SET CONSTRAINTS document_dmg_value_rule DEFERRED;
SELECT pg_temp.refuses('56b', 'a release took more out of quarantine than it holds',
  $q$SELECT pg_temp.post_dmg(pg_temp.fx_id('q_big'), '_VERIFY-Q-BIG-P')$q$,
  '23Z02', '%Not enough stock%');
DO $$
DECLARE
    q_before NUMERIC := pg_temp.glass_at('KGL-QUAR');
    m_before NUMERIC := pg_temp.glass_at('KGL-MAIN');
BEGIN
    PERFORM pg_temp.post_dmg(pg_temp.fx_id('q_ok'), '_VERIFY-Q-OK-P', ARRAY[2000.00], ARRAY[2000.00]);
    IF pg_temp.glass_at('KGL-QUAR') = q_before - 2 AND pg_temp.glass_at('KGL-MAIN') = m_before + 2 THEN
        RAISE NOTICE 'ok   56c  a release moves 2 sheets from quarantine into the sellable location at equal value';
    ELSE
        RAISE WARNING 'FAIL 56c  quarantine % -> %, main % -> %', q_before, pg_temp.glass_at('KGL-QUAR'),
            m_before, pg_temp.glass_at('KGL-MAIN');
    END IF;
END $$;
SELECT pg_temp.refuses('56d', 'a posted release was cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('q_ok')$q$,
  '23Z02', '%reversing document%');

-- Everything posted so far completed its legs and its value rules.
SELECT pg_temp.accepts_at_commit('56e', 'posted reports that moved their stock pass every commit check',
  $q$SELECT 1$q$,
  'document_dmg_posted_moved_stock, document_dmg_value_rule, document_trr_value_share, document_trf_value_kept, stock_movement_ticket_posted');
SET CONSTRAINTS document_dmg_posted_moved_stock, document_dmg_value_rule, document_trr_value_share,
                document_trf_value_kept, stock_movement_ticket_posted DEFERRED;

-- A posted report whose stock never moved.
DO $$
BEGIN
    INSERT INTO fx VALUES ('w_commit', pg_temp.dmg_at(pg_temp.make_dmg('_VERIFY-W-COMMIT', 'WRITE_OFF', 1, 'KGL-MAIN'), 'APPROVED'));
END $$;
SELECT pg_temp.refuses_at_commit('56f', 'a report was posted whose stock never reached the ledger',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('FINANCE') WHERE id = pg_temp.fx_id('w_commit')$q$,
  'document_dmg_posted_moved_stock', '%never completed its DAMAGE leg%');
SET CONSTRAINTS document_dmg_posted_moved_stock DEFERRED;

-- ---------------------------------------------------------------------
-- 57. Independence runs both ways, and customs follows the goods.
--     Whoever wrote off part of a transfer does not record its arrival;
--     a loss or a return carries the customs reference its goods moved
--     under.
-- ---------------------------------------------------------------------
INSERT INTO app_user (username, full_name, password_hash, is_active, must_change_password)
VALUES ('_verify_lost1', 'Verification Loss Author', 'x', TRUE, FALSE),
       ('_verify_lost2', 'Verification Loss Line Enterer', 'x', TRUE, FALSE);
CREATE FUNCTION pg_temp.lost(p_n INT) RETURNS UUID AS $$
    SELECT id FROM app_user WHERE username = '_verify_lost' || p_n;
$$ LANGUAGE sql STABLE;
-- This section brings its own stock, so the sections after it find what
-- they found before.
DO $$
DECLARE
    fin UUID := pg_temp.holder('FINANCE');
    g UUID; tt UUID;
BEGIN
    g := pg_temp.make_grn('_VERIFY-G-X57', NULL, 'KGL', 'KGL-MAIN');
    UPDATE goods_received_line SET quantity = 41, qty_base_uom = 41, storage_bin_id = NULL WHERE document_id = g;
    UPDATE document SET status = 'PENDING' WHERE id = g;
    PERFORM pg_temp.sign_upto(g, pg_temp.steps_in(g));
    UPDATE document SET status = 'APPROVED' WHERE id = g;
    UPDATE document SET status = 'POSTED', posted_by = fin WHERE id = g;
    tt := pg_temp.make_ticket(g, fin, '_VERIFY-G-X57-TT');
    PERFORM pg_temp.move_ticket(tt, fin, kigali_today());
    UPDATE document SET status = 'POSTED', posted_by = fin WHERE id = tt;
END $$;
DO $$
DECLARE l UUID;
BEGIN
    PERFORM pg_temp.dispatch('_VERIFY-T-X1', 20);
    -- a draft loss of 5, raised by lost1, its line entered by lost2
    l := pg_temp.make_dmg('_VERIFY-L-X1', 'TRANSIT_LOSS', 5, p_transfer => pg_temp.fx_id('_VERIFY-T-X1'),
                          p_creator => pg_temp.lost(1));
    UPDATE damage_report_line SET entered_by = pg_temp.lost(2) WHERE document_id = l;
END $$;
SELECT pg_temp.refuses('57a', 'a receipt was raised by the author of a loss of the same transfer',
  $q$SELECT pg_temp.make_trr('_VERIFY-R-X1A', pg_temp.fx_id('_VERIFY-T-X1'), pg_temp.lost(1))$q$,
  '23Z02', '%cannot be raised by the person who wrote off part of transfer%');
DO $$
BEGIN
    -- the independent receiver's draft receipt of the 15 still expected
    INSERT INTO fx VALUES ('trr_x1', pg_temp.make_trr('_VERIFY-R-X1', pg_temp.fx_id('_VERIFY-T-X1'), p_qty => 15));
END $$;
SELECT pg_temp.refuses('57b', 'a receipt line was entered by someone who entered a line of a loss of the same transfer',
  $q$UPDATE transfer_receipt_line SET entered_by = pg_temp.lost(2) WHERE document_id = pg_temp.fx_id('trr_x1')$q$,
  '23Z02', '%cannot be entered by the person who wrote off part of transfer%');
SELECT pg_temp.refuses('57c', 'a receipt was posted by someone who entered a line of a loss of the same transfer',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.lost(2) WHERE id = pg_temp.fx_id('trr_x1')$q$,
  '23Z02', '%cannot be posted by the person who wrote off part of transfer%');
DO $$
DECLARE l UUID;
BEGIN
    PERFORM pg_temp.dispatch('_VERIFY-T-X2', 20);
    l := pg_temp.make_dmg('_VERIFY-L-X2', 'TRANSIT_LOSS', 5, p_transfer => pg_temp.fx_id('_VERIFY-T-X2'),
                          p_creator => pg_temp.lost(1));
    UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
     WHERE id = l;
END $$;
SELECT pg_temp.accepts('57d', 'once the loss is cancelled its author may record the arrival',
  $q$SELECT pg_temp.make_trr('_VERIFY-R-X2', pg_temp.fx_id('_VERIFY-T-X2'), pg_temp.lost(1))$q$);
-- Judged on its value rule only: an earlier section leaves a deliberately
-- incomplete receipt pending on the moved-stock check, as 54s also avoids.
SELECT pg_temp.accepts_at_commit('57e', 'an independent receiver still records the arrival',
  $q$SELECT pg_temp.finish_receipt(pg_temp.fx_id('trr_x1'), '_VERIFY-R-X1')$q$,
  'document_trr_value_share');
SET CONSTRAINTS document_trr_value_share DEFERRED;

DO $$
DECLARE l UUID; ref TEXT;
BEGIN
    l := pg_temp.make_dmg('_VERIFY-L-X3', 'TRANSIT_LOSS', 1, p_transfer => pg_temp.fx_id('_VERIFY-T-X2'));
    SELECT customs_reference INTO ref FROM damage_report WHERE document_id = l;
    IF ref = 'C-TRF-1' THEN
        RAISE NOTICE 'ok   57f  a loss of a consignment that moved under customs reference C-TRF-1 carries it';
    ELSE
        RAISE WARNING 'FAIL 57f  the loss carries customs reference %', ref;
    END IF;
END $$;
SELECT pg_temp.refuses('57g', 'a loss of that consignment named a different customs reference',
  $q$SELECT pg_temp.make_dmg('_VERIFY-L-X4', 'TRANSIT_LOSS', 1, p_transfer => pg_temp.fx_id('_VERIFY-T-X2'),
                             p_customs => 'C-OTHER')$q$,
  '23514', '%moved under customs reference C-TRF-1%');
DO $$
DECLARE r UUID; ref TEXT;
BEGIN
    PERFORM pg_temp.deliver_v('_VERIFY-D-CUS', 1, 1000.00, 'C-DAO-1');
    r := pg_temp.make_dmg('_VERIFY-C-CUS', 'CUSTOMER_RETURN', 1, p_dn => pg_temp.fx_id('_VERIFY-D-CUS-DN'));
    SELECT customs_reference INTO ref FROM damage_report WHERE document_id = r;
    IF ref = 'C-DAO-1' THEN
        RAISE NOTICE 'ok   57h  a return of a delivery made under customs reference C-DAO-1 carries it';
    ELSE
        RAISE WARNING 'FAIL 57h  the return carries customs reference %', ref;
    END IF;
END $$;
SELECT pg_temp.refuses('57i', 'a return of that delivery named a different customs reference',
  $q$SELECT pg_temp.make_dmg('_VERIFY-C-CUS2', 'CUSTOMER_RETURN', 1, p_dn => pg_temp.fx_id('_VERIFY-D-CUS-DN'),
                             p_customs => 'C-OTHER')$q$,
  '23514', '%moved under customs reference C-DAO-1%');

-- =====================================================================
-- Stock counts (V15): the blind count, the freeze, the verification
-- count and the adjustment. Every refusal must carry SQLSTATE 23Z02 (a
-- workflow or document control) or 23514 (a malformed count) with the
-- reason named. Counts run at their own locations so that the freeze they
-- impose touches nothing the earlier checks lean on.
-- =====================================================================

INSERT INTO location (branch_id, code, name, location_type)
SELECT b.id, v.code, v.name, 'WAREHOUSE'
  FROM branch b,
       (VALUES ('_VERIFY-CNT',  'Verification count store'),
               ('_VERIFY-CNT2', 'Verification cycle-count store'),
               ('_VERIFY-CNT3', 'Verification no-variance store'),
               ('_VERIFY-CNT4', 'Verification value store'),
               ('_VERIFY-CNT5', 'Verification unrecounted store')) AS v(code, name)
 WHERE b.code = 'KGL';
INSERT INTO storage_bin (location_id, bin_code)
SELECT id, '_VERIFY-CBIN' FROM location WHERE code = '_VERIFY-CNT';
INSERT INTO app_user (username, full_name, password_hash, is_active, must_change_password)
VALUES ('_verify_fin2', 'Verification Second Finance Officer', 'x', TRUE, FALSE);
SELECT pg_temp.grant_role('_verify_fin2', 'FINANCE');

-- The Finance officer who posts counts: Finance signs the chain's APPROVE
-- step, so the poster is a second one.
CREATE FUNCTION pg_temp.fin2() RETURNS UUID AS $$
    SELECT id FROM app_user WHERE username = '_verify_fin2';
$$ LANGUAGE sql;

-- The role that signs a count's VERIFY step, and so takes its verification count.
CREATE FUNCTION pg_temp.verify_role(p_doc UUID) RETURNS TEXT AS $$
    SELECT r.code FROM workflow_step ws JOIN document d ON d.workflow_definition_id = ws.workflow_definition_id
      JOIN role r ON r.id = ws.required_role_id
     WHERE d.id = p_doc AND ws.action_label = 'VERIFY' ORDER BY ws.sequence_no LIMIT 1;
$$ LANGUAGE sql;

-- Stock into a location through a real receipt: p_qty of an item (glass by
-- default) at 1000 a unit, unbinned or into a bin.
CREATE FUNCTION pg_temp.stock_into(p_serial TEXT, p_loc TEXT, p_qty NUMERIC, p_bin TEXT DEFAULT NULL,
                                   p_item TEXT DEFAULT '_VERIFY-GLASS') RETURNS VOID AS $$
DECLARE
    fin UUID := pg_temp.holder('FINANCE');
    g   UUID;
    tt  UUID;
BEGIN
    g := pg_temp.make_grn(p_serial, NULL, 'KGL', p_loc);
    UPDATE goods_received_line SET item_id = (SELECT id FROM item WHERE item_code = p_item),
           quantity = p_qty, qty_base_uom = p_qty,
           storage_bin_id = (SELECT id FROM storage_bin WHERE bin_code = p_bin)
     WHERE document_id = g;
    UPDATE document SET status = 'PENDING' WHERE id = g;
    PERFORM pg_temp.sign_upto(g, pg_temp.steps_in(g));
    UPDATE document SET status = 'APPROVED' WHERE id = g;
    UPDATE document SET status = 'POSTED', posted_by = fin WHERE id = g;
    tt := pg_temp.make_ticket(g, fin, p_serial || '-TT');
    PERFORM pg_temp.move_ticket(tt, fin, kigali_today());
    UPDATE document SET status = 'POSTED', posted_by = fin WHERE id = tt;
END $$ LANGUAGE plpgsql;

-- A count opened by the Warehouse Manager holder (who signs PREPARE).
CREATE FUNCTION pg_temp.make_cnt(p_serial TEXT, p_loc TEXT, p_scope TEXT DEFAULT 'FULL',
                                 p_customs TEXT DEFAULT NULL, p_branch TEXT DEFAULT NULL) RETURNS UUID AS $$
DECLARE d UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, COALESCE((SELECT id FROM branch WHERE code = p_branch), l.branch_id), p_serial,
           pg_temp.holder('WH_MANAGER')
      FROM document_type dt, location l WHERE dt.code = 'CNT' AND l.code = p_loc
    RETURNING id INTO d;
    INSERT INTO stock_count (document_id, location_id, scope, customs_reference)
    SELECT d, l.id, p_scope, p_customs FROM location l WHERE l.code = p_loc;
    INSERT INTO fx VALUES (p_serial, d);
    RETURN d;
END $$ LANGUAGE plpgsql;

-- The first count of every line, by the Warehouse Manager holder: the book,
-- plus p_delta on line p_line.
CREATE FUNCTION pg_temp.count_all(p_cnt UUID, p_delta NUMERIC DEFAULT 0, p_line INT DEFAULT 1) RETURNS VOID AS $$
    UPDATE stock_count_line
       SET counted_qty = book_qty + CASE WHEN line_no = p_line THEN p_delta ELSE 0 END,
           counted_by = pg_temp.holder('WH_MANAGER')
     WHERE document_id = p_cnt;
$$ LANGUAGE sql;

-- The verification count of every line chosen for it, by the holder of the
-- VERIFY step's role, at the first count.
CREATE FUNCTION pg_temp.verify_all(p_cnt UUID) RETURNS VOID AS $$
    UPDATE stock_count_line
       SET verified_qty = counted_qty, verified_by = pg_temp.holder(pg_temp.verify_role(p_cnt))
     WHERE document_id = p_cnt AND verify_required;
$$ LANGUAGE sql;

-- A counted count taken to a state: PENDING (submitted, step 1 signed),
-- VERIFIED (the verification counted and signed) or APPROVED.
CREATE FUNCTION pg_temp.cnt_at(p_cnt UUID, p_state TEXT) RETURNS UUID AS $$
DECLARE n INT;
BEGIN
    UPDATE document SET status = 'PENDING' WHERE id = p_cnt;
    PERFORM pg_temp.sign_upto(p_cnt, 1);
    IF p_state = 'PENDING' THEN RETURN p_cnt; END IF;
    PERFORM pg_temp.verify_all(p_cnt);
    FOR n IN 2..pg_temp.steps_in(p_cnt) LOOP
        PERFORM pg_temp.sign(p_cnt, n, pg_temp.holder(pg_temp.step_role(p_cnt, n)));
        EXIT WHEN p_state = 'VERIFIED'
              AND (SELECT action_label FROM workflow_step WHERE id = pg_temp.step_id(p_cnt, n)) = 'VERIFY';
    END LOOP;
    IF p_state = 'VERIFIED' THEN RETURN p_cnt; END IF;
    UPDATE document SET status = 'APPROVED' WHERE id = p_cnt;
    RETURN p_cnt;
END $$ LANGUAGE plpgsql;

-- Post a count as the second Finance officer: ADJUSTMENT OUT for the
-- shortages, ADJUSTMENT IN for the surpluses (at the line's unit cost, or
-- at p_in_value), each ticket posted. p_skip_moves writes the tickets but
-- no movements.
CREATE FUNCTION pg_temp.post_cnt(p_cnt UUID, p_serial TEXT, p_in_value NUMERIC DEFAULT NULL,
                                 p_skip_moves BOOLEAN DEFAULT FALSE) RETURNS VOID AS $$
DECLARE
    poster UUID := pg_temp.fin2();
    h   RECORD;
    t   UUID;
    dir TEXT;
BEGIN
    SELECT c.location_id, c.customs_reference, d.branch_id INTO h
      FROM stock_count c JOIN document d ON d.id = c.document_id WHERE c.document_id = p_cnt;
    UPDATE document SET status = 'POSTED', posted_by = poster WHERE id = p_cnt;
    FOREACH dir IN ARRAY ARRAY['OUT', 'IN'] LOOP
        CONTINUE WHEN NOT EXISTS (SELECT 1 FROM stock_count_line
                                   WHERE document_id = p_cnt
                                     AND CASE dir WHEN 'IN' THEN variance_qty > 0 ELSE variance_qty < 0 END);
        INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
        SELECT dt.id, h.branch_id, p_serial || '-' || dir, poster FROM document_type dt WHERE dt.code = 'TT'
        RETURNING id INTO t;
        INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id, to_location_id,
                                        source_document_id, customs_reference)
        VALUES (t, 'ADJUSTMENT', dir, CASE WHEN dir = 'OUT' THEN h.location_id END,
                CASE WHEN dir = 'IN' THEN h.location_id END, p_cnt, h.customs_reference);
        INSERT INTO ticket_line (ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom, storage_bin_id)
        SELECT t, l.line_no, l.item_id, abs(l.variance_qty), i.base_uom_id, abs(l.variance_qty), l.storage_bin_id
          FROM stock_count_line l JOIN item i ON i.id = l.item_id
         WHERE l.document_id = p_cnt AND CASE dir WHEN 'IN' THEN l.variance_qty > 0 ELSE l.variance_qty < 0 END;
        IF NOT p_skip_moves THEN
            INSERT INTO stock_movement (ticket_line_id, document_id, branch_id, item_id, location_id, storage_bin_id,
                                        direction, quantity_base_uom, signed_quantity, unit_cost, value,
                                        running_balance, business_date, posted_by)
            SELECT tl.id, t, h.branch_id, tl.item_id, h.location_id, tl.storage_bin_id, dir, tl.qty_base_uom,
                   CASE dir WHEN 'IN' THEN tl.qty_base_uom ELSE -tl.qty_base_uom END, l.unit_cost,
                   CASE WHEN dir = 'IN' AND p_in_value IS NOT NULL THEN p_in_value
                        ELSE round(tl.qty_base_uom * l.unit_cost, 2) END,
                   0, kigali_today(), poster
              FROM ticket_line tl JOIN stock_count_line l ON l.document_id = p_cnt AND l.line_no = tl.line_no
             WHERE tl.ticket_id = t
             ORDER BY tl.line_no;
        END IF;
        UPDATE document SET status = 'POSTED', posted_by = poster WHERE id = t;
    END LOOP;
END $$ LANGUAGE plpgsql;

-- Stock of an item at a location (and bin, or every bin when p_all), from the ledger.
CREATE FUNCTION pg_temp.held(p_loc TEXT, p_bin TEXT DEFAULT NULL, p_item TEXT DEFAULT '_VERIFY-GLASS') RETURNS NUMERIC AS $$
    SELECT COALESCE(SUM(m.signed_quantity), 0)
      FROM stock_movement m JOIN location l ON l.id = m.location_id JOIN item i ON i.id = m.item_id
     WHERE l.code = p_loc AND i.item_code = p_item
       AND m.storage_bin_id IS NOT DISTINCT FROM (SELECT id FROM storage_bin WHERE bin_code = p_bin);
$$ LANGUAGE sql;

-- _VERIFY-CNT holds 10 sheets unbinned and 5 in bin _VERIFY-CBIN, at 1000
-- a sheet; _VERIFY-CNT2 holds 4 glass sheets; _VERIFY-CNT3 holds 3;
-- _VERIFY-CNT4 holds 6 in its only place; _VERIFY-CNT5 holds 3.
SELECT pg_temp.stock_into('_VERIFY-C-IN1', '_VERIFY-CNT', 10);
SELECT pg_temp.stock_into('_VERIFY-C-IN2', '_VERIFY-CNT', 5, '_VERIFY-CBIN');
SELECT pg_temp.stock_into('_VERIFY-C-IN3', '_VERIFY-CNT2', 4);
SELECT pg_temp.stock_into('_VERIFY-C-IN4', '_VERIFY-CNT3', 3);
SELECT pg_temp.stock_into('_VERIFY-C-IN5', '_VERIFY-CNT4', 6);
SELECT pg_temp.stock_into('_VERIFY-C-IN6', '_VERIFY-CNT5', 3);

-- ---------------------------------------------------------------------
-- 58. The count rights sit on the roles whose steps they serve; a count
--     is a stock-moving document now
-- ---------------------------------------------------------------------
DO $$
DECLARE
    bad TEXT;
    msg TEXT;
BEGIN
    SELECT string_agg(v.role_code || ' has {' || COALESCE(have.perms, '') || '} expected {' || v.want || '}', '; ')
      INTO bad
      FROM (VALUES
            ('WH_MANAGER',    'count.create,count.enter,count.view'),
            ('INTERNAL_CTRL', 'count.verify,count.view'),
            ('FINANCE',       'count.approve,count.post,count.view')
           ) AS v(role_code, want)
      LEFT JOIN LATERAL (
            SELECT string_agg(p.code, ',' ORDER BY p.code) AS perms
              FROM role r JOIN role_permission rp ON rp.role_id = r.id
              JOIN permission p ON p.id = rp.permission_id AND p.module = 'count'
             WHERE r.code = v.role_code) have ON TRUE
     WHERE have.perms IS DISTINCT FROM v.want;
    IF bad IS NULL THEN
        RAISE NOTICE 'ok   58a  count rights sit on the roles whose steps they serve';
    ELSE
        RAISE WARNING 'FAIL 58a  %', bad;
    END IF;

    SELECT string_agg(r.code || ' signs ' || ws.action_label || ' without ' || need.code, '; ')
      INTO bad
      FROM workflow_step ws
      JOIN workflow_definition wd ON wd.id = ws.workflow_definition_id
      JOIN document_type dt ON dt.id = wd.document_type_id AND dt.code = 'CNT'
      JOIN role r ON r.id = ws.required_role_id
      CROSS JOIN LATERAL (SELECT 'count.' || CASE ws.action_label WHEN 'PREPARE' THEN 'create'
                                                                  ELSE lower(ws.action_label) END AS code) need
     WHERE NOT EXISTS (SELECT 1 FROM role_permission rp JOIN permission p ON p.id = rp.permission_id
                        WHERE rp.role_id = r.id AND p.code = need.code);
    IF bad IS NULL THEN
        RAISE NOTICE 'ok   58b  every count chain signer carries the right its step needs';
    ELSE
        RAISE WARNING 'FAIL 58b  %', bad;
    END IF;

    -- Of the policy's roles. A runtime role may carry either right: the
    -- segregation rules judge it as the side of the one role that does.
    SELECT string_agg(r.code || ':' || p.code, ',' ORDER BY r.code) INTO bad
      FROM role r JOIN role_permission rp ON rp.role_id = r.id JOIN permission p ON p.id = rp.permission_id
     WHERE role_is_policy_defined(r.id)
       AND ((p.code = 'count.post' AND r.code <> 'FINANCE') OR (p.code = 'count.enter' AND r.code <> 'WH_MANAGER'));
    IF bad IS NULL THEN
        RAISE NOTICE 'ok   58c  of the policy''s roles only Finance posts a count and only the Warehouse Manager enters one';
    ELSE
        RAISE WARNING 'FAIL 58c  %', bad;
    END IF;

    IF (SELECT moves_stock FROM document_type WHERE code = 'CNT') THEN
        RAISE NOTICE 'ok   58d  a count is a stock-moving document: once posted it is never cancelled';
    ELSE
        RAISE WARNING 'FAIL 58d  CNT is not marked as moving stock';
    END IF;

    msg := access_conflict_anywhere();
    IF msg IS NULL THEN
        RAISE NOTICE 'ok   58e  nobody, and no role, is left in conflict';
    ELSE
        RAISE WARNING 'FAIL 58e  %', msg;
    END IF;
END $$;
SELECT pg_temp.refuses('58f', 'the Internal Controller was given count.post',
  $q$INSERT INTO role_permission (role_id, permission_id)
     SELECT r.id, p.id FROM role r, permission p WHERE r.code = 'INTERNAL_CTRL' AND p.code = 'count.post'$q$,
  '23Z01');
SELECT pg_temp.refuses('58g', 'the Internal Controller was given count.enter',
  $q$INSERT INTO role_permission (role_id, permission_id)
     SELECT r.id, p.id FROM role r, permission p WHERE r.code = 'INTERNAL_CTRL' AND p.code = 'count.enter'$q$,
  '23Z01');

-- ---------------------------------------------------------------------
-- 59. A count opens at one location where stock is kept, one at a time;
--     the database writes its sheet and its book
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('59a', 'a count of a transit location was accepted',
  $q$SELECT pg_temp.make_cnt('_VERIFY-K-TRAN', 'KGL-TRAN')$q$,
  '23514', '%counted where it is kept%');
SELECT pg_temp.refuses('59b', 'a count at Gahanga named a Rubavu location',
  $q$SELECT pg_temp.make_cnt('_VERIFY-K-WRONGBR', 'RBV-BOND', 'FULL', 'C-1', 'KGL')$q$,
  '23514', '%not at the branch%');
SELECT pg_temp.refuses('59c', 'a count of bonded stock without a customs reference was accepted',
  $q$SELECT pg_temp.make_cnt('_VERIFY-K-BOND', 'RBV-BOND')$q$,
  '23514', '%customs reference%');
SELECT pg_temp.refuses('59d', 'a blank customs reference was accepted',
  $q$SELECT pg_temp.make_cnt('_VERIFY-K-BLANK', 'RBV-BOND', 'FULL', '   ')$q$,
  '23514', '%cnt_customs_reference_not_blank%');

DO $$
DECLARE
    d   UUID := pg_temp.make_cnt('_VERIFY-K-FULL', '_VERIFY-CNT');
    got TEXT;
BEGIN
    SELECT string_agg(COALESCE(sb.bin_code, 'unbinned') || '=' || l.book_qty::text || '@' || l.unit_cost::text
                      || CASE WHEN l.on_sheet THEN '' ELSE '(found)' END, ', ' ORDER BY l.line_no)
      INTO got
      FROM stock_count_line l LEFT JOIN storage_bin sb ON sb.id = l.storage_bin_id
     WHERE l.document_id = d;
    IF got = 'unbinned=10.000@1000.0000, _VERIFY-CBIN=5.000@1000.0000' THEN
        RAISE NOTICE 'ok   59e  a full count''s sheet lists every place holding stock, with the ledger''s book and cost';
    ELSE
        RAISE WARNING 'FAIL 59e  the sheet reads %', got;
    END IF;
END $$;
SELECT pg_temp.refuses('59f', 'a second count of a location already being counted was accepted',
  $q$SELECT pg_temp.make_cnt('_VERIFY-K-TWICE', '_VERIFY-CNT', 'PARTIAL')$q$,
  '23Z02', '%already counting it%');
SELECT pg_temp.refuses('59g', 'a count''s location was changed',
  $q$UPDATE stock_count SET location_id = (SELECT id FROM location WHERE code = '_VERIFY-CNT2')
      WHERE document_id = pg_temp.fx_id('_VERIFY-K-FULL')$q$,
  '23Z02', '%fixed when it is opened%');
DO $$
DECLARE b NUMERIC;
BEGIN
    INSERT INTO stock_count_line (document_id, line_no, item_id, book_qty, book_value, unit_cost, booked_at)
    SELECT pg_temp.fx_id('_VERIFY-K-FULL'), 3, id, 999, 999, 1, now() - interval '1 day'
      FROM item WHERE item_code = '_VERIFY-OTHER';
    SELECT book_qty INTO b FROM stock_count_line WHERE document_id = pg_temp.fx_id('_VERIFY-K-FULL') AND line_no = 3;
    IF b = 0 THEN
        RAISE NOTICE 'ok   59h  a book quantity supplied with a line is replaced by the ledger''s (999 became 0)';
    ELSE
        RAISE WARNING 'FAIL 59h  the line kept a supplied book of %', b;
    END IF;
END $$;
SELECT pg_temp.refuses('59i', 'a line''s book was changed',
  $q$UPDATE stock_count_line SET book_qty = 7 WHERE document_id = pg_temp.fx_id('_VERIFY-K-FULL') AND line_no = 1$q$,
  '23Z02', '%fixed when it is added%');
SELECT pg_temp.refuses('59j', 'a line whose place holds stock was removed',
  $q$DELETE FROM stock_count_line WHERE document_id = pg_temp.fx_id('_VERIFY-K-FULL') AND line_no = 1$q$,
  '23Z02', '%cannot be removed%');
SELECT pg_temp.accepts('59k', 'a found line at a place holding nothing can be removed while counting',
  $q$DELETE FROM stock_count_line WHERE document_id = pg_temp.fx_id('_VERIFY-K-FULL') AND line_no = 3$q$);
SELECT pg_temp.refuses('59l', 'a line named a bin of another location',
  $q$INSERT INTO stock_count_line (document_id, line_no, item_id, storage_bin_id)
     SELECT pg_temp.fx_id('_VERIFY-K-FULL'), 4, i.id, sb.id FROM item i, storage_bin sb
      WHERE i.item_code = '_VERIFY-GLASS' AND sb.bin_code = '_VERIFY-BIN'$q$,
  '23514', '%is not in the location%');
SELECT pg_temp.refuses('59m', 'a line started out verified',
  $q$INSERT INTO stock_count_line (document_id, line_no, item_id, verified_qty, verified_by)
     SELECT pg_temp.fx_id('_VERIFY-K-FULL'), 5, i.id, 1, pg_temp.holder('INTERNAL_CTRL')
       FROM item i WHERE i.item_code = '_VERIFY-OTHER'$q$,
  '23Z02', '%starts with no verification%');
DO $$
DECLARE
    d UUID := pg_temp.fx_id('_VERIFY-K-FULL');
    n INT;
BEGIN
    n := count_add_places(d, (SELECT id FROM item WHERE item_code = '_VERIFY-OTHER'), FALSE);
    IF n = 0 AND NOT EXISTS (SELECT 1 FROM stock_count_line l JOIN item i ON i.id = l.item_id
                              WHERE l.document_id = d AND i.item_code = '_VERIFY-OTHER') THEN
        RAISE NOTICE 'ok   59n  an item a counter found brings onto the sheet only the places the book holds it at';
    ELSE
        RAISE WARNING 'FAIL 59n  % line(s) written for an item the location does not hold', n;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 60. The freeze: nothing counted moves until the verification is signed
-- ---------------------------------------------------------------------
SELECT pg_temp.refuses('60a', 'stock was received into a location under a full count',
  $q$SELECT pg_temp.stock_into('_VERIFY-C-FRZ1', '_VERIFY-CNT', 1)$q$,
  '23Z02', '%is being counted%');
DO $$
DECLARE d UUID := pg_temp.make_cnt('_VERIFY-K-PART', '_VERIFY-CNT2', 'PARTIAL');
BEGIN
    PERFORM count_add_places(d, (SELECT id FROM item WHERE item_code = '_VERIFY-GLASS'));
END $$;
SELECT pg_temp.refuses('60b', 'an item on a cycle count''s sheet was received into its location',
  $q$SELECT pg_temp.stock_into('_VERIFY-C-FRZ2', '_VERIFY-CNT2', 1)$q$,
  '23Z02', '%is being counted%');
SELECT pg_temp.accepts('60c', 'an item not on a cycle count''s sheet still moves at that location',
  $q$SELECT pg_temp.stock_into('_VERIFY-C-FRZ3', '_VERIFY-CNT2', 2, NULL, '_VERIFY-OTHER')$q$);
DO $$
BEGIN
    PERFORM pg_temp.count_all(pg_temp.fx_id('_VERIFY-K-FULL'), -2, 1);
    PERFORM pg_temp.cnt_at(pg_temp.fx_id('_VERIFY-K-FULL'), 'PENDING');
END $$;
SELECT pg_temp.refuses('60d', 'stock was received into a counted location after submission, before the verification',
  $q$SELECT pg_temp.stock_into('_VERIFY-C-FRZ4', '_VERIFY-CNT', 1)$q$,
  '23Z02', '%is being counted%');
SELECT pg_temp.accepts('60e', 'cancelling a cycle count lifts its freeze',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('_VERIFY-K-PART')$q$);
SELECT pg_temp.accepts('60f', 'the item moves again once the count is cancelled',
  $q$SELECT pg_temp.stock_into('_VERIFY-C-FRZ5', '_VERIFY-CNT2', 1)$q$);

-- ---------------------------------------------------------------------
-- 61. The first count closes at submission; the verification count is
--     independent, blind, and covers every variance
-- ---------------------------------------------------------------------
DO $$
DECLARE d UUID := pg_temp.make_cnt('_VERIFY-K-UNC', '_VERIFY-CNT3');
BEGIN
    NULL;
END $$;
SELECT pg_temp.refuses('61a', 'a count with an uncounted line was submitted',
  $q$UPDATE document SET status = 'PENDING' WHERE id = pg_temp.fx_id('_VERIFY-K-UNC')$q$,
  '23Z02', '%has not been counted%');
SELECT pg_temp.refuses('61b', 'a verification count was entered while still counting',
  $q$UPDATE stock_count_line SET verified_qty = 3, verified_by = pg_temp.holder('INTERNAL_CTRL')
      WHERE document_id = pg_temp.fx_id('_VERIFY-K-UNC')$q$,
  '23Z02', '%is DRAFT: the verification count%');
SELECT pg_temp.accepts('61c', 'the cancelled count''s location can be counted afresh',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('_VERIFY-K-UNC')$q$);

DO $$
DECLARE
    d    UUID := pg_temp.fx_id('_VERIFY-K-FULL');
    req  TEXT;
BEGIN
    SELECT string_agg(line_no::text || ':' || verify_required::text, ',' ORDER BY line_no) INTO req
      FROM stock_count_line WHERE document_id = d;
    -- line 1 differs (8 counted, 10 on the book), line 2 agrees and is the
    -- one agreeing line, so the sample takes it.
    IF req = '1:true,2:true' THEN
        RAISE NOTICE 'ok   61d  submission chose every line that differs from the book, and sampled one that agrees';
    ELSE
        RAISE WARNING 'FAIL 61d  lines chosen for verification: %', req;
    END IF;
END $$;
SELECT pg_temp.refuses('61e', 'a first count was changed after submission',
  $q$UPDATE stock_count_line SET counted_qty = 10 WHERE document_id = pg_temp.fx_id('_VERIFY-K-FULL') AND line_no = 1$q$,
  '23Z02', '%PENDING, so its first count cannot change%');
SELECT pg_temp.refuses('61f', 'someone not holding the verification step''s role took the verification count',
  $q$UPDATE stock_count_line SET verified_qty = 8, verified_by = pg_temp.holder('FINANCE')
      WHERE document_id = pg_temp.fx_id('_VERIFY-K-FULL') AND line_no = 1$q$,
  '23Z02', '%does not hold%');
SELECT pg_temp.refuses('61g', 'the verification step was signed with a chosen line not recounted',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('_VERIFY-K-FULL'), 2, pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('_VERIFY-K-FULL'), 2)))$q$,
  '23Z02', '%not been recounted%');

-- Whoever took part in the first count takes no verification count, of
-- any line. Line 1's counter here is given as the verification step's role
-- holder, which the application never does; line 2 is counted by someone
-- else, and that counter still may not recount it.
DO $$
DECLARE d UUID := pg_temp.make_cnt('_VERIFY-K-SELF', '_VERIFY-CNT3');
BEGIN
    UPDATE stock_count_line SET counted_qty = 3, counted_by = pg_temp.holder(pg_temp.verify_role(d))
     WHERE document_id = d;
    INSERT INTO stock_count_line (document_id, line_no, item_id, counted_qty, counted_by)
    SELECT d, 2, id, 1, pg_temp.verify_user() FROM item WHERE item_code = '_VERIFY-OTHER';
    PERFORM pg_temp.cnt_at(d, 'PENDING');
END $$;
SELECT pg_temp.refuses('61h', 'someone who counted one line of a count recounted another',
  $q$UPDATE stock_count_line SET verified_qty = 1, verified_by = pg_temp.holder(pg_temp.verify_role(document_id))
      WHERE document_id = pg_temp.fx_id('_VERIFY-K-SELF') AND line_no = 2$q$,
  '23Z02', '%took part in the first count%');
SELECT pg_temp.accepts('61i', 'a count awaiting its verification can still be cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('_VERIFY-K-SELF')$q$);

-- The verification count prevails: line 1 was first counted 8, the
-- Internal Controller finds 7, so 7 is what the count says and the
-- variance is 7 - 10 = -3.
DO $$
DECLARE
    d  UUID := pg_temp.fx_id('_VERIFY-K-FULL');
    r  RECORD;
BEGIN
    UPDATE stock_count_line SET verified_qty = CASE line_no WHEN 1 THEN 7 ELSE counted_qty END,
           verified_by = pg_temp.holder(pg_temp.verify_role(d))
     WHERE document_id = d AND verify_required;
    SELECT final_qty, variance_qty, verified_at IS NOT NULL AS stamped INTO r
      FROM stock_count_line WHERE document_id = d AND line_no = 1;
    IF r.final_qty = 7 AND r.variance_qty = -3 AND r.stamped THEN
        RAISE NOTICE 'ok   61j  the verification count prevails over the first count, stamped by the database';
    ELSE
        RAISE WARNING 'FAIL 61j  final %, variance %, stamped %', r.final_qty, r.variance_qty, r.stamped;
    END IF;
    IF NOT count_book_visible(d) THEN
        RAISE NOTICE 'ok   61k  the book stays unread while the verification count is open';
    ELSE
        RAISE WARNING 'FAIL 61k  the book is readable before the verification is signed';
    END IF;
    PERFORM pg_temp.sign(d, 2, pg_temp.holder(pg_temp.step_role(d, 2)));
    IF count_book_visible(d) THEN
        RAISE NOTICE 'ok   61l  the book is read once the verification is signed';
    ELSE
        RAISE WARNING 'FAIL 61l  the book is still hidden after the verification';
    END IF;
END $$;
SELECT pg_temp.refuses('61m', 'a verification count was changed after the verification was signed',
  $q$UPDATE stock_count_line SET verified_qty = 10 WHERE document_id = pg_temp.fx_id('_VERIFY-K-FULL') AND line_no = 1$q$,
  '23Z02', '%fixed once the verification is signed%');
SELECT pg_temp.accepts('61n', 'the freeze lifts once the verification is signed',
  $q$SELECT pg_temp.stock_into('_VERIFY-C-FRZ6', '_VERIFY-CNT', 1)$q$);

-- Approval refuses an unverified variance whatever the chain: the signature
-- guard is switched off for this test so the approval guard is reached.
DO $$
DECLARE d UUID := pg_temp.make_cnt('_VERIFY-K-NOVER', '_VERIFY-CNT5');
BEGIN
    PERFORM pg_temp.count_all(d, 1, 1);
    PERFORM pg_temp.cnt_at(d, 'PENDING');
END $$;
ALTER TABLE document_approval DISABLE TRIGGER document_approval_verification_counted;
DO $$
DECLARE d UUID := pg_temp.fx_id('_VERIFY-K-NOVER');
BEGIN
    PERFORM pg_temp.sign(d, 2, pg_temp.holder(pg_temp.step_role(d, 2)));
    PERFORM pg_temp.sign(d, 3, pg_temp.holder(pg_temp.step_role(d, 3)));
END $$;
ALTER TABLE document_approval ENABLE TRIGGER document_approval_verification_counted;
SELECT pg_temp.refuses('61o', 'a count was approved with a variance nobody recounted',
  $q$UPDATE document SET status = 'APPROVED' WHERE id = pg_temp.fx_id('_VERIFY-K-NOVER')$q$,
  '23Z02', '%never approved on one person''s count%');
SELECT pg_temp.refuses('61p', 'a count whose verification is signed was cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('_VERIFY-K-NOVER')$q$,
  '23Z02', '%verification count is signed%');
DO $$
BEGIN
    IF NOT count_book_visible(pg_temp.fx_id('_VERIFY-K-UNC')) THEN
        RAISE NOTICE 'ok   61q  a count cancelled while counting never shows its book';
    ELSE
        RAISE WARNING 'FAIL 61q  a count cancelled while counting shows its book';
    END IF;
END $$;

-- Whoever took part in the first count signs no step after the first,
-- approving or rejecting: no segregation rule pairs the Warehouse Manager
-- with Finance, so the second Finance officer counts line 1 here as a
-- holder of both would.
DO $$
DECLARE d UUID := pg_temp.make_cnt('_VERIFY-K-JUDGE', '_VERIFY-CNT2');
BEGIN
    PERFORM pg_temp.count_all(d, -1, 1);
    UPDATE stock_count_line SET counted_by = pg_temp.fin2() WHERE document_id = d AND line_no = 1;
    PERFORM pg_temp.cnt_at(d, 'VERIFIED');
END $$;
SELECT pg_temp.refuses('61r', 'a Finance officer who counted a line approved the count',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('_VERIFY-K-JUDGE'), 3, pg_temp.fin2())$q$,
  '23Z02', '%took part in the first count%');
SELECT pg_temp.refuses('61s', 'a Finance officer who counted a line rejected the count',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('_VERIFY-K-JUDGE'), 3, pg_temp.fin2(), 'REJECTED')$q$,
  '23Z02', '%took part in the first count%');
SELECT pg_temp.accepts('61t', 'a Finance officer who took no part in the count rejects it',
  $q$SELECT pg_temp.sign(pg_temp.fx_id('_VERIFY-K-JUDGE'), 3, pg_temp.holder('FINANCE'), 'REJECTED')$q$);
UPDATE document SET status = 'REJECTED' WHERE id = pg_temp.fx_id('_VERIFY-K-JUDGE');

-- ---------------------------------------------------------------------
-- 62. Posting: by a second Finance officer independent of everything on
--     the count; exactly the variance, each way at its own value
-- ---------------------------------------------------------------------

-- A hand-made adjustment ticket of a count: one direction, at a location,
-- with one line naming a count line and a quantity (none when p_qty is NULL).
CREATE FUNCTION pg_temp.cnt_ticket(p_cnt UUID, p_serial TEXT, p_dir TEXT, p_loc TEXT,
                                   p_line INT DEFAULT NULL, p_qty NUMERIC DEFAULT NULL) RETURNS UUID AS $$
DECLARE
    t   UUID;
    loc UUID := (SELECT id FROM location WHERE code = p_loc);
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, d.branch_id, p_serial, pg_temp.fin2()
      FROM document_type dt, document d WHERE dt.code = 'TT' AND d.id = p_cnt
    RETURNING id INTO t;
    INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id, to_location_id,
                                    source_document_id, customs_reference)
    SELECT t, 'ADJUSTMENT', p_dir, CASE WHEN p_dir = 'OUT' THEN loc END, CASE WHEN p_dir = 'IN' THEN loc END,
           p_cnt, c.customs_reference
      FROM stock_count c WHERE c.document_id = p_cnt;
    IF p_qty IS NOT NULL THEN
        INSERT INTO ticket_line (ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom, storage_bin_id)
        SELECT t, l.line_no, l.item_id, p_qty, i.base_uom_id, p_qty, l.storage_bin_id
          FROM stock_count_line l JOIN item i ON i.id = l.item_id
         WHERE l.document_id = p_cnt AND l.line_no = p_line;
    END IF;
    RETURN t;
END $$ LANGUAGE plpgsql;

DO $$
DECLARE d UUID := pg_temp.fx_id('_VERIFY-K-FULL');
BEGIN
    PERFORM pg_temp.sign(d, 3, pg_temp.holder(pg_temp.step_role(d, 3)));
    UPDATE document SET status = 'APPROVED' WHERE id = d;
END $$;
SELECT pg_temp.refuses('62a', 'the Finance officer who approved the count posted it',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('FINANCE') WHERE id = pg_temp.fx_id('_VERIFY-K-FULL')$q$,
  '23Z02', '%signed%cannot also post%');
SELECT pg_temp.refuses('62b', 'the person who raised (and counted) the count posted it',
  $q$UPDATE document SET status = 'POSTED', posted_by = created_by WHERE id = pg_temp.fx_id('_VERIFY-K-FULL')$q$,
  '23Z02', '%cannot be posted by the person who%');
SELECT pg_temp.refuses('62c', 'an adjustment ticket brought a shortage in instead of taking it out',
  $q$SELECT pg_temp.cnt_ticket(pg_temp.fx_id('_VERIFY-K-FULL'), '_VERIFY-K-WRONG-TT', 'IN', '_VERIFY-CNT', 1, 3)$q$,
  '23Z02', '%the wrong way%');
SELECT pg_temp.refuses('62d', 'an adjustment ticket named another location',
  $q$SELECT pg_temp.cnt_ticket(pg_temp.fx_id('_VERIFY-K-FULL'), '_VERIFY-K-LOC-TT', 'OUT', 'KGL-MAIN')$q$,
  '23Z02', '%must be an ADJUSTMENT of count%');
SELECT pg_temp.refuses('62e', 'an adjustment moved more than the variance',
  $q$SELECT pg_temp.cnt_ticket(pg_temp.fx_id('_VERIFY-K-FULL'), '_VERIFY-K-QTY-TT', 'OUT', '_VERIFY-CNT', 1, 4)$q$,
  '23Z02', '%differs from line 1 of count%');
SELECT pg_temp.refuses('62f', 'an agreeing line was adjusted',
  $q$SELECT pg_temp.cnt_ticket(pg_temp.fx_id('_VERIFY-K-FULL'), '_VERIFY-K-ZERO-TT', 'OUT', '_VERIFY-CNT', 2, 1)$q$,
  '23Z02', '%not adjusted at all%');
SELECT pg_temp.accepts_at_commit('62g', 'the approved count, posted by a second Finance officer, adjusts the ledger',
  $q$SELECT pg_temp.post_cnt(pg_temp.fx_id('_VERIFY-K-FULL'), '_VERIFY-K-FULL-P')$q$,
  'document_cnt_posted_moved_stock, document_cnt_value_rule');
SET CONSTRAINTS document_cnt_posted_moved_stock, document_cnt_value_rule DEFERRED;
DO $$
BEGIN
    -- 10 unbinned were on the book when counted; 7 were found; 1 more
    -- arrived after the verification (61n). The -3 applies to the counted
    -- book, so 8 remain. The bin agreed and is untouched.
    IF pg_temp.held('_VERIFY-CNT') = 8 AND pg_temp.held('_VERIFY-CNT', '_VERIFY-CBIN') = 5 THEN
        RAISE NOTICE 'ok   62h  the posting took 3 sheets out of the short place and left the agreeing bin alone';
    ELSE
        RAISE WARNING 'FAIL 62h  unbinned %, bin %', pg_temp.held('_VERIFY-CNT'), pg_temp.held('_VERIFY-CNT', '_VERIFY-CBIN');
    END IF;
END $$;
SELECT pg_temp.refuses('62i', 'a posted count was cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('_VERIFY-K-FULL')$q$,
  '23Z02', '%reversing document%');
SELECT pg_temp.refuses('62j', 'a posted count''s line was changed',
  $q$UPDATE stock_count_line SET note = 'changed' WHERE document_id = pg_temp.fx_id('_VERIFY-K-FULL') AND line_no = 1$q$,
  '23Z02', '%POSTED, so its first count cannot change%');

-- Whoever counted or verified a line does not post the count.
DO $$
DECLARE d UUID := pg_temp.make_cnt('_VERIFY-K-CTR', '_VERIFY-CNT3');
BEGIN
    UPDATE stock_count_line SET counted_qty = book_qty, counted_by = pg_temp.fin2() WHERE document_id = d;
    PERFORM pg_temp.cnt_at(d, 'APPROVED');
END $$;
SELECT pg_temp.refuses('62k', 'the person who counted a line posted the count',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.fin2() WHERE id = pg_temp.fx_id('_VERIFY-K-CTR')$q$,
  '23Z02', '%who counted line 1%');
SELECT pg_temp.refuses('62l', 'the person who verified a line posted the count',
  $q$UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder(pg_temp.verify_role(id)) WHERE id = pg_temp.fx_id('_VERIFY-K-CTR')$q$,
  '23Z02', '%who verified line 1%');
SELECT pg_temp.refuses('62m', 'an approved count was cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('_VERIFY-K-CTR')$q$,
  '23Z02', '%verification count is signed%');

-- A count that agrees with the book everywhere posts with no ticket and
-- moves nothing.
DO $$
DECLARE d UUID := pg_temp.make_cnt('_VERIFY-K-EXACT', '_VERIFY-CNT2');
BEGIN
    PERFORM pg_temp.count_all(d);
    PERFORM pg_temp.cnt_at(d, 'APPROVED');
END $$;
SELECT pg_temp.accepts_at_commit('62n', 'a count with no variance posts with no adjustment',
  $q$SELECT pg_temp.post_cnt(pg_temp.fx_id('_VERIFY-K-EXACT'), '_VERIFY-K-EXACT-P')$q$,
  'document_cnt_posted_moved_stock');
SET CONSTRAINTS document_cnt_posted_moved_stock DEFERRED;

-- _VERIFY-CNT4 holds 6 at 1000: a count finding 8 brings 2 in at 2000.00,
-- and at no other value.
DO $$
DECLARE d UUID := pg_temp.make_cnt('_VERIFY-K-SURP', '_VERIFY-CNT4');
BEGIN
    PERFORM pg_temp.count_all(d, 2, 1);
    PERFORM pg_temp.cnt_at(d, 'APPROVED');
END $$;
SELECT pg_temp.refuses('62o', 'stock moved against a count approved but not posted',
  $q$SELECT pg_temp.move_leg(pg_temp.cnt_ticket(pg_temp.fx_id('_VERIFY-K-SURP'), '_VERIFY-K-EARLY-TT', 'IN', '_VERIFY-CNT4', 1, 2),
                             pg_temp.fin2(), kigali_today(), 2000)$q$,
  '23Z02', '%must be posted before its stock moves%');
SELECT pg_temp.refuses_at_commit('62p', 'a surplus was brought in at a value chosen when posting (2500.00 instead of 2000.00)',
  $q$SELECT pg_temp.post_cnt(pg_temp.fx_id('_VERIFY-K-SURP'), '_VERIFY-K-SURP-BAD', 2500.00)$q$,
  'document_cnt_value_rule', '%worth 2000.00%');
SET CONSTRAINTS document_cnt_value_rule DEFERRED;
SELECT pg_temp.refuses_at_commit('62q', 'a count was posted without adjusting the ledger',
  $q$SELECT pg_temp.post_cnt(pg_temp.fx_id('_VERIFY-K-SURP'), '_VERIFY-K-SURP-NOMOVE', NULL, TRUE)$q$,
  'document_cnt_posted_moved_stock', '%never adjusted the ledger%');
SET CONSTRAINTS document_cnt_posted_moved_stock DEFERRED;
SELECT pg_temp.accepts_at_commit('62r', 'the surplus enters at its line''s unit cost, 2 x 1000.00',
  $q$SELECT pg_temp.post_cnt(pg_temp.fx_id('_VERIFY-K-SURP'), '_VERIFY-K-SURP-P')$q$,
  'document_cnt_value_rule');
SET CONSTRAINTS document_cnt_value_rule DEFERRED;
DO $$
BEGIN
    IF pg_temp.held('_VERIFY-CNT4') = 8 THEN
        RAISE NOTICE 'ok   62s  after the count the place holds what was found: 8';
    ELSE
        RAISE WARNING 'FAIL 62s  the place holds %', pg_temp.held('_VERIFY-CNT4');
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 46. The chain switch for transfers is a row, and a document finishes
--     under its chain. Skipped once the date passes.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    old_def UUID; new_def UUID; d UUID; bound UUID;
BEGIN
    IF kigali_today() >= DATE '2027-01-01' THEN
        RAISE NOTICE 'ok   46   (skipped: the 2027 chain is already in force)';
        RETURN;
    END IF;
    SELECT wd.id INTO old_def FROM workflow_definition wd JOIN document_type dt ON dt.id = wd.document_type_id
     WHERE dt.code = 'TRF' AND wd.basis = 'POLICY_2026';
    SELECT wd.id INTO new_def FROM workflow_definition wd JOIN document_type dt ON dt.id = wd.document_type_id
     WHERE dt.code = 'TRF' AND wd.basis = 'RESTRUCTURE_2027';

    UPDATE workflow_definition SET effective_to = kigali_today() WHERE id = old_def;
    UPDATE workflow_definition SET effective_from = kigali_today() WHERE id = new_def;

    d := pg_temp.trf_at('_VERIFY-T-2027', 'PENDING');
    SELECT workflow_definition_id INTO bound FROM document WHERE id = d;
    IF bound = new_def THEN
        RAISE NOTICE 'ok   46a  once the switch date arrives a new transfer binds to the 2027 chain';
    ELSE
        RAISE WARNING 'FAIL 46a  a new transfer bound to % instead of the 2027 chain', bound;
    END IF;

    BEGIN
        PERFORM pg_temp.sign_upto(d, pg_temp.steps_in(d));
        UPDATE document SET status = 'APPROVED' WHERE id = d;
        RAISE NOTICE 'ok   46b  the 2027 chain (WH Manager, Managing Director, Internal Controller) signs and approves';
    EXCEPTION WHEN OTHERS THEN
        RAISE WARNING 'FAIL 46b  the 2027 chain could not sign: %', SQLERRM;
    END;

    SELECT workflow_definition_id INTO bound FROM document WHERE id = pg_temp.fx_id('t_step1');
    IF bound = old_def THEN
        BEGIN
            PERFORM pg_temp.sign(pg_temp.fx_id('t_step1'), 2, pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('t_step1'), 2)));
            RAISE NOTICE 'ok   46c  an open transfer finishes under the 2026 chain it began with (Head of Inventory)';
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'FAIL 46c  an open transfer could not finish under its own chain: %', SQLERRM;
        END;
    ELSE
        RAISE WARNING 'FAIL 46c  an open transfer moved to another chain';
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 38. The chain switch for the delivery authorization is a row, and the
--     2027 chain signs with the new rights. Skipped once the date passes.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    old_def UUID; new_def UUID; d UUID; bound UUID;
BEGIN
    IF kigali_today() >= DATE '2027-01-01' THEN
        RAISE NOTICE 'ok   38   (skipped: the 2027 chain is already in force)';
        RETURN;
    END IF;
    SELECT wd.id INTO old_def FROM workflow_definition wd JOIN document_type dt ON dt.id = wd.document_type_id
     WHERE dt.code = 'DAO' AND wd.basis = 'POLICY_2026';
    SELECT wd.id INTO new_def FROM workflow_definition wd JOIN document_type dt ON dt.id = wd.document_type_id
     WHERE dt.code = 'DAO' AND wd.basis = 'RESTRUCTURE_2027';

    UPDATE workflow_definition SET effective_to = kigali_today() WHERE id = old_def;
    UPDATE workflow_definition SET effective_from = kigali_today() WHERE id = new_def;

    d := pg_temp.dao_at('_VERIFY-D-2027', 'PENDING');
    SELECT workflow_definition_id INTO bound FROM document WHERE id = d;
    IF bound = new_def THEN
        RAISE NOTICE 'ok   38a  once the switch date arrives a new authorization binds to the 2027 chain';
    ELSE
        RAISE WARNING 'FAIL 38a  a new authorization bound to % instead of the 2027 chain', bound;
    END IF;

    BEGIN
        PERFORM pg_temp.sign_upto(d, pg_temp.steps_in(d));
        UPDATE document SET status = 'APPROVED' WHERE id = d;
        RAISE NOTICE 'ok   38b  the 2027 chain (ITO, Director Supply Chain, Director Commercial, Internal Controller) signs and approves';
    EXCEPTION WHEN OTHERS THEN
        RAISE WARNING 'FAIL 38b  the 2027 chain could not sign: %', SQLERRM;
    END;

    SELECT workflow_definition_id INTO bound FROM document WHERE id = pg_temp.fx_id('d_step1');
    IF bound = old_def THEN
        BEGIN
            PERFORM pg_temp.sign(pg_temp.fx_id('d_step1'), 2, pg_temp.holder(pg_temp.step_role(pg_temp.fx_id('d_step1'), 2)));
            RAISE NOTICE 'ok   38c  an open authorization finishes under the 2026 chain it began with';
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'FAIL 38c  an open authorization could not finish under its own chain: %', SQLERRM;
        END;
    ELSE
        RAISE WARNING 'FAIL 38c  an open authorization moved to another chain';
    END IF;
END $$;

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
