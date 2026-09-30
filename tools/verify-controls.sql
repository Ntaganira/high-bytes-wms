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
  SELECT r.id, p.id FROM role r, permission p WHERE r.code = '_VERIFY_CLOSER' AND p.code = 'transfer.approve';
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

-- A count sheet moves no stock itself: raised, signed by its whole chain,
-- approved and posted, it can still be cancelled. (This was a delivery
-- authorization until V12 made a DAO permanently unpostable.)
DO $$
DECLARE d UUID;
BEGIN
    INSERT INTO document (document_type_id, branch_id, serial_no, created_by)
    SELECT dt.id, b.id, '_VERIFY-CNT-31', pg_temp.verify_user()
      FROM document_type dt, branch b WHERE dt.code = 'CNT' AND b.code = 'KGL'
    RETURNING id INTO d;
    UPDATE document SET status = 'PENDING' WHERE id = d;
    PERFORM pg_temp.sign_upto(d, pg_temp.steps_in(d));
    UPDATE document SET status = 'APPROVED' WHERE id = d;
    UPDATE document SET status = 'POSTED', posted_by = pg_temp.holder('SALES') WHERE id = d;
    INSERT INTO fx VALUES ('cnt_posted', d);
END $$;
SELECT pg_temp.accepts('31c', 'a posted document of a type that moves no stock (a count sheet) can still be cancelled',
  $q$UPDATE document SET status = 'CANCELLED', cancel_reason = 'Verification', cancelled_by = pg_temp.verify_user()
      WHERE id = pg_temp.fx_id('cnt_posted')$q$);

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
CREATE FUNCTION pg_temp.dao_at(p_serial TEXT, p_state TEXT, p_qty NUMERIC DEFAULT 40) RETURNS UUID AS $$
DECLARE d UUID;
BEGIN
    d := pg_temp.make_dao(p_serial, p_qty);
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
  '23Z02', '%answers only to a goods received note or a delivery note%');
SELECT pg_temp.refuses('39b', 'a ticket sourced from a count sheet was accepted',
  $q$INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id, source_document_id)
     SELECT pg_temp.fx_id('_VERIFY-TT-S2'), 'ADJUSTMENT', 'OUT', l.id, pg_temp.fx_id('cnt_posted')
       FROM location l WHERE l.code = 'KGL-MAIN'$q$,
  '23Z02', '%answers only to a goods received note or a delivery note%');
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
  '23Z02', '%answers to no goods received note or delivery note%');
SELECT pg_temp.refuses('39e', 'stock left against a ticket with no source',
  $q$SELECT pg_temp.move_out(pg_temp.fx_id('_VERIFY-TT-S4'), pg_temp.holder('FINANCE'), kigali_today())$q$,
  '23Z02', '%answers to no goods received note or delivery note%');

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
