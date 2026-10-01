-- =====================================================================
-- V14 — Returns and damage: write-offs, transit losses, customer
--       returns and quarantine release
--
-- SRS: FR-DMG-01..10 (returns, damage, write-off), FR-WF-03..07,
-- FR-SEC-07, FR-IN-10 (the ledger). Form DMG-009. Inventory Policy s.13:
-- no disposal or write-off without prior written management approval.
--
-- One document type, DMG, four kinds of report; each report is ONE kind,
-- fixed at creation:
--
--   WRITE_OFF           damaged, expired or missing stock written off from a
--                       WAREHOUSE, BONDED or QUARANTINE location at the
--                       report's branch, at average cost. Source bins are
--                       optional. Direct: no mandatory quarantine step.
--   TRANSIT_LOSS        a transfer's shortfall written off from the source
--                       branch's TRANSIT location, against lines of a
--                       dispatched (POSTED) transfer, at that consignment's
--                       own remaining cost.
--   CUSTOMER_RETURN     goods a customer sent back, against lines of a
--                       POSTED delivery note, received into the QUARANTINE
--                       location of the delivering branch, never more than
--                       was delivered less what has already come back,
--                       valued at the cost they left with.
--   QUARANTINE_RELEASE  stock that passed inspection moves from QUARANTINE
--                       into a sellable WAREHOUSE or BONDED location at the
--                       same branch, at no change of value.
--
-- The chain (V5, one definition for both eras) is WH Manager PREPARE,
-- Internal Controller VERIFY, Managing Director APPROVE. THE WRITE-OFF
-- THRESHOLD IS AN OPEN QUESTION FOR THE CLIENT: until it is answered every
-- report needs the full chain, the strictest reading.
--
-- What answers to what. A DMG has its own chain, so it is its own authority:
-- its tickets name it as source and the ledger asks only that it be fully
-- signed and POSTED by someone who neither raised nor signed it (V11). It
-- answers to no other document for approval. It RELATES to a transfer (a
-- transit loss) or a delivery note (a return) by line references, which the
-- triggers below check for quantity, value and the independence of the
-- people involved. document_support_link records those relations too, so
-- the cancellation guard sees them; they never give a DMG an authority
-- other than itself, because document_authority() stops at the first
-- document that has a chain.
--
-- Ticket legs (one direction per ticket; movement types are V4's, no CHECK
-- change is needed):
--     WRITE_OFF, TRANSIT_LOSS  DAMAGE        OUT  the location / the transit location
--     CUSTOMER_RETURN          RETURN        IN   the branch's quarantine location
--     QUARANTINE_RELEASE       TRANSFER_OUT  OUT  quarantine
--                              TRANSFER_IN   IN   the sellable location
-- A release is a relocation between two locations of one branch, which is
-- what TRANSFER_OUT/TRANSFER_IN mean at the ledger; the ticket's source
-- (a DMG of kind QUARANTINE_RELEASE) says why. Reports separate it from a
-- branch-to-branch transfer by that source, not by a new movement type.
--
-- Contents
--   1. Rights
--   2. The report and its lines
--   3. Independence, consumption and value helpers
--   4. Transfer lines: one consumption rule for receipts and losses
--   5. Lifecycle: submit, posting, commit-time checks
--   6. Tickets and ledger, widened for DMG
-- =====================================================================

SET LOCAL highbytes.migration = 'on';

-- ---------------------------------------------------------------------
-- 1. Rights, placed on the role whose step each one serves.
--
--   WH_MANAGER      PREPARE  damage.create, damage.view (held)
--   INTERNAL_CTRL   VERIFY   damage.verify (new; V5 gave it every VERIFY
--                   right that existed then). It NEVER carries
--                   damage.post: it does not record what it tests.
--   MANAGING_DIR    APPROVE  damage.approve (held)
--   FINANCE         (no step) damage.post (new) and damage.view. Finance is
--                   in no step of the DMG chain, and a write-off is a
--                   valuation event, as posting a GRN is: so the poster
--                   is independent of every signature by construction, and
--                   V11 refuses a poster who signed or raised the report
--                   anyway.
-- ---------------------------------------------------------------------
INSERT INTO permission (code, module, action, description) VALUES
    ('damage.verify', 'damage', 'VERIFY', 'Verify a return or damage report against the physical stock'),
    ('damage.post',   'damage', 'POST',   'Post an approved return or damage report to the ledger');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM (VALUES
        ('INTERNAL_CTRL', 'damage.verify'),
        ('FINANCE',       'damage.post'),
        ('FINANCE',       'damage.view')
       ) AS v(role_code, permission_code)
  JOIN role r       ON r.code = v.role_code
  JOIN permission p ON p.code = v.permission_code;

-- ---------------------------------------------------------------------
-- 2. The report and its lines.
-- ---------------------------------------------------------------------
CREATE TABLE damage_report (
    document_id        UUID PRIMARY KEY REFERENCES document(id),
    kind               VARCHAR(20) NOT NULL
                       CHECK (kind IN ('WRITE_OFF', 'TRANSIT_LOSS', 'CUSTOMER_RETURN', 'QUARANTINE_RELEASE')),
    reason_code        VARCHAR(20) NOT NULL
                       CHECK (reason_code IN ('DAMAGED', 'EXPIRED', 'MISSING', 'LOST_IN_TRANSIT',
                                              'CUSTOMER_RETURN', 'INSPECTION_PASSED', 'OTHER')),
    reason             VARCHAR(400) NOT NULL,
    -- Where stock leaves from. For a TRANSIT_LOSS it is the transfer's transit
    -- location, set by the database; omit it on insert.
    from_location_id   UUID REFERENCES location(id),
    -- Where stock arrives. For a CUSTOMER_RETURN it is the delivering
    -- branch's quarantine location, set by the database; omit it on insert.
    to_location_id     UUID REFERENCES location(id),
    transfer_id        UUID REFERENCES transfer_order(document_id),
    delivery_note_id   UUID REFERENCES delivery_note(document_id),
    customs_reference  VARCHAR(80),
    CONSTRAINT dmg_reason_not_blank CHECK (btrim(reason) <> ''),
    CONSTRAINT dmg_customs_reference_not_blank
        CHECK (customs_reference IS NULL OR btrim(customs_reference) <> ''),
    -- Each kind has one shape: a report cannot be half a return and half a write-off.
    CONSTRAINT dmg_kind_shape CHECK (
        (kind = 'WRITE_OFF'          AND from_location_id IS NOT NULL AND to_location_id IS NULL
                                     AND transfer_id IS NULL AND delivery_note_id IS NULL)
     OR (kind = 'TRANSIT_LOSS'       AND from_location_id IS NOT NULL AND to_location_id IS NULL
                                     AND transfer_id IS NOT NULL AND delivery_note_id IS NULL)
     OR (kind = 'CUSTOMER_RETURN'    AND from_location_id IS NULL AND to_location_id IS NOT NULL
                                     AND transfer_id IS NULL AND delivery_note_id IS NOT NULL)
     OR (kind = 'QUARANTINE_RELEASE' AND from_location_id IS NOT NULL AND to_location_id IS NOT NULL
                                     AND transfer_id IS NULL AND delivery_note_id IS NULL))
);

CREATE INDEX damage_report_transfer ON damage_report (transfer_id) WHERE transfer_id IS NOT NULL;
CREATE INDEX damage_report_delivery ON damage_report (delivery_note_id) WHERE delivery_note_id IS NOT NULL;

