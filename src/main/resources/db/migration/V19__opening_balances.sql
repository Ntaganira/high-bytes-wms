-- =====================================================================
-- V19 — Opening balances: the stock that was already there at cutover.
--
-- SRS: FR-MD-01..04 (item master), FR-IN-01 (stock enters only on a
-- document), FR-CNT-01..03 (the figures Finance reconciles from).
--
-- HIGH BYTES is replacing QuickBooks, not configuring it, so on the day
-- the system goes live every warehouse already holds stock. Invariant 2
-- says no stock moves without a document, and stock_movement.document_id
-- is NOT NULL, so that stock cannot be loaded by inserting ledger rows:
-- it needs a document, and the document needs signatures.
--
-- This is the highest-risk data entry the system will ever take. Every
-- figure the Board is shown afterwards is measured from it, and unlike a
-- receipt there is no supplier, no invoice and no customs entry standing
-- behind it — only somebody's word that the stock was on the floor. So it
-- carries four mandatory signatures and two structural controls no other
-- document needs:
--
--   1. At most one posted opening balance per LOCATION, ever. Enforced by
--      a partial unique index rather than a trigger, because two
--      concurrent postings would each find the other uncommitted and both
--      pass a COUNT(*) check. An index cannot be raced.
--
--   2. An opening balance may not post once anything else has moved stock
--      at its branch. Without this, stock could be injected under months
--      of trading with no supplier and no customs trail, and every figure
--      above it would silently stop meaning anything. Other opening
--      balances are excluded, so a branch holding stock in a main store
--      and a bonded store loads each; a branch that has started trading
--      loads nothing. Judged at commit, so the document's own movements
--      are in and visible.
--
-- Both are here rather than in Java because they are what makes the
-- cutover auditable, and a bug in a service must not be able to bypass
-- them.
--
-- One location per document, as every other subtype does: ticket_guard
-- judges a ticket against the location its source names, so a document
-- spanning locations could not raise a ticket it would accept.
--
-- The chain is deliberately NOT effective-dated into the 2027
-- restructuring. An opening balance is a single event in the life of a
-- location, not an operational flow, so one open-ended definition applies
-- whenever go-live happens. A chain ending on 2026-12-31 would leave the
-- module unusable with no chain in force if go-live slipped into 2027.
-- Confirm the four signatories with the client before go-live.
-- =====================================================================

SET LOCAL highbytes.migration = 'on';

-- ---------------------------------------------------------------------
-- 1. The document type. Sorted before the receipt: at a location it is
--    the first document that will ever exist.
-- ---------------------------------------------------------------------
-- form_reference is NULL: the Controlled Forms Manual has no form for a
-- cutover, because the client has never done one. If Operations issues a
-- form number, set it in a later migration.
INSERT INTO document_type (code, name, form_reference, requires_approval, moves_stock, sort_order)
VALUES ('OPB', 'Opening Stock Balance', NULL, TRUE, TRUE, 5);

-- ---------------------------------------------------------------------
-- 2. The ticket movement type. Widening a CHECK to admit one more value,
--    never relaxing it: an opening balance is not an ADJUSTMENT, and
--    conflating the two would bury the cutover inside the count
--    variances on every report that groups by movement type.
-- ---------------------------------------------------------------------
ALTER TABLE transaction_ticket DROP CONSTRAINT transaction_ticket_movement_type_check;
ALTER TABLE transaction_ticket
    ADD CONSTRAINT transaction_ticket_movement_type_check
    CHECK (movement_type IN ('RECEIPT','TRANSFER_OUT','TRANSFER_IN',
                             'ISSUE_TO_VAN','DELIVERY','RETURN',
                             'DAMAGE','CUT_CONSUME','CUT_OUTPUT',
                             'ADJUSTMENT','OPENING'));

