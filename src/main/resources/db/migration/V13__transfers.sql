-- =====================================================================
-- V13 — Inter-branch transfers: dispatch at the source gate, receipt at
--       the destination, and what is short stays in transit
--
-- SRS: FR-TRF-01..08 (transfers, goods in transit), FR-WF-03..07,
-- FR-SEC-07, FR-IN-10 (the ledger). Form TRF-007.
--
-- A consignment that never arrives must be a balance sitting in transit,
-- not an absence nobody notices (V2). Two documents carry it:
--
--   TRF  the Transfer. It has the approval chain and is raised at, and
--        belongs to, the SOURCE branch. It goes POSTED at dispatch: the
--        source warehouse records the goods leaving, by someone who
--        neither raised the transfer nor signed it (V11's posting rule,
--        unchanged), and the stock moves out of the source location into
--        the source branch's TRANSIT location.
--   TRR  the Transfer Receipt (new document type, form TRF-007R). It has
--        no chain of its own and belongs to the DESTINATION branch, so
--        that branch's register and daily close see it. It answers to its
--        TRF through document_support_link, exactly as a Delivery Note
--        answers to its DAO. The receiver records, per line, what
--        actually arrived, never more than was dispatched; posting it
--        moves that quantity out of the source branch's TRANSIT location
--        and into the destination location.
--
--   Whatever is dispatched and not received is the balance left in
--   TRANSIT (view transfer_line_position). A later damage or loss
--   document clears it. Over-receipt is refused.
--
-- Decisions taken by the business, enforced below:
--   1. The 2026 chain stays: WH Manager PREPARE, Head of Inventory
--      APPROVE, Internal Controller VERIFY. The Head of Inventory exists
--      only under the 2026 policy, so that definition ends on 1 January
--      2027 and a second begins: WH Manager PREPARE, MANAGING DIRECTOR
--      APPROVE, Internal Controller VERIFY. Inventory Policy s.11 says a
--      transfer is "authorised by the Head of Inventory or Managing
--      Director". THE CLIENT IS TO CONFIRM THE 2027 APPROVER.
--   2. Short arrival: recorded per line, stays in transit, over-receipt
--      refused.
-- Decided here, mirroring the Delivery Note:
--   - Transfers are between branches only; both locations are WAREHOUSE
--     or BONDED and active; customs reference when a bonded side.
--   - One load: dispatch is exactly the approved quantity per line.
--   - The receiver is not the dispatcher; one live receipt per transfer.
--
-- Ticket legs. The ledger allows one direction per ticket and one
-- original movement per ticket line, so each leg is its own transaction
-- ticket (TT), and a transfer is four:
--     dispatch  TRANSFER_OUT  OUT  source location              source = TRF
--     dispatch  TRANSFER_IN   IN   source branch's TRANSIT      source = TRF
--     receipt   TRANSFER_OUT  OUT  source branch's TRANSIT      source = TRR
--     receipt   TRANSFER_IN   IN   destination location         source = TRR
-- Each ticket sits on the register of the branch whose location it moves:
-- the receipt's OUT leg is at the SOURCE branch although its TRR is at the
-- destination. The TRF is the ledger's authority for all four (its chain
-- is what must be complete).
--
-- Contents
--   1. Rights and the two chains
--   2. The transfer and its lines
--   3. The transfer receipt and its lines
--   4. Support link and in-transit position
--   5. Lifecycle: submit, dispatch, receipt, and commit-time checks
--   6. Tickets and ledger, widened for both documents
-- =====================================================================

SET LOCAL highbytes.migration = 'on';

-- ---------------------------------------------------------------------
-- 1. Rights, placed on the role whose step each one serves.
--
--   WH_MANAGER        PREPARE   transfer.create (held), plus
--                     transfer.dispatch (new) and transfer.receive (held):
--                     the warehouse records goods leaving and arriving
--   ASST_WH_MANAGER   (no step) transfer.view, transfer.dispatch,
--                     transfer.receive: it loads and unloads, and is the
--                     natural dispatcher and receiver because it signs
--                     nothing on a transfer
--   HEAD_INVENTORY    APPROVE   transfer.view, transfer.approve (2026)
--   MANAGING_DIR      APPROVE   transfer.approve (2027; it already views
--                     everything)
--   INTERNAL_CTRL     VERIFY    transfer.verify (new: V5 gave it every
--                     VERIFY right that existed then, and this is not one
--                     of them). The Internal Controller never carries
--                     transfer.dispatch or transfer.receive: it does not
--                     do what it tests.
-- ---------------------------------------------------------------------
INSERT INTO permission (code, module, action, description) VALUES
    ('transfer.dispatch', 'transfer', 'POST',
     'Record goods leaving the source branch into transit against an approved transfer'),
    ('transfer.verify',   'transfer', 'VERIFY',
     'Verify a transfer against the physical consignment');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM (VALUES
        ('WH_MANAGER',      'transfer.dispatch'),
        ('ASST_WH_MANAGER', 'transfer.view'),
        ('ASST_WH_MANAGER', 'transfer.dispatch'),
        ('ASST_WH_MANAGER', 'transfer.receive'),
        ('HEAD_INVENTORY',  'transfer.view'),
        ('HEAD_INVENTORY',  'transfer.approve'),
        ('MANAGING_DIR',    'transfer.approve'),
        ('INTERNAL_CTRL',   'transfer.verify')
       ) AS v(role_code, permission_code)
  JOIN role r       ON r.code = v.role_code
  JOIN permission p ON p.code = v.permission_code;

-- The 2026 chain ends where the Head of Inventory's role ends; the 2027
-- chain replaces that approver with the Managing Director.
UPDATE workflow_definition wd
   SET effective_to = DATE '2027-01-01',
       notes = COALESCE(wd.notes, '') || ' Ends 2027-01-01: the Head of Inventory exists only under the 2026 policy.'
  FROM document_type dt
 WHERE dt.id = wd.document_type_id AND dt.code = 'TRF' AND wd.version = 1;

WITH wd AS (
    INSERT INTO workflow_definition (document_type_id, version, effective_from, effective_to, basis, notes)
    SELECT id, 2, DATE '2027-01-01', NULL, 'RESTRUCTURE_2027',
           'Inventory Policy s.11: authorised by the Head of Inventory or Managing Director. The Head of Inventory role exists only under the 2026 policy, so from 2027 the Managing Director approves. THE CLIENT IS TO CONFIRM THE 2027 APPROVER.'
      FROM document_type WHERE code = 'TRF'
    RETURNING id
)
INSERT INTO workflow_step (workflow_definition_id, sequence_no, required_role_id, action_label, is_mandatory, blocks_release)
SELECT wd.id, v.seq, r.id, v.act, TRUE, TRUE
  FROM wd
  JOIN (VALUES
        (1, 'WH_MANAGER',    'PREPARE'),
        (2, 'MANAGING_DIR',  'APPROVE'),
        (3, 'INTERNAL_CTRL', 'VERIFY')
       ) AS v(seq, role_code, act) ON TRUE
  JOIN role r ON r.code = v.role_code;

-- The receipt: its own document type and serial sequences.
INSERT INTO document_type (code, name, form_reference, moves_stock, sort_order)
VALUES ('TRR', 'Transfer Receipt', 'TRF-007R', TRUE, 41);