COMMENT ON TABLE damage_report IS
  'Subtype of document (type DMG). One kind per report, fixed at creation. Content is what the approvers signed, so it changes only while a draft.';

CREATE TABLE damage_report_line (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id             UUID NOT NULL REFERENCES damage_report(document_id),
    line_no                 SMALLINT NOT NULL CHECK (line_no > 0),
    item_id                 UUID NOT NULL REFERENCES item(id),
    uom_id                  UUID NOT NULL REFERENCES uom(id),
    quantity                NUMERIC(16,3) NOT NULL CHECK (quantity > 0),
    qty_base_uom            NUMERIC(16,3) NOT NULL CHECK (qty_base_uom > 0),
    -- The bin the stock LEAVES (write-off, release); optional.
    storage_bin_id          UUID REFERENCES storage_bin(id),
    -- The bin the stock ARRIVES in (customer return into quarantine, release
    -- into the sellable location); optional.
    to_storage_bin_id       UUID REFERENCES storage_bin(id),
    -- TRANSIT_LOSS: the transfer line this writes off.
    transfer_line_id        UUID REFERENCES transfer_order_line(id),
    -- CUSTOMER_RETURN: the delivery note line this returns.
    delivery_note_line_id   UUID REFERENCES delivery_note_line(id),
    note                    VARCHAR(240),
    -- Who entered this line: for a transit loss or a return, the independence
    -- rules are judged on every line as well as on the report's author and
    -- poster. Like created_by it is SUPPLIED BY THE APPLICATION (the service
    -- sets it to the current user on every write); direct SQL is outside what
    -- the application controls, which the production database-roles TODO covers.
    entered_by              UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT dmg_line_one_reference CHECK (transfer_line_id IS NULL OR delivery_note_line_id IS NULL),
    UNIQUE (document_id, line_no)
);

