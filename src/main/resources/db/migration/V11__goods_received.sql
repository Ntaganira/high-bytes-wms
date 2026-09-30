-- =====================================================================
-- V11 — Goods Received Note, and the workflow guards every document
--       type inherits
--
-- SRS: FR-IN-01..10 (receiving), FR-WF-03..07 (chains and signatures),
-- FR-SEC-07 (posted documents), FR-IN-10 (the ledger).
--
-- The GRN is the first document that posts to the ledger, so this is where
-- the Board's finding stops being a design intention and becomes a set of
-- refusals: whoever raised, signed or checked a receipt is not the person
-- who posts it, nobody signs out of order or in a role they do not hold,
-- what the approvers signed cannot be edited afterwards, and the ledger
-- takes no movement whose supporting document is not fully signed.
--
-- Why here rather than in Java: every one of these is a rule a bug, a
-- second application or a psql session would otherwise be able to skip.
-- The service asks first so the refusal carries its reason; the database
-- refuses regardless. Refusals raise SQLSTATE 23Z02 (a workflow or
-- document control), 23514 for a malformed line, 23Z01 stays V9's.
--
-- Contents
--   1. Rights placed on the roles whose chain steps they serve
--   2. The Kigali business date, and the guards on the document spine
--   3. Signatures: order, role, independence
--   4. Status transitions
--   5. The goods received note and its lines
--   6. Ticket and ledger: no stock without a fully approved document
--   7. stock_balance uniqueness with a NULL bin
--
-- DECISION — which document the ledger rows carry. A movement carries the
-- TRANSACTION TICKET (document type TT) and a ticket_line_id. The ticket
-- names the GRN in source_document_id, and the ledger resolves that link
-- (document_authority) to the document whose chain must be complete. So a
-- ledger row is traceable movement -> ticket line -> ticket -> GRN ->
-- signatures, and the same shape serves a delivery note under a DAO, a
-- transfer or a count adjustment later: a new module only names its
-- authorising document as the ticket's source.
-- =====================================================================

SET LOCAL highbytes.migration = 'on';

-- ---------------------------------------------------------------------
-- 1. Rights, placed on the role whose step each one serves.
--
-- Until now receiving.verify and receiving.post were the only action
-- rights and four chain signers held none. A right nobody in the policy
-- carries cannot be handed to a runtime role at all (V10 rule 4), so the
-- migration that builds a screen places its rights.
--
--   2026: ASST_WH_MANAGER prepares -> WH_MANAGER verifies -> INTERNAL_CTRL
--         approves
--   2027: WH_MANAGER prepares -> INV_TX_OFFICER verifies ->
--         DIR_SUPPLY_CHAIN approves -> INTERNAL_CTRL approves
--
--   receiving.approve is new: approval is a different act from
--   verification, and the 2027 chain has two approving steps.
--   ASST_WH_MANAGER   view, create   it PREPAREs the note (2026 step 1)
--   INV_TX_OFFICER    view, verify   it VERIFYs (2027 step 2)
--   DIR_SUPPLY_CHAIN  view, approve  it APPROVEs (2027 step 3)
--   INTERNAL_CTRL     approve        it APPROVEs last in both chains; it
--                                    already reads and verifies, and
--                                    still holds nothing it does not sign
--   WH_MANAGER        unchanged      view, create, verify: it prepares in
--                                    2027 and verifies in 2026
--   FINANCE           unchanged      receiving.post: Finance is in neither
--                                    chain, so posting is independent of
--                                    every signature by construction, and
--                                    section 4 refuses a poster who signed
--                                    anyway
-- ---------------------------------------------------------------------
INSERT INTO permission (code, module, action, description)
VALUES ('receiving.approve', 'receiving', 'APPROVE', 'Approve a goods received note at a chain step');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM (VALUES
        ('ASST_WH_MANAGER',  'receiving.view'),
        ('ASST_WH_MANAGER',  'receiving.create'),
        ('INV_TX_OFFICER',   'receiving.view'),
        ('INV_TX_OFFICER',   'receiving.verify'),
        ('DIR_SUPPLY_CHAIN', 'receiving.view'),
        ('DIR_SUPPLY_CHAIN', 'receiving.approve'),
        ('INTERNAL_CTRL',    'receiving.approve')
       ) AS v(role_code, permission_code)
  JOIN role r       ON r.code = v.role_code
  JOIN permission p ON p.code = v.permission_code;

-- ---------------------------------------------------------------------
-- 2. The business date, and the spine's guards on INSERT and header.
--
-- The zone is the one application.yml configures for the JVM and the
-- daily close. It is written into the function because a document's date
-- must not depend on the session that happens to insert it.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION kigali_today() RETURNS DATE AS $$
    SELECT (now() AT TIME ZONE 'Africa/Kigali')::date;
$$ LANGUAGE sql STABLE;

ALTER TABLE document ALTER COLUMN document_date SET DEFAULT kigali_today();

-- A new document is a draft, dated today in Kigali, bound to the chain live
-- today. document_date is the date the 2026 -> 2027 switch is judged on, so
-- a caller who could choose it could choose which chain applies.
CREATE OR REPLACE FUNCTION document_open_guard() RETURNS TRIGGER AS $$
DECLARE
    def UUID;