INSERT INTO serial_sequence (document_type_id, branch_id, year, prefix)
SELECT dt.id, b.id, y.year, dt.code || '-' || b.code || '-' || y.year
  FROM document_type dt CROSS JOIN branch b CROSS JOIN (VALUES (2026), (2027)) AS y(year)
 WHERE dt.code = 'TRR';

-- ---------------------------------------------------------------------
-- 2. The transfer.
-- ---------------------------------------------------------------------
CREATE TABLE transfer_order (
    document_id          UUID PRIMARY KEY REFERENCES document(id),
    -- Where the goods leave from: a location at the transfer's own (source) branch.
    from_location_id     UUID NOT NULL REFERENCES location(id),
    -- Where they are going: a location at a different branch.
    to_location_id       UUID NOT NULL REFERENCES location(id),
    -- The source branch's TRANSIT location. Set by the database from the one
    -- active TRANSIT location at the source branch; omit it on insert.
    transit_location_id  UUID NOT NULL REFERENCES location(id),
    customs_reference    VARCHAR(80),
    note                 VARCHAR(400),
    CONSTRAINT transfer_locations_differ CHECK (from_location_id <> to_location_id),
    CONSTRAINT transfer_customs_reference_not_blank
        CHECK (customs_reference IS NULL OR btrim(customs_reference) <> '')
);

CREATE INDEX transfer_order_to_location ON transfer_order (to_location_id);

COMMENT ON TABLE transfer_order IS
  'Subtype of document (type TRF), at the SOURCE branch. Goes POSTED at dispatch. Its content is what the approvers signed, so it changes only while a draft.';

CREATE TABLE transfer_order_line (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id      UUID NOT NULL REFERENCES transfer_order(document_id),
    line_no          SMALLINT NOT NULL CHECK (line_no > 0),
    item_id          UUID NOT NULL REFERENCES item(id),
    uom_id           UUID NOT NULL REFERENCES uom(id),
    quantity         NUMERIC(16,3) NOT NULL CHECK (quantity > 0),
    qty_base_uom     NUMERIC(16,3) NOT NULL CHECK (qty_base_uom > 0),
    -- The bin at the source the stock leaves from; optional.
    storage_bin_id   UUID REFERENCES storage_bin(id),
    note             VARCHAR(240),
    UNIQUE (document_id, line_no)
);

CREATE INDEX transfer_order_line_item ON transfer_order_line (item_id);

CREATE OR REPLACE FUNCTION transfer_header_guard() RETURNS TRIGGER AS $$
DECLARE
    doc      RECORD;
    fl       RECORD;
    tl       RECORD;
    n_transit INT;
    transit  UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'TRF', 'details');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.document_id <> OLD.document_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A transfer''s details cannot be moved to another document.';
    END IF;
    PERFORM assert_document_is_draft_of(NEW.document_id, 'TRF', 'details');

    SELECT d.serial_no, d.branch_id, b.is_bonded AS branch_bonded INTO doc
      FROM document d JOIN branch b ON b.id = d.branch_id WHERE d.id = NEW.document_id;
    SELECT l.code, l.branch_id, l.location_type, l.is_active, l.is_bonded, b.is_bonded AS branch_bonded INTO fl
      FROM location l JOIN branch b ON b.id = l.branch_id WHERE l.id = NEW.from_location_id;
    SELECT l.code, l.branch_id, l.location_type, l.is_active, l.is_bonded, b.is_bonded AS branch_bonded INTO tl
      FROM location l JOIN branch b ON b.id = l.branch_id WHERE l.id = NEW.to_location_id;

    IF fl.branch_id <> doc.branch_id THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Location %s is not at the branch %s belongs to. A transfer is raised at, and leaves from, its source branch.',
                               fl.code, doc.serial_no);
    END IF;
    IF tl.branch_id = fl.branch_id THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('%s and %s are at the same branch. A transfer moves stock between branches; movement within a branch is not a transfer.',
                               fl.code, tl.code);
    END IF;
    IF NOT fl.is_active OR NOT tl.is_active THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Location %s is deactivated and cannot take part in a transfer.',
                               CASE WHEN NOT fl.is_active THEN fl.code ELSE tl.code END);
    END IF;
    IF fl.location_type NOT IN ('WAREHOUSE', 'BONDED') OR tl.location_type NOT IN ('WAREHOUSE', 'BONDED') THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('A transfer runs between warehouse or bonded locations; %s is %s.',
                               CASE WHEN fl.location_type NOT IN ('WAREHOUSE', 'BONDED') THEN fl.code ELSE tl.code END,
                               CASE WHEN fl.location_type NOT IN ('WAREHOUSE', 'BONDED') THEN fl.location_type ELSE tl.location_type END);
    END IF;
    IF (fl.is_bonded OR tl.is_bonded OR fl.branch_bonded OR tl.branch_bonded) AND NEW.customs_reference IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('%s touches bonded stock or a bonded branch and needs a customs reference. Duty-suspended goods are accountable to Customs on the move.',
                               doc.serial_no);
    END IF;

    -- Stock in flight is held in the source branch's TRANSIT location: there
    -- must be exactly one active, so nothing is ever put in the wrong one.
    SELECT COUNT(*), MIN(l.id::text)::uuid INTO n_transit, transit
      FROM location l
     WHERE l.branch_id = doc.branch_id AND l.location_type = 'TRANSIT' AND l.is_active;
    IF n_transit <> 1 THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('The source branch must have exactly one active transit location to hold goods in flight; it has %s.', n_transit);
    END IF;
    IF NEW.transit_location_id IS NOT NULL AND NEW.transit_location_id <> transit THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = 'Goods in flight are held in the source branch''s transit location; another cannot be chosen.';
    END IF;
    NEW.transit_location_id := transit;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- transit_location_id is NOT NULL and assigned by this trigger, which runs
-- before the NOT NULL is judged.
CREATE TRIGGER transfer_order_content
    BEFORE INSERT OR UPDATE OR DELETE ON transfer_order
    FOR EACH ROW EXECUTE FUNCTION transfer_header_guard();

CREATE OR REPLACE FUNCTION transfer_line_guard() RETURNS TRIGGER AS $$
DECLARE
    doc    RECORD;
    it     RECORD;
    bin    RECORD;
    factor NUMERIC;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'TRF', 'lines');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.document_id <> OLD.document_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A transfer line cannot be moved to another document.';
    END IF;
    PERFORM assert_document_is_draft_of(NEW.document_id, 'TRF', 'lines');

    SELECT d.serial_no, t.from_location_id INTO doc
      FROM document d JOIN transfer_order t ON t.document_id = d.id WHERE d.id = NEW.document_id;
    SELECT i.item_code, i.is_active INTO it FROM item i WHERE i.id = NEW.item_id;
    IF NOT it.is_active THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Item %s is deactivated and cannot be transferred.', it.item_code);
    END IF;
    factor := uom_factor_to_base(NEW.item_id, NEW.uom_id);
    IF factor IS NULL OR round(NEW.quantity * factor, 3) <> NEW.qty_base_uom THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Line %s of %s: %s x %s is %s in the base unit, not %s. The ledger carries the base quantity, so it must follow from what was entered.',
                               NEW.line_no, doc.serial_no, NEW.quantity, factor,
                               round(NEW.quantity * COALESCE(factor, 0), 3), NEW.qty_base_uom);
    END IF;
    IF NEW.storage_bin_id IS NOT NULL THEN
        SELECT sb.location_id, sb.bin_code, sb.is_active INTO bin FROM storage_bin sb WHERE sb.id = NEW.storage_bin_id;
        IF bin.location_id <> doc.from_location_id THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Bin %s is not in the location %s leaves from. Stock leaves from the source location the transfer names.',
                                   bin.bin_code, doc.serial_no);
        END IF;
        IF NOT bin.is_active THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Bin %s is deactivated.', bin.bin_code);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transfer_order_line_content
    BEFORE INSERT OR UPDATE OR DELETE ON transfer_order_line
    FOR EACH ROW EXECUTE FUNCTION transfer_line_guard();

