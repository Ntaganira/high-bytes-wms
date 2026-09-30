-- =====================================================================
-- V12 — Delivery Authorization, Delivery Note, and the release gate
--
-- SRS: FR-OUT-01..11 (authorization, gate, release), FR-WF-03..07,
-- FR-SEC-07, FR-IN-10 (the ledger).
--
-- Stock leaving without approval is the loss the Board found. Two
-- documents answer it:
--
--   DAO  the Delivery Authorization Order. Raised, then signed down its
--        chain (2026: Finance, Assistant WH Manager, WH Manager, Internal
--        Controller RELEASE; from 1 January 2027: Inventory Transactions
--        Officer, Director Supply Chain, Director Commercial COUNTERSIGN,
--        Internal Controller RELEASE). It moves no stock and NEVER
--        becomes POSTED: an approved DAO is a permission, and what it
--        permitted having happened is a posted Delivery Note. A DAO that
--        has been acted on can never read CANCELLED.
--   DN   the Delivery Note. It has no chain of its own; it answers to its
--        DAO. The warehouse posts it at the gate, and posting moves the
--        stock OUT. So an unsigned DAO is refused before the truck
--        leaves, by the database, not by the gate guard's memory.
--
-- Decisions taken by the business, enforced below:
--   1. The warehouse posts the DN (new right dispatch.post).
--   2. One load, exact quantity: at most one live DN per DAO, and per DAO
--      line the DN's quantity, summed over bins, equals the DAO's. A short
--      load means cancelling the DN and authorizing again. (The COO
--      waiver is not built.)
--   3. Whoever raised the DAO does not post its DN. A DAO signer may: the
--      2026 policy has the Assistant WH Manager verify and then load.
--
-- Why here rather than in Java: each of these is a rule a bug, a second
-- application or a psql session would otherwise skip. The service asks
-- first so the refusal carries its reason; the database refuses anyway.
-- Refusals raise SQLSTATE 23Z02 (a workflow or document control) or
-- 23514 (a malformed line), as in V11.
--
-- Contents
--   1. Rights on the roles whose steps they serve
--   2. Delivery authorization and its lines
--   3. Delivery note and its lines
--   4. Support links: what answers to what (document_authority, generalised)
--   5. DAO and DN lifecycle rules
--   6. Cancellation: never a document whose stock has moved
--   7. Ticket and ledger for a delivery
--   8. Stock never goes negative
-- =====================================================================

SET LOCAL highbytes.migration = 'on';

-- ---------------------------------------------------------------------
-- 1. Rights. A signature needs the step's role (the database checks it,
--    V11) and the matching dispatch.<action> right (the service checks it).
--
--   2026: FINANCE           PREPARE  dispatch.create   (already held)
--         ASST_WH_MANAGER   VERIFY   dispatch.view, dispatch.verify
--         WH_MANAGER        VERIFY   dispatch.verify   (already held)
--         INTERNAL_CTRL     RELEASE  dispatch.release
--   2027: INV_TX_OFFICER    PREPARE  dispatch.view, dispatch.create
--         DIR_SUPPLY_CHAIN  VERIFY   dispatch.view, dispatch.verify
--         DIR_COMMERCIAL    COUNTERSIGN dispatch.view, dispatch.countersign
--         INTERNAL_CTRL     RELEASE  dispatch.release
--
--   dispatch.release is the Internal Controller's release SIGNATURE on the
--   DAO. It is not the gate posting: that is dispatch.post, held by the
--   warehouse (WH_MANAGER, ASST_WH_MANAGER) and never by the Internal
--   Controller, whose whole value is that it does not do what it tests.
--   dispatch.countersign is new: countersigning that a delivery answers to
--   a real order is neither verifying nor approving.
--   Finance's dispatch.approve is left as it is. No chain step uses it
--   (dispatch.create prepares, dispatch.release signs off); it is not
--   removed, only unused, and removing a right from a policy role is not
--   this migration's business.
-- ---------------------------------------------------------------------
INSERT INTO permission (code, module, action, description) VALUES
    ('dispatch.post',        'dispatch', 'POST',
     'Record goods leaving the premises against a released delivery authorization'),
    ('dispatch.countersign', 'dispatch', 'APPROVE',
     'Countersign that a delivery answers to a real order');