-- ---------------------------------------------------------------------
-- 3. The subtype.
-- ---------------------------------------------------------------------
CREATE TABLE opening_balance (
    document_id     UUID PRIMARY KEY REFERENCES document(id),

    -- Denormalised from the document so the index below can be a plain
    -- partial unique index; the header guard keeps the two in step.
    branch_id       UUID NOT NULL REFERENCES branch(id),

    -- The one place this baseline loads. A branch with a main store and a
    -- bonded store raises one for each.
    location_id     UUID NOT NULL REFERENCES location(id),

    -- The date the figures represent — the QuickBooks cutover date. The
    -- document itself is dated the day it is keyed (document_open_guard
    -- forces that), and the two are usually different: figures are struck
    -- at close of business and loaded the next morning.
    as_at_date      DATE NOT NULL,

    -- Where the figures came from, so a reader in 2029 knows.
    source_system   VARCHAR(40) NOT NULL DEFAULT 'QuickBooks'
                    CONSTRAINT opening_source_system_not_blank CHECK (btrim(source_system) <> ''),

    -- What was relied on: the report, its run date, who struck it. Not
    -- optional. A baseline nobody can trace back is not a baseline.
    basis_note      VARCHAR(400) NOT NULL
                    CONSTRAINT opening_basis_note_not_blank CHECK (btrim(basis_note) <> ''),

    -- Duty-suspended stock is accountable to Customs from the moment the
    -- system says it is there, exactly as on a receipt (V11). Required by
    -- the trigger below when the place is bonded, which needs the
    -- location and branch a CHECK cannot see. ticket_guard compares the
    -- ticket's reference with this one.
    customs_reference VARCHAR(80)
                    CONSTRAINT opening_customs_reference_not_blank
                    CHECK (customs_reference IS NULL OR btrim(customs_reference) <> ''),

    -- Set when the document posts; the partial unique index is built on
    -- it. Never written by hand — the header guard refuses that.
    is_posted       BOOLEAN NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE opening_balance IS
  'Subtype of document (type OPB). The stock a place already held when the system replaced QuickBooks. Its content is what the signatories attest to, so it changes only while the document is a draft. At most one may be posted per location, and none may post once anything other than an opening balance has moved stock at the branch.';

-- One posted opening balance per location, ever.
CREATE UNIQUE INDEX opening_balance_one_posted_per_location
    ON opening_balance (location_id) WHERE is_posted;

CREATE INDEX opening_balance_branch ON opening_balance (branch_id);

CREATE TABLE opening_balance_line (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id           UUID NOT NULL REFERENCES opening_balance(document_id),
    line_no               SMALLINT NOT NULL CHECK (line_no > 0),
    item_id               UUID NOT NULL REFERENCES item(id),
    uom_id                UUID NOT NULL REFERENCES uom(id),
    quantity              NUMERIC(16,3) NOT NULL CHECK (quantity > 0),
    -- What the ledger will carry: converted to the item's base unit.
    qty_base_uom          NUMERIC(16,3) NOT NULL CHECK (qty_base_uom > 0),

    -- The carrying cost per base unit brought over from the old books.
    -- Zero is allowed: stock written down to nothing is still stock, and
    -- refusing zero would push people into inventing a token value.
    unit_cost             NUMERIC(18,4) NOT NULL CHECK (unit_cost >= 0),

    storage_bin_id        UUID REFERENCES storage_bin(id),

    -- Measured on the cutover count, as on a receipt (FR-IN-03).
    -- Required for glass by the trigger below, which can see the item
    -- where a CHECK cannot.
    measured_thickness_mm NUMERIC(6,2)
                          CONSTRAINT opening_line_thickness_positive
                          CHECK (measured_thickness_mm IS NULL OR measured_thickness_mm > 0),

    note                  VARCHAR(240),

    UNIQUE (document_id, line_no)
);

COMMENT ON TABLE opening_balance_line IS
  'One item, at the quantity and carrying cost the old books held it at, in the place its document names.';

CREATE INDEX opening_balance_line_item ON opening_balance_line (item_id);

-- ---------------------------------------------------------------------
-- 4. The header: draft only, the branch follows the document, the
--    location belongs to that branch, bonded stock names its customs
--    entry, and the figures are not dated into the future. All here
--    because a CHECK cannot join, and kigali_today() is not immutable so
--    it cannot appear in one either.
--
--    The one change allowed to a non-draft row is posting setting
--    is_posted and nothing else.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION opening_header_guard() RETURNS TRIGGER AS $$
DECLARE
    doc RECORD;
    loc RECORD;
BEGIN
    SELECT d.branch_id, d.status, d.serial_no, b.is_bonded AS branch_bonded
      INTO doc
      FROM document d JOIN branch b ON b.id = d.branch_id
     WHERE d.id = NEW.document_id;

    IF TG_OP = 'UPDATE' AND NEW.is_posted AND NOT OLD.is_posted THEN
        IF doc.status <> 'POSTED' THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = 'An opening balance is marked posted only when its document posts.';
        END IF;
        IF (NEW.branch_id, NEW.location_id, NEW.as_at_date, NEW.source_system,
            NEW.basis_note, NEW.customs_reference)
           IS DISTINCT FROM
           (OLD.branch_id, OLD.location_id, OLD.as_at_date, OLD.source_system,
            OLD.basis_note, OLD.customs_reference) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = 'Posting marks an opening balance posted; it does not change what it says.';
        END IF;
        RETURN NEW;
    END IF;

    IF doc.status <> 'DRAFT' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is %s. What an opening balance says is what four people signed; it changes only while it is a draft.',
                               doc.serial_no, doc.status);
    END IF;
    IF NEW.is_posted THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'Whether an opening balance is posted follows its document, and is not set by hand.';
    END IF;
    IF NEW.branch_id <> doc.branch_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'An opening balance belongs to the branch of its document.';
    END IF;

    IF NEW.as_at_date > kigali_today() THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('The figures are dated %s, which is still to come. An opening balance carries what was on the floor, not what is expected.',
                               NEW.as_at_date);
    END IF;

    SELECT l.branch_id, l.code, l.is_active, l.is_bonded, l.location_type
      INTO loc FROM location l WHERE l.id = NEW.location_id;
    IF loc.branch_id <> NEW.branch_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Location %s is not at the branch %s is raised for.', loc.code, doc.serial_no);
    END IF;
    IF NOT loc.is_active THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Location %s is deactivated and cannot take stock.', loc.code);
    END IF;
    -- Stock in transit belongs to a transfer that is under way, and there
    -- is none of that before the system starts.
    IF loc.location_type = 'TRANSIT' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is a transit location. Stock is in transit because a transfer put it there, and no transfer precedes the opening balance.', loc.code);
    END IF;
    IF (loc.is_bonded OR doc.branch_bonded) AND NEW.customs_reference IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s loads bonded stock and needs a customs reference. Duty-suspended goods are accountable to Customs from the moment the system says they are there.',
                               loc.code);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER opening_balance_header
    BEFORE INSERT OR UPDATE ON opening_balance
    FOR EACH ROW EXECUTE FUNCTION opening_header_guard();