-- ---------------------------------------------------------------------
-- 3. The transfer receipt, at the destination branch.
-- ---------------------------------------------------------------------
CREATE TABLE transfer_receipt (
    document_id   UUID PRIMARY KEY REFERENCES document(id),
    -- The transfer this receives. Fixed for life. The destination location
    -- is the transfer's own: what was approved is where it is received.
    transfer_id   UUID NOT NULL REFERENCES transfer_order(document_id),
    note          VARCHAR(400)
);

CREATE INDEX transfer_receipt_transfer ON transfer_receipt (transfer_id);

COMMENT ON TABLE transfer_receipt IS
  'Subtype of document (type TRR), at the DESTINATION branch. No chain: it answers to its transfer (document_support_link). Posting moves what actually arrived out of transit and into the destination location.';

CREATE TABLE transfer_receipt_line (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id       UUID NOT NULL REFERENCES transfer_receipt(document_id),
    line_no           SMALLINT NOT NULL CHECK (line_no > 0),
    transfer_line_id  UUID NOT NULL REFERENCES transfer_order_line(id),
    item_id           UUID NOT NULL REFERENCES item(id),
    uom_id            UUID NOT NULL REFERENCES uom(id),
    -- What actually arrived. A line that received nothing is simply absent:
    -- the whole of it stays in transit.
    quantity          NUMERIC(16,3) NOT NULL CHECK (quantity > 0),
    qty_base_uom      NUMERIC(16,3) NOT NULL CHECK (qty_base_uom > 0),
    -- The bin at the destination it is put into; optional.
    storage_bin_id    UUID REFERENCES storage_bin(id),
    note              VARCHAR(240),
    -- Who entered this line. The transfer's independence rule (not raised,
    -- dispatched or signed by the same person) is judged on every line as
    -- well as on the receipt's author and poster, so a draft cannot be
    -- raised by a bystander, rewritten by the dispatcher and posted by the
    -- bystander. entered_by, like created_by, is SUPPLIED BY THE APPLICATION
    -- (the service sets it to the current user on every write): direct SQL
    -- is outside what the application controls, which the production
    -- database-roles TODO covers.
    entered_by        UUID NOT NULL REFERENCES app_user(id),
    UNIQUE (document_id, line_no)
);

CREATE INDEX transfer_receipt_line_tline ON transfer_receipt_line (transfer_line_id);

-- Who has a part in a transfer that may not also receive it: the person who
-- raised it, the person who dispatched it (posted it), or anyone who signed
-- a step of its chain. Returns 'raised', 'dispatched' or 'signed', else NULL.
-- Applied to a receipt's author when it is raised and to its poster when it
-- is posted: a colleague cannot do the typing while the other clicks post.
CREATE OR REPLACE FUNCTION transfer_party_role(p_person UUID, p_transfer UUID) RETURNS TEXT AS $$
    SELECT CASE
             WHEN d.created_by = p_person THEN 'raised'
             WHEN d.posted_by  = p_person THEN 'dispatched'
             WHEN EXISTS (SELECT 1 FROM document_approval da
                           WHERE da.document_id = d.id AND da.actor_user_id = p_person) THEN 'signed'
           END
      FROM document d WHERE d.id = p_transfer;
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION receipt_header_guard() RETURNS TRIGGER AS $$
DECLARE
    doc RECORD;
    trf RECORD;
    party TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'TRR', 'details');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.document_id <> OLD.document_id OR NEW.transfer_id <> OLD.transfer_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A receipt is bound to one transfer for life; it cannot be moved to another.';
    END IF;
    PERFORM assert_document_is_draft_of(NEW.document_id, 'TRR', 'details');

    SELECT d.serial_no, d.branch_id, d.created_by INTO doc FROM document d WHERE d.id = NEW.document_id;
    SELECT d.serial_no, d.status, l.branch_id AS dest_branch INTO trf
      FROM transfer_order t
      JOIN document d ON d.id = t.document_id
      JOIN location l ON l.id = t.to_location_id
     WHERE t.document_id = NEW.transfer_id;

    IF doc.branch_id <> trf.dest_branch THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is raised at the wrong branch. Goods are received at the destination branch of transfer %s, which records them on its own register.',
                               doc.serial_no, trf.serial_no);
    END IF;
    IF trf.status <> 'POSTED' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Transfer %s is %s, not dispatched. Nothing can be received before it has left the source branch.',
                               trf.serial_no, trf.status);
    END IF;

    -- The author of a receipt is independent of the transfer.
    IF TG_OP = 'INSERT' THEN
        party := transfer_party_role(doc.created_by, NEW.transfer_id);
        IF party IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s cannot be raised by %s transfer %s. Whoever raised, dispatched or signed a transfer does not also record that it arrived.',
                                   doc.serial_no,
                                   CASE party WHEN 'raised' THEN 'the person who raised'
                                              WHEN 'dispatched' THEN 'the person who dispatched'
                                              ELSE 'a person who signed' END,
                                   trf.serial_no);
        END IF;
    END IF;

    -- One receipt per transfer: one live receipt. Serialised, so two raised
    -- at once cannot each miss the other.
    IF TG_OP = 'INSERT' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended('trr:' || NEW.transfer_id::text, 0));
        IF EXISTS (SELECT 1 FROM transfer_receipt r JOIN document d ON d.id = r.document_id
                    WHERE r.transfer_id = NEW.transfer_id
                      AND r.document_id <> NEW.document_id AND d.status <> 'CANCELLED') THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Transfer %s already has a live receipt. A transfer is received once; cancel that receipt first if it is being redone.',
                                   trf.serial_no);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transfer_receipt_content
    BEFORE INSERT OR UPDATE OR DELETE ON transfer_receipt
    FOR EACH ROW EXECUTE FUNCTION receipt_header_guard();