UPDATE permission
   SET description = 'Sign the Internal Controller''s release of a delivery authorization (not the gate posting: see dispatch.post)'
 WHERE code = 'dispatch.release';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM (VALUES
        ('WH_MANAGER',       'dispatch.post'),
        ('ASST_WH_MANAGER',  'dispatch.post'),
        ('ASST_WH_MANAGER',  'dispatch.view'),
        ('ASST_WH_MANAGER',  'dispatch.verify'),
        ('INTERNAL_CTRL',    'dispatch.release'),
        ('INV_TX_OFFICER',   'dispatch.view'),
        ('INV_TX_OFFICER',   'dispatch.create'),
        ('DIR_SUPPLY_CHAIN', 'dispatch.view'),
        ('DIR_SUPPLY_CHAIN', 'dispatch.verify'),
        ('DIR_COMMERCIAL',   'dispatch.view'),
        ('DIR_COMMERCIAL',   'dispatch.countersign')
       ) AS v(role_code, permission_code)
  JOIN role r       ON r.code = v.role_code
  JOIN permission p ON p.code = v.permission_code;

-- ---------------------------------------------------------------------
-- Helpers shared by the two documents.
-- ---------------------------------------------------------------------

-- The factor turning a quantity in p_uom into the item's base unit: 1 for
-- the base unit itself, the conversion row otherwise, NULL when there is
-- none (the quantity cannot then be derived).
CREATE OR REPLACE FUNCTION uom_factor_to_base(p_item UUID, p_uom UUID) RETURNS NUMERIC AS $$
    SELECT CASE WHEN i.base_uom_id = p_uom THEN 1::numeric
                ELSE (SELECT c.factor_to_base FROM item_uom_conversion c
                       WHERE c.item_id = p_item AND c.uom_id = p_uom) END
      FROM item i WHERE i.id = p_item;
$$ LANGUAGE sql STABLE;

-- Content is frozen once the document leaves DRAFT (V11's rule for the
-- GRN), for any document type.
CREATE OR REPLACE FUNCTION assert_document_is_draft_of(p_doc UUID, p_code TEXT, p_what TEXT) RETURNS VOID AS $$
DECLARE
    doc RECORD;
BEGIN
    SELECT d.serial_no, d.status, dt.code, dt.name INTO doc
      FROM document d JOIN document_type dt ON dt.id = d.document_type_id
     WHERE d.id = p_doc;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02', MESSAGE = 'The document this belongs to does not exist.';
    END IF;
    IF doc.code <> p_code THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is a %s, not the document type these details belong to.', doc.serial_no, doc.name);
    END IF;
    IF doc.status <> 'DRAFT' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is %s, so its %s cannot change. What it says is what was signed or posted; correct it by cancelling and raising a new one.',
                               doc.serial_no, doc.status, p_what);
    END IF;
END;
$$ LANGUAGE plpgsql STABLE;

-- ---------------------------------------------------------------------
-- 2. The delivery authorization.
-- ---------------------------------------------------------------------
CREATE TABLE delivery_authorization (
    document_id           UUID PRIMARY KEY REFERENCES document(id),
    customer_id           UUID NOT NULL REFERENCES customer(id),
    -- Where the goods leave from. Must be at the document's own branch.
    location_id           UUID NOT NULL REFERENCES location(id),
    customer_reference    VARCHAR(60),
    delivery_address      VARCHAR(300),
    -- Duty-suspended stock leaving a bonded location or branch needs the
    -- customs reference on the way out as well as on the way in.
    customs_reference     VARCHAR(80),
    CONSTRAINT dao_customs_reference_not_blank
        CHECK (customs_reference IS NULL OR btrim(customs_reference) <> '')
);

CREATE INDEX delivery_authorization_customer ON delivery_authorization (customer_id);
CREATE INDEX delivery_authorization_location ON delivery_authorization (location_id);

COMMENT ON TABLE delivery_authorization IS
  'Subtype of document (type DAO). Never POSTED: it authorizes, and the posted Delivery Note is what was delivered. Content is what the signers signed, so it changes only while the document is a draft.';

CREATE TABLE delivery_authorization_line (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES delivery_authorization(document_id),
    line_no         SMALLINT NOT NULL CHECK (line_no > 0),
    item_id         UUID NOT NULL REFERENCES item(id),
    uom_id          UUID NOT NULL REFERENCES uom(id),
    quantity        NUMERIC(16,3) NOT NULL CHECK (quantity > 0),
    -- What the ledger will carry: quantity converted to the item's base unit.
    qty_base_uom    NUMERIC(16,3) NOT NULL CHECK (qty_base_uom > 0),
    note            VARCHAR(240),
    UNIQUE (document_id, line_no)
);

CREATE INDEX delivery_authorization_line_item ON delivery_authorization_line (item_id);