BEGIN
    IF NEW.status <> 'DRAFT' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('A document starts as a draft, not as %s. Signatures and posting are earned one step at a time, never inserted.', NEW.status);
    END IF;
    IF NEW.document_date <> kigali_today() THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('A document is dated the day it is created (%s, Kigali time), not %s. The date decides which approval chain applies, so it cannot be chosen.',
                               kigali_today(), NEW.document_date);
    END IF;
    IF NEW.submitted_at IS NOT NULL OR NEW.approved_at IS NOT NULL OR NEW.posted_at IS NOT NULL
       OR NEW.posted_by IS NOT NULL OR NEW.cancelled_at IS NOT NULL OR NEW.cancelled_by IS NOT NULL
       OR NEW.cancel_reason IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A new document carries no submission, approval, posting or cancellation stamp; those are set when it happens.';
    END IF;
    NEW.created_at := now();

    SELECT wd.id INTO def
      FROM workflow_definition wd
     WHERE wd.document_type_id = NEW.document_type_id
       AND NEW.document_date >= wd.effective_from
       AND (wd.effective_to IS NULL OR NEW.document_date < wd.effective_to);

    IF def IS NULL AND EXISTS (SELECT 1 FROM workflow_definition WHERE document_type_id = NEW.document_type_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('No approval chain is in force for this document type on %s, so it cannot be opened: without a chain nobody would have to sign it.',
                               NEW.document_date);
    END IF;
    IF NEW.workflow_definition_id IS NOT NULL AND NEW.workflow_definition_id IS DISTINCT FROM def THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A document binds to the approval chain in force on its creation date; it cannot be given another.';
    END IF;
    NEW.workflow_definition_id := def;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_open
    BEFORE INSERT ON document
    FOR EACH ROW EXECUTE FUNCTION document_open_guard();

-- A document is never deleted: a cancelled one keeps its serial, and a
-- missing serial is an incident (invariant 6).
CREATE OR REPLACE FUNCTION document_no_delete() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23Z02',
          MESSAGE = format('Document %s cannot be deleted. Cancel it: the serial stays on the register, and a gap in the serials is a reportable incident.', OLD.serial_no);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_never_deleted
    BEFORE DELETE ON document
    FOR EACH ROW EXECUTE FUNCTION document_no_delete();

-- ---------------------------------------------------------------------
-- The approval state of a document, judged from its signatures and not
-- from its status column. Returns why it is NOT fully approved, or NULL.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION document_approval_gap(p_doc UUID) RETURNS TEXT AS $$
DECLARE
    doc      RECORD;
    rejected RECORD;
    missing  RECORD;
BEGIN
    SELECT serial_no, workflow_definition_id INTO doc FROM document WHERE id = p_doc;
    IF NOT FOUND THEN
        RETURN 'The document does not exist.';
    END IF;
    IF doc.workflow_definition_id IS NULL THEN
        RETURN format('%s has no approval chain, so nothing can have approved it.', doc.serial_no);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM workflow_step
                    WHERE workflow_definition_id = doc.workflow_definition_id AND is_mandatory) THEN
        RETURN format('The approval chain of %s has no mandatory step, so it cannot show approval.', doc.serial_no);
    END IF;

    SELECT ws.sequence_no, da.actor_name INTO rejected
      FROM document_approval da
      JOIN workflow_step ws ON ws.id = da.workflow_step_id
     WHERE da.document_id = p_doc AND da.decision = 'REJECTED'
     ORDER BY ws.sequence_no LIMIT 1;
    IF FOUND THEN
        RETURN format('%s was rejected at step %s by %s.', doc.serial_no, rejected.sequence_no, rejected.actor_name);
    END IF;

    SELECT ws.sequence_no, r.name AS role_name INTO missing
      FROM workflow_step ws
      JOIN role r ON r.id = ws.required_role_id
     WHERE ws.workflow_definition_id = doc.workflow_definition_id
       AND ws.is_mandatory
       AND NOT EXISTS (SELECT 1 FROM document_approval da
                        WHERE da.document_id = p_doc AND da.workflow_step_id = ws.id
                          AND da.decision = 'APPROVED')
     ORDER BY ws.sequence_no LIMIT 1;
    IF FOUND THEN
        RETURN format('%s is not fully approved: step %s (%s) has not signed.',
                      doc.serial_no, missing.sequence_no, missing.role_name);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

-- ---------------------------------------------------------------------
-- The document whose chain answers for another: itself when it has one;
-- otherwise, for a transaction ticket, the document it names as its
-- source, followed to the first document that has a chain. NULL when
-- there is none. The ledger asks this of every movement.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION document_authority(p_doc UUID) RETURNS UUID AS $$
DECLARE
    cur  UUID := p_doc;
    wf   UUID;
    src  UUID;
    hops INT := 0;
