-- =====================================================================
-- V21 — Reversing documents: what posted stock did, undone by its mirror
--
-- SRS: FR-IN-10 (the ledger), FR-WF-03..07 (approval), Inventory Policy
-- s.9 (corrections are documented, never overwritten). Invariants 1 and 4:
-- the ledger is append-only and a posted document is never altered or
-- cancelled once it has moved stock. Until now that made a mistake
-- permanent. A reversing document (REV) is the correction both invariants
-- point to: it keeps the original exactly as it was and records, beside
-- it, the opposite of every movement it made.
--
-- WHAT IT REVERSES (decided 5 October 2026). A whole posted document, never
-- part of one: every movement the original made gets one mirror, with the
-- same item, place, bin, quantity and value, in the other direction, and
-- naming the movement it undoes (stock_movement.reverses_movement_id, which
-- V4 provided and nothing has written until now). A document that was
-- partly wrong is reversed whole and raised again correctly.
--
-- This first version reverses the documents that move stock at their own
-- place and change no other document's state:
--     GRN   goods received                 RECEIPT IN      -> mirror OUT
--     OPB   opening balance                OPENING IN      -> mirror OUT
--     CNT   count adjustment               ADJUSTMENT both -> mirrors both
--     DMG   write-off                      DAMAGE OUT      -> mirror IN
--     DMG   quarantine release             TRANSFER_OUT and TRANSFER_IN,
--                                          both mirrored
-- Not yet: a delivery note (what its authorization may still release), a
-- transfer or its receipt (goods in transit), a cutting order (the pieces
-- and the cost split), and a damage report's transit loss or customer
-- return (what the transfer or delivery note may still give up or take
-- back). Each changes another document's state, so each gets its own rules
-- when its reversal is built.
--
-- THE CHAIN IS A PROPOSAL, NOT A CLIENT DECISION. Who may undo a posted
-- movement is the client's to decide. Until they do:
--     1 PREPARE  Warehouse Manager    (reversal.create)
--     2 VERIFY   Internal Controller  (reversal.verify)
--     3 APPROVE  Managing Director    (reversal.approve)
--     posted by Finance               (reversal.post), who neither raised
--                                     nor signed it (V11)
-- A reversal can make stock disappear from the books as surely as a theft,
-- and present it as a clerical error, so it carries the assurance role and
-- the top approver. One open-ended definition serves both eras: both roles
-- exist after the 2027 restructuring. Recorded in CLAUDE.md's open
-- questions. The Warehouse Manager raises because submitting signs step 1
-- and a step names one role; an error Finance finds is raised by the
-- Warehouse Manager with Finance's finding as the reason.
--
-- INDEPENDENCE FROM THE ORIGINAL (decided 5 October 2026). Whoever raised
-- the original, and whoever posted it, takes no part in its reversal:
-- neither raises it, signs it nor posts it. They made and recorded what is
-- being undone. The original's signers may sign again: the Internal
-- Controller signs every document this reverses, and barring them would
-- make reversal impossible with one person in the role.
--
-- AN OPENING BALANCE MAY BE LOADED AGAIN (decided 5 October 2026), until
-- the branch trades. V19 allowed one posted opening balance per location,
-- ever, and none once anything else had moved stock at the branch. A wrong
-- baseline found before go-live could then be fixed only by dropping the
-- database. Both controls now set aside an opening balance that has been
-- reversed in full (and its reversal's movements), so a corrected one can
-- load. Once anything else has moved stock at the branch, nothing loads,
-- as before: stock found later still arrives by count adjustment.
--
-- WHY HERE. Every rule below is a trigger, constraint or index, because a
-- reversal that a service bug could misdirect would be the most damaging
-- write in the system: it can take stock off the books, put it back on, or
-- hide either. The service asks the same questions first so a refusal
-- carries its reason; the database remains the authority.
--
-- Contents
--    1. The document type and the REVERSAL movement type
--    2. What a reversing document names, and why: document.reverses_document_id
--       and the reversal row
--    3. Independence from the original's raiser and poster
--    4. Tickets: the mirror ticket names the ticket it reverses
--    5. The three places that list the sources the ledger acts on, widened
--    6. The movement is an exact mirror
--    7. Posted means every movement reversed
--    8. The opening balance may load again once reversed, until trading
--    9. The daily close counts a reversal as an adjustment
--   10. Rights
--   11. The chain (a proposal)
--   12. The rights above, judged for everyone
-- =====================================================================

SET LOCAL highbytes.migration = 'on';

-- ---------------------------------------------------------------------
-- 1. The document type, and the movement type its tickets carry.
--
--    form_reference is NULL: the Controlled Forms Manual has no reversing
--    form. If Operations issues one, set it in a later migration.
--
--    REVERSAL is a movement type of its own, never the original's: a
--    reversed receipt is not a delivery, and a report or close that read it
--    as one would show goods leaving that never left.
-- ---------------------------------------------------------------------
INSERT INTO document_type (code, name, form_reference, requires_approval, moves_stock, sort_order)
VALUES ('REV', 'Reversing Document', NULL, TRUE, TRUE, 85);

ALTER TABLE transaction_ticket DROP CONSTRAINT transaction_ticket_movement_type_check;
ALTER TABLE transaction_ticket
    ADD CONSTRAINT transaction_ticket_movement_type_check
    CHECK (movement_type IN ('RECEIPT','TRANSFER_OUT','TRANSFER_IN',
                             'ISSUE_TO_VAN','DELIVERY','RETURN',
                             'DAMAGE','CUT_CONSUME','CUT_OUTPUT',
                             'ADJUSTMENT','OPENING','REVERSAL'));

-- ---------------------------------------------------------------------
-- 2. What a reversing document names, and why.
--
--    The spine has carried document.reverses_document_id since V3, unused
--    and unchecked. It is now the link: set on a REV, and only on a REV,
--    naming a posted document of a type this version reverses, at the same
--    branch, that moved stock. The lifecycle guard (V11) already fixes it
--    once the document leaves DRAFT.
--
--    One live reversal per original: a partial unique index on the
--    document itself, so two people raising one at the same moment cannot
--    both succeed. A cancelled or rejected reversal steps aside; a posted
--    one never does, since a posted REV can be neither.
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX document_one_live_reversal
    ON document (reverses_document_id)
    WHERE reverses_document_id IS NOT NULL AND status NOT IN ('CANCELLED', 'REJECTED');

CREATE OR REPLACE FUNCTION reversal_target_guard() RETURNS TRIGGER AS $$
DECLARE
    is_rev  BOOLEAN;
    o       RECORD;
    dmg     TEXT;
    raiser  TEXT;
BEGIN
    SELECT dt.code = 'REV' INTO is_rev FROM document_type dt WHERE dt.id = NEW.document_type_id;

    IF NEW.reverses_document_id IS NULL THEN
        IF is_rev THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is a reversing document and must name the posted document it reverses.', NEW.serial_no);
        END IF;
        RETURN NEW;
    END IF;
    IF NOT is_rev THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is not a reversing document, so it reverses nothing. Only a reversing document names a document it undoes.', NEW.serial_no);
    END IF;

    SELECT d.serial_no, d.status, d.branch_id, d.created_by, d.posted_by, dt.code INTO o
      FROM document d JOIN document_type dt ON dt.id = d.document_type_id
     WHERE d.id = NEW.reverses_document_id;

    IF o.code NOT IN ('GRN', 'OPB', 'CNT', 'DMG') THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s cannot be reversed yet. Reversing documents undo goods received notes, opening balances, count adjustments, write-offs and quarantine releases. A delivery note, transfer, transfer receipt or cutting order changes what other documents may still do, and its reversal is not built yet.', o.serial_no);
    END IF;
    IF o.code = 'DMG' THEN
        SELECT r.kind INTO dmg FROM damage_report r WHERE r.document_id = NEW.reverses_document_id;
        IF dmg NOT IN ('WRITE_OFF', 'QUARANTINE_RELEASE') THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is a %s, which changes what its %s may still do, so its reversal is not built yet. A write-off or a quarantine release can be reversed.',
                                   o.serial_no, CASE dmg WHEN 'TRANSIT_LOSS' THEN 'transit loss' ELSE 'customer return' END,
                                   CASE dmg WHEN 'TRANSIT_LOSS' THEN 'transfer' ELSE 'delivery note' END);
        END IF;
    END IF;
    IF o.status <> 'POSTED' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is %s. Only a posted document is reversed: one that has not posted has moved no stock, and is cancelled instead.', o.serial_no, o.status);
    END IF;
    IF o.branch_id <> NEW.branch_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is reversed at its own branch. The reversal moves the same places the original moved.', o.serial_no);
    END IF;
    IF NEW.created_by IN (o.created_by, o.posted_by) THEN
        SELECT full_name INTO raiser FROM app_user WHERE id = NEW.created_by;
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s %s %s, so cannot raise its reversal. Whoever raised or posted a document takes no part in undoing it.',
                               raiser, CASE WHEN NEW.created_by = o.created_by THEN 'raised' ELSE 'posted' END, o.serial_no);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM transaction_ticket t
                     JOIN stock_movement m ON m.document_id = t.document_id
                    WHERE t.source_document_id = NEW.reverses_document_id
                      AND m.reverses_movement_id IS NULL) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s moved no stock, so there is nothing to reverse.', o.serial_no);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_reversal_target
    BEFORE INSERT OR UPDATE OF reverses_document_id, document_type_id ON document
    FOR EACH ROW EXECUTE FUNCTION reversal_target_guard();