CREATE OR REPLACE FUNCTION dao_header_guard() RETURNS TRIGGER AS $$
DECLARE
    doc  RECORD;
    loc  RECORD;
    cust RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'DAO', 'details');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.document_id <> OLD.document_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A delivery authorization''s details cannot be moved to another document.';
    END IF;
    PERFORM assert_document_is_draft_of(NEW.document_id, 'DAO', 'details');

    SELECT d.serial_no, d.branch_id, b.is_bonded AS branch_bonded INTO doc
      FROM document d JOIN branch b ON b.id = d.branch_id WHERE d.id = NEW.document_id;
    SELECT l.code, l.branch_id, l.is_bonded, l.is_active INTO loc FROM location l WHERE l.id = NEW.location_id;
    SELECT c.name, c.is_active, c.is_blocked INTO cust FROM customer c WHERE c.id = NEW.customer_id;

    IF loc.branch_id <> doc.branch_id THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Location %s is not at the branch %s belongs to. Goods are authorized to leave from a location at the branch that holds them.',
                               loc.code, doc.serial_no);
    END IF;
    IF NOT loc.is_active THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Location %s is deactivated and cannot release goods.', loc.code);
    END IF;
    IF NOT cust.is_active THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Customer %s is deactivated and cannot be delivered to.', cust.name);
    END IF;
    IF cust.is_blocked THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Customer %s is blocked. Nothing is authorized to leave for a blocked customer.', cust.name);
    END IF;
    IF (loc.is_bonded OR doc.branch_bonded) AND NEW.customs_reference IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('%s releases bonded stock and needs a customs reference. Duty-suspended goods are accountable to Customs when they leave.',
                               doc.serial_no);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER delivery_authorization_content
    BEFORE INSERT OR UPDATE OR DELETE ON delivery_authorization
    FOR EACH ROW EXECUTE FUNCTION dao_header_guard();

CREATE OR REPLACE FUNCTION dao_line_guard() RETURNS TRIGGER AS $$
DECLARE
    doc    RECORD;
    it     RECORD;
    factor NUMERIC;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'DAO', 'lines');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.document_id <> OLD.document_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A delivery authorization line cannot be moved to another document.';
    END IF;
    PERFORM assert_document_is_draft_of(NEW.document_id, 'DAO', 'lines');

    SELECT d.serial_no INTO doc FROM document d WHERE d.id = NEW.document_id;
    SELECT i.item_code, i.is_active INTO it FROM item i WHERE i.id = NEW.item_id;
    IF NOT it.is_active THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Item %s is deactivated and cannot be authorized for delivery.', it.item_code);
    END IF;

    factor := uom_factor_to_base(NEW.item_id, NEW.uom_id);
    IF factor IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Item %s has no conversion from this unit to its base unit, so the stock quantity cannot be derived.', it.item_code);
    END IF;
    IF round(NEW.quantity * factor, 3) <> NEW.qty_base_uom THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Line %s of %s: %s x %s is %s in the base unit, not %s. The ledger carries the base quantity, so it must follow from what was entered.',
                               NEW.line_no, doc.serial_no, NEW.quantity, factor,
                               round(NEW.quantity * factor, 3), NEW.qty_base_uom);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER delivery_authorization_line_content
    BEFORE INSERT OR UPDATE OR DELETE ON delivery_authorization_line
    FOR EACH ROW EXECUTE FUNCTION dao_line_guard();

-- An authorization with no details or no lines is not worth a signature.
CREATE OR REPLACE FUNCTION dao_submit_guard() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'DAO') THEN
        IF NOT EXISTS (SELECT 1 FROM delivery_authorization WHERE document_id = NEW.id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s has no customer or dispatch location yet and cannot be submitted.', NEW.serial_no);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM delivery_authorization_line WHERE document_id = NEW.id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s has no lines and cannot be submitted: there would be nothing to authorize.', NEW.serial_no);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_dao_submit
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (OLD.status = 'DRAFT' AND NEW.status = 'PENDING')
    EXECUTE FUNCTION dao_submit_guard();

-- The DAO never becomes POSTED: its delivered state is the posted DN.
-- Named to fire before V11's document_lifecycle.
CREATE OR REPLACE FUNCTION dao_never_posted() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'DAO') THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is a delivery authorization and is never posted: it authorizes, and the posted Delivery Note is what was delivered.',
                               NEW.serial_no);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_dao_never_posted
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (NEW.status = 'POSTED' AND OLD.status IS DISTINCT FROM 'POSTED')
    EXECUTE FUNCTION dao_never_posted();