-- ---------------------------------------------------------------------
-- 5. Line content: draft only, the base quantity must follow from what
--    was entered, the bin must belong to the document's location, and
--    glass needs its measured thickness.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION opening_line_guard() RETURNS TRIGGER AS $$
DECLARE
    doc    RECORD;
    it     RECORD;
    bin    RECORD;
    factor NUMERIC;
BEGIN
    SELECT d.serial_no, d.status, o.location_id
      INTO doc
      FROM opening_balance o JOIN document d ON d.id = o.document_id
     WHERE o.document_id = COALESCE(NEW.document_id, OLD.document_id);

    IF doc.status <> 'DRAFT' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is %s, so its lines are fixed. An opening balance that has been signed is corrected by a reversing document, never edited.',
                               doc.serial_no, doc.status);
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    SELECT i.item_code, i.product_type, i.base_uom_id, i.is_active
      INTO it FROM item i WHERE i.id = NEW.item_id;
    IF NOT it.is_active THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Item %s is deactivated. Stock on the floor at cutover needs an active item to be carried under.', it.item_code);
    END IF;

    IF NEW.uom_id = it.base_uom_id THEN
        factor := 1;
    ELSE
        SELECT c.factor_to_base INTO factor
          FROM item_uom_conversion c WHERE c.item_id = NEW.item_id AND c.uom_id = NEW.uom_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Item %s has no conversion from this unit to its base unit, so the stock quantity cannot be derived.', it.item_code);
        END IF;
    END IF;
    IF round(NEW.quantity * factor, 3) <> NEW.qty_base_uom THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Line %s of %s: %s x %s is %s in the base unit, not %s. The ledger carries the base quantity, so it must follow from what was entered.',
                               NEW.line_no, doc.serial_no, NEW.quantity, factor,
                               round(NEW.quantity * factor, 3), NEW.qty_base_uom);
    END IF;

    -- Glass is verified at the gate by its thickness, so glass carried in
    -- without one can never be checked against later.
    IF it.product_type = 'GLASS' AND NEW.measured_thickness_mm IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Line %s of %s carries glass (%s) and needs its measured thickness. Glass leaving the gate is checked against the thickness it came in at.',
                               NEW.line_no, doc.serial_no, it.item_code);
    END IF;

    IF NEW.storage_bin_id IS NOT NULL THEN
        SELECT sb.location_id, sb.bin_code, sb.is_active
          INTO bin FROM storage_bin sb WHERE sb.id = NEW.storage_bin_id;
        IF bin.location_id <> doc.location_id THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Bin %s is not in the location %s loads into. A bin belongs to one location.',
                                   bin.bin_code, doc.serial_no);
        END IF;
        IF NOT bin.is_active THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Bin %s is deactivated and cannot take stock.', bin.bin_code);
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER opening_balance_line_content
    BEFORE INSERT OR UPDATE OR DELETE ON opening_balance_line
    FOR EACH ROW EXECUTE FUNCTION opening_line_guard();