-- Why it is being reversed. Not optional: the reason is what the Internal
-- Controller and the Managing Director judge, and what a reader in a year
-- needs to tell a correction from a cover-up.
CREATE TABLE reversal (
    document_id  UUID PRIMARY KEY REFERENCES document(id),
    reason       VARCHAR(600) NOT NULL
                 CONSTRAINT reversal_reason_says_what_was_wrong CHECK (length(btrim(reason)) >= 15)
);

COMMENT ON TABLE reversal IS
  'Subtype of document (type REV). Why a posted document is being undone, in words the signers judge. The document it undoes is document.reverses_document_id. Changes only while the reversal is a draft.';

CREATE OR REPLACE FUNCTION reversal_guard() RETURNS TRIGGER AS $$
DECLARE
    doc RECORD;
BEGIN
    SELECT d.serial_no, d.status, dt.code INTO doc
      FROM document d JOIN document_type dt ON dt.id = d.document_type_id
     WHERE d.id = COALESCE(NEW.document_id, OLD.document_id);
    IF doc.code <> 'REV' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is not a reversing document and carries no reversal reason.', doc.serial_no);
    END IF;
    IF doc.status <> 'DRAFT' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is %s. The reason is what the signers judged, so it changes only while the reversal is a draft.',
                               doc.serial_no, doc.status);
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.document_id <> OLD.document_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02', MESSAGE = 'A reversal reason stays with its document.';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER reversal_rules
    BEFORE INSERT OR UPDATE OR DELETE ON reversal
    FOR EACH ROW EXECUTE FUNCTION reversal_guard();