-- ---------------------------------------------------------------------
-- 3. The delivery note.
-- ---------------------------------------------------------------------
CREATE TABLE delivery_note (
    document_id           UUID PRIMARY KEY REFERENCES document(id),
    -- The authorization this note delivers. Fixed for life.
    authorization_id      UUID NOT NULL REFERENCES delivery_authorization(document_id),
    vehicle_registration  VARCHAR(20) NOT NULL,
    driver_name           VARCHAR(160) NOT NULL,
    driver_phone          VARCHAR(40),
    driver_id_no          VARCHAR(40),
    -- The gate time is the document's posted_at stamp, set by the database.
    CONSTRAINT dn_vehicle_not_blank CHECK (btrim(vehicle_registration) <> ''),
    CONSTRAINT dn_driver_not_blank  CHECK (btrim(driver_name) <> '')
);

CREATE INDEX delivery_note_authorization ON delivery_note (authorization_id);

COMMENT ON TABLE delivery_note IS
  'Subtype of document (type DN). No chain of its own: it answers to its authorization (document_authority). Posted by the warehouse at the gate, which moves the stock OUT.';

CREATE TABLE delivery_note_line (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id             UUID NOT NULL REFERENCES delivery_note(document_id),
    line_no                 SMALLINT NOT NULL CHECK (line_no > 0),
    authorization_line_id   UUID NOT NULL REFERENCES delivery_authorization_line(id),
    -- Derived from, and checked against, the authorization line.
    item_id                 UUID NOT NULL REFERENCES item(id),
    uom_id                  UUID NOT NULL REFERENCES uom(id),
    quantity                NUMERIC(16,3) NOT NULL CHECK (quantity > 0),
    qty_base_uom            NUMERIC(16,3) NOT NULL CHECK (qty_base_uom > 0),
    -- Where the stock leaves from; optional, in the authorization's location.
    storage_bin_id          UUID REFERENCES storage_bin(id),
    -- Measured with the approved device at the gate (FR-OUT-05). The item's
    -- nominal thickness is for display only: no tolerance is enforced, which
    -- is an open question for the client.
    measured_thickness_mm   NUMERIC(6,2)
                            CONSTRAINT dn_line_thickness_positive CHECK (measured_thickness_mm IS NULL OR measured_thickness_mm > 0),
    UNIQUE (document_id, line_no)
);

CREATE INDEX delivery_note_line_auth_line ON delivery_note_line (authorization_line_id);

CREATE OR REPLACE FUNCTION dn_header_guard() RETURNS TRIGGER AS $$
DECLARE
    dn  RECORD;
    dao RECORD;
    gap TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'DN', 'details');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.document_id <> OLD.document_id OR NEW.authorization_id <> OLD.authorization_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A delivery note is bound to one authorization for life; it cannot be moved to another.';
    END IF;
    PERFORM assert_document_is_draft_of(NEW.document_id, 'DN', 'details');

    -- One load per authorization, and never against one being cancelled:
    -- the lock the cancel guard (section 6) also takes is held BEFORE the
    -- authorization is read, so a cancel that commits while this waits is
    -- seen below, and two notes raised at once cannot each miss the other.
    IF TG_OP = 'INSERT' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended('dn:' || NEW.authorization_id::text, 0));
    END IF;

    SELECT d.serial_no, d.branch_id INTO dn FROM document d WHERE d.id = NEW.document_id;
    SELECT d.serial_no, d.status, d.branch_id INTO dao FROM document d WHERE d.id = NEW.authorization_id;

    IF dao.branch_id <> dn.branch_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s and the authorization %s belong to different branches. Goods leave against an authorization of the branch that holds them.',
                               dn.serial_no, dao.serial_no);
    END IF;
    IF dao.status <> 'APPROVED' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Authorization %s is %s. A delivery note is raised only against an authorization that is fully signed, including the Internal Controller''s release.',
                               dao.serial_no, dao.status);
    END IF;
    gap := document_approval_gap(NEW.authorization_id);
    IF gap IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = gap || ' A delivery note is raised only against a fully signed authorization.';
    END IF;

    -- One load per authorization: one live note (serialised by the lock above).
    IF TG_OP = 'INSERT' THEN
        IF EXISTS (SELECT 1 FROM delivery_note n JOIN document d ON d.id = n.document_id
                    WHERE n.authorization_id = NEW.authorization_id
                      AND n.document_id <> NEW.document_id AND d.status <> 'CANCELLED') THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Authorization %s already has a live delivery note. One authorization is one load; cancel that note first if the load is being redone.',
                                   dao.serial_no);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER delivery_note_content
    BEFORE INSERT OR UPDATE OR DELETE ON delivery_note
    FOR EACH ROW EXECUTE FUNCTION dn_header_guard();