CREATE OR REPLACE FUNCTION receipt_line_guard() RETURNS TRIGGER AS $$
DECLARE
    doc    RECORD;
    tl     RECORD;
    bin    RECORD;
    factor NUMERIC;
    party  TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'TRR', 'lines');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.document_id <> OLD.document_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A receipt line cannot be moved to another document.';
    END IF;
    PERFORM assert_document_is_draft_of(NEW.document_id, 'TRR', 'lines');

    SELECT d.serial_no, r.transfer_id, t.to_location_id INTO doc
      FROM document d
      JOIN transfer_receipt r ON r.document_id = d.id
      JOIN transfer_order t ON t.document_id = r.transfer_id
     WHERE d.id = NEW.document_id;
    SELECT l.document_id, l.item_id, l.uom_id, l.line_no INTO tl
      FROM transfer_order_line l WHERE l.id = NEW.transfer_line_id;

    party := transfer_party_role(NEW.entered_by, doc.transfer_id);
    IF party IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Line %s of %s cannot be entered by %s transfer. Whoever raised, dispatched or signed a transfer does not write down what arrived.',
                               NEW.line_no, doc.serial_no,
                               CASE party WHEN 'raised' THEN 'the person who raised the'
                                          WHEN 'dispatched' THEN 'the person who dispatched the'
                                          ELSE 'a person who signed the' END);
    END IF;

    IF tl.document_id IS DISTINCT FROM doc.transfer_id THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Line %s of %s receives a line of a different transfer. A receipt records only what its own transfer dispatched.',
                               NEW.line_no, doc.serial_no);
    END IF;
    IF NEW.item_id <> tl.item_id OR NEW.uom_id <> tl.uom_id THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Line %s of %s differs from transfer line %s in item or unit. What arrives is recorded against what was sent.',
                               NEW.line_no, doc.serial_no, tl.line_no);
    END IF;
    factor := uom_factor_to_base(NEW.item_id, NEW.uom_id);
    IF factor IS NULL OR round(NEW.quantity * factor, 3) <> NEW.qty_base_uom THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Line %s of %s: %s x %s is %s in the base unit, not %s. The ledger carries the base quantity, so it must follow from what was entered.',
                               NEW.line_no, doc.serial_no, NEW.quantity, factor,
                               round(NEW.quantity * COALESCE(factor, 0), 3), NEW.qty_base_uom);
    END IF;
    IF NEW.storage_bin_id IS NOT NULL THEN
        SELECT sb.location_id, sb.bin_code, sb.is_active INTO bin FROM storage_bin sb WHERE sb.id = NEW.storage_bin_id;
        IF bin.location_id <> doc.to_location_id THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Bin %s is not in the destination location of the transfer. Stock is put away where the transfer is going.',
                                   bin.bin_code);
        END IF;
        IF NOT bin.is_active THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Bin %s is deactivated.', bin.bin_code);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transfer_receipt_line_content
    BEFORE INSERT OR UPDATE OR DELETE ON transfer_receipt_line
    FOR EACH ROW EXECUTE FUNCTION receipt_line_guard();

-- ---------------------------------------------------------------------
-- 4. Support link, and where the consignment is.
--
-- A receipt answers to its transfer: the ledger walks this to the TRF,
-- whose chain must be complete, and the cancel guard walks it the other
-- way (a dispatched transfer, or one under a live receipt, is never
-- cancelled). Widened together with ticket_guard's handled sources below.
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW document_support_link AS
SELECT t.document_id AS supported_id, t.source_document_id AS supporting_id
  FROM transaction_ticket t WHERE t.source_document_id IS NOT NULL
UNION ALL
SELECT n.document_id, n.authorization_id
  FROM delivery_note n
UNION ALL
SELECT r.document_id, r.transfer_id
  FROM transfer_receipt r;

-- Per dispatched transfer line: what was sent, what has been received on
-- posted receipts, and what is therefore still in transit. A shortfall
-- stays here as a visible balance until a damage or loss document clears it.
CREATE OR REPLACE VIEW transfer_line_position AS
SELECT t.document_id   AS transfer_id,
       l.id            AS transfer_line_id,
       l.line_no,
       l.item_id,
       l.qty_base_uom  AS dispatched_base,
       COALESCE(r.received, 0)                  AS received_base,
       l.qty_base_uom - COALESCE(r.received, 0) AS in_transit_base
  FROM transfer_order t
  JOIN document td ON td.id = t.document_id AND td.status = 'POSTED'
  JOIN transfer_order_line l ON l.document_id = t.document_id
  LEFT JOIN LATERAL (
        SELECT SUM(rl.qty_base_uom) AS received
          FROM transfer_receipt_line rl
          JOIN document rd ON rd.id = rl.document_id AND rd.status = 'POSTED'
         WHERE rl.transfer_line_id = l.id) r ON TRUE;

COMMENT ON VIEW transfer_line_position IS
  'Dispatched, received and still-in-transit quantity per line of a dispatched transfer. in_transit_base > 0 after the receipt is posted is a shortfall sitting in the source branch''s transit location.';

-- ---------------------------------------------------------------------
-- 5. Lifecycle.
-- ---------------------------------------------------------------------

-- A transfer with no details or no lines is not worth a signature.
CREATE OR REPLACE FUNCTION transfer_submit_guard() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'TRF') THEN
        IF NOT EXISTS (SELECT 1 FROM transfer_order WHERE document_id = NEW.id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s has no source or destination location yet and cannot be submitted.', NEW.serial_no);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM transfer_order_line WHERE document_id = NEW.id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s has no lines and cannot be submitted: there would be nothing to approve.', NEW.serial_no);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_trf_submit
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (OLD.status = 'DRAFT' AND NEW.status = 'PENDING')
    EXECUTE FUNCTION transfer_submit_guard();

-- Dispatch: the TRF's move APPROVED -> POSTED. V11's rule already demands a
-- fully signed chain and a poster who neither raised the transfer nor
-- signed any step of it (so not the Internal Controller who verified it).
-- This adds that the source branch still has exactly one transit location,
-- the one the transfer names. The load is exactly the approved quantity:
-- the dispatch tickets must equal the transfer lines (ticket_line_guard) and
-- every line must have moved, both legs, by commit (below).
CREATE OR REPLACE FUNCTION transfer_dispatch_guard() RETURNS TRIGGER AS $$
DECLARE
    t         RECORD;
    n_transit INT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'TRF') THEN
        RETURN NEW;
    END IF;
    SELECT x.transit_location_id INTO t FROM transfer_order x WHERE x.document_id = NEW.id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s has no locations recorded and cannot be dispatched.', NEW.serial_no);
    END IF;
    SELECT COUNT(*) INTO n_transit FROM location l
     WHERE l.branch_id = NEW.branch_id AND l.location_type = 'TRANSIT' AND l.is_active;
    IF n_transit <> 1 OR NOT EXISTS (SELECT 1 FROM location l WHERE l.id = t.transit_location_id AND l.is_active) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('The source branch of %s must have exactly one active transit location, the one the transfer names; it has %s.',
                               NEW.serial_no, n_transit);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_trf_dispatch
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (NEW.status = 'POSTED' AND OLD.status IS DISTINCT FROM 'POSTED')
    EXECUTE FUNCTION transfer_dispatch_guard();