CREATE OR REPLACE FUNCTION reversal_submit_guard() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'REV')
       AND NOT EXISTS (SELECT 1 FROM reversal WHERE document_id = NEW.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s says nothing about why it reverses %s and cannot be submitted. The reason is what the signers judge.',
                               NEW.serial_no, (SELECT serial_no FROM document WHERE id = NEW.reverses_document_id));
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_reversal_submit
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (OLD.status = 'DRAFT' AND NEW.status = 'PENDING')
    EXECUTE FUNCTION reversal_submit_guard();

-- ---------------------------------------------------------------------
-- 3. Independence from the original. Raising is judged in section 2;
--    signing, posting and cancelling here. Cancelling counts: the
--    original's raiser could otherwise withdraw a reversal the Internal
--    Controller and the Managing Director have signed, as often as it is
--    raised. Editing a draft's reason records no actor in the database, so
--    the service refuses that one. The reversal's own chain rules (V11)
--    apply as to any document: its raiser signs no later step and does not
--    post, nobody signs twice, its poster signed nothing.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION reversal_signature_guard() RETURNS TRIGGER AS $$
DECLARE
    r RECORD;
BEGIN
    SELECT o.serial_no, o.created_by, o.posted_by, d.serial_no AS rev_serial, u.full_name INTO r
      FROM document d
      JOIN document_type dt ON dt.id = d.document_type_id AND dt.code = 'REV'
      JOIN document o       ON o.id = d.reverses_document_id
      JOIN app_user u       ON u.id = NEW.actor_user_id
     WHERE d.id = NEW.document_id;
    IF FOUND AND NEW.actor_user_id IN (r.created_by, r.posted_by) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s %s %s, so cannot sign its reversal %s. Whoever raised or posted a document takes no part in undoing it.',
                               r.full_name, CASE WHEN NEW.actor_user_id = r.created_by THEN 'raised' ELSE 'posted' END,
                               r.serial_no, r.rev_serial);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_approval_reversal_hands
    BEFORE INSERT ON document_approval
    FOR EACH ROW EXECUTE FUNCTION reversal_signature_guard();