BEGIN
    LOOP
        SELECT d.workflow_definition_id, t.source_document_id INTO wf, src
          FROM document d
          LEFT JOIN transaction_ticket t ON t.document_id = d.id
         WHERE d.id = cur;
        IF NOT FOUND THEN
            RETURN NULL;
        END IF;
        IF wf IS NOT NULL THEN
            RETURN cur;
        END IF;
        IF src IS NULL OR hops >= 5 THEN
            RETURN NULL;
        END IF;
        cur := src;
        hops := hops + 1;
    END LOOP;
END;
$$ LANGUAGE plpgsql STABLE;

-- ---------------------------------------------------------------------
-- 3. Signatures.
--
-- V3 made a signature immutable and one per user per document. What it
-- did not say is who may sign, when and in what order. All of it is here:
--   - only while the document awaits approval;
--   - only a step of the chain the document was bound to;
--   - no step before every mandatory step below it has approved;
--   - only someone who holds that step's role today, at this document's
--     branch or at every branch, and whose account is active;
--   - after the first step, never the person who raised the document.
-- The signer's name, role title and time are written from the database
-- so a caller cannot sign as someone else or backdate a signature.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION document_approval_guard() RETURNS TRIGGER AS $$
DECLARE
    doc     RECORD;
    stp     RECORD;
    usr     RECORD;
    lower_s RECORD;
    today   DATE := kigali_today();
BEGIN
    SELECT d.serial_no, d.status, d.branch_id, d.created_by, d.workflow_definition_id INTO doc
      FROM document d WHERE d.id = NEW.document_id;
    SELECT ws.workflow_definition_id, ws.sequence_no, ws.required_role_id, r.name AS role_name, r.is_active AS role_active
      INTO stp
      FROM workflow_step ws JOIN role r ON r.id = ws.required_role_id
     WHERE ws.id = NEW.workflow_step_id;
    SELECT full_name, is_active INTO usr FROM app_user WHERE id = NEW.actor_user_id;

    IF doc.status <> 'PENDING' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is %s. Signatures are taken only while a document awaits approval, after it has been submitted.',
                               doc.serial_no, doc.status);
    END IF;
    IF doc.workflow_definition_id IS DISTINCT FROM stp.workflow_definition_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('That step is not part of the approval chain %s is bound to. A document finishes under the chain in force when it was created.',
                               doc.serial_no);
    END IF;
    IF EXISTS (SELECT 1 FROM document_approval WHERE document_id = NEW.document_id AND decision = 'REJECTED') THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s has been rejected and takes no further signatures. Raise a new document to correct it.', doc.serial_no);
    END IF;

    SELECT ws.sequence_no, r.name AS role_name INTO lower_s
      FROM workflow_step ws JOIN role r ON r.id = ws.required_role_id
     WHERE ws.workflow_definition_id = stp.workflow_definition_id
       AND ws.is_mandatory
       AND ws.sequence_no < stp.sequence_no
       AND NOT EXISTS (SELECT 1 FROM document_approval da
                        WHERE da.document_id = NEW.document_id
                          AND da.workflow_step_id = ws.id AND da.decision = 'APPROVED')
     ORDER BY ws.sequence_no LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Step %s (%s) must sign %s before step %s (%s). Steps sign in order, so a later signer sees what earlier ones approved.',
                               lower_s.sequence_no, lower_s.role_name, doc.serial_no, stp.sequence_no, stp.role_name);
    END IF;

    IF NOT COALESCE(usr.is_active, FALSE) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A deactivated account cannot sign.';
    END IF;
    IF NOT stp.role_active OR NOT EXISTS (
            SELECT 1 FROM user_role ur
             WHERE ur.user_id = NEW.actor_user_id
               AND ur.role_id = stp.required_role_id
               AND ur.revoked_at IS NULL
               AND ur.valid_from <= today
               AND (ur.valid_to IS NULL OR ur.valid_to >= today)
               AND (ur.branch_id IS NULL OR ur.branch_id = doc.branch_id)) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s does not hold %s today at this branch, so cannot sign step %s of %s. Only the role the chain names signs its step.',
                               usr.full_name, stp.role_name, stp.sequence_no, doc.serial_no);
    END IF;

    IF NEW.actor_user_id = doc.created_by
       AND stp.sequence_no > (SELECT MIN(sequence_no) FROM workflow_step
                               WHERE workflow_definition_id = stp.workflow_definition_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s raised %s and may not sign a step after the first. Whoever raises a document does not also approve it.',
                               usr.full_name, doc.serial_no);
    END IF;

    NEW.actor_name       := usr.full_name;
    NEW.actor_role_label := stp.role_name;
    NEW.decided_at       := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_approval_signing_rules
    BEFORE INSERT ON document_approval
    FOR EACH ROW EXECUTE FUNCTION document_approval_guard();