-- Receipt: the TRR's move DRAFT -> POSTED, judged first by V11's rule for a
-- document with no chain of its own (its authority, the TRF, is fully
-- signed). This adds: the transfer is still dispatched, the receiver is not
-- the dispatcher, and no line receives more than was sent.
CREATE OR REPLACE FUNCTION receipt_post_guard() RETURNS TRIGGER AS $$
DECLARE
    trf RECORD;
    mm  RECORD;
    party TEXT;
    bad_line INT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'TRR') THEN
        RETURN NEW;
    END IF;
    SELECT d.serial_no, d.status, d.posted_by, r.transfer_id INTO trf
      FROM transfer_receipt r JOIN document d ON d.id = r.transfer_id
     WHERE r.document_id = NEW.id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s names no transfer and cannot be posted.', NEW.serial_no);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM transfer_receipt_line WHERE document_id = NEW.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s has no lines: nothing was received, so there is nothing to post. Whatever did not arrive stays in transit.', NEW.serial_no);
    END IF;
    IF NEW.posted_by IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Posting %s must name who records the goods arriving.', NEW.serial_no);
    END IF;
    IF trf.status <> 'POSTED' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Transfer %s is %s, so %s cannot be posted: only a dispatched transfer can be received.',
                               trf.serial_no, trf.status, NEW.serial_no);
    END IF;
    party := transfer_party_role(NEW.posted_by, trf.transfer_id);
    IF party IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s cannot be posted by %s transfer %s. Whoever raised, dispatched or signed a transfer does not also confirm it arrived.',
                               NEW.serial_no,
                               CASE party WHEN 'raised' THEN 'the person who raised'
                                          WHEN 'dispatched' THEN 'the person who dispatched'
                                          ELSE 'a person who signed' END,
                               trf.serial_no);
    END IF;

    -- Every line's author is independent too, however the draft was edited.
    SELECT rl.line_no INTO bad_line
      FROM transfer_receipt_line rl
     WHERE rl.document_id = NEW.id AND transfer_party_role(rl.entered_by, trf.transfer_id) IS NOT NULL
     ORDER BY rl.line_no LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Line %s of %s was entered by someone who raised, dispatched or signed transfer %s. A receipt records only what an independent person saw arrive.',
                               bad_line, NEW.serial_no, trf.serial_no);
    END IF;

    SELECT tl.line_no, tl.qty_base_uom AS sent, SUM(rl.qty_base_uom) AS got INTO mm
      FROM transfer_receipt_line rl
      JOIN transfer_order_line tl ON tl.id = rl.transfer_line_id
     WHERE rl.document_id = NEW.id
     GROUP BY tl.id, tl.line_no, tl.qty_base_uom, tl.quantity
    HAVING SUM(rl.qty_base_uom) > tl.qty_base_uom OR SUM(rl.quantity) > tl.quantity
     ORDER BY tl.line_no
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Line %s of transfer %s dispatched %s and %s receives %s. More cannot arrive than was sent.',
                               mm.line_no, trf.serial_no, mm.sent, NEW.serial_no, mm.got);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_trr_post
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (NEW.status = 'POSTED' AND OLD.status IS DISTINCT FROM 'POSTED')
    EXECUTE FUNCTION receipt_post_guard();

-- A posted transfer or receipt must have moved its stock, both legs of
-- every line, checked at commit (as the GRN and the DN). One function and
-- trigger each, so each can be asked on its own.
CREATE OR REPLACE FUNCTION transfer_posted_must_have_moved_stock() RETURNS TRIGGER AS $$
DECLARE
    ln   RECORD;
    leg  TEXT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'TRF') THEN
        RETURN NULL;
    END IF;
    FOR ln IN SELECT line_no FROM transfer_order_line WHERE document_id = NEW.id ORDER BY line_no LOOP
        FOREACH leg IN ARRAY ARRAY['TRANSFER_OUT', 'TRANSFER_IN'] LOOP
            IF NOT EXISTS (
                   SELECT 1
                     FROM transaction_ticket t
                     JOIN ticket_line tl   ON tl.ticket_id = t.document_id AND tl.line_no = ln.line_no
                     JOIN stock_movement m ON m.ticket_line_id = tl.id AND m.reverses_movement_id IS NULL
                    WHERE t.source_document_id = NEW.id AND t.movement_type = leg) THEN
                RAISE EXCEPTION USING ERRCODE = '23Z02',
                      MESSAGE = format('%s is dispatched but line %s never completed its %s leg in the ledger. A transfer is posted together with both its tickets and their stock movements, or not at all.',
                                       NEW.serial_no, ln.line_no, CASE leg WHEN 'TRANSFER_OUT' THEN 'out-of-source' ELSE 'into-transit' END);
            END IF;
        END LOOP;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER document_trf_posted_moved_stock
    AFTER UPDATE ON document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION transfer_posted_must_have_moved_stock();

CREATE OR REPLACE FUNCTION receipt_posted_must_have_moved_stock() RETURNS TRIGGER AS $$
DECLARE
    ln   RECORD;
    leg  TEXT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'TRR') THEN
        RETURN NULL;
    END IF;
    FOR ln IN SELECT line_no FROM transfer_receipt_line WHERE document_id = NEW.id ORDER BY line_no LOOP
        FOREACH leg IN ARRAY ARRAY['TRANSFER_OUT', 'TRANSFER_IN'] LOOP
            IF NOT EXISTS (
                   SELECT 1
                     FROM transaction_ticket t
                     JOIN ticket_line tl   ON tl.ticket_id = t.document_id AND tl.line_no = ln.line_no
                     JOIN stock_movement m ON m.ticket_line_id = tl.id AND m.reverses_movement_id IS NULL
                    WHERE t.source_document_id = NEW.id AND t.movement_type = leg) THEN
                RAISE EXCEPTION USING ERRCODE = '23Z02',
                      MESSAGE = format('%s is posted but line %s never completed its %s leg in the ledger. A receipt is posted together with both its tickets and their stock movements, or not at all.',
                                       NEW.serial_no, ln.line_no, CASE leg WHEN 'TRANSFER_OUT' THEN 'out-of-transit' ELSE 'into-destination' END);
            END IF;
        END LOOP;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER document_trr_posted_moved_stock
    AFTER UPDATE ON document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION receipt_posted_must_have_moved_stock();

-- A transfer creates and moves no value, checked at commit on the posted
-- documents (the ledger accepts any value on a leg; these two triggers
-- constrain the transfer's legs).
--   TRF: each line's transit IN movement carries exactly the value of the
--        source OUT movement of the same line. Each consignment keeps its
--        own dispatch cost; a blended transit average would misattribute
--        cost between transfers.
--   TRR: per TRF line, take its receipt lines in line_no order and let
--        cum_i be the running total of their base quantities (cum_0 = 0).
--        Line i's OUT-of-transit movement carries
--            share_i = round(v * cum_i / Q, 2) - round(v * cum_(i-1) / Q, 2)
--        where v is the value of the transit IN movement of that TRF line
--        and Q its dispatched base quantity; the destination IN movement
--        carries that same value. Rounding the CUMULATIVE quantity, not each
--        line, means the shares always sum exactly: a full receipt takes
--        exactly v (nothing stranded at zero quantity), a partial one takes
--        round(v * received / Q, 2) (the same as a single line would), and
--        splitting a line across bins can neither strand nor over-take
--        cents. Per-line rounding strands 0.01 on 10.00 received 1+1+1 of 3,
--        and over-takes 0.02 on 1.00 received as six lines of 6. A transfer
--        has at most one live receipt (receipt_header_guard), so no
--        cumulation across receipts is needed. What stays in transit is the
--        shortfall and its share of the cost. round(numeric, 2) rounds half
--        away from zero, which equals HALF_UP for positive values.
CREATE OR REPLACE FUNCTION transfer_value_must_be_kept() RETURNS TRIGGER AS $$
DECLARE
    bad RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'TRF') THEN
        RETURN NULL;
    END IF;
    SELECT ln.line_no, mo.value AS out_v, mi.value AS in_v INTO bad
      FROM transfer_order_line ln
      JOIN transaction_ticket tout ON tout.source_document_id = NEW.id AND tout.movement_type = 'TRANSFER_OUT'
      JOIN ticket_line lo ON lo.ticket_id = tout.document_id AND lo.line_no = ln.line_no
      JOIN stock_movement mo ON mo.ticket_line_id = lo.id AND mo.reverses_movement_id IS NULL
      JOIN transaction_ticket tin ON tin.source_document_id = NEW.id AND tin.movement_type = 'TRANSFER_IN'
      JOIN ticket_line li ON li.ticket_id = tin.document_id AND li.line_no = ln.line_no
      JOIN stock_movement mi ON mi.ticket_line_id = li.id AND mi.reverses_movement_id IS NULL
     WHERE ln.document_id = NEW.id AND mo.value <> mi.value
     ORDER BY ln.line_no LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s line %s left the source at value %s but entered transit at %s. A transfer creates and moves no value: what enters transit is what left the source.',
                               NEW.serial_no, bad.line_no, bad.out_v, bad.in_v);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER document_trf_value_kept
    AFTER UPDATE ON document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION transfer_value_must_be_kept();