CREATE OR REPLACE FUNCTION reversal_hands_guard() RETURNS TRIGGER AS $$
DECLARE
    actor UUID := CASE NEW.status WHEN 'POSTED' THEN NEW.posted_by ELSE NEW.cancelled_by END;
    r     RECORD;
BEGIN
    SELECT o.serial_no, o.created_by, o.posted_by, u.full_name INTO r
      FROM document_type dt
      JOIN document o ON o.id = NEW.reverses_document_id
      JOIN app_user u ON u.id = actor
     WHERE dt.id = NEW.document_type_id AND dt.code = 'REV';
    IF FOUND AND actor IN (r.created_by, r.posted_by) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s %s %s, so cannot %s its reversal %s. Whoever raised or posted a document takes no part in undoing it.',
                               r.full_name, CASE WHEN actor = r.created_by THEN 'raised' ELSE 'posted' END,
                               r.serial_no, CASE NEW.status WHEN 'POSTED' THEN 'post' ELSE 'cancel' END,
                               NEW.serial_no);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_reversal_hands
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status AND NEW.status IN ('POSTED', 'CANCELLED'))
    EXECUTE FUNCTION reversal_hands_guard();

-- ---------------------------------------------------------------------
-- 4. Tickets. A reversal raises one ticket for each ticket of the
--    original, its mirror: movement type REVERSAL, the other direction,
--    the two locations swapped, the same customs reference, line for line.
--    The mirror names the ticket it reverses, so ticket_guard and
--    ticket_line_guard can hold it to exactly that, and no ticket is ever
--    reversed twice.
-- ---------------------------------------------------------------------
ALTER TABLE transaction_ticket
    ADD COLUMN reverses_ticket_id UUID REFERENCES transaction_ticket(document_id);

ALTER TABLE transaction_ticket
    ADD CONSTRAINT ticket_reversal_names_what_it_reverses
    CHECK ((movement_type = 'REVERSAL') = (reverses_ticket_id IS NOT NULL));

CREATE UNIQUE INDEX transaction_ticket_one_reversal
    ON transaction_ticket (reverses_ticket_id) WHERE reverses_ticket_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 5. The three places that list the sources the ledger will act on, each
--    widened by one (CLAUDE.md: a new stock-moving document widens these
--    together, or its tickets are refused). Each is restated whole from
--    the applied V19 definition with only the REV branch added and the
--    refusal messages extended, so nothing changes for GRN, DN, TRF, TRR,
--    DMG, CNT, CUT or OPB.
--
--    document_support_link needs no change: its first branch links a
--    ticket to its source_document_id, and document_authority stops at the
--    reversal, which has a chain of its own.
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
    rev  RECORD;
    t0   RECORD;
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
    ELSIF src.code = 'REV' THEN
        -- The mirror of one posted ticket of the document being reversed:
        -- REVERSAL, the other way, between the same places swapped, under
        -- the same customs reference (V21).
        SELECT d.reverses_document_id INTO rev FROM document d WHERE d.id = NEW.source_document_id;
        SELECT t.source_document_id, t.direction, t.from_location_id, t.to_location_id, t.customs_reference,
               d.status, d.serial_no INTO t0
          FROM transaction_ticket t JOIN document d ON d.id = t.document_id
         WHERE t.document_id = NEW.reverses_ticket_id;
        IF NOT FOUND
           OR t0.source_document_id IS DISTINCT FROM rev.reverses_document_id
           OR t0.status <> 'POSTED'
           OR NEW.movement_type <> 'REVERSAL'
           OR NEW.direction = t0.direction
           OR NEW.from_location_id IS DISTINCT FROM t0.to_location_id
           OR NEW.to_location_id IS DISTINCT FROM t0.from_location_id
           OR NEW.customs_reference IS DISTINCT FROM t0.customs_reference THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s must be the mirror of a posted ticket of the document %s reverses: a REVERSAL, the other way, between the same two places swapped, under the same customs reference.', tdoc.serial_no, src.serial_no);
        END IF;
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s cannot be supported by %s: a ticket answers only to a goods received note, a delivery note, a transfer, a transfer receipt, a return and damage report, a stock count, a cutting order, an opening balance or a reversing document. An authorization or any other document does not move stock by itself.', tdoc.serial_no, src.serial_no);
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
    rl RECORD;