-- ---------------------------------------------------------------------
-- 6. A sheet with no header or no lines is not worth anyone's signature.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION opening_submit_guard() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'OPB') THEN
        IF NOT EXISTS (SELECT 1 FROM opening_balance WHERE document_id = NEW.id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s has no location or cutover date yet and cannot be submitted.', NEW.serial_no);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM opening_balance_line WHERE document_id = NEW.id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s has no lines. An opening balance of nothing is not a baseline; a place that held no stock needs none loaded.', NEW.serial_no);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_opening_submit
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (OLD.status = 'DRAFT' AND NEW.status = 'PENDING')
    EXECUTE FUNCTION opening_submit_guard();

-- ---------------------------------------------------------------------
-- 7. The three places that list the sources the ledger will act on, each
--    widened by one. CLAUDE.md: a new stock-moving document widens these
--    together, or its tickets are refused.
--
--    Each function is restated whole, as V15 and V18 restated them, with
--    only the OPB branch added and the refusal messages extended. The
--    existing branches are reproduced exactly from the applied V18
--    definitions, so this migration changes nothing about GRN, DN, TRF,
--    TRR, DMG, CNT or CUT.
--
--    An opening balance's ticket is an OPENING into the place the
--    document names, IN, with no from-location (the stock comes from
--    nowhere: that is what makes it an opening balance) and under the
--    document's customs reference.
--
--    document_support_link needs no change: its first branch already
--    links a ticket to its source_document_id, which is how the ledger
--    finds the signatures behind an opening balance.
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.ticket_guard()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    tdoc RECORD;
    src  RECORD;
    grn  RECORD;
    dao  RECORD;
    trf  RECORD;
    dmg  RECORD;
    dmg_ok BOOLEAN;
    cnt  RECORD;
    cut  RECORD;
    opb  RECORD;
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
    -- (a RECEIPT), a DN (a DELIVERY), a TRF (a dispatch leg), a TRR (a
    -- receipt leg), a DMG (a write-off, loss, return or release), a CNT
    -- (an ADJUSTMENT) or a CUT (its CUT_CONSUME and CUT_OUTPUT legs). Any
    -- other source, or none, is refused here as well as
    -- at the ledger. Each later module widens this list alongside its
    -- document_support_link entry.
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
        -- The note's authority: its delivery authorization or its cutting order (V18).
        SELECT na.location_id, na.customs_reference INTO dao
          FROM delivery_note_authority na
         WHERE na.note_id = NEW.source_document_id;
        IF NEW.movement_type <> 'DELIVERY' OR NEW.direction <> 'OUT'
           OR NEW.from_location_id IS DISTINCT FROM dao.location_id
           OR NEW.to_location_id IS NOT NULL
           OR NEW.customs_reference IS DISTINCT FROM dao.customs_reference THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s must be a DELIVERY out of the location and under the customs reference the authorization or cutting order behind %s records.', tdoc.serial_no, src.serial_no);
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
    ELSIF src.code = 'CNT' THEN
        SELECT c.location_id, c.customs_reference INTO cnt
          FROM stock_count c WHERE c.document_id = NEW.source_document_id;
        IF NEW.customs_reference IS DISTINCT FROM cnt.customs_reference
           OR NEW.movement_type <> 'ADJUSTMENT'
           OR NOT ((NEW.direction = 'OUT' AND NEW.from_location_id IS NOT DISTINCT FROM cnt.location_id
                    AND NEW.to_location_id IS NULL)
                OR (NEW.direction = 'IN' AND NEW.to_location_id IS NOT DISTINCT FROM cnt.location_id
                    AND NEW.from_location_id IS NULL)) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s must be an ADJUSTMENT of count %s: OUT of the counted location for what was short, IN to it for what was found, under the count''s customs reference.', tdoc.serial_no, src.serial_no);
        END IF;
    ELSIF src.code = 'CUT' THEN
        SELECT c.location_id, c.customs_reference INTO cut
          FROM cutting_order c WHERE c.document_id = NEW.source_document_id;
        IF NEW.customs_reference IS DISTINCT FROM cut.customs_reference
           OR NOT ((NEW.movement_type = 'CUT_CONSUME' AND NEW.direction = 'OUT'
                    AND NEW.from_location_id IS NOT DISTINCT FROM cut.location_id AND NEW.to_location_id IS NULL)
                OR (NEW.movement_type = 'CUT_OUTPUT' AND NEW.direction = 'IN'
                    AND NEW.to_location_id IS NOT DISTINCT FROM cut.location_id AND NEW.from_location_id IS NULL)) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s must be a leg of cutting order %s: CUT_CONSUME out of its place for the sheets, or CUT_OUTPUT into it for the pieces and off-cuts, under its customs reference.', tdoc.serial_no, src.serial_no);
        END IF;
    ELSIF src.code = 'OPB' THEN
        SELECT o.location_id, o.customs_reference INTO opb
          FROM opening_balance o WHERE o.document_id = NEW.source_document_id;
        IF NEW.movement_type <> 'OPENING' OR NEW.direction <> 'IN'
           OR NEW.to_location_id IS DISTINCT FROM opb.location_id
           OR NEW.from_location_id IS NOT NULL
           OR NEW.customs_reference IS DISTINCT FROM opb.customs_reference THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s must be an OPENING into the location and under the customs reference %s records. Opening stock comes from nowhere, so the ticket names no from-location.', tdoc.serial_no, src.serial_no);
        END IF;
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s cannot be supported by %s: a ticket answers only to a goods received note, a delivery note, a transfer, a transfer receipt, a return and damage report, a stock count, a cutting order or an opening balance. An authorization or any other document does not move stock by itself.', tdoc.serial_no, src.serial_no);
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION public.ticket_line_guard()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    tk RECORD;
    gl RECORD;
    dl RECORD;
    xl RECORD;
    want_bin UUID;
    dd RECORD;
    cl RECORD;
    ct RECORD;
    ol RECORD;