-- ---------------------------------------------------------------------
-- 4. Lifecycle: the header is fixed, and a status changes only by a legal
--    move with its evidence.
--
--   DRAFT    -> PENDING, CANCELLED
--   PENDING  -> APPROVED (every mandatory step approved), REJECTED (a
--               rejecting signature exists), CANCELLED
--   APPROVED -> POSTED (by someone who neither raised nor signed it),
--               CANCELLED
--   POSTED   -> CANCELLED, but ONLY for a type that does not move stock
--               (document_type.moves_stock = FALSE: DAO, CNT, VR). Posted
--               stock is corrected by a reversing document, never by
--               cancellation: the ledger would still carry the stock while
--               the document read CANCELLED. So for GRN, DN, TRF, CUT, DMG
--               and TT the database refuses it, with or without a chain.
--   REJECTED and CANCELLED are final. A rejection cannot be re-signed
--   (one signature per step) and a correction is a new document.
--
-- A document with no chain of its own (a transaction ticket) goes
-- DRAFT -> POSTED, and only when the document it answers to is fully
-- approved. A document with neither cannot be posted at all.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION document_lifecycle_guard() RETURNS TRIGGER AS $$
DECLARE
    gap      TEXT;
    auth     UUID;
    legal    BOOLEAN;
    stock_type BOOLEAN;
    signer   TEXT;