BEGIN
    SELECT d.serial_no, d.status, t.source_document_id, t.movement_type AS mtype, t.direction AS tdir,
           s.serial_no AS source_serial, sdt.code AS source_code, t.reverses_ticket_id AS rev_of INTO tk
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
    ELSIF tk.source_code = 'REV' THEN
        -- Line for line the ticket it reverses, value included (V21).
        SELECT l.*, d.serial_no AS ticket_serial INTO rl
          FROM ticket_line l JOIN document d ON d.id = l.ticket_id
         WHERE l.ticket_id = tk.rev_of AND l.line_no = NEW.line_no;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s has no line %s on the ticket it reverses. A reversal carries exactly the lines that moved.',
                                   tk.serial_no, NEW.line_no, NEW.line_no);
        END IF;
        IF (rl.item_id, rl.uom_id, rl.quantity, rl.qty_base_uom, rl.storage_bin_id, rl.unit_value, rl.total_value)
           IS DISTINCT FROM
           (NEW.item_id, NEW.uom_id, NEW.quantity, NEW.qty_base_uom, NEW.storage_bin_id, NEW.unit_value, NEW.total_value) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s line %s differs from line %s of %s in item, unit, quantity, bin or value. A reversal undoes exactly what moved, no more and no less.',
                                   tk.serial_no, NEW.line_no, NEW.line_no, rl.ticket_serial);
        END IF;
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s has no receipt, delivery, transfer, transfer receipt, report, count, cutting order, opening balance or reversal behind it, so it can carry no lines.', tk.serial_no);
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
                    WHERE s.id = tk.source_document_id AND st.code IN ('GRN', 'DN', 'TRF', 'TRR', 'DMG', 'CNT', 'CUT', 'OPB', 'REV')) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s answers to no goods received note, delivery note, transfer, transfer receipt, return and damage report, stock count, cutting order, opening balance or reversing document, so its stock does not move. An authorization alone lets nothing leave.', tdoc.serial_no);
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
-- 6. The movement is an exact mirror.
--
--    Every movement on a REVERSAL ticket names the movement it undoes, and
--    nothing else may name one. The undone movement is on the ticket the
--    mirror ticket reverses, on the line of the same number; the mirror
--    moves the same item at the same place and bin, the same quantity, the
--    other way, at the same value. So a reversed receipt leaves at the
--    value it came in at, whatever the average has done since, and the
--    ledger refuses it (LedgerService) when the place no longer holds that
--    much stock or that much value: what was received and has since been
--    sold cannot be "un-received".
--
--    Before V21 any movement could have named another as reversed, and
--    every read that filters reverses_movement_id IS NULL would then have
--    dropped the one it named. That door closes here. V4's unique index
--    already keeps a movement from being reversed twice.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION stock_movement_mirrors_what_it_reverses() RETURNS TRIGGER AS $$
DECLARE
    tk   RECORD;
    m0   RECORD;
    line SMALLINT;