BEGIN
    SELECT d.serial_no, d.status, t.source_document_id, t.movement_type AS mtype, t.direction AS tdir,
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
    ELSIF tk.source_code = 'CNT' THEN
        SELECT l.item_id, l.storage_bin_id, l.variance_qty, i.base_uom_id INTO cl
          FROM stock_count_line l JOIN item i ON i.id = l.item_id
         WHERE l.document_id = tk.source_document_id AND l.line_no = NEW.line_no;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s has no line %s on count %s. An adjustment carries exactly the lines that were counted.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
        -- A surplus comes IN, a shortage goes OUT; a line that agrees with the
        -- book adjusts nothing.
        IF cl.variance_qty IS NULL OR cl.variance_qty = 0 OR (tk.tdir = 'IN') <> (cl.variance_qty > 0) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s adjusts line %s of count %s the wrong way: its variance is %s, so it is %s.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial, COALESCE(cl.variance_qty, 0),
                                   CASE WHEN COALESCE(cl.variance_qty, 0) = 0 THEN 'not adjusted at all'
                                        WHEN cl.variance_qty > 0 THEN 'an adjustment IN' ELSE 'an adjustment OUT' END);
        END IF;
        IF (cl.item_id, cl.base_uom_id, abs(cl.variance_qty), abs(cl.variance_qty), cl.storage_bin_id)
           IS DISTINCT FROM
           (NEW.item_id, NEW.uom_id, NEW.quantity, NEW.qty_base_uom, NEW.storage_bin_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s differs from line %s of count %s in item, unit, quantity or bin. An adjustment moves exactly the variance that was approved, in the item''s base unit, in the counted bin.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
    ELSIF tk.source_code = 'CUT' THEN
        -- The sheets leave from their bin; what is cut comes in unbinned. Both
        -- in the item's own unit, whole.
        IF tk.mtype = 'CUT_CONSUME' THEN
            SELECT s.item_id, i.base_uom_id, s.quantity, s.storage_bin_id INTO ct
              FROM cutting_order_sheet s JOIN item i ON i.id = s.item_id
             WHERE s.document_id = tk.source_document_id AND s.line_no = NEW.line_no;
        ELSE
            SELECT o.item_id, i.base_uom_id, o.quantity, NULL::uuid AS storage_bin_id INTO ct
              FROM cutting_order_output o JOIN item i ON i.id = o.item_id
             WHERE o.document_id = tk.source_document_id AND o.line_no = NEW.line_no;
        END IF;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s has no line %s on cutting order %s. A cutting ticket carries exactly the lines that were released.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
        IF (ct.item_id, ct.base_uom_id, ct.quantity, ct.quantity, ct.storage_bin_id)
           IS DISTINCT FROM
           (NEW.item_id, NEW.uom_id, NEW.quantity, NEW.qty_base_uom, NEW.storage_bin_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s differs from line %s of cutting order %s in item, unit, quantity or bin. What is cut is exactly what was released.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
    ELSIF tk.source_code = 'OPB' THEN
        SELECT * INTO ol FROM opening_balance_line
         WHERE document_id = tk.source_document_id AND line_no = NEW.line_no;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s has no line %s on %s. An opening ticket carries exactly the lines that were signed for.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
        IF (ol.item_id, ol.uom_id, ol.quantity, ol.qty_base_uom, ol.storage_bin_id)
           IS DISTINCT FROM
           (NEW.item_id, NEW.uom_id, NEW.quantity, NEW.qty_base_uom, NEW.storage_bin_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s differs from line %s of %s in item, unit, quantity or bin. The stock loaded is what the signatories attested to.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, tk.source_serial);
        END IF;
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s has no receipt, delivery, transfer, transfer receipt, report, count, cutting order or opening balance behind it, so it can carry no lines.', tk.serial_no);
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION public.stock_movement_needs_approved_document()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
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
                    WHERE s.id = tk.source_document_id AND st.code IN ('GRN', 'DN', 'TRF', 'TRR', 'DMG', 'CNT', 'CUT', 'OPB')) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s answers to no goods received note, delivery note, transfer, transfer receipt, return and damage report, stock count, cutting order or opening balance, so its stock does not move. An authorization alone lets nothing leave.', tdoc.serial_no);
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
    -- to its DAO or its CUT, a TRR to its TRF): it posts stock, so it must have been
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
$function$;