CREATE INDEX damage_report_line_tline ON damage_report_line (transfer_line_id) WHERE transfer_line_id IS NOT NULL;
CREATE INDEX damage_report_line_dnline ON damage_report_line (delivery_note_line_id) WHERE delivery_note_line_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 3. Independence helpers.
--
-- A transit loss: whoever sent or received the goods must not be the one
-- writing off what went missing. Returns 'raised', 'dispatched', 'signed'
-- (V13's transfer_party_role), 'received' (posted a receipt of it) or
-- 'recorded' (wrote a non-cancelled receipt of it, draft or posted, or
-- entered any of its lines): whoever recorded what arrived does not also
-- write off what did not.
-- A return: whoever let the goods out must not be the one who brings them
-- back in. Returns 'released' (posted the delivery note), 'authorized'
-- (raised the authorization behind it), 'loaded' (drew the note up) or
-- 'approved' (signed any step of that authorization), the first that applies. Every signer, not only the
-- releaser as at the gate (V12): the gate executes a load the chain fixed to
-- the unit, but a return is a judgment of what came back, and nobody who
-- approved the goods leaving makes it. Under the 2026 chain a Warehouse
-- Manager verifies every authorization, so a return against it is raised by
-- another Warehouse Manager, or by one covering from another branch.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION transfer_loss_party(p_person UUID, p_transfer UUID) RETURNS TEXT AS $$
    SELECT COALESCE(
        transfer_party_role(p_person, p_transfer),
        CASE
          -- posted a receipt of it
          WHEN EXISTS (SELECT 1 FROM transfer_receipt r JOIN document d ON d.id = r.document_id
                        WHERE r.transfer_id = p_transfer AND d.status = 'POSTED' AND d.posted_by = p_person)
               THEN 'received'
          -- wrote a receipt of it (draft or posted), or entered any of its lines
          WHEN EXISTS (SELECT 1 FROM transfer_receipt r JOIN document d ON d.id = r.document_id
                        WHERE r.transfer_id = p_transfer AND d.status <> 'CANCELLED' AND d.created_by = p_person)
               THEN 'recorded'
          WHEN EXISTS (SELECT 1 FROM transfer_receipt_line l
                         JOIN transfer_receipt r ON r.document_id = l.document_id
                         JOIN document d ON d.id = r.document_id
                        WHERE r.transfer_id = p_transfer AND d.status <> 'CANCELLED' AND l.entered_by = p_person)
               THEN 'recorded'
        END);
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION delivery_party_role(p_person UUID, p_note UUID) RETURNS TEXT AS $$
    SELECT CASE
             WHEN d.posted_by  = p_person THEN 'released'
             WHEN a.created_by = p_person THEN 'authorized'
             WHEN d.created_by = p_person THEN 'loaded'
             WHEN EXISTS (SELECT 1 FROM document_approval da
                           WHERE da.document_id = a.id AND da.actor_user_id = p_person) THEN 'approved'
           END
      FROM document d
      JOIN delivery_note n ON n.document_id = d.id
      JOIN document a ON a.id = n.authorization_id
     WHERE d.id = p_note;
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION dmg_party_text(p_party TEXT) RETURNS TEXT AS $$
    SELECT CASE p_party
             WHEN 'raised'     THEN 'the person who raised'
             WHEN 'dispatched' THEN 'the person who dispatched'
             WHEN 'signed'     THEN 'a person who signed'
             WHEN 'received'   THEN 'the person who received'
             WHEN 'recorded'   THEN 'the person who recorded the arrival of'
             WHEN 'released'   THEN 'the person who let out'
             WHEN 'loaded'     THEN 'the person who drew up'
             WHEN 'authorized' THEN 'the person who authorized'
             WHEN 'approved'   THEN 'a person who signed the authorization behind' END;
$$ LANGUAGE sql IMMUTABLE;

-- The independence of one person from what a report is about (NULL: none).
CREATE OR REPLACE FUNCTION dmg_party_of(p_person UUID, p_kind TEXT, p_transfer UUID, p_note UUID) RETURNS TEXT AS $$
    SELECT CASE p_kind
             WHEN 'TRANSIT_LOSS'    THEN transfer_loss_party(p_person, p_transfer)
             WHEN 'CUSTOMER_RETURN' THEN delivery_party_role(p_person, p_note)
           END;
$$ LANGUAGE sql STABLE;

-- What a transit loss or a return is "about", for messages.
CREATE OR REPLACE FUNCTION dmg_subject(p_kind TEXT, p_transfer UUID, p_note UUID) RETURNS TEXT AS $$
    SELECT CASE p_kind
             WHEN 'TRANSIT_LOSS'    THEN 'transfer ' || (SELECT serial_no FROM document WHERE id = p_transfer)
             WHEN 'CUSTOMER_RETURN' THEN 'delivery note ' || (SELECT serial_no FROM document WHERE id = p_note)
           END;
$$ LANGUAGE sql STABLE;

-- The same independence the other way round. Whoever wrote off part of a
-- transfer (raised, posted, or entered a line of a non-cancelled transit
-- loss of it) does not also record what arrived: one person would then say
-- both what was lost and what came. Its own triggers on the receipt tables,
-- and a clause in receipt_post_guard below, so V13's guards stay as they
-- were and keep their wording.
CREATE OR REPLACE FUNCTION transfer_loss_author(p_person UUID, p_transfer UUID) RETURNS BOOLEAN AS $$
    SELECT EXISTS (
        SELECT 1 FROM damage_report r JOIN document d ON d.id = r.document_id
         WHERE r.kind = 'TRANSIT_LOSS' AND r.transfer_id = p_transfer AND d.status <> 'CANCELLED'
           AND (d.created_by = p_person OR d.posted_by = p_person
                OR EXISTS (SELECT 1 FROM damage_report_line l
                            WHERE l.document_id = r.document_id AND l.entered_by = p_person)));
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION receipt_not_raised_by_loss_author() RETURNS TRIGGER AS $$
DECLARE
    rec RECORD;
BEGIN
    SELECT d.serial_no, d.created_by, t.serial_no AS transfer_serial INTO rec
      FROM document d, document t
     WHERE d.id = NEW.document_id AND t.id = NEW.transfer_id;
    IF transfer_loss_author(rec.created_by, NEW.transfer_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s cannot be raised by the person who wrote off part of transfer %s. Whoever said what was lost does not also say what arrived.',
                               rec.serial_no, rec.transfer_serial);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transfer_receipt_not_by_loss_author
    BEFORE INSERT ON transfer_receipt
    FOR EACH ROW EXECUTE FUNCTION receipt_not_raised_by_loss_author();

-- Named to fire after V13's transfer_receipt_line_content, so a line of a
-- receipt that is no longer a draft still meets that trigger's message first.
CREATE OR REPLACE FUNCTION receipt_line_not_entered_by_loss_author() RETURNS TRIGGER AS $$
DECLARE
    rec RECORD;
BEGIN
    SELECT d.serial_no, r.transfer_id, t.serial_no AS transfer_serial INTO rec
      FROM transfer_receipt r
      JOIN document d ON d.id = r.document_id
      JOIN document t ON t.id = r.transfer_id
     WHERE r.document_id = NEW.document_id;
    IF transfer_loss_author(NEW.entered_by, rec.transfer_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Line %s of %s cannot be entered by the person who wrote off part of transfer %s. Whoever said what was lost does not also say what arrived.',
                               NEW.line_no, rec.serial_no, rec.transfer_serial);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transfer_receipt_line_not_by_loss_author
    BEFORE INSERT OR UPDATE ON transfer_receipt_line
    FOR EACH ROW EXECUTE FUNCTION receipt_line_not_entered_by_loss_author();

CREATE OR REPLACE FUNCTION dmg_header_guard() RETURNS TRIGGER AS $$
DECLARE
    doc    RECORD;
    fl     RECORD;
    tl     RECORD;
    src    RECORD;
    n_q    INT;
    qloc   UUID;
    party  TEXT;
    inherited VARCHAR(80);
    fl_bonded BOOLEAN := FALSE;
    tl_bonded BOOLEAN := FALSE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'DMG', 'details');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.document_id <> OLD.document_id OR NEW.kind <> OLD.kind
                             OR NEW.transfer_id IS DISTINCT FROM OLD.transfer_id
                             OR NEW.delivery_note_id IS DISTINCT FROM OLD.delivery_note_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A report''s kind, and the transfer or delivery note it is about, are fixed when it is created. Cancel it and raise another.';
    END IF;
    PERFORM assert_document_is_draft_of(NEW.document_id, 'DMG', 'details');

    SELECT d.serial_no, d.branch_id, d.created_by, b.is_bonded AS branch_bonded INTO doc
      FROM document d JOIN branch b ON b.id = d.branch_id WHERE d.id = NEW.document_id;

    IF NEW.kind = 'TRANSIT_LOSS' THEN
        SELECT d.serial_no, d.status, d.branch_id, t.transit_location_id INTO src
          FROM transfer_order t JOIN document d ON d.id = t.document_id WHERE t.document_id = NEW.transfer_id;
        IF src.status <> 'POSTED' THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Transfer %s is %s, not dispatched. Only goods that left the source branch can be lost in transit.',
                                   src.serial_no, src.status);
        END IF;
        IF src.branch_id <> doc.branch_id THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is raised at the wrong branch: a transit loss is written off at the source branch of transfer %s, where the goods sit in transit.',
                                   doc.serial_no, src.serial_no);
        END IF;
        IF NEW.from_location_id IS NOT NULL AND NEW.from_location_id <> src.transit_location_id THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                  MESSAGE = 'A transit loss is written off from the transfer''s own transit location; another cannot be chosen.';
        END IF;
        NEW.from_location_id := src.transit_location_id;
    ELSIF NEW.kind = 'CUSTOMER_RETURN' THEN
        SELECT d.serial_no, d.status, d.branch_id INTO src
          FROM delivery_note n JOIN document d ON d.id = n.document_id WHERE n.document_id = NEW.delivery_note_id;
        IF src.status <> 'POSTED' THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Delivery note %s is %s, so nothing has been delivered to bring back.', src.serial_no, src.status);
        END IF;
        IF src.branch_id <> doc.branch_id THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is raised at the wrong branch: returned goods come back to the branch that delivered them (delivery note %s).',
                                   doc.serial_no, src.serial_no);
        END IF;
        SELECT COUNT(*), MIN(l.id::text)::uuid INTO n_q, qloc
          FROM location l WHERE l.branch_id = doc.branch_id AND l.location_type = 'QUARANTINE' AND l.is_active;
        IF n_q <> 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                  MESSAGE = format('The delivering branch must have exactly one active quarantine location to receive returns; it has %s.', n_q);
        END IF;
        IF NEW.to_location_id IS NOT NULL AND NEW.to_location_id <> qloc THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                  MESSAGE = 'Returned goods go to the delivering branch''s quarantine location; another cannot be chosen.';
        END IF;
        NEW.to_location_id := qloc;
    END IF;

    -- Customs follows the goods. A loss of a consignment, or a return of a
    -- delivery, that moved under a customs reference carries that same
    -- reference, whatever the report's own locations are: transit and
    -- quarantine are never bonded, yet the goods in them may be duty-suspended.
    -- Omitted, it is taken from there; a different one is refused.
    IF NEW.kind = 'TRANSIT_LOSS' THEN
        SELECT t.customs_reference INTO inherited FROM transfer_order t WHERE t.document_id = NEW.transfer_id;
    ELSIF NEW.kind = 'CUSTOMER_RETURN' THEN
        SELECT a.customs_reference INTO inherited
          FROM delivery_note n JOIN delivery_authorization a ON a.document_id = n.authorization_id
         WHERE n.document_id = NEW.delivery_note_id;
    END IF;
    IF inherited IS NOT NULL THEN
        IF NEW.customs_reference IS NULL THEN
            NEW.customs_reference := inherited;
        ELSIF NEW.customs_reference <> inherited THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                  MESSAGE = format('%s is about %s, which moved under customs reference %s, so it carries that reference, not %s. Duty-suspended goods stay accountable under the reference they travelled with.',
                                   doc.serial_no, dmg_subject(NEW.kind, NEW.transfer_id, NEW.delivery_note_id),
                                   inherited, NEW.customs_reference);
        END IF;
    END IF;

    IF NEW.from_location_id IS NOT NULL THEN
        SELECT l.code, l.branch_id, l.location_type, l.is_active, l.is_bonded, l.is_sellable INTO fl
          FROM location l WHERE l.id = NEW.from_location_id;
        fl_bonded := fl.is_bonded;
        IF fl.branch_id <> doc.branch_id THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                  MESSAGE = format('Location %s is not at the branch %s belongs to.', fl.code, doc.serial_no);
        END IF;
        IF NOT fl.is_active THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = format('Location %s is deactivated.', fl.code);
        END IF;
        IF NEW.kind = 'WRITE_OFF' AND fl.location_type NOT IN ('WAREHOUSE', 'BONDED', 'QUARANTINE') THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                  MESSAGE = format('Stock is written off from a warehouse, bonded or quarantine location; %s is %s.', fl.code, fl.location_type);
        END IF;
        IF NEW.kind = 'QUARANTINE_RELEASE' AND fl.location_type <> 'QUARANTINE' THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                  MESSAGE = format('Stock is released FROM quarantine; %s is %s.', fl.code, fl.location_type);
        END IF;
    END IF;
    IF NEW.to_location_id IS NOT NULL THEN
        SELECT l.code, l.branch_id, l.location_type, l.is_active, l.is_bonded, l.is_sellable INTO tl
          FROM location l WHERE l.id = NEW.to_location_id;
        tl_bonded := tl.is_bonded;
        IF tl.branch_id <> doc.branch_id THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                  MESSAGE = format('Location %s is not at the branch %s belongs to. A release stays within one branch.', tl.code, doc.serial_no);
        END IF;
        IF NOT tl.is_active THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = format('Location %s is deactivated.', tl.code);
        END IF;
        IF NEW.kind = 'QUARANTINE_RELEASE' THEN
            IF tl.location_type NOT IN ('WAREHOUSE', 'BONDED') OR NOT tl.is_sellable THEN
                RAISE EXCEPTION USING ERRCODE = '23514',
                      MESSAGE = format('Released stock goes to a sellable warehouse or bonded location; %s is not sellable stock. Releasing into it would hide stock that cannot be sold.', tl.code);
            END IF;
        END IF;
    END IF;

    IF (fl_bonded OR tl_bonded OR doc.branch_bonded)
       AND NEW.customs_reference IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
              MESSAGE = format('%s touches bonded stock or a bonded branch and needs a customs reference. Duty-suspended goods are accountable to Customs when they are written off, returned or released.',
                               doc.serial_no);
    END IF;

    -- A transit loss or a return is not raised by someone who sent or
    -- received the goods, or let them out.
    IF TG_OP = 'INSERT' AND NEW.kind IN ('TRANSIT_LOSS', 'CUSTOMER_RETURN') THEN
        party := dmg_party_of(doc.created_by, NEW.kind, NEW.transfer_id, NEW.delivery_note_id);
        IF party IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s cannot be raised by %s %s. Whoever sent, received or let out the goods does not also write off what went missing or take back what was returned.',
                                   doc.serial_no, dmg_party_text(party),
                                   dmg_subject(NEW.kind, NEW.transfer_id, NEW.delivery_note_id));
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER damage_report_content
    BEFORE INSERT OR UPDATE OR DELETE ON damage_report
    FOR EACH ROW EXECUTE FUNCTION dmg_header_guard();