BEGIN
    -- Identity never changes, whatever the status.
    IF (NEW.document_type_id, NEW.serial_no, NEW.created_by, NEW.created_at,
        NEW.document_date, NEW.workflow_definition_id)
       IS DISTINCT FROM
       (OLD.document_type_id, OLD.serial_no, OLD.created_by, OLD.created_at,
        OLD.document_date, OLD.workflow_definition_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('The type, serial, creator, date and approval chain of %s are fixed when it is created. The date decides the chain, so neither can be moved afterwards.', OLD.serial_no);
    END IF;

    -- Once it leaves DRAFT the rest of the header is what the signers saw.
    IF OLD.status <> 'DRAFT'
       AND (NEW.branch_id, NEW.reference, NEW.notes, NEW.supersedes_document_id, NEW.reverses_document_id)
           IS DISTINCT FROM
           (OLD.branch_id, OLD.reference, OLD.notes, OLD.supersedes_document_id, OLD.reverses_document_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is %s, so its header is fixed: approvers sign what they saw. Cancel it and raise a new document.', OLD.serial_no, OLD.status);
    END IF;

    IF OLD.status = 'DRAFT' AND NEW.branch_id <> OLD.branch_id AND EXISTS (
           SELECT 1 FROM goods_received_note g JOIN location l ON l.id = g.location_id
            WHERE g.document_id = OLD.id AND l.branch_id <> NEW.branch_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s receives into a location at its current branch, so the branch cannot change.', OLD.serial_no);
    END IF;

    IF NEW.status = OLD.status THEN
        -- Stamps move with a status change and never on their own.
        IF (NEW.submitted_at, NEW.approved_at, NEW.posted_at, NEW.posted_by,
            NEW.cancelled_at, NEW.cancelled_by, NEW.cancel_reason)
           IS DISTINCT FROM
           (OLD.submitted_at, OLD.approved_at, OLD.posted_at, OLD.posted_by,
            OLD.cancelled_at, OLD.cancelled_by, OLD.cancel_reason) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('The submission, approval, posting and cancellation stamps of %s change only when its status does.', OLD.serial_no);
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.workflow_definition_id IS NOT NULL THEN
        legal := (OLD.status, NEW.status) IN
                 (('DRAFT','PENDING'), ('DRAFT','CANCELLED'),
                  ('PENDING','APPROVED'), ('PENDING','REJECTED'), ('PENDING','CANCELLED'),
                  ('APPROVED','POSTED'), ('APPROVED','CANCELLED'),
                  ('POSTED','CANCELLED'));
    ELSE
        legal := (OLD.status, NEW.status) IN
                 (('DRAFT','POSTED'), ('DRAFT','CANCELLED'), ('POSTED','CANCELLED'));
    END IF;
    -- Both lists above allow POSTED -> CANCELLED; it is withdrawn here for a
    -- type whose posting moved stock.
    IF legal AND OLD.status = 'POSTED' AND NEW.status = 'CANCELLED' THEN
        SELECT moves_stock INTO stock_type FROM document_type WHERE id = OLD.document_type_id;
        IF stock_type THEN
            legal := FALSE;
        END IF;
    END IF;
    IF NOT legal THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = CASE
                  WHEN OLD.status = 'POSTED' AND NEW.status = 'CANCELLED' THEN
                      format('%s is posted and has moved stock, so it cannot be cancelled: the ledger would still carry the stock while the document read CANCELLED. Posted stock is corrected by a reversing document, not by cancellation.', OLD.serial_no)
                  WHEN OLD.status IN ('REJECTED','CANCELLED') THEN
                      format('%s is %s and final. A correction is a new document referencing this one.', OLD.serial_no, OLD.status)
                  WHEN OLD.workflow_definition_id IS NULL AND NEW.status IN ('PENDING','APPROVED','REJECTED') THEN
                      format('%s has no approval chain of its own, so it cannot be %s. It answers to the document it names as its source.', OLD.serial_no, NEW.status)
                  ELSE
                      format('%s cannot move from %s to %s. A document takes each step in turn: submitted, approved, then posted.', OLD.serial_no, OLD.status, NEW.status)
              END;
    END IF;

    IF NEW.status = 'PENDING' THEN
        NEW.submitted_at := now();

    ELSIF NEW.status = 'APPROVED' THEN
        gap := document_approval_gap(OLD.id);
        IF gap IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = gap || ' A document is approved only when every mandatory step has signed.';
        END IF;
        NEW.approved_at := now();

    ELSIF NEW.status = 'REJECTED' THEN
        IF NOT EXISTS (SELECT 1 FROM document_approval WHERE document_id = OLD.id AND decision = 'REJECTED') THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is rejected only by a rejecting signature from a step of its chain; none has been recorded.', OLD.serial_no);
        END IF;

    ELSIF NEW.status = 'POSTED' THEN
        IF NEW.posted_by IS NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Posting %s must name who posts it.', OLD.serial_no);
        END IF;
        IF OLD.workflow_definition_id IS NOT NULL THEN
            gap := document_approval_gap(OLD.id);
            IF gap IS NOT NULL THEN
                RAISE EXCEPTION USING ERRCODE = '23Z02', MESSAGE = gap;
            END IF;
            IF NEW.posted_by = OLD.created_by THEN
                RAISE EXCEPTION USING ERRCODE = '23Z02',
                      MESSAGE = format('%s cannot be posted by the person who raised it. Raising and recording the same transaction is the concentration the Board found.', OLD.serial_no);
            END IF;
            SELECT da.actor_name INTO signer
              FROM document_approval da
             WHERE da.document_id = OLD.id AND da.actor_user_id = NEW.posted_by LIMIT 1;
            IF FOUND THEN
                RAISE EXCEPTION USING ERRCODE = '23Z02',
                      MESSAGE = format('%s signed %s and cannot also post it. Whoever approved a transaction does not record it in the ledger.', signer, OLD.serial_no);
            END IF;
        ELSE
            auth := document_authority(OLD.id);
            gap := CASE WHEN auth IS NULL
                        THEN format('%s answers to no document with an approval chain, so it cannot be posted.', OLD.serial_no)
                        ELSE document_approval_gap(auth) END;
            IF gap IS NOT NULL THEN
                RAISE EXCEPTION USING ERRCODE = '23Z02', MESSAGE = gap;
            END IF;
        END IF;
        NEW.posted_at := now();

    ELSIF NEW.status = 'CANCELLED' THEN
        IF NEW.cancelled_by IS NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Cancelling %s must name who cancels it.', OLD.serial_no);
        END IF;
        NEW.cancelled_at := now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Fires after V3's document_immutable_once_posted (alphabetical), so a
-- posted document still meets V3's message first.
CREATE TRIGGER document_lifecycle
    BEFORE UPDATE ON document
    FOR EACH ROW EXECUTE FUNCTION document_lifecycle_guard();

-- ---------------------------------------------------------------------
-- 5. The goods received note.
-- ---------------------------------------------------------------------
CREATE TABLE goods_received_note (
    document_id           UUID PRIMARY KEY REFERENCES document(id),
    supplier_id           UUID NOT NULL REFERENCES supplier(id),
    -- Where the goods are put. Must be at the document's own branch.
    location_id           UUID NOT NULL REFERENCES location(id),

    supplier_delivery_note_no  VARCHAR(60),
    supplier_invoice_no        VARCHAR(60),
    purchase_order_no          VARCHAR(60),

    -- Duty-suspended stock is accountable to Customs: receiving into a
    -- bonded location, or at a bonded branch, needs the reference.
    customs_reference     VARCHAR(80),

    currency_code         CHAR(3) NOT NULL DEFAULT 'RWF',
    -- Units of RWF per unit of the invoice currency.
    exchange_rate         NUMERIC(18,6) NOT NULL DEFAULT 1,

    -- Landed cost, in RWF whatever the invoice currency. Allocated across
    -- the lines by the service when the note is posted.
    freight_rwf           NUMERIC(18,2) NOT NULL DEFAULT 0,
    duty_rwf              NUMERIC(18,2) NOT NULL DEFAULT 0,
    clearing_rwf          NUMERIC(18,2) NOT NULL DEFAULT 0,
    demurrage_rwf         NUMERIC(18,2) NOT NULL DEFAULT 0,

    CONSTRAINT grn_currency_code_shape
        CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT grn_exchange_rate_positive
        CHECK (exchange_rate > 0),
    -- A rate other than 1 on an RWF invoice would misstate every value.
    CONSTRAINT grn_rwf_rate_is_one
        CHECK (currency_code <> 'RWF' OR exchange_rate = 1),
    CONSTRAINT grn_landed_costs_not_negative
        CHECK (freight_rwf >= 0 AND duty_rwf >= 0 AND clearing_rwf >= 0 AND demurrage_rwf >= 0),
    -- A blank customs reference is no reference.
    CONSTRAINT grn_customs_reference_not_blank
        CHECK (customs_reference IS NULL OR btrim(customs_reference) <> '')
);

CREATE INDEX goods_received_note_supplier ON goods_received_note (supplier_id);
CREATE INDEX goods_received_note_location ON goods_received_note (location_id);

COMMENT ON TABLE goods_received_note IS
  'Subtype of document (type GRN). Its content is what the approvers sign, so it changes only while the document is a draft. The customs reference rule needs the location and branch, so it is a trigger, not a CHECK.';

CREATE TABLE goods_received_line (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id           UUID NOT NULL REFERENCES goods_received_note(document_id),
    line_no               SMALLINT NOT NULL CHECK (line_no > 0),
    item_id               UUID NOT NULL REFERENCES item(id),
    uom_id                UUID NOT NULL REFERENCES uom(id),
    quantity              NUMERIC(16,3) NOT NULL CHECK (quantity > 0),
    -- What the ledger will carry: quantity converted to the item's base unit.
    qty_base_uom          NUMERIC(16,3) NOT NULL CHECK (qty_base_uom > 0),
    -- In the invoice currency, per unit of uom_id.
    unit_price            NUMERIC(18,4) NOT NULL CHECK (unit_price >= 0),
    storage_bin_id        UUID REFERENCES storage_bin(id),

    -- Measured with the approved device on receipt (FR-IN-03). A glass
    -- line without it cannot be verified at the gate later; the trigger
    -- below requires it because the CHECK cannot see the item.
    measured_thickness_mm NUMERIC(6,2)
                          CONSTRAINT grn_line_thickness_positive CHECK (measured_thickness_mm IS NULL OR measured_thickness_mm > 0),

    -- What the supplier's own document says arrived, in base units, so a
    -- short or over delivery shows. No tolerance is enforced: that is an
    -- open question for the client.
    supplier_quantity_base NUMERIC(16,3)
                          CONSTRAINT grn_line_supplier_quantity_not_negative CHECK (supplier_quantity_base IS NULL OR supplier_quantity_base >= 0),
    note                  VARCHAR(240),

    UNIQUE (document_id, line_no)
);

CREATE INDEX goods_received_line_item ON goods_received_line (item_id);

COMMENT ON COLUMN goods_received_line.supplier_quantity_base IS
  'Quantity on the supplier''s delivery note, in the item''s base unit. The difference from qty_base_uom is the short or over delivery.';

-- Content is frozen once the document leaves DRAFT. An approver's
-- signature means "I approved this"; an edit afterwards would leave the
-- signature vouching for something they never saw.
CREATE OR REPLACE FUNCTION assert_document_is_draft(p_doc UUID, p_what TEXT) RETURNS VOID AS $$
DECLARE
    doc RECORD;
BEGIN
    SELECT d.serial_no, d.status, dt.code INTO doc
      FROM document d JOIN document_type dt ON dt.id = d.document_type_id
     WHERE d.id = p_doc;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02', MESSAGE = 'The document this belongs to does not exist.';
    END IF;
    IF doc.code <> 'GRN' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is not a Goods Received Note, so it cannot carry receipt details.', doc.serial_no);
    END IF;
    IF doc.status <> 'DRAFT' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is %s, so its %s cannot change. The approvers signed what it says; correct it by cancelling and raising a new note.',
                               doc.serial_no, doc.status, p_what);
    END IF;
END;
$$ LANGUAGE plpgsql STABLE;

CREATE OR REPLACE FUNCTION grn_header_guard() RETURNS TRIGGER AS $$
DECLARE
    doc RECORD;
    loc RECORD;
    sup RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft(OLD.document_id, 'details');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.document_id <> OLD.document_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A receipt''s details cannot be moved to another document.';
    END IF;
    PERFORM assert_document_is_draft(NEW.document_id, 'details');

    SELECT d.serial_no, d.branch_id, b.is_bonded AS branch_bonded INTO doc
      FROM document d JOIN branch b ON b.id = d.branch_id WHERE d.id = NEW.document_id;
    SELECT l.code, l.branch_id, l.is_bonded, l.is_active INTO loc FROM location l WHERE l.id = NEW.location_id;
    SELECT s.name, s.is_active INTO sup FROM supplier s WHERE s.id = NEW.supplier_id;

    IF loc.branch_id <> doc.branch_id THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Location %s is not at the branch %s belongs to. Goods are received into a location at the branch that receives them.',
                               loc.code, doc.serial_no);
    END IF;
    IF NOT loc.is_active THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Location %s is deactivated and cannot receive goods.', loc.code);
    END IF;
    IF NOT sup.is_active THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Supplier %s is deactivated and cannot be received from.', sup.name);
    END IF;
    IF (loc.is_bonded OR doc.branch_bonded) AND NEW.customs_reference IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('%s receives bonded stock and needs a customs reference. Duty-suspended goods are accountable to Customs from the moment they arrive.',
                               doc.serial_no);
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.location_id <> OLD.location_id AND EXISTS (
           SELECT 1 FROM goods_received_line gl JOIN storage_bin sb ON sb.id = gl.storage_bin_id
            WHERE gl.document_id = NEW.document_id AND sb.location_id <> NEW.location_id) THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Some lines of %s name a bin in the previous location. Clear their bins before changing where the goods are received.', doc.serial_no);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER goods_received_note_content
    BEFORE INSERT OR UPDATE OR DELETE ON goods_received_note
    FOR EACH ROW EXECUTE FUNCTION grn_header_guard();

CREATE OR REPLACE FUNCTION grn_line_guard() RETURNS TRIGGER AS $$
DECLARE
    doc    RECORD;
    it     RECORD;
    factor NUMERIC(18,8);
    bin    RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft(OLD.document_id, 'lines');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.document_id <> OLD.document_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A receipt line cannot be moved to another document.';
    END IF;
    PERFORM assert_document_is_draft(NEW.document_id, 'lines');

    SELECT d.serial_no, g.location_id INTO doc
      FROM document d JOIN goods_received_note g ON g.document_id = d.id WHERE d.id = NEW.document_id;
    SELECT i.item_code, i.product_type, i.is_active, i.base_uom_id INTO it FROM item i WHERE i.id = NEW.item_id;

    IF NOT it.is_active THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Item %s is deactivated and cannot be received.', it.item_code);
    END IF;
    IF it.product_type = 'GLASS' AND NEW.measured_thickness_mm IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Line %s of %s is glass (%s) and needs its thickness measured with the approved device at receipt. Without it the sheet cannot be verified at the gate later.',
                               NEW.line_no, doc.serial_no, it.item_code);
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

    IF NEW.storage_bin_id IS NOT NULL THEN
        SELECT sb.location_id, sb.bin_code, sb.is_active INTO bin FROM storage_bin sb WHERE sb.id = NEW.storage_bin_id;
        IF bin.location_id <> doc.location_id THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Bin %s is not in the location %s receives into. A bin belongs to one location.', bin.bin_code, doc.serial_no);
        END IF;
        IF NOT bin.is_active THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Bin %s is deactivated and cannot take stock.', bin.bin_code);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER goods_received_line_content
    BEFORE INSERT OR UPDATE OR DELETE ON goods_received_line
    FOR EACH ROW EXECUTE FUNCTION grn_line_guard();