-- ---------------------------------------------------------------------
-- 8. The two controls that make the cutover auditable, judged at commit
--    so the document's own ledger rows are in and visible.
--
--    (a) Nothing but another opening balance has moved stock at this
--        branch.
--    (b) Every line reached the ledger — the mirror of every module.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION opening_posted_guard() RETURNS TRIGGER AS $$
DECLARE
    ol    RECORD;
    stray RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'OPB') THEN
        RETURN NULL;
    END IF;

    SELECT d.serial_no, m.business_date INTO stray
      FROM stock_movement m
      JOIN document d        ON d.id = m.document_id
      LEFT JOIN transaction_ticket t ON t.document_id = m.document_id
      LEFT JOIN document src ON src.id = t.source_document_id
      LEFT JOIN document_type st ON st.id = src.document_type_id
     WHERE m.branch_id = NEW.branch_id
       AND COALESCE(st.code, '') <> 'OPB'
     ORDER BY m.id
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s cannot be posted: stock has already moved at this branch (%s, on %s). An opening balance is the first entry in the ledger at a branch, or it is not an opening balance — loading one underneath existing trading would leave every figure above it measuring from nothing. Correct the stock with a count adjustment instead.',
                               NEW.serial_no, stray.serial_no, stray.business_date);
    END IF;

    FOR ol IN SELECT line_no FROM opening_balance_line WHERE document_id = NEW.id ORDER BY line_no LOOP
        IF NOT EXISTS (
               SELECT 1
                 FROM transaction_ticket t
                 JOIN ticket_line tl   ON tl.ticket_id = t.document_id AND tl.line_no = ol.line_no
                 JOIN stock_movement m ON m.ticket_line_id = tl.id AND m.reverses_movement_id IS NULL
                WHERE t.source_document_id = NEW.id AND t.movement_type = 'OPENING') THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is posted but line %s never reached the ledger. An opening balance is posted together with its ticket and stock movements, or not at all.',
                                   NEW.serial_no, ol.line_no);
        END IF;
    END LOOP;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER document_opening_posted
    AFTER UPDATE ON document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION opening_posted_guard();