CREATE OR REPLACE FUNCTION dn_line_guard() RETURNS TRIGGER AS $$
DECLARE
    dn     RECORD;
    al     RECORD;
    it     RECORD;
    bin    RECORD;
    factor NUMERIC;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'DN', 'lines');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.document_id <> OLD.document_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A delivery note line cannot be moved to another document.';
    END IF;
    PERFORM assert_document_is_draft_of(NEW.document_id, 'DN', 'lines');

    SELECT d.serial_no, n.authorization_id, a.location_id INTO dn
      FROM document d
      JOIN delivery_note n ON n.document_id = d.id
      JOIN delivery_authorization a ON a.document_id = n.authorization_id
     WHERE d.id = NEW.document_id;
    SELECT l.document_id, l.item_id, l.uom_id, l.line_no INTO al
      FROM delivery_authorization_line l WHERE l.id = NEW.authorization_line_id;
    SELECT i.item_code, i.product_type INTO it FROM item i WHERE i.id = NEW.item_id;

    IF al.document_id IS DISTINCT FROM dn.authorization_id THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Line %s of %s serves a line of a different authorization. A note loads only what its own authorization lists.',
                               NEW.line_no, dn.serial_no);
    END IF;
    IF NEW.item_id <> al.item_id OR NEW.uom_id <> al.uom_id THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Line %s of %s differs from authorization line %s in item or unit. What is loaded is what was authorized.',
                               NEW.line_no, dn.serial_no, al.line_no);
    END IF;

    factor := uom_factor_to_base(NEW.item_id, NEW.uom_id);
    IF factor IS NULL OR round(NEW.quantity * factor, 3) <> NEW.qty_base_uom THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Line %s of %s: %s x %s is %s in the base unit, not %s. The ledger carries the base quantity, so it must follow from what was entered.',
                               NEW.line_no, dn.serial_no, NEW.quantity, factor,
                               round(NEW.quantity * COALESCE(factor, 0), 3), NEW.qty_base_uom);
    END IF;

    IF it.product_type = 'GLASS' AND NEW.measured_thickness_mm IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Line %s of %s is glass (%s) and needs its thickness measured at the gate. The Internal Controller verifies every sheet leaving; without a measurement it cannot be verified.',
                               NEW.line_no, dn.serial_no, it.item_code);
    END IF;

    IF NEW.storage_bin_id IS NOT NULL THEN
        SELECT sb.location_id, sb.bin_code, sb.is_active INTO bin FROM storage_bin sb WHERE sb.id = NEW.storage_bin_id;
        IF bin.location_id <> dn.location_id THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Bin %s is not in the location %s releases from. Stock leaves from the location the authorization names.',
                                   bin.bin_code, dn.serial_no);
        END IF;
        IF NOT bin.is_active THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Bin %s is deactivated.', bin.bin_code);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER delivery_note_line_content
    BEFORE INSERT OR UPDATE OR DELETE ON delivery_note_line
    FOR EACH ROW EXECUTE FUNCTION dn_line_guard();

-- ---------------------------------------------------------------------
-- 4. Support links, and document_authority generalised.
--
-- Which document answers for which is now data, not a hard-coded pair of
-- joins: this view lists every "supported document -> supporting document"
-- link in the system. A ticket is supported by its source; a delivery note
-- by its authorization. A later module adds its own link by replacing this
-- view (one more UNION ALL) and inherits both uses below: the authority
-- the ledger asks for, and the cancellation guard of section 6.
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW document_support_link AS
SELECT t.document_id AS supported_id, t.source_document_id AS supporting_id
  FROM transaction_ticket t WHERE t.source_document_id IS NOT NULL
UNION ALL
SELECT n.document_id, n.authorization_id
  FROM delivery_note n;

COMMENT ON VIEW document_support_link IS
  'Which document answers for which. document_authority() follows it upward to the first document with an approval chain; the cancellation guard follows it downward to see whether stock has moved. A new module extends it with one UNION ALL.';

CREATE OR REPLACE FUNCTION document_authority(p_doc UUID) RETURNS UUID AS $$
DECLARE
    cur  UUID := p_doc;
    wf   UUID;
    src  UUID;
    hops INT := 0;
BEGIN
    LOOP
        SELECT d.workflow_definition_id INTO wf FROM document d WHERE d.id = cur;
        IF NOT FOUND THEN
            RETURN NULL;
        END IF;
        IF wf IS NOT NULL THEN
            RETURN cur;
        END IF;
        SELECT l.supporting_id INTO src FROM document_support_link l WHERE l.supported_id = cur LIMIT 1;
        IF src IS NULL OR hops >= 5 THEN
            RETURN NULL;
        END IF;
        cur := src;
        hops := hops + 1;
    END LOOP;