CREATE OR REPLACE FUNCTION dmg_line_guard() RETURNS TRIGGER AS $$
DECLARE
    h      RECORD;
    it     RECORD;
    bin    RECORD;
    factor NUMERIC;
    party  TEXT;
    ref_item UUID;
    ref_uom  UUID;
    ref_line INT;
    ref_doc  UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'DMG', 'lines');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.document_id <> OLD.document_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02', MESSAGE = 'A report line cannot be moved to another document.';
    END IF;
    PERFORM assert_document_is_draft_of(NEW.document_id, 'DMG', 'lines');

    SELECT d.serial_no, r.kind, r.from_location_id, r.to_location_id, r.transfer_id, r.delivery_note_id INTO h
      FROM document d JOIN damage_report r ON r.document_id = d.id WHERE d.id = NEW.document_id;
    SELECT i.item_code, i.is_active INTO it FROM item i WHERE i.id = NEW.item_id;
    IF NOT it.is_active THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
              MESSAGE = format('Item %s is deactivated and cannot be reported.', it.item_code);
    END IF;
    factor := uom_factor_to_base(NEW.item_id, NEW.uom_id);
    IF factor IS NULL OR round(NEW.quantity * factor, 3) <> NEW.qty_base_uom THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
              MESSAGE = format('Line %s of %s: %s x %s is %s in the base unit, not %s. The ledger carries the base quantity, so it must follow from what was entered.',
                               NEW.line_no, h.serial_no, NEW.quantity, factor,
                               round(NEW.quantity * COALESCE(factor, 0), 3), NEW.qty_base_uom);
    END IF;

    party := dmg_party_of(NEW.entered_by, h.kind, h.transfer_id, h.delivery_note_id);
    IF party IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Line %s of %s cannot be entered by %s %s. Whoever sent, received or let out the goods does not write the report.',
                               NEW.line_no, h.serial_no, dmg_party_text(party),
                               dmg_subject(h.kind, h.transfer_id, h.delivery_note_id));
    END IF;

    -- References belong to the kind, and only to it.
    IF (h.kind = 'TRANSIT_LOSS') <> (NEW.transfer_line_id IS NOT NULL)
       OR (h.kind = 'CUSTOMER_RETURN') <> (NEW.delivery_note_line_id IS NOT NULL) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
              MESSAGE = format('Line %s of %s: a transit loss names a transfer line, a customer return a delivery note line, and no other kind names either.',
                               NEW.line_no, h.serial_no);
    END IF;
    IF h.kind = 'TRANSIT_LOSS' THEN
        SELECT l.document_id, l.item_id, l.uom_id, l.line_no INTO ref_doc, ref_item, ref_uom, ref_line
          FROM transfer_order_line l WHERE l.id = NEW.transfer_line_id;
        IF ref_doc IS DISTINCT FROM h.transfer_id THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                  MESSAGE = format('Line %s of %s names a line of a different transfer.', NEW.line_no, h.serial_no);
        END IF;
    ELSIF h.kind = 'CUSTOMER_RETURN' THEN
        SELECT l.document_id, l.item_id, l.uom_id, l.line_no INTO ref_doc, ref_item, ref_uom, ref_line
          FROM delivery_note_line l WHERE l.id = NEW.delivery_note_line_id;
        IF ref_doc IS DISTINCT FROM h.delivery_note_id THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                  MESSAGE = format('Line %s of %s names a line of a different delivery note.', NEW.line_no, h.serial_no);
        END IF;
    END IF;
    IF ref_item IS NOT NULL AND (NEW.item_id <> ref_item OR NEW.uom_id <> ref_uom) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
              MESSAGE = format('Line %s of %s differs from line %s it is about in item or unit.', NEW.line_no, h.serial_no, ref_line);
    END IF;

    -- Bins: the side the stock leaves, and the side it arrives.
    IF NEW.storage_bin_id IS NOT NULL THEN
        IF h.kind NOT IN ('WRITE_OFF', 'QUARANTINE_RELEASE') THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                  MESSAGE = format('Line %s of %s: this kind of report takes no source bin.', NEW.line_no, h.serial_no);
        END IF;
        SELECT sb.location_id, sb.bin_code, sb.is_active INTO bin FROM storage_bin sb WHERE sb.id = NEW.storage_bin_id;
        IF bin.location_id <> h.from_location_id THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                  MESSAGE = format('Bin %s is not in the location %s takes stock from.', bin.bin_code, h.serial_no);
        END IF;
        IF NOT bin.is_active THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = format('Bin %s is deactivated.', bin.bin_code);
        END IF;
    END IF;
    IF NEW.to_storage_bin_id IS NOT NULL THEN
        IF h.kind NOT IN ('CUSTOMER_RETURN', 'QUARANTINE_RELEASE') THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                  MESSAGE = format('Line %s of %s: this kind of report takes no destination bin.', NEW.line_no, h.serial_no);
        END IF;
        SELECT sb.location_id, sb.bin_code, sb.is_active INTO bin FROM storage_bin sb WHERE sb.id = NEW.to_storage_bin_id;
        IF bin.location_id <> h.to_location_id THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                  MESSAGE = format('Bin %s is not in the location %s puts stock into.', bin.bin_code, h.serial_no);
        END IF;
        IF NOT bin.is_active THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = format('Bin %s is deactivated.', bin.bin_code);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER damage_report_line_content
    BEFORE INSERT OR UPDATE OR DELETE ON damage_report_line
    FOR EACH ROW EXECUTE FUNCTION dmg_line_guard();