-- is_posted follows the document immediately, so the unique index refuses
-- a second posting at the same location when it is tried rather than at
-- commit. Two concurrent postings: the second blocks on the index, then
-- fails.
CREATE OR REPLACE FUNCTION opening_mark_posted() RETURNS TRIGGER AS $$
BEGIN
    UPDATE opening_balance SET is_posted = TRUE
     WHERE document_id = NEW.id AND NOT is_posted;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_opening_mark_posted
    AFTER UPDATE ON document
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION opening_mark_posted();

-- ---------------------------------------------------------------------
-- 9. Rights. Permission codes, never role names, and nothing to
--    SYS_ADMIN: whoever manages access takes no part in the cutover
--    (invariant 8).
-- ---------------------------------------------------------------------
-- duty is a generated column (V9): module 'admin' administers, VIEW
-- reads, CONFIGURE configures, everything else transacts. So these five
-- become READ for the first and TRANSACT for the rest, which is what the
-- segregation rules must see.
INSERT INTO permission (code, module, action, description) VALUES
    ('opening.view',    'opening', 'VIEW',    'See opening stock balances'),
    ('opening.create',  'opening', 'CREATE',  'Raise an opening stock balance and enter its lines'),
    ('opening.verify',  'opening', 'VERIFY',  'Verify an opening stock balance at a chain step'),
    ('opening.approve', 'opening', 'APPROVE', 'Approve an opening stock balance at a chain step'),
    ('opening.post',    'opening', 'POST',    'Post an approved opening stock balance to the ledger');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM (VALUES
        -- Everyone who will reconcile against the baseline can read it.
        ('WH_MANAGER',       'opening.view'),
        ('ASST_WH_MANAGER',  'opening.view'),
        ('INV_TX_OFFICER',   'opening.view'),
        ('FINANCE',          'opening.view'),
        ('INTERNAL_CTRL',    'opening.view'),
        ('DIR_SUPPLY_CHAIN', 'opening.view'),
        ('MANAGING_DIR',     'opening.view'),
        -- The Warehouse Manager counts the floor and raises the sheet.
        ('WH_MANAGER',       'opening.create'),
        -- Verified independently of whoever counted.
        ('INV_TX_OFFICER',   'opening.verify'),
        ('INTERNAL_CTRL',    'opening.verify'),
        -- The baseline becomes the Board's opening position, so the
        -- Managing Director signs for it.
        ('MANAGING_DIR',     'opening.approve'),
        -- Finance posts, as it posts receipts: the books are its charge,
        -- and nobody posts what they signed.
        ('FINANCE',          'opening.post')
       ) AS v(role_code, permission_code)
  JOIN role r       ON r.code = v.role_code
  JOIN permission p ON p.code = v.permission_code;