-- A note with no details or no lines is not worth anyone's signature.
CREATE OR REPLACE FUNCTION grn_submit_guard() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'GRN') THEN
        IF NOT EXISTS (SELECT 1 FROM goods_received_note WHERE document_id = NEW.id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s has no supplier or receiving location yet and cannot be submitted.', NEW.serial_no);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM goods_received_line WHERE document_id = NEW.id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s has no lines and cannot be submitted: there would be nothing to approve.', NEW.serial_no);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_grn_submit
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (OLD.status = 'DRAFT' AND NEW.status = 'PENDING')
    EXECUTE FUNCTION grn_submit_guard();

-- ---------------------------------------------------------------------
-- 6. Ticket and ledger.
--
-- Posting a GRN raises a transaction ticket (type TT, no chain of its own)
-- whose source_document_id is the GRN, one ticket line per GRN line, and
-- one ledger row per ticket line. Each link is checked, so stock posted is
-- exactly what was signed, once.
-- ---------------------------------------------------------------------

-- One receipt ticket per source document: a GRN cannot be posted twice.
CREATE UNIQUE INDEX transaction_ticket_one_receipt_per_source
    ON transaction_ticket (source_document_id)
    WHERE movement_type = 'RECEIPT' AND source_document_id IS NOT NULL;