BEGIN
    SELECT t.movement_type, t.reverses_ticket_id, d.serial_no INTO tk
      FROM transaction_ticket t JOIN document d ON d.id = t.document_id
     WHERE t.document_id = NEW.document_id;

    IF tk.movement_type IS DISTINCT FROM 'REVERSAL' THEN
        IF NEW.reverses_movement_id IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = 'Only a reversing document''s ticket reverses a movement. A movement that claims to undo another, on any other ticket, would hide the one it names from every figure.';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.reverses_movement_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s reverses a ticket, so each of its movements names the movement it undoes.', tk.serial_no);
    END IF;

    SELECT m.*, tl.line_no INTO m0
      FROM stock_movement m JOIN ticket_line tl ON tl.id = m.ticket_line_id
     WHERE m.id = NEW.reverses_movement_id;
    SELECT tl.line_no INTO line FROM ticket_line tl WHERE tl.id = NEW.ticket_line_id;
    IF m0.id IS NULL OR m0.document_id <> tk.reverses_ticket_id OR m0.line_no <> line THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s line %s must undo the movement of the same line on the ticket it reverses.', tk.serial_no, line);
    END IF;
    IF m0.reverses_movement_id IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A reversal is never itself reversed. Raise the original document again instead.';
    END IF;
    IF (NEW.item_id, NEW.branch_id, NEW.location_id, NEW.storage_bin_id, NEW.quantity_base_uom, NEW.value)
       IS DISTINCT FROM
       (m0.item_id, m0.branch_id, m0.location_id, m0.storage_bin_id, m0.quantity_base_uom, m0.value)
       OR NEW.direction = m0.direction THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s line %s must be the exact mirror of what it reverses: the same item, place, bin, quantity and value (%s), the other way.',
                               tk.serial_no, line, m0.value);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER stock_movement_reversal_mirrors
    BEFORE INSERT ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION stock_movement_mirrors_what_it_reverses();

-- ---------------------------------------------------------------------
-- 7. Posted means every movement reversed. Judged at commit, so the
--    reversal's own tickets and movements are in and visible: a reversal
--    undoes the whole document, or nothing.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION reversal_posted_guard() RETURNS TRIGGER AS $$
DECLARE
    gap RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'REV') THEN
        RETURN NULL;
    END IF;
    SELECT td.serial_no AS ticket_serial, tl.line_no, i.item_code INTO gap
      FROM transaction_ticket t
      JOIN document td      ON td.id = t.document_id
      JOIN stock_movement m ON m.document_id = t.document_id AND m.reverses_movement_id IS NULL
      JOIN ticket_line tl   ON tl.id = m.ticket_line_id
      JOIN item i           ON i.id = m.item_id
     WHERE t.source_document_id = NEW.reverses_document_id
       AND NOT EXISTS (SELECT 1
                         FROM stock_movement r
                         JOIN transaction_ticket rt ON rt.document_id = r.document_id
                        WHERE r.reverses_movement_id = m.id AND rt.source_document_id = NEW.id)
     ORDER BY m.id
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is posted but line %s of ticket %s (%s) was not reversed. A reversal undoes the whole document, or nothing.',
                               NEW.serial_no, gap.line_no, gap.ticket_serial, gap.item_code);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER document_reversal_posted
    AFTER UPDATE ON document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION reversal_posted_guard();

-- ---------------------------------------------------------------------
-- 8. An opening balance may load again once reversed, until the branch
--    trades (decided 5 October 2026; see the header).
--
--    is_reversed is set when the reversal posts, never by hand, and never
--    cleared. The one-per-location index now counts only a posted opening
--    balance that has not been reversed. The "nothing else has moved
--    stock here" control leaves out a reversal of an opening balance, as
--    it already left out opening balances themselves. The header guard is
--    restated whole from V19 with the is_reversed branch added.
-- ---------------------------------------------------------------------
ALTER TABLE opening_balance ADD COLUMN is_reversed BOOLEAN NOT NULL DEFAULT FALSE;