-- ---------------------------------------------------------------------
-- 10. The chain. One definition, open-ended: see the header comment on
--     why this one does not switch on 1 January 2027.
--
--     Warehouse Manager counts and attests  ->  Inventory Transactions
--     Officer verifies independently  ->  Internal Controller verifies
--     the control  ->  Managing Director approves the opening position.
-- ---------------------------------------------------------------------
WITH wd AS (
    INSERT INTO workflow_definition (document_type_id, version, effective_from, effective_to, basis, notes)
    SELECT id, 1, DATE '2026-07-01', NULL, 'POLICY_2026',
           'Cutover from QuickBooks. Four mandatory signatures: the baseline every later figure is measured from, with no supplier, invoice or customs entry standing behind it. Signatories to be confirmed by the client before go-live.'
      FROM document_type WHERE code = 'OPB'
    RETURNING id
)
INSERT INTO workflow_step (workflow_definition_id, sequence_no, required_role_id, action_label, is_mandatory, blocks_release, escalate_after_hours)
SELECT wd.id, v.seq, r.id, v.act, TRUE, TRUE, v.esc
  FROM wd
  JOIN (VALUES
        (1, 'WH_MANAGER',     'PREPARE', 48),
        (2, 'INV_TX_OFFICER', 'VERIFY',  24),
        (3, 'INTERNAL_CTRL',  'VERIFY',  24),
        (4, 'MANAGING_DIR',   'APPROVE', 48)
       ) AS v(seq, role_code, act, esc) ON TRUE
  JOIN role r ON r.code = v.role_code;

-- ---------------------------------------------------------------------
-- 11. The rights placed above are judged for everyone, now, as they will
--     be at commit.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    msg TEXT;
BEGIN
    msg := access_conflict_anywhere();
    IF msg IS NOT NULL THEN
        RAISE EXCEPTION 'V19 cannot apply: the opening-balance rights leave someone in conflict. %', msg;
    END IF;
END $$;