-- A report with no details or no lines is not worth a signature.
CREATE OR REPLACE FUNCTION dmg_submit_guard() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'DMG') THEN
        IF NOT EXISTS (SELECT 1 FROM damage_report WHERE document_id = NEW.id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s has no kind or location yet and cannot be submitted.', NEW.serial_no);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM damage_report_line WHERE document_id = NEW.id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s has no lines and cannot be submitted: there would be nothing to approve.', NEW.serial_no);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_dmg_submit
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (OLD.status = 'DRAFT' AND NEW.status = 'PENDING')
    EXECUTE FUNCTION dmg_submit_guard();

-- ---------------------------------------------------------------------
-- 4. Transfer lines: one consumption rule for receipts and losses, and
--    one value rule for everything that leaves transit.
--
-- Per transfer line, what was RECEIVED plus what was WRITTEN OFF as lost
-- can never exceed what was dispatched, whichever comes first. A total
-- loss can be written off with no receipt at all (a consignment that never
-- arrives can only be closed this way); a later receipt is then limited to
-- what remains. Only POSTED documents count; posting takes a lock per
-- transfer so a receipt and a loss posted together cannot both see the
-- same remainder.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION transfer_line_received(p_tline UUID) RETURNS NUMERIC AS $$
    SELECT COALESCE(SUM(rl.qty_base_uom), 0)
      FROM transfer_receipt_line rl JOIN document d ON d.id = rl.document_id AND d.status = 'POSTED'
     WHERE rl.transfer_line_id = p_tline;
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION transfer_line_written_off(p_tline UUID, p_except UUID DEFAULT NULL) RETURNS NUMERIC AS $$
    SELECT COALESCE(SUM(dl.qty_base_uom), 0)
      FROM damage_report_line dl JOIN document d ON d.id = dl.document_id AND d.status = 'POSTED'
     WHERE dl.transfer_line_id = p_tline AND dl.document_id IS DISTINCT FROM p_except;
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION delivery_line_returned(p_dnline UUID, p_except UUID DEFAULT NULL) RETURNS NUMERIC AS $$
    SELECT COALESCE(SUM(dl.qty_base_uom), 0)
      FROM damage_report_line dl JOIN document d ON d.id = dl.document_id AND d.status = 'POSTED'
     WHERE dl.delivery_note_line_id = p_dnline AND dl.document_id IS DISTINCT FROM p_except;
$$ LANGUAGE sql STABLE;

-- Where the consignment is, now counting losses: in transit = dispatched -
-- received - written off. The first seven columns keep V13's names and order.
CREATE OR REPLACE VIEW transfer_line_position AS
SELECT t.document_id   AS transfer_id,
       l.id            AS transfer_line_id,
       l.line_no,
       l.item_id,
       l.qty_base_uom  AS dispatched_base,
       transfer_line_received(l.id)                                        AS received_base,
       l.qty_base_uom - transfer_line_received(l.id) - transfer_line_written_off(l.id) AS in_transit_base,
       transfer_line_written_off(l.id)                                     AS written_off_base
  FROM transfer_order t
  JOIN document td ON td.id = t.document_id AND td.status = 'POSTED'
  JOIN transfer_order_line l ON l.document_id = t.document_id;

COMMENT ON VIEW transfer_line_position IS
  'Dispatched, received, still in transit (dispatched - received - written off) and written off as lost, per line of a dispatched transfer. A shortfall that is neither received nor written off stays in the source branch''s transit location.';

-- The unified transit value rule. Every OUT-of-transit movement of one
-- transfer line, a receipt's or a loss's, in ledger order (movement id),
-- carries
--     share_k = round(v * cumQ_k / Q, 2) - round(v * cumQ_(k-1) / Q, 2)
-- where v is the value of that line's transit IN movement (dispatch), Q its
-- dispatched base quantity, and cumQ_k the running total of the base
-- quantities of those OUT-of-transit movements up to and including k. So
-- everything consumed, received or lost, sums to exactly v and no cent is
-- stranded or over-taken. Within one receipt this is V13's cumulative rule
-- unchanged (a receipt's movements are in line_no order). Expected values
-- depend on quantities and order only, never on the values already posted.
CREATE OR REPLACE FUNCTION transit_out_shares(p_tline UUID)
RETURNS TABLE (movement_id BIGINT, source_id UUID, line_no INT, qty NUMERIC, value NUMERIC,
               cum NUMERIC, dispatched NUMERIC, dispatch_value NUMERIC, expected NUMERIC) AS $$
    WITH outs AS (
        SELECT m.id AS mid, t.source_document_id AS sid, l.line_no::int AS lno,
               m.quantity_base_uom AS q, m.value AS val
          FROM transfer_receipt_line rl
          JOIN transaction_ticket t ON t.source_document_id = rl.document_id AND t.movement_type = 'TRANSFER_OUT'
          JOIN ticket_line l ON l.ticket_id = t.document_id AND l.line_no = rl.line_no
          JOIN stock_movement m ON m.ticket_line_id = l.id AND m.reverses_movement_id IS NULL
         WHERE rl.transfer_line_id = p_tline
        UNION ALL
        SELECT m.id, t.source_document_id, l.line_no::int, m.quantity_base_uom, m.value
          FROM damage_report_line dl
          JOIN transaction_ticket t ON t.source_document_id = dl.document_id AND t.movement_type = 'DAMAGE'
          JOIN ticket_line l ON l.ticket_id = t.document_id AND l.line_no = dl.line_no
          JOIN stock_movement m ON m.ticket_line_id = l.id AND m.reverses_movement_id IS NULL
         WHERE dl.transfer_line_id = p_tline
    ), dispatch AS (
        SELECT tl.qty_base_uom AS qq, mi.value AS v
          FROM transfer_order_line tl
          JOIN transaction_ticket ti ON ti.source_document_id = tl.document_id AND ti.movement_type = 'TRANSFER_IN'
          JOIN ticket_line li ON li.ticket_id = ti.document_id AND li.line_no = tl.line_no
          JOIN stock_movement mi ON mi.ticket_line_id = li.id AND mi.reverses_movement_id IS NULL
         WHERE tl.id = p_tline
    ), running AS (
        SELECT o.*, SUM(o.q) OVER (ORDER BY o.mid) AS cumq FROM outs o
    )
    SELECT r.mid, r.sid, r.lno, r.q, r.val, r.cumq, d.qq, d.v,
           round(d.v * r.cumq / d.qq, 2) - round(d.v * (r.cumq - r.q) / d.qq, 2)
      FROM running r CROSS JOIN dispatch d
     ORDER BY r.mid;
$$ LANGUAGE sql STABLE;

-- The same for returns: every RETURN IN movement against one delivery note
-- line, in ledger order, against that line's OUT movement (value v, base
-- quantity Q). Returns can never bring back more value than left.
CREATE OR REPLACE FUNCTION return_shares(p_dnline UUID)
RETURNS TABLE (movement_id BIGINT, source_id UUID, line_no INT, qty NUMERIC, value NUMERIC,
               cum NUMERIC, delivered NUMERIC, delivered_value NUMERIC, expected NUMERIC) AS $$
    WITH rets AS (
        SELECT m.id AS mid, t.source_document_id AS sid, l.line_no::int AS lno,
               m.quantity_base_uom AS q, m.value AS val
          FROM damage_report_line dl
          JOIN transaction_ticket t ON t.source_document_id = dl.document_id AND t.movement_type = 'RETURN'
          JOIN ticket_line l ON l.ticket_id = t.document_id AND l.line_no = dl.line_no
          JOIN stock_movement m ON m.ticket_line_id = l.id AND m.reverses_movement_id IS NULL
         WHERE dl.delivery_note_line_id = p_dnline
    ), out_leg AS (
        SELECT nl.qty_base_uom AS qq, mo.value AS v
          FROM delivery_note_line nl
          JOIN transaction_ticket t ON t.source_document_id = nl.document_id AND t.movement_type = 'DELIVERY'
          JOIN ticket_line l ON l.ticket_id = t.document_id AND l.line_no = nl.line_no
          JOIN stock_movement mo ON mo.ticket_line_id = l.id AND mo.reverses_movement_id IS NULL
         WHERE nl.id = p_dnline
    ), running AS (
        SELECT r.*, SUM(r.q) OVER (ORDER BY r.mid) AS cumq FROM rets r
    )
    SELECT r.mid, r.sid, r.lno, r.q, r.val, r.cumq, o.qq, o.v,
           round(o.v * r.cumq / o.qq, 2) - round(o.v * (r.cumq - r.q) / o.qq, 2)
      FROM running r CROSS JOIN out_leg o
     ORDER BY r.mid;