CREATE OR REPLACE FUNCTION receipt_value_must_follow_dispatch() RETURNS TRIGGER AS $$
DECLARE
    ln       RECORD;
    v        NUMERIC;
    out_v    NUMERIC;
    in_v     NUMERIC;
    expected NUMERIC;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'TRR') THEN
        RETURN NULL;
    END IF;
    FOR ln IN
        SELECT rl.line_no, tl.qty_base_uom AS qq, tl.line_no AS tline, r.transfer_id,
               SUM(rl.qty_base_uom) OVER w AS cum,
               COALESCE(SUM(rl.qty_base_uom) OVER (PARTITION BY rl.transfer_line_id ORDER BY rl.line_no
                                                   ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS prev
          FROM transfer_receipt_line rl
          JOIN transfer_receipt r ON r.document_id = rl.document_id
          JOIN transfer_order_line tl ON tl.id = rl.transfer_line_id
         WHERE rl.document_id = NEW.id
        WINDOW w AS (PARTITION BY rl.transfer_line_id ORDER BY rl.line_no)
         ORDER BY rl.line_no
    LOOP
        SELECT mi.value INTO v
          FROM transaction_ticket ti
          JOIN ticket_line li ON li.ticket_id = ti.document_id AND li.line_no = ln.tline
          JOIN stock_movement mi ON mi.ticket_line_id = li.id AND mi.reverses_movement_id IS NULL
         WHERE ti.source_document_id = ln.transfer_id AND ti.movement_type = 'TRANSFER_IN';
        SELECT m.value INTO out_v
          FROM transaction_ticket t
          JOIN ticket_line l ON l.ticket_id = t.document_id AND l.line_no = ln.line_no
          JOIN stock_movement m ON m.ticket_line_id = l.id AND m.reverses_movement_id IS NULL
         WHERE t.source_document_id = NEW.id AND t.movement_type = 'TRANSFER_OUT';
        SELECT m.value INTO in_v
          FROM transaction_ticket t
          JOIN ticket_line l ON l.ticket_id = t.document_id AND l.line_no = ln.line_no
          JOIN stock_movement m ON m.ticket_line_id = l.id AND m.reverses_movement_id IS NULL
         WHERE t.source_document_id = NEW.id AND t.movement_type = 'TRANSFER_IN';
        -- A missing leg is the moved-stock trigger's to refuse.
        CONTINUE WHEN v IS NULL OR out_v IS NULL OR in_v IS NULL;
        expected := round(v * ln.cum / ln.qq, 2) - round(v * ln.prev / ln.qq, 2);
        IF out_v <> expected THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s line %s takes %s out of transit, but its share of the dispatched value is %s (cumulative %s of %s dispatched, at %s, rounded cumulatively so the shares sum exactly). A receipt moves out of transit exactly the cost it was dispatched at.',
                                   NEW.serial_no, ln.line_no, out_v, expected, ln.cum, ln.qq, v);
        END IF;
        IF in_v <> out_v THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s line %s enters the destination at %s but left transit at %s. A receipt creates and moves no value.',
                                   NEW.serial_no, ln.line_no, in_v, out_v);
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER document_trr_value_share
    AFTER UPDATE ON document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION receipt_value_must_follow_dispatch();

-- ---------------------------------------------------------------------
-- 6. Tickets and ledger, widened for both documents.
--
-- ticket_guard's handled sources are now GRN, DN, TRF and TRR (with
-- document_support_link, section 4). Four tickets make a transfer; see the
-- header. The three functions are replaced whole; the GRN and DN paths are
-- unchanged except where noted.
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX transaction_ticket_one_transfer_leg_per_source
    ON transaction_ticket (source_document_id, movement_type)
    WHERE movement_type IN ('TRANSFER_OUT', 'TRANSFER_IN') AND source_document_id IS NOT NULL;

-- A movement never carries a negative value (zero stays allowed, for
-- zero-cost stock). Without it a leg could create value by going negative.
ALTER TABLE stock_movement
    ADD CONSTRAINT stock_movement_value_not_negative CHECK (value >= 0);

CREATE OR REPLACE FUNCTION ticket_guard() RETURNS TRIGGER AS $$
DECLARE
    tdoc RECORD;
    src  RECORD;
    grn  RECORD;
    dao  RECORD;
    trf  RECORD;