DROP INDEX opening_balance_one_posted_per_location;
CREATE UNIQUE INDEX opening_balance_one_live_per_location
    ON opening_balance (location_id) WHERE is_posted AND NOT is_reversed;

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
            NEW.basis_note, NEW.customs_reference, NEW.is_reversed)
           IS DISTINCT FROM
           (OLD.branch_id, OLD.location_id, OLD.as_at_date, OLD.source_system,
            OLD.basis_note, OLD.customs_reference, OLD.is_reversed) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = 'Posting marks an opening balance posted; it does not change what it says.';
        END IF;
        RETURN NEW;
    END IF;

    -- V21: marked reversed when, and only when, its reversal posts. Never
    -- cleared: a reversed baseline stays reversed.
    IF TG_OP = 'UPDATE' AND NEW.is_reversed IS DISTINCT FROM OLD.is_reversed THEN
        IF OLD.is_reversed OR NOT OLD.is_posted
           OR NOT EXISTS (SELECT 1 FROM document r JOIN document_type rt ON rt.id = r.document_type_id AND rt.code = 'REV'
                           WHERE r.reverses_document_id = NEW.document_id AND r.status = 'POSTED') THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = 'An opening balance is marked reversed only when its reversal posts, and is never unmarked.';
        END IF;
        IF (NEW.branch_id, NEW.location_id, NEW.as_at_date, NEW.source_system,
            NEW.basis_note, NEW.customs_reference, NEW.is_posted)
           IS DISTINCT FROM
           (OLD.branch_id, OLD.location_id, OLD.as_at_date, OLD.source_system,
            OLD.basis_note, OLD.customs_reference, OLD.is_posted) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = 'Reversing an opening balance marks it reversed; it does not change what it says.';
        END IF;
        RETURN NEW;
    END IF;

    IF doc.status <> 'DRAFT' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is %s. What an opening balance says is what four people signed; it changes only while it is a draft.',
                               doc.serial_no, doc.status);
    END IF;
    IF NEW.is_posted OR NEW.is_reversed THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'Whether an opening balance is posted or reversed follows its documents, and is not set by hand.';
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

-- is_reversed follows the reversal's posting at once, as is_posted follows
-- the opening balance's, so a corrected opening balance posted in the same
-- moment meets the index rather than slipping past it.
CREATE OR REPLACE FUNCTION opening_mark_reversed() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'REV') THEN
        UPDATE opening_balance SET is_reversed = TRUE
         WHERE document_id = NEW.reverses_document_id AND NOT is_reversed;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_opening_mark_reversed
    AFTER UPDATE ON document
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION opening_mark_reversed();

-- Restated whole from V19 with one change in (a): a reversal of an
-- opening balance is part of the cutover, not trading.
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
       AND NOT (st.code = 'REV'
                AND EXISTS (SELECT 1 FROM document o JOIN document_type ot ON ot.id = o.document_type_id
                             WHERE o.id = src.reverses_document_id AND ot.code = 'OPB'))
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

-- ---------------------------------------------------------------------
-- 9. The daily close counts a reversal as an adjustment. Restated whole
--    from V16 with REVERSAL added to the adjusting types: a reversed
--    receipt is not a dispatch and a reversed write-off is not a receipt,
--    and the close must not show goods arriving or leaving that never
--    did. No REVERSAL movement exists before this migration, so no figure
--    already reconciled or locked changes. The identity opening + receipts
--    - dispatches + adjustments = closing holds as before: every movement
--    still falls in exactly one bucket.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION close_figures(p_branch UUID, p_date DATE)
RETURNS TABLE (opening_value NUMERIC, receipts_value NUMERIC, dispatches_value NUMERIC,
               adjustments_value NUMERIC, closing_value NUMERIC, movement_count INTEGER) AS $$
    WITH m AS (
        SELECT m.business_date,
               m.direction,
               CASE m.direction WHEN 'IN' THEN m.value ELSE -m.value END AS signed_value,
               COALESCE(t.movement_type, '') IN ('DAMAGE', 'ADJUSTMENT', 'CUT_CONSUME', 'CUT_OUTPUT', 'REVERSAL') AS adjusting
          FROM stock_movement m
     LEFT JOIN transaction_ticket t ON t.document_id = m.document_id
         WHERE m.branch_id = p_branch AND m.business_date <= p_date
    )
    SELECT COALESCE(SUM(signed_value) FILTER (WHERE business_date < p_date), 0)::numeric(18,2),
           COALESCE(SUM(signed_value) FILTER (WHERE business_date = p_date AND NOT adjusting AND direction = 'IN'), 0)::numeric(18,2),
           COALESCE(-SUM(signed_value) FILTER (WHERE business_date = p_date AND NOT adjusting AND direction = 'OUT'), 0)::numeric(18,2),
           COALESCE(SUM(signed_value) FILTER (WHERE business_date = p_date AND adjusting), 0)::numeric(18,2),
           COALESCE(SUM(signed_value), 0)::numeric(18,2),
           (COUNT(*) FILTER (WHERE business_date = p_date))::int
      FROM m;