$$ LANGUAGE sql STABLE;

-- V13's receipt posting guard, replaced whole: the same checks, and the
-- consumption rule above (received + written off <= dispatched, per line).
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

    -- Nor by anyone who wrote off part of the same transfer (section 3).
    IF transfer_loss_author(NEW.posted_by, trf.transfer_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s cannot be posted by the person who wrote off part of transfer %s. Whoever said what was lost does not also say what arrived.',
                               NEW.serial_no, trf.serial_no);
    END IF;
    SELECT rl.line_no INTO bad_line
      FROM transfer_receipt_line rl
     WHERE rl.document_id = NEW.id AND transfer_loss_author(rl.entered_by, trf.transfer_id)
     ORDER BY rl.line_no LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Line %s of %s was entered by the person who wrote off part of transfer %s. Whoever said what was lost does not also say what arrived.',
                               bad_line, NEW.serial_no, trf.serial_no);
    END IF;

    -- Received + written off <= dispatched, per transfer line. Serialised with
    -- loss postings on the same transfer.
    PERFORM pg_advisory_xact_lock(hashtextextended('trfuse:' || trf.transfer_id::text, 0));
    SELECT tl.line_no, tl.qty_base_uom AS sent, SUM(rl.qty_base_uom) AS got,
           transfer_line_written_off(tl.id) AS lost INTO mm
      FROM transfer_receipt_line rl
      JOIN transfer_order_line tl ON tl.id = rl.transfer_line_id
     WHERE rl.document_id = NEW.id
     GROUP BY tl.id, tl.line_no, tl.qty_base_uom, tl.quantity
    HAVING SUM(rl.qty_base_uom) + transfer_line_written_off(tl.id) > tl.qty_base_uom
        OR SUM(rl.quantity) > tl.quantity
     ORDER BY tl.line_no
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Line %s of transfer %s dispatched %s and %s receives %s. More cannot arrive than was sent.%s',
                               mm.line_no, trf.serial_no, mm.sent, NEW.serial_no, mm.got,
                               CASE WHEN mm.lost > 0
                                    THEN format(' %s of it has already been written off as lost in transit, so only %s remains to receive.',
                                                mm.lost, mm.sent - mm.lost)
                                    ELSE '' END);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- V13's receipt value trigger, replaced by the unified rule: each receipt
-- line's OUT-of-transit movement carries its share from transit_out_shares
-- (which counts losses in the same order), and the destination IN movement
-- carries that same value.
CREATE OR REPLACE FUNCTION receipt_value_must_follow_dispatch() RETURNS TRIGGER AS $$
DECLARE
    ln    RECORD;
    sh    RECORD;
    in_v  NUMERIC;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'TRR') THEN
        RETURN NULL;
    END IF;
    FOR ln IN SELECT rl.line_no, rl.transfer_line_id FROM transfer_receipt_line rl
               WHERE rl.document_id = NEW.id ORDER BY rl.line_no
    LOOP
        SELECT s.* INTO sh FROM transit_out_shares(ln.transfer_line_id) s
         WHERE s.source_id = NEW.id AND s.line_no = ln.line_no;
        SELECT m.value INTO in_v
          FROM transaction_ticket t
          JOIN ticket_line l ON l.ticket_id = t.document_id AND l.line_no = ln.line_no
          JOIN stock_movement m ON m.ticket_line_id = l.id AND m.reverses_movement_id IS NULL
         WHERE t.source_document_id = NEW.id AND t.movement_type = 'TRANSFER_IN';
        -- A missing leg or dispatch value is the moved-stock trigger's to refuse.
        CONTINUE WHEN sh.movement_id IS NULL OR in_v IS NULL OR sh.dispatch_value IS NULL;
        IF sh.value <> sh.expected THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s line %s takes %s out of transit, but its share of the dispatched value is %s (cumulative %s of %s dispatched, at %s, counting every receipt and loss in ledger order so the shares sum exactly). A receipt moves out of transit exactly the cost it was dispatched at.',
                                   NEW.serial_no, ln.line_no, sh.value, sh.expected, sh.cum, sh.dispatched, sh.dispatch_value);
        END IF;
        IF in_v <> sh.value THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s line %s enters the destination at %s but left transit at %s. A receipt creates and moves no value.',
                                   NEW.serial_no, ln.line_no, in_v, sh.value);
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- 5. Lifecycle: posting, and what must be true at commit.
--
-- The DMG goes POSTED by V11's rule (a fully signed chain; a poster who
-- neither raised it nor signed it). This adds, per kind, what the report is
-- about: a transit loss never writes off more than remains in transit
-- (received + written off <= dispatched), a return never brings back more
-- than was delivered less what has already come back, and the people behind
-- the transfer or the delivery stay out. Named to fire before V11's
-- document_lifecycle.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION dmg_post_guard() RETURNS TRIGGER AS $$
DECLARE
    h    RECORD;
    mm   RECORD;
    party TEXT;
    bad_line INT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'DMG') THEN
        RETURN NEW;
    END IF;
    SELECT r.kind, r.transfer_id, r.delivery_note_id INTO h FROM damage_report r WHERE r.document_id = NEW.id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s has no kind or locations recorded and cannot be posted.', NEW.serial_no);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM damage_report_line WHERE document_id = NEW.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s has no lines and cannot be posted.', NEW.serial_no);
    END IF;

    IF h.kind IN ('TRANSIT_LOSS', 'CUSTOMER_RETURN') AND NEW.posted_by IS NOT NULL THEN
        party := dmg_party_of(NEW.posted_by, h.kind, h.transfer_id, h.delivery_note_id);
        IF party IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s cannot be posted by %s %s. Whoever sent, received or let out the goods does not also write off what went missing or take back what was returned.',
                                   NEW.serial_no, dmg_party_text(party),
                                   dmg_subject(h.kind, h.transfer_id, h.delivery_note_id));
        END IF;
        SELECT dl.line_no INTO bad_line
          FROM damage_report_line dl
         WHERE dl.document_id = NEW.id
           AND dmg_party_of(dl.entered_by, h.kind, h.transfer_id, h.delivery_note_id) IS NOT NULL
         ORDER BY dl.line_no LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Line %s of %s was entered by someone behind %s. A report is written by an independent person.',
                                   bad_line, NEW.serial_no, dmg_subject(h.kind, h.transfer_id, h.delivery_note_id));
        END IF;
    END IF;

    IF h.kind = 'TRANSIT_LOSS' THEN
        IF NOT EXISTS (SELECT 1 FROM document WHERE id = h.transfer_id AND status = 'POSTED') THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is about a transfer that is not dispatched.', NEW.serial_no);
        END IF;
        PERFORM pg_advisory_xact_lock(hashtextextended('trfuse:' || h.transfer_id::text, 0));
        SELECT tl.line_no, tl.qty_base_uom AS sent, mine.q AS mine,
               transfer_line_received(tl.id) AS received, transfer_line_written_off(tl.id, NEW.id) AS lost INTO mm
          FROM (SELECT dl.transfer_line_id, SUM(dl.qty_base_uom) AS q
                  FROM damage_report_line dl WHERE dl.document_id = NEW.id GROUP BY dl.transfer_line_id) mine
          JOIN transfer_order_line tl ON tl.id = mine.transfer_line_id
         WHERE mine.q + transfer_line_received(tl.id) + transfer_line_written_off(tl.id, NEW.id) > tl.qty_base_uom
         ORDER BY tl.line_no LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Line %s of the transfer dispatched %s; %s has been received and %s already written off, so only %s remains in transit, and %s writes off %s. More cannot be lost than is in transit.',
                                   mm.line_no, mm.sent, mm.received, mm.lost,
                                   mm.sent - mm.received - mm.lost, NEW.serial_no, mm.mine);
        END IF;
    ELSIF h.kind = 'CUSTOMER_RETURN' THEN
        IF NOT EXISTS (SELECT 1 FROM document WHERE id = h.delivery_note_id AND status = 'POSTED') THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is about a delivery note that is not posted.', NEW.serial_no);
        END IF;
        PERFORM pg_advisory_xact_lock(hashtextextended('dmgret:' || h.delivery_note_id::text, 0));
        SELECT nl.line_no, nl.qty_base_uom AS sent, mine.q AS mine,
               delivery_line_returned(nl.id, NEW.id) AS back INTO mm
          FROM (SELECT dl.delivery_note_line_id, SUM(dl.qty_base_uom) AS q
                  FROM damage_report_line dl WHERE dl.document_id = NEW.id GROUP BY dl.delivery_note_line_id) mine
          JOIN delivery_note_line nl ON nl.id = mine.delivery_note_line_id
         WHERE mine.q + delivery_line_returned(nl.id, NEW.id) > nl.qty_base_uom
         ORDER BY nl.line_no LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Line %s of the delivery note delivered %s and %s has already come back, so at most %s can be returned, and %s returns %s. More cannot come back than was delivered.',
                                   mm.line_no, mm.sent, mm.back, mm.sent - mm.back, NEW.serial_no, mm.mine);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_dmg_post
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (NEW.status = 'POSTED' AND OLD.status IS DISTINCT FROM 'POSTED')
    EXECUTE FUNCTION dmg_post_guard();