END;
$$ LANGUAGE plpgsql STABLE;

-- ---------------------------------------------------------------------
-- 5. Posting the delivery note: the gate.
--
-- The DN's move DRAFT -> POSTED is judged first by V11's rule for a
-- document with no chain (its authority is fully signed); this adds what
-- is specific to a delivery. Named to fire before document_lifecycle.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION dn_post_guard() RETURNS TRIGGER AS $$
DECLARE
    dao RECORD;
    gap TEXT;
    mm  RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'DN') THEN
        RETURN NEW;
    END IF;
    SELECT n.authorization_id, a.serial_no, a.status, a.created_by INTO dao
      FROM delivery_note n JOIN document a ON a.id = n.authorization_id
     WHERE n.document_id = NEW.id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s has no vehicle, driver or authorization recorded and cannot be posted.', NEW.serial_no);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM delivery_note_line WHERE document_id = NEW.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s has no lines: nothing is being loaded, so there is nothing to post.', NEW.serial_no);
    END IF;
    IF NEW.posted_by IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Posting %s must name who records the goods leaving.', NEW.serial_no);
    END IF;
    IF dao.status <> 'APPROVED' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Authorization %s is %s, so %s cannot be posted: goods leave only against a fully signed, released authorization.',
                               dao.serial_no, dao.status, NEW.serial_no);
    END IF;
    gap := document_approval_gap(dao.authorization_id);
    IF gap IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = gap || ' Goods do not leave until it is.';
    END IF;
    IF NEW.posted_by = dao.created_by THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s cannot be posted by the person who raised authorization %s. Whoever authorizes a delivery does not also let it out of the gate.',
                               NEW.serial_no, dao.serial_no);
    END IF;

    IF EXISTS (SELECT 1 FROM document_approval da JOIN workflow_step ws ON ws.id = da.workflow_step_id
                WHERE da.document_id = dao.authorization_id AND da.actor_user_id = NEW.posted_by
                  AND ws.action_label = 'RELEASE') THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s cannot be posted by the person who signed the release of authorization %s. Whoever releases a delivery does not also let it out of the gate. (A verifier who then loads may post; a releaser may not.)',
                               NEW.serial_no, dao.serial_no);
    END IF;

    -- One load, exact quantity, per authorization line, summed over bins.
    SELECT al.line_no, al.quantity AS want, COALESCE(SUM(dl.quantity), 0) AS got INTO mm
      FROM delivery_authorization_line al
      LEFT JOIN delivery_note_line dl ON dl.authorization_line_id = al.id AND dl.document_id = NEW.id
     WHERE al.document_id = dao.authorization_id
     GROUP BY al.id, al.line_no, al.quantity, al.qty_base_uom
    HAVING COALESCE(SUM(dl.quantity), 0) <> al.quantity
        OR COALESCE(SUM(dl.qty_base_uom), 0) <> al.qty_base_uom
     ORDER BY al.line_no
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Line %s of authorization %s allows %s and %s loads %s. The load must equal the authorization exactly. A short load means cancelling this note and authorizing again.',
                               mm.line_no, dao.serial_no, mm.want, NEW.serial_no, mm.got);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_dn_post
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (NEW.status = 'POSTED' AND OLD.status IS DISTINCT FROM 'POSTED')
    EXECUTE FUNCTION dn_post_guard();

-- A posted note must have moved its stock, checked at commit (as the GRN).
CREATE OR REPLACE FUNCTION dn_posted_must_have_moved_stock() RETURNS TRIGGER AS $$
DECLARE
    dl RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'DN') THEN
        RETURN NULL;
    END IF;
    FOR dl IN SELECT line_no FROM delivery_note_line WHERE document_id = NEW.id ORDER BY line_no LOOP
        IF NOT EXISTS (
               SELECT 1
                 FROM transaction_ticket t
                 JOIN ticket_line tl   ON tl.ticket_id = t.document_id AND tl.line_no = dl.line_no
                 JOIN stock_movement m ON m.ticket_line_id = tl.id AND m.reverses_movement_id IS NULL
                WHERE t.source_document_id = NEW.id AND t.movement_type = 'DELIVERY') THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is posted but line %s never left the ledger. A delivery note is posted together with its ticket and stock movements, or not at all.',
                                   NEW.serial_no, dl.line_no);
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER document_dn_posted_moved_stock
    AFTER UPDATE ON document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION dn_posted_must_have_moved_stock();