$$ LANGUAGE sql STABLE;


-- ---------------------------------------------------------------------
-- 10. Rights. Permission codes, never role names, and nothing to
--     SYS_ADMIN: whoever manages access takes no part in undoing stock
--     (invariant 8). Each right sits on the role whose step it signs; the
--     Internal Controller holds only reversal.verify and reversal.view,
--     never the right to record what it tests.
-- ---------------------------------------------------------------------
INSERT INTO permission (code, module, action, description) VALUES
    ('reversal.view',    'reversal', 'VIEW',    'See reversing documents'),
    ('reversal.create',  'reversal', 'CREATE',  'Raise a reversing document against a posted document'),
    ('reversal.verify',  'reversal', 'VERIFY',  'Verify a reversing document at a chain step'),
    ('reversal.approve', 'reversal', 'APPROVE', 'Approve a reversing document at a chain step'),
    ('reversal.post',    'reversal', 'POST',    'Post an approved reversing document to the ledger');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM (VALUES
        -- Everyone who reads the documents a reversal undoes can read it.
        ('WH_MANAGER',       'reversal.view'),
        ('ASST_WH_MANAGER',  'reversal.view'),
        ('INV_TX_OFFICER',   'reversal.view'),
        ('FINANCE',          'reversal.view'),
        ('INTERNAL_CTRL',    'reversal.view'),
        ('DIR_SUPPLY_CHAIN', 'reversal.view'),
        ('MANAGING_DIR',     'reversal.view'),
        -- The office holding custody raises it (PROPOSAL, see the header).
        ('WH_MANAGER',       'reversal.create'),
        -- The assurance role verifies it.
        ('INTERNAL_CTRL',    'reversal.verify'),
        -- The top approver approves it.
        ('MANAGING_DIR',     'reversal.approve'),
        -- Finance records it, as it records what it undoes; V11 refuses a
        -- poster who raised or signed it, and section 3 one who raised or
        -- posted the original.
        ('FINANCE',          'reversal.post')
       ) AS v(role_code, permission_code)
  JOIN role r       ON r.code = v.role_code
  JOIN permission p ON p.code = v.permission_code;

-- ---------------------------------------------------------------------
-- 11. The chain: one open-ended definition, the same in both eras.
--     A PROPOSAL until the client confirms it (see the header).
-- ---------------------------------------------------------------------
WITH wd AS (
    INSERT INTO workflow_definition (document_type_id, version, effective_from, effective_to, basis, notes)
    SELECT id, 1, DATE '2026-07-01', NULL, 'POLICY_2026',
           'PROPOSAL, to be confirmed by the client: the Warehouse Manager raises, the Internal Controller verifies, the Managing Director approves, Finance posts. Whoever raised or posted the original takes no part. One definition for both eras: both signing roles continue after the 2027 restructuring.'
      FROM document_type WHERE code = 'REV'
    RETURNING id
)
INSERT INTO workflow_step (workflow_definition_id, sequence_no, required_role_id, action_label, is_mandatory, blocks_release, escalate_after_hours)
SELECT wd.id, v.seq, r.id, v.act, TRUE, TRUE, v.esc
  FROM wd
  JOIN (VALUES
        (1, 'WH_MANAGER',    'PREPARE', 48),
        (2, 'INTERNAL_CTRL', 'VERIFY',  24),
        (3, 'MANAGING_DIR',  'APPROVE', 48)
       ) AS v(seq, role_code, act, esc) ON TRUE
  JOIN role r ON r.code = v.role_code;

-- ---------------------------------------------------------------------
-- 12. The rights placed above are judged for everyone, now, as they will
--     be at commit.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    msg TEXT;
BEGIN
    msg := access_conflict_anywhere();
    IF msg IS NOT NULL THEN
        RAISE EXCEPTION 'V21 cannot apply: the reversal rights leave someone in conflict. %', msg;
    END IF;
END $$;