-- A posted report must have moved its stock, checked at commit.
CREATE OR REPLACE FUNCTION dmg_posted_must_have_moved_stock() RETURNS TRIGGER AS $$
DECLARE
    h   RECORD;
    ln  RECORD;
    leg TEXT;
    legs TEXT[];
BEGIN
    SELECT r.kind INTO h FROM damage_report r WHERE r.document_id = NEW.id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    legs := CASE h.kind WHEN 'CUSTOMER_RETURN'    THEN ARRAY['RETURN']
                        WHEN 'QUARANTINE_RELEASE' THEN ARRAY['TRANSFER_OUT', 'TRANSFER_IN']
                        ELSE ARRAY['DAMAGE'] END;
    FOR ln IN SELECT line_no FROM damage_report_line WHERE document_id = NEW.id ORDER BY line_no LOOP
        FOREACH leg IN ARRAY legs LOOP
            IF NOT EXISTS (
                   SELECT 1
                     FROM transaction_ticket t
                     JOIN ticket_line tl   ON tl.ticket_id = t.document_id AND tl.line_no = ln.line_no
                     JOIN stock_movement m ON m.ticket_line_id = tl.id AND m.reverses_movement_id IS NULL
                    WHERE t.source_document_id = NEW.id AND t.movement_type = leg) THEN
                RAISE EXCEPTION USING ERRCODE = '23Z02',
                      MESSAGE = format('%s is posted but line %s never completed its %s leg in the ledger. A report is posted together with its tickets and stock movements, or not at all.',
                                       NEW.serial_no, ln.line_no, leg);
            END IF;
        END LOOP;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER document_dmg_posted_moved_stock
    AFTER UPDATE ON document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION dmg_posted_must_have_moved_stock();

-- Value, checked at commit on the posted report:
--   TRANSIT_LOSS     each line's OUT movement carries its unified share of
--                    the consignment's own transit value (section 4);
--   CUSTOMER_RETURN  each line's RETURN IN movement carries its cumulative
--                    share of the cost the goods left with (return_shares);
--   QUARANTINE_RELEASE  IN value = OUT value, per line: a release changes
--                    where stock is, never what it is worth;
--   WRITE_OFF        at average cost: the ledger's ordinary issue, no stated
--                    value to check.
CREATE OR REPLACE FUNCTION dmg_value_must_follow_rule() RETURNS TRIGGER AS $$
DECLARE
    h    RECORD;
    ln   RECORD;
    sh   RECORD;
    out_v NUMERIC;
    in_v  NUMERIC;