-- ---------------------------------------------------------------------
-- 6. Cancellation: never a document whose stock has moved, nor one that
--    something live still answers to.
--
-- V11 closed cancelling a posted document that moves stock. It left open
-- cancelling the document that AUTHORIZED stock that moved: an approved
-- DAO whose DN has posted would still read CANCELLED with the goods gone.
-- This is general: it follows the support links (section 4) downward.
--   - stock has moved against the document or anything that answers to it
--     (its tickets, its notes, theirs): refused whatever the status;
--   - a live (not cancelled) document answers to it: refused until that
--     one is cancelled, so a DAO cannot be cancelled under a draft note.
-- Named to fire after V11's document_lifecycle, so an illegal move still
-- meets that trigger's message first.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION document_cancel_guard() RETURNS TRIGGER AS $$
DECLARE
    moved RECORD;
    dep   RECORD;
BEGIN
    -- Cancelling a DAO and raising a DN against it must serialise: the DN
    -- insert takes this lock (dn_header_guard), so each sees the other.
    IF EXISTS (SELECT 1 FROM document_type WHERE id = OLD.document_type_id AND code = 'DAO') THEN
        PERFORM pg_advisory_xact_lock(hashtextextended('dn:' || OLD.id::text, 0));
    END IF;
    WITH RECURSIVE answers(id, depth) AS (
        SELECT OLD.id, 0
        UNION
        SELECT l.supported_id, a.depth + 1
          FROM answers a JOIN document_support_link l ON l.supporting_id = a.id
         WHERE a.depth < 6
    )
    SELECT d.serial_no INTO moved
      FROM answers a
      JOIN stock_movement m ON m.document_id = a.id
      JOIN document d ON d.id = m.document_id
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s cannot be cancelled: stock has already moved against it (through %s). The ledger would still carry the stock while the document read CANCELLED. Correct posted stock with a reversing document.',
                               OLD.serial_no, moved.serial_no);
    END IF;

    SELECT s.serial_no, s.status INTO dep
      FROM document_support_link l JOIN document s ON s.id = l.supported_id
     WHERE l.supporting_id = OLD.id AND s.status <> 'CANCELLED'
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s cannot be cancelled while %s (%s) still answers to it. Cancel that document first.',
                               OLD.serial_no, dep.serial_no, dep.status);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_lifecycle_cancel
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (OLD.status <> 'CANCELLED' AND NEW.status = 'CANCELLED')
    EXECUTE FUNCTION document_cancel_guard();

-- ---------------------------------------------------------------------
-- 7. Ticket and ledger for a delivery.
--
-- Posting a DN raises a transaction ticket (TT) whose source_document_id
-- is the DN: movement_type DELIVERY, direction OUT, from the DAO's
-- location, under the DAO's customs reference; one ticket line per DN
-- line, one OUT movement per ticket line. The ledger resolves
-- movement -> ticket -> DN -> DAO and needs the DAO fully signed. The DAO
-- has moves_stock = FALSE, so APPROVED suffices there.
--
-- The three V11 functions below are replaced whole, with what a delivery
-- adds; the GRN path is unchanged.
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX transaction_ticket_one_delivery_per_source
    ON transaction_ticket (source_document_id)
    WHERE movement_type = 'DELIVERY' AND source_document_id IS NOT NULL;