-- One original movement per ticket line. A correction is a reversal.
CREATE UNIQUE INDEX stock_movement_one_per_ticket_line
    ON stock_movement (ticket_line_id)
    WHERE ticket_line_id IS NOT NULL AND reverses_movement_id IS NULL;

CREATE OR REPLACE FUNCTION ticket_guard() RETURNS TRIGGER AS $$
DECLARE
    tdoc RECORD;
    src  RECORD;
    grn  RECORD;
BEGIN
    SELECT d.serial_no, d.status, d.branch_id, dt.code INTO tdoc
      FROM document d JOIN document_type dt ON dt.id = d.document_type_id
     WHERE d.id = COALESCE(NEW.document_id, OLD.document_id);
    IF tdoc.status = 'POSTED' AND TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s is posted and cannot be changed: its lines are what the ledger carries.', tdoc.serial_no);
    END IF;
    -- Whatever its status: once stock has moved against it, the ticket is
    -- what the ledger carries and cannot be rewritten.
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

    IF NEW.source_document_id IS NOT NULL THEN
        SELECT d.serial_no, d.branch_id, dt.code INTO src
          FROM document d JOIN document_type dt ON dt.id = d.document_type_id
         WHERE d.id = NEW.source_document_id;
        IF NEW.source_document_id = NEW.document_id THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02', MESSAGE = 'A ticket cannot be its own supporting document.';
        END IF;
        IF src.branch_id <> tdoc.branch_id THEN
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
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transaction_ticket_rules
    BEFORE INSERT OR UPDATE OR DELETE ON transaction_ticket
    FOR EACH ROW EXECUTE FUNCTION ticket_guard();

CREATE OR REPLACE FUNCTION ticket_line_guard() RETURNS TRIGGER AS $$
DECLARE
    tk RECORD;
    gl RECORD;