BEGIN
    SELECT r.kind INTO h FROM damage_report r WHERE r.document_id = NEW.id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    IF h.kind = 'TRANSIT_LOSS' THEN
        FOR ln IN SELECT dl.line_no, dl.transfer_line_id FROM damage_report_line dl
                   WHERE dl.document_id = NEW.id ORDER BY dl.line_no LOOP
            SELECT s.* INTO sh FROM transit_out_shares(ln.transfer_line_id) s
             WHERE s.source_id = NEW.id AND s.line_no = ln.line_no;
            CONTINUE WHEN sh.movement_id IS NULL OR sh.dispatch_value IS NULL;
            IF sh.value <> sh.expected THEN
                RAISE EXCEPTION USING ERRCODE = '23Z02',
                      MESSAGE = format('%s line %s writes off %s out of transit, but its share of the consignment''s dispatched value is %s (cumulative %s of %s dispatched, at %s, counting every receipt and loss in ledger order so the shares sum exactly). A loss is written off at the cost it was dispatched at.',
                                       NEW.serial_no, ln.line_no, sh.value, sh.expected, sh.cum, sh.dispatched, sh.dispatch_value);
            END IF;
        END LOOP;
    ELSIF h.kind = 'CUSTOMER_RETURN' THEN
        FOR ln IN SELECT dl.line_no, dl.delivery_note_line_id FROM damage_report_line dl
                   WHERE dl.document_id = NEW.id ORDER BY dl.line_no LOOP
            SELECT s.* INTO sh FROM return_shares(ln.delivery_note_line_id) s
             WHERE s.source_id = NEW.id AND s.line_no = ln.line_no;
            CONTINUE WHEN sh.movement_id IS NULL OR sh.delivered_value IS NULL;
            IF sh.value <> sh.expected THEN
                RAISE EXCEPTION USING ERRCODE = '23Z02',
                      MESSAGE = format('%s line %s brings back %s, but its share of the value the goods left with is %s (cumulative %s of %s delivered, at %s, counting every return in ledger order so the shares sum exactly). Returns are valued at the cost they left with and can never bring back more.',
                                       NEW.serial_no, ln.line_no, sh.value, sh.expected, sh.cum, sh.delivered, sh.delivered_value);
            END IF;
        END LOOP;
    ELSIF h.kind = 'QUARANTINE_RELEASE' THEN
        FOR ln IN SELECT dl.line_no FROM damage_report_line dl WHERE dl.document_id = NEW.id ORDER BY dl.line_no LOOP
            SELECT m.value INTO out_v
              FROM transaction_ticket t JOIN ticket_line l ON l.ticket_id = t.document_id AND l.line_no = ln.line_no
              JOIN stock_movement m ON m.ticket_line_id = l.id AND m.reverses_movement_id IS NULL
             WHERE t.source_document_id = NEW.id AND t.movement_type = 'TRANSFER_OUT';
            SELECT m.value INTO in_v
              FROM transaction_ticket t JOIN ticket_line l ON l.ticket_id = t.document_id AND l.line_no = ln.line_no
              JOIN stock_movement m ON m.ticket_line_id = l.id AND m.reverses_movement_id IS NULL
             WHERE t.source_document_id = NEW.id AND t.movement_type = 'TRANSFER_IN';
            CONTINUE WHEN out_v IS NULL OR in_v IS NULL;
            IF in_v <> out_v THEN
                RAISE EXCEPTION USING ERRCODE = '23Z02',
                      MESSAGE = format('%s line %s left quarantine at %s but enters the sellable location at %s. A release changes where stock is, never what it is worth.',
                                       NEW.serial_no, ln.line_no, out_v, in_v);
            END IF;
        END LOOP;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER document_dmg_value_rule
    AFTER UPDATE ON document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION dmg_value_must_follow_rule();

-- ---------------------------------------------------------------------
-- 6. Tickets and ledger, widened for DMG (with document_support_link, as
-- CLAUDE.md requires: a module that moves stock widens both together).
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW document_support_link AS
SELECT t.document_id AS supported_id, t.source_document_id AS supporting_id
  FROM transaction_ticket t WHERE t.source_document_id IS NOT NULL
UNION ALL
SELECT n.document_id, n.authorization_id
  FROM delivery_note n
UNION ALL
SELECT r.document_id, r.transfer_id
  FROM transfer_receipt r
UNION ALL
SELECT d.document_id, d.transfer_id
  FROM damage_report d WHERE d.transfer_id IS NOT NULL
UNION ALL
SELECT d.document_id, d.delivery_note_id
  FROM damage_report d WHERE d.delivery_note_id IS NOT NULL;

-- One ticket per (source, movement type) for write-off/loss and return
-- legs, as for deliveries and transfers: a report posts once.
CREATE UNIQUE INDEX transaction_ticket_one_damage_or_return_per_source
    ON transaction_ticket (source_document_id, movement_type)
    WHERE movement_type IN ('DAMAGE', 'RETURN') AND source_document_id IS NOT NULL;

-- A release's two legs are TRANSFER_OUT and TRANSFER_IN with a DMG source;
-- the existing one-leg-per-source index (V13) already covers them.

CREATE OR REPLACE FUNCTION ticket_guard() RETURNS TRIGGER AS $$
DECLARE
    tdoc RECORD;
    src  RECORD;
    grn  RECORD;
    dao  RECORD;
    trf  RECORD;
    dmg  RECORD;
    dmg_ok BOOLEAN;
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
    ELSIF src.code = 'DMG' THEN
        SELECT d.kind, d.from_location_id, d.to_location_id, d.customs_reference INTO dmg
          FROM damage_report d WHERE d.document_id = NEW.source_document_id;
        dmg_ok := CASE dmg.kind
                WHEN 'WRITE_OFF' THEN
                    (NEW.movement_type = 'DAMAGE' AND NEW.direction = 'OUT'
                     AND NEW.from_location_id IS NOT DISTINCT FROM dmg.from_location_id AND NEW.to_location_id IS NULL)
                WHEN 'TRANSIT_LOSS' THEN
                    (NEW.movement_type = 'DAMAGE' AND NEW.direction = 'OUT'
                     AND NEW.from_location_id IS NOT DISTINCT FROM dmg.from_location_id AND NEW.to_location_id IS NULL)
                WHEN 'CUSTOMER_RETURN' THEN
                    (NEW.movement_type = 'RETURN' AND NEW.direction = 'IN'
                     AND NEW.to_location_id IS NOT DISTINCT FROM dmg.to_location_id AND NEW.from_location_id IS NULL)
                WHEN 'QUARANTINE_RELEASE' THEN
                    ((NEW.movement_type = 'TRANSFER_OUT' AND NEW.direction = 'OUT'
                      AND NEW.from_location_id IS NOT DISTINCT FROM dmg.from_location_id AND NEW.to_location_id IS NULL)
                  OR (NEW.movement_type = 'TRANSFER_IN' AND NEW.direction = 'IN'
                      AND NEW.to_location_id IS NOT DISTINCT FROM dmg.to_location_id AND NEW.from_location_id IS NULL))
                ELSE FALSE END;
        IF NEW.customs_reference IS DISTINCT FROM dmg.customs_reference OR NOT COALESCE(dmg_ok, FALSE) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s does not match the %s report %s: write-offs and losses are DAMAGE out of the report''s location, a return is a RETURN into quarantine, a release is TRANSFER_OUT of quarantine and TRANSFER_IN to the sellable location, each under the report''s customs reference.', tdoc.serial_no, dmg.kind, src.serial_no);
        END IF;
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s cannot be supported by %s: a ticket answers only to a goods received note, a delivery note, a transfer, a transfer receipt or a return and damage report. An authorization or any other document does not move stock by itself.', tdoc.serial_no, src.serial_no);
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
    dd RECORD;
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
    ELSIF tk.source_code = 'DMG' THEN
        SELECT * INTO dd FROM damage_report_line
         WHERE document_id = tk.source_document_id AND line_no = NEW.line_no;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s has no line %s on %s. A report ticket carries exactly the lines that were approved.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
        -- The bin on the leaving side for OUT legs, on the arriving side for IN legs.
        want_bin := CASE WHEN tk.mtype IN ('DAMAGE', 'TRANSFER_OUT') THEN dd.storage_bin_id ELSE dd.to_storage_bin_id END;
        IF (dd.item_id, dd.uom_id, dd.quantity, dd.qty_base_uom, want_bin)
           IS DISTINCT FROM
           (NEW.item_id, NEW.uom_id, NEW.quantity, NEW.qty_base_uom, NEW.storage_bin_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s differs from line %s of %s in item, unit, quantity or bin. Stock moves exactly as the approved report says.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s has no receipt, delivery, transfer or transfer receipt behind it, so it can carry no lines.', tk.serial_no);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

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
                    WHERE s.id = tk.source_document_id AND st.code IN ('GRN', 'DN', 'TRF', 'TRR', 'DMG')) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s answers to no goods received note, delivery note, transfer, transfer receipt or return and damage report, so its stock does not move. An authorization alone lets nothing leave.', tdoc.serial_no);
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
        RAISE EXCEPTION 'V14 cannot apply: the damage rights leave someone in conflict. %', msg;
    END IF;
END $$;