CREATE OR REPLACE FUNCTION ticket_guard() RETURNS TRIGGER AS $$
DECLARE
    tdoc RECORD;
    src  RECORD;
    grn  RECORD;
    dao  RECORD;
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
    -- (then a RECEIPT into its location) or a DN (then a DELIVERY out of
    -- its authorization's location). Any other source, or none, is refused
    -- here as well as at the ledger. Each later module widens this list
    -- alongside its document_support_link entry (section 4).
    IF NEW.source_document_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s names no supporting document. A ticket answers to the receipt or delivery note it records, and stock does not move without one.', tdoc.serial_no);
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
        ELSE
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Ticket %s cannot be supported by %s: a ticket answers only to a goods received note or a delivery note. An authorization or any other document does not move stock by itself.', tdoc.serial_no, src.serial_no);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION ticket_line_guard() RETURNS TRIGGER AS $$
DECLARE
    tk RECORD;
    gl RECORD;
    dl RECORD;
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
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s has no goods received note or delivery note behind it, so it can carry no lines.', tk.serial_no);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- The ledger guard of V11, plus: when the ticket's source is a document
-- that itself posts stock (a DN, as a GRN), that document must be POSTED
-- and the movement recorded by the person who posted it. Authority
-- (the DAO, for a delivery) is judged exactly as before.
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
                    WHERE s.id = tk.source_document_id AND st.code IN ('GRN', 'DN')) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s answers to no goods received note or delivery note, so its stock does not move. An authorization alone lets nothing leave.', tdoc.serial_no);
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
    IF auth.status = 'POSTED' AND NEW.posted_by IS DISTINCT FROM auth.posted_by THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s was posted by someone else. The movement must be recorded by the person who posted it, the one checked as independent.', auth.serial_no);
    END IF;

    -- The ticket's own source, when it is not the authority (a DN answering
    -- to its DAO): it posts stock, so it must have been posted, by the
    -- person recording the movement.
    IF tk.source_document_id IS NOT NULL AND tk.source_document_id <> auth_id THEN
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
-- 8. Stock never goes negative.
--
-- Two guards, because the balance table is a cache and the ledger is the
-- authority:
--   - the cache cannot hold a negative on-hand quantity (CHECK);
--   - the ledger refuses an OUT movement that would take the on-hand
--     quantity of the item at that location below zero, and, when the
--     movement names a bin, of that bin. Summed from the movements, not
--     from the running_balance the caller supplies. Serialised per item
--     and location so two simultaneous dispatches cannot both see the
--     stock and both take it.
-- A reversal path: reversing a receipt is an OUT, so it is refused if the
-- stock has since left (correct: it cannot be taken back twice); reversing
-- a delivery is an IN and never trips this. Counted shortages adjust down
-- only to zero. Every movement is judged twice: the location total, and
-- the bucket it names, the unbinned bucket (bin NULL) included. So stock
-- received unbinned leaves unbinned, and a named bin must hold what leaves
-- from it; 10 in bin A does not cover an unbinned OUT of 10. The lock key
-- 'stock:' || item_id || ':' || location_id, hashed with
-- hashtextextended(..., 0), is what LedgerService takes too. Do not change it.
-- ---------------------------------------------------------------------
ALTER TABLE stock_balance
    ADD CONSTRAINT stock_balance_never_negative CHECK (qty_on_hand >= 0);

CREATE OR REPLACE FUNCTION stock_movement_no_negative_stock() RETURNS TRIGGER AS $$
DECLARE
    on_hand NUMERIC;
    it      TEXT;
    loc     TEXT;
BEGIN
    IF NEW.direction <> 'OUT' THEN
        RETURN NULL;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('stock:' || NEW.item_id::text || ':' || NEW.location_id::text, 0));

    SELECT COALESCE(SUM(m.signed_quantity), 0) INTO on_hand
      FROM stock_movement m WHERE m.item_id = NEW.item_id AND m.location_id = NEW.location_id;
    IF on_hand < 0 THEN
        SELECT i.item_code INTO it FROM item i WHERE i.id = NEW.item_id;
        SELECT l.code INTO loc FROM location l WHERE l.id = NEW.location_id;
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Not enough stock: %s at %s would be short by %s. Stock cannot go negative, so this cannot leave.',
                               it, loc, -on_hand);
    END IF;

    -- The bucket the movement names, null bin included: stock in bin A does
    -- not cover an unbinned OUT, which would leave the unbinned bucket
    -- negative.
    SELECT COALESCE(SUM(m.signed_quantity), 0) INTO on_hand
      FROM stock_movement m
     WHERE m.item_id = NEW.item_id AND m.location_id = NEW.location_id
       AND m.storage_bin_id IS NOT DISTINCT FROM NEW.storage_bin_id;
    IF on_hand < 0 THEN
        SELECT i.item_code INTO it FROM item i WHERE i.id = NEW.item_id;
        IF NEW.storage_bin_id IS NULL THEN
            SELECT l.code INTO loc FROM location l WHERE l.id = NEW.location_id;
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Not enough unbinned stock: %s at %s would be short by %s in the unbinned bucket, because the rest sits in bins. Name the bin it leaves from. Stock cannot go negative.',
                                   it, loc, -on_hand);
        END IF;
        SELECT sb.bin_code INTO loc FROM storage_bin sb WHERE sb.id = NEW.storage_bin_id;
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Not enough stock in bin %s: %s would be short by %s. Stock cannot go negative.',
                               loc, it, -on_hand);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER stock_movement_never_below_zero
    AFTER INSERT ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION stock_movement_no_negative_stock();

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
        RAISE EXCEPTION 'V12 cannot apply: the dispatch rights leave someone in conflict. %', msg;
    END IF;
END $$;