BEGIN
    SELECT d.serial_no, d.status, d.branch_id, dt.code INTO tdoc
      FROM document d JOIN document_type dt ON dt.id = d.document_type_id
     WHERE d.id = COALESCE(NEW.document_id, OLD.document_id);
    IF tdoc.status = 'POSTED' AND TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s is posted and cannot be changed: its lines are what the ledger carries.', tdoc.serial_no);
    END IF;
    IF TG_OP IN ('UPDATE', 'DELETE')
       AND EXISTS (SELECT 1 FROM stock_movement WHERE document_id = OLD.document_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Stock has already moved against ticket %s, so it cannot be changed or deleted. Correct it with a reversing movement.', tdoc.serial_no);
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF tdoc.code <> 'TT' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is not a transaction ticket.', tdoc.serial_no);
    END IF;

    -- A ticket answers to a document the ledger knows how to judge: a GRN
    -- (a RECEIPT), a DN (a DELIVERY), a TRF (a dispatch leg) or a TRR (a
    -- receipt leg). Any other source, or none, is refused here as well as at
    -- the ledger. Each later module widens this list alongside its
    -- document_support_link entry (section 4).
    IF NEW.source_document_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s names no supporting document. A ticket answers to the document it records, and stock does not move without one.', tdoc.serial_no);
    END IF;
    SELECT d.serial_no, d.branch_id, dt.code INTO src
      FROM document d JOIN document_type dt ON dt.id = d.document_type_id
     WHERE d.id = NEW.source_document_id;
    IF NEW.source_document_id = NEW.document_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02', MESSAGE = 'A ticket cannot be its own supporting document.';
    END IF;
    -- A transfer receipt is at the destination branch, but its out-of-transit
    -- leg moves the SOURCE branch's transit location, so its branch is judged
    -- per leg below. Every other source shares the ticket's branch.
    IF src.code <> 'TRR' AND src.branch_id <> tdoc.branch_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s and its supporting document %s belong to different branches.', tdoc.serial_no, src.serial_no);
    END IF;

    IF src.code = 'GRN' THEN
        SELECT g.location_id, g.customs_reference INTO grn
          FROM goods_received_note g WHERE g.document_id = NEW.source_document_id;
        IF NEW.movement_type <> 'RECEIPT' OR NEW.direction <> 'IN'
           OR NEW.to_location_id IS DISTINCT FROM grn.location_id
           OR NEW.from_location_id IS NOT NULL
           OR NEW.customs_reference IS DISTINCT FROM grn.customs_reference THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s must be a RECEIPT into the location and under the customs reference %s records.', tdoc.serial_no, src.serial_no);
        END IF;
    ELSIF src.code = 'DN' THEN
        SELECT a.location_id, a.customs_reference INTO dao
          FROM delivery_note n JOIN delivery_authorization a ON a.document_id = n.authorization_id
         WHERE n.document_id = NEW.source_document_id;
        IF NEW.movement_type <> 'DELIVERY' OR NEW.direction <> 'OUT'
           OR NEW.from_location_id IS DISTINCT FROM dao.location_id
           OR NEW.to_location_id IS NOT NULL
           OR NEW.customs_reference IS DISTINCT FROM dao.customs_reference THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s must be a DELIVERY out of the location and under the customs reference the authorization behind %s records.', tdoc.serial_no, src.serial_no);
        END IF;
    ELSIF src.code = 'TRF' THEN
        SELECT t.from_location_id, t.transit_location_id, t.customs_reference INTO trf
          FROM transfer_order t WHERE t.document_id = NEW.source_document_id;
        IF NEW.customs_reference IS DISTINCT FROM trf.customs_reference
           OR NOT ((NEW.movement_type = 'TRANSFER_OUT' AND NEW.direction = 'OUT'
                    AND NEW.from_location_id IS NOT DISTINCT FROM trf.from_location_id AND NEW.to_location_id IS NULL)
                OR (NEW.movement_type = 'TRANSFER_IN' AND NEW.direction = 'IN'
                    AND NEW.to_location_id IS NOT DISTINCT FROM trf.transit_location_id AND NEW.from_location_id IS NULL)) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s must be a dispatch leg of %s: TRANSFER_OUT from its source location, or TRANSFER_IN into its transit location, under its customs reference.', tdoc.serial_no, src.serial_no);
        END IF;
    ELSIF src.code = 'TRR' THEN
        SELECT t.to_location_id, t.transit_location_id, t.customs_reference, td.branch_id AS source_branch INTO trf
          FROM transfer_receipt r
          JOIN transfer_order t ON t.document_id = r.transfer_id
          JOIN document td ON td.id = t.document_id
         WHERE r.document_id = NEW.source_document_id;
        IF NEW.customs_reference IS DISTINCT FROM trf.customs_reference
           OR NOT ((NEW.movement_type = 'TRANSFER_OUT' AND NEW.direction = 'OUT'
                    AND NEW.from_location_id IS NOT DISTINCT FROM trf.transit_location_id AND NEW.to_location_id IS NULL
                    AND tdoc.branch_id = trf.source_branch)
                OR (NEW.movement_type = 'TRANSFER_IN' AND NEW.direction = 'IN'
                    AND NEW.to_location_id IS NOT DISTINCT FROM trf.to_location_id AND NEW.from_location_id IS NULL
                    AND tdoc.branch_id = src.branch_id)) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s must be a receipt leg of %s: TRANSFER_OUT of the source branch''s transit location, or TRANSFER_IN into the destination location, each on the register of the branch whose location it moves.', tdoc.serial_no, src.serial_no);
        END IF;
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s cannot be supported by %s: a ticket answers only to a goods received note, a delivery note, a transfer or a transfer receipt. An authorization or any other document does not move stock by itself.', tdoc.serial_no, src.serial_no);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION ticket_line_guard() RETURNS TRIGGER AS $$
DECLARE
    tk RECORD;
    gl RECORD;
    dl RECORD;
    xl RECORD;
    want_bin UUID;
BEGIN
    SELECT d.serial_no, d.status, t.source_document_id, t.movement_type AS mtype,
           s.serial_no AS source_serial, sdt.code AS source_code INTO tk
      FROM transaction_ticket t
      JOIN document d ON d.id = t.document_id
      LEFT JOIN document s ON s.id = t.source_document_id
      LEFT JOIN document_type sdt ON sdt.id = s.document_type_id
     WHERE t.document_id = COALESCE(NEW.ticket_id, OLD.ticket_id);
    IF tk.status = 'POSTED' AND TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s is posted and its lines cannot change.', tk.serial_no);
    END IF;
    IF tk.status = 'POSTED' AND TG_OP = 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s is posted and takes no further lines.', tk.serial_no);
    END IF;
    IF TG_OP IN ('UPDATE', 'DELETE')
       AND EXISTS (SELECT 1 FROM stock_movement WHERE ticket_line_id = OLD.id OR document_id = OLD.ticket_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Stock has already moved against ticket %s, so its lines cannot be changed or deleted. Correct it with a reversing movement.', tk.serial_no);
    END IF;
    IF TG_OP = 'INSERT' AND EXISTS (SELECT 1 FROM stock_movement WHERE document_id = NEW.ticket_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Stock has already moved against ticket %s, so it takes no further lines.', tk.serial_no);
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    IF tk.source_code = 'GRN' THEN
        SELECT * INTO gl FROM goods_received_line
         WHERE document_id = tk.source_document_id AND line_no = NEW.line_no;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s has no line %s on %s. A receipt ticket carries exactly the lines that were approved.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
        IF (gl.item_id, gl.uom_id, gl.quantity, gl.qty_base_uom, gl.storage_bin_id)
           IS DISTINCT FROM
           (NEW.item_id, NEW.uom_id, NEW.quantity, NEW.qty_base_uom, NEW.storage_bin_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s differs from line %s of %s in item, unit, quantity or bin. Stock posted must be what the approvers signed.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
    ELSIF tk.source_code = 'DN' THEN
        SELECT * INTO dl FROM delivery_note_line
         WHERE document_id = tk.source_document_id AND line_no = NEW.line_no;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s has no line %s on %s. A delivery ticket carries exactly the lines that were loaded.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
        IF (dl.item_id, dl.uom_id, dl.quantity, dl.qty_base_uom, dl.storage_bin_id)
           IS DISTINCT FROM
           (NEW.item_id, NEW.uom_id, NEW.quantity, NEW.qty_base_uom, NEW.storage_bin_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s differs from line %s of %s in item, unit, quantity or bin. Stock leaving must be what was loaded and authorized.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
    ELSIF tk.source_code = 'TRF' THEN
        SELECT * INTO xl FROM transfer_order_line
         WHERE document_id = tk.source_document_id AND line_no = NEW.line_no;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s has no line %s on %s. A dispatch ticket carries exactly the lines that were approved.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
        -- The bin belongs to the source location, where the goods leave; the
        -- transit location has none.
        want_bin := CASE WHEN tk.mtype = 'TRANSFER_OUT' THEN xl.storage_bin_id END;
        IF (xl.item_id, xl.uom_id, xl.quantity, xl.qty_base_uom, want_bin)
           IS DISTINCT FROM
           (NEW.item_id, NEW.uom_id, NEW.quantity, NEW.qty_base_uom, NEW.storage_bin_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s differs from line %s of %s in item, unit, quantity or bin. Dispatch is exactly the approved quantity: one load.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
    ELSIF tk.source_code = 'TRR' THEN
        SELECT * INTO xl FROM transfer_receipt_line
         WHERE document_id = tk.source_document_id AND line_no = NEW.line_no;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s has no line %s on %s. A receipt ticket carries exactly the lines that were received.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
        want_bin := CASE WHEN tk.mtype = 'TRANSFER_IN' THEN xl.storage_bin_id END;
        IF (xl.item_id, xl.uom_id, xl.quantity, xl.qty_base_uom, want_bin)
           IS DISTINCT FROM
           (NEW.item_id, NEW.uom_id, NEW.quantity, NEW.qty_base_uom, NEW.storage_bin_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s differs from line %s of %s in item, unit, quantity or bin. What moves is what the receiver recorded.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s has no receipt, delivery, transfer or transfer receipt behind it, so it can carry no lines.', tk.serial_no);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- The ledger guard of V12. Changes: the handled sources include TRF and
-- TRR; and the poster-equality on the AUTHORITY now applies only when the
-- authority is the ticket's own source. A receipt's tickets answer to the
-- TRR (posted by the receiver), whose authority is the TRF (posted by the
-- dispatcher): each movement is checked against the document it records,
-- and that document's own posting already proved the receiver is not the
-- dispatcher. For GRN, DN and TRF tickets nothing changes.
CREATE OR REPLACE FUNCTION stock_movement_needs_approved_document() RETURNS TRIGGER AS $$
DECLARE
    tdoc    RECORD;
    tk      RECORD;
    tl      RECORD;
    loc     RECORD;
    bin_loc UUID;
    auth_id UUID;
    auth    RECORD;
    src     RECORD;
    gap     TEXT;
BEGIN
    SELECT d.serial_no, d.status, d.branch_id, dt.code INTO tdoc
      FROM document d JOIN document_type dt ON dt.id = d.document_type_id
     WHERE d.id = NEW.document_id;
    IF tdoc.code <> 'TT' OR NEW.ticket_line_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Stock moves only against a transaction ticket line, and %s is not one. The ticket is what ties a movement to the document that approved it.',
                               tdoc.serial_no);
    END IF;
    IF tdoc.status = 'CANCELLED' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s is cancelled and cannot move stock.', tdoc.serial_no);
    END IF;

    SELECT * INTO tl FROM ticket_line WHERE id = NEW.ticket_line_id;
    IF NOT FOUND OR tl.ticket_id <> NEW.document_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('The ticket line does not belong to ticket %s.', tdoc.serial_no);
    END IF;
    SELECT * INTO tk FROM transaction_ticket WHERE document_id = NEW.document_id;
    -- Even if a ticket row somehow exists, a source the ledger does not
    -- handle moves nothing (ticket_guard refuses to create one).
    IF NOT EXISTS (SELECT 1 FROM document s JOIN document_type st ON st.id = s.document_type_id
                    WHERE s.id = tk.source_document_id AND st.code IN ('GRN', 'DN', 'TRF', 'TRR')) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s answers to no goods received note, delivery note, transfer or transfer receipt, so its stock does not move. An authorization alone lets nothing leave.', tdoc.serial_no);
    END IF;

    IF (NEW.item_id, NEW.quantity_base_uom, NEW.storage_bin_id)
       IS DISTINCT FROM (tl.item_id, tl.qty_base_uom, tl.storage_bin_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('The movement differs from line %s of ticket %s in item, quantity or bin. The ledger records what the ticket says, no more and no less.',
                               tl.line_no, tdoc.serial_no);
    END IF;
    IF NEW.direction <> tk.direction
       OR NEW.location_id IS DISTINCT FROM (CASE NEW.direction WHEN 'IN' THEN tk.to_location_id ELSE tk.from_location_id END) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('The movement''s direction or location does not match ticket %s.', tdoc.serial_no);
    END IF;

    SELECT l.branch_id INTO loc FROM location l WHERE l.id = NEW.location_id;
    IF NEW.branch_id <> tdoc.branch_id OR loc.branch_id <> NEW.branch_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('The movement, its location and ticket %s must all belong to one branch.', tdoc.serial_no);
    END IF;
    IF NEW.storage_bin_id IS NOT NULL THEN
        SELECT sb.location_id INTO bin_loc FROM storage_bin sb WHERE sb.id = NEW.storage_bin_id;
        IF bin_loc <> NEW.location_id THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02', MESSAGE = 'The bin is not in the movement''s location.';
        END IF;
    END IF;

    auth_id := document_authority(NEW.document_id);
    IF auth_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s answers to no document with an approval chain, so the stock does not move. A ticket no approved document supports is not countersigned.',
                               tdoc.serial_no);
    END IF;
    gap := document_approval_gap(auth_id);
    IF gap IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = gap || ' Stock does not move against a document that is not fully approved.';
    END IF;

    SELECT d.serial_no, d.status, d.posted_by, dt.moves_stock INTO auth
      FROM document d JOIN document_type dt ON dt.id = d.document_type_id WHERE d.id = auth_id;
    IF auth.status NOT IN ('APPROVED', 'POSTED') THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is %s, not approved or posted, so it cannot support a stock movement.', auth.serial_no, auth.status);
    END IF;
    -- A document that itself posts stock is posted first, which is when its
    -- poster is judged independent of its creator and signers (V11 s.4).
    IF auth.moves_stock AND auth.status <> 'POSTED' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s must be posted before its stock moves: posting is the step that checks the poster signed nothing.', auth.serial_no);
    END IF;
    IF auth.status = 'POSTED' AND tk.source_document_id = auth_id
       AND NEW.posted_by IS DISTINCT FROM auth.posted_by THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s was posted by someone else. The movement must be recorded by the person who posted it, the one checked as independent.', auth.serial_no);
    END IF;

    -- The ticket's own source, when it is not the authority (a DN answering
    -- to its DAO, a TRR to its TRF): it posts stock, so it must have been
    -- posted, by the person recording the movement.
    IF tk.source_document_id <> auth_id THEN
        SELECT d.serial_no, d.status, d.posted_by, dt.moves_stock INTO src
          FROM document d JOIN document_type dt ON dt.id = d.document_type_id
         WHERE d.id = tk.source_document_id;
        IF src.moves_stock AND src.status <> 'POSTED' THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s must be posted before its stock moves: posting is the step that checks who releases the goods.', src.serial_no);
        END IF;
        IF src.status = 'POSTED' AND NEW.posted_by IS DISTINCT FROM src.posted_by THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s was posted by someone else. The movement must be recorded by the person who posted it.', src.serial_no);
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- The rights placed above are judged for everyone, now, as they will be
-- at commit.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    msg TEXT;
BEGIN
    msg := access_conflict_anywhere();
    IF msg IS NOT NULL THEN
        RAISE EXCEPTION 'V13 cannot apply: the transfer rights leave someone in conflict. %', msg;
    END IF;
END $$;