BEGIN
    SELECT d.serial_no, d.status, t.source_document_id, s.serial_no AS source_serial, sdt.code AS source_code INTO tk
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
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ticket_line_rules
    BEFORE INSERT OR UPDATE OR DELETE ON ticket_line
    FOR EACH ROW EXECUTE FUNCTION ticket_line_guard();

-- The ledger's fourth guarantee: no movement without a fully approved
-- supporting document. This is the Board's central finding, in the place
-- a bug in Java cannot reach.
--
-- Judged from the signatures themselves (document_approval_gap), not from
-- the status column, so a wrongly set status cannot let stock through.
-- AFTER INSERT so the table's own CHECKs (sign against direction, positive
-- quantity) speak first; a refusal here rolls the statement back the same.
CREATE OR REPLACE FUNCTION stock_movement_needs_approved_document() RETURNS TRIGGER AS $$
DECLARE
    tdoc   RECORD;
    tk     RECORD;
    tl     RECORD;
    loc    RECORD;
    bin_loc UUID;
    auth_id UUID;
    auth   RECORD;
    gap    TEXT;
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
    -- poster is judged independent of its creator and signers (section 4).
    IF auth.moves_stock AND auth.status <> 'POSTED' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s must be posted before its stock moves: posting is the step that checks the poster signed nothing.', auth.serial_no);
    END IF;
    IF auth.status = 'POSTED' AND NEW.posted_by IS DISTINCT FROM auth.posted_by THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s was posted by someone else. The movement must be recorded by the person who posted it, the one checked as independent.', auth.serial_no);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER stock_movement_supported_by_approved_document
    AFTER INSERT ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION stock_movement_needs_approved_document();

-- A movement is dated the day it is written, Kigali time, like a document.
-- Locked days are already refused by V4's trigger; this closes the rest:
-- any unlocked past or future day would otherwise be a backdating route.
-- Named so it fires after V4's stock_movement_not_into_locked_day
-- (alphabetical), which therefore still speaks first for a locked day.
CREATE OR REPLACE FUNCTION stock_movement_dated_today() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.business_date <> kigali_today() THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('A stock movement is dated the day it is recorded (%s, Kigali time), not %s. A movement cannot be backdated or post-dated, even into a day that is still open.',
                               kigali_today(), NEW.business_date);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER stock_movement_today_only
    BEFORE INSERT ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION stock_movement_dated_today();

-- Checked at commit: a ticket that has moved stock must have been posted.
-- Otherwise a DRAFT ticket, whose lines the ledger already carries, could
-- sit unposted and its document status would disagree with the ledger.
CREATE OR REPLACE FUNCTION ticket_with_movements_must_be_posted() RETURNS TRIGGER AS $$
DECLARE
    tdoc RECORD;
BEGIN
    SELECT serial_no, status INTO tdoc FROM document WHERE id = NEW.document_id;
    IF tdoc.status IS DISTINCT FROM 'POSTED' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s has moved stock but is %s. A ticket whose movements are in the ledger must be posted in the same transaction.',
                               tdoc.serial_no, tdoc.status);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER stock_movement_ticket_posted
    AFTER INSERT ON stock_movement
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ticket_with_movements_must_be_posted();

-- The reverse: a receipt marked posted must have moved its stock, checked
-- at commit so the posting can be written in any order inside its
-- transaction. A "posted" note with nothing in the ledger is a gap nobody
-- would notice until the count.
CREATE OR REPLACE FUNCTION grn_posted_must_have_moved_stock() RETURNS TRIGGER AS $$
DECLARE
    gl RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'GRN') THEN
        RETURN NULL;
    END IF;
    FOR gl IN SELECT line_no FROM goods_received_line WHERE document_id = NEW.id ORDER BY line_no LOOP
        IF NOT EXISTS (
               SELECT 1
                 FROM transaction_ticket t
                 JOIN ticket_line tl    ON tl.ticket_id = t.document_id AND tl.line_no = gl.line_no
                 JOIN stock_movement m  ON m.ticket_line_id = tl.id AND m.reverses_movement_id IS NULL
                WHERE t.source_document_id = NEW.id AND t.movement_type = 'RECEIPT') THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is posted but line %s never reached the ledger. A receipt is posted together with its ticket and stock movements, or not at all.',
                                   NEW.serial_no, gl.line_no);
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER document_grn_posted_moved_stock
    AFTER UPDATE ON document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION grn_posted_must_have_moved_stock();

-- ---------------------------------------------------------------------
-- 7. One balance row per item, location and bin. V4's UNIQUE treated two
--    NULL bins as different, so an item at a location with no bin could
--    hold any number of balance rows. NULLS NOT DISTINCT (PostgreSQL 15+).
-- ---------------------------------------------------------------------
ALTER TABLE stock_balance DROP CONSTRAINT stock_balance_item_id_location_id_storage_bin_id_key;
ALTER TABLE stock_balance
    ADD CONSTRAINT stock_balance_one_row_per_place
    UNIQUE NULLS NOT DISTINCT (item_id, location_id, storage_bin_id);

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
        RAISE EXCEPTION 'V11 cannot apply: the receiving rights leave someone in conflict. %', msg;
    END IF;
END $$;
