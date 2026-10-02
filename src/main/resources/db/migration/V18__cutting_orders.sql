-- =====================================================================
-- V18 — Cutting orders: sheets cut to a customer's sizes, off-cuts kept
--
-- SRS: FR-CUT-01..06, FR-MD-04 (off-cuts as items), FR-OUT-01..11 (the
-- gate), FR-WF-03..07, FR-IN-10 (the ledger).
--
-- A Retail Cutting Order (CUT-006) takes whole sheets of glass from stock,
-- cuts the customer's pieces from them, keeps the usable off-cuts and
-- writes off the rest. Its chain is unchanged by the 2027 restructuring
-- (V5): Finance prepares, the Warehouse Manager verifies the cut sizes,
-- and the Internal Controller releases. Inventory Policy §10: no cut glass
-- leaves without the Internal Controller's signed release.
--
-- Decisions taken by the business (2 October 2026), enforced below:
--   1. Off-cut identity: one item per parent sheet and exact size, found
--      or made by cut_item_for() and reused for the same size. The longer
--      side first, so 800 x 1200 is 1200 x 800. A piece cut for a customer
--      is filed the same way: it is the same glass.
--   2. Cost: the sheets leave at the ledger's average cost; that value is
--      split across the pieces and kept off-cuts by area, kerf and scrap
--      absorbed (cut_output_shares). Nothing is created or lost in value.
--   3. An off-cut shorter than 300 mm on either side is not kept: it is
--      waste (cut_offcut_minimum_mm). Waste moves no stock of its own: its
--      cost is carried by what was cut, as kerf and scrap are.
--   4. A second Finance officer posts the order (cutting.post): nobody
--      posts what they prepared or signed (V11).
--   5. The customer's pieces leave through the gate on a Delivery Note
--      raised against the posted cutting order, as against a delivery
--      authorization (V12): the warehouse posts it, never whoever raised
--      the order, signed its release or posted it; one load, exact quantity.
--
-- The order holds one place: the sheets are taken from it, cut there, and
-- the pieces and off-cuts put back into it, from where the pieces leave.
--
-- Contents
--   1. Rights on the roles whose steps they serve
--   2. The cutting order, its sheets and what is cut from them
--   3. Cut sizes as items
--   4. Content rules, submission and posting
--   5. What a posted order must have written: stock and value
--   6. Tickets and ledger, widened for CUT
--   7. The gate: a delivery note against a cutting order
--   8. Returns of cut glass
-- =====================================================================

SET LOCAL highbytes.migration = 'on';

-- ---------------------------------------------------------------------
-- 1. Rights. A signature needs the step's role (V11) and the matching
--    cutting.<action> right (the service).
--
--   FINANCE        PREPARE  cutting.create   (V5)
--   WH_MANAGER     VERIFY   cutting.verify   (V5)
--   INTERNAL_CTRL  RELEASE  cutting.release  (here: the step it signs)
--   FINANCE        posts    cutting.post     (new; a second officer)
--
--   cutting.release is the Internal Controller's release signature, not
--   the gate: the pieces leave on a delivery note posted with
--   dispatch.post by the warehouse, as every delivery does.
-- ---------------------------------------------------------------------
INSERT INTO permission (code, module, action, description) VALUES
    ('cutting.post', 'cutting', 'POST',
     'Record a released cutting order in the ledger: the sheets consumed, the pieces and off-cuts made');

UPDATE permission
   SET description = 'Sign the Internal Controller''s release of a cutting order (not the posting, nor the gate)'
 WHERE code = 'cutting.release';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM (VALUES
        ('INTERNAL_CTRL', 'cutting.release'),
        ('FINANCE',       'cutting.post')
       ) AS v(role_code, permission_code)
  JOIN role r       ON r.code = v.role_code
  JOIN permission p ON p.code = v.permission_code
 WHERE NOT EXISTS (SELECT 1 FROM role_permission x WHERE x.role_id = r.id AND x.permission_id = p.id);

-- ---------------------------------------------------------------------
-- 2. The cutting order.
-- ---------------------------------------------------------------------
CREATE TABLE cutting_order (
    document_id         UUID PRIMARY KEY REFERENCES document(id),
    customer_id         UUID NOT NULL REFERENCES customer(id),
    -- Where the sheets are taken from, cut, and the pieces and off-cuts put.
    location_id         UUID NOT NULL REFERENCES location(id),
    customer_reference  VARCHAR(60),
    -- Duty-suspended glass cut in a bonded place stays accountable under it.
    customs_reference   VARCHAR(80),
    CONSTRAINT cut_customs_reference_not_blank
        CHECK (customs_reference IS NULL OR btrim(customs_reference) <> '')
);

CREATE INDEX cutting_order_customer ON cutting_order (customer_id);
CREATE INDEX cutting_order_location ON cutting_order (location_id);

COMMENT ON TABLE cutting_order IS
  'Subtype of document (type CUT). Its content is what the signers signed, so it changes only while a draft. Posting consumes its sheets and brings in its pieces and off-cuts; the pieces leave on a delivery note raised against it.';

-- The sheets cut: whole sheets of one glass item, from the order's place.
CREATE TABLE cutting_order_sheet (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES cutting_order(document_id),
    line_no         SMALLINT NOT NULL CHECK (line_no > 0),
    item_id         UUID NOT NULL REFERENCES item(id),
    storage_bin_id  UUID REFERENCES storage_bin(id),
    -- Whole sheets, in the item's base unit (the sheet).
    quantity        NUMERIC(16,3) NOT NULL CHECK (quantity > 0 AND quantity = trunc(quantity)),
    UNIQUE (document_id, line_no)
);

CREATE INDEX cutting_order_sheet_item ON cutting_order_sheet (item_id);

-- What is cut from them: the customer's pieces, and the off-cuts kept.
-- Whatever area is neither is waste: its cost is carried by what was cut.
CREATE TABLE cutting_order_output (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES cutting_order(document_id),
    line_no         SMALLINT NOT NULL CHECK (line_no > 0),
    kind            VARCHAR(8) NOT NULL CHECK (kind IN ('PIECE', 'OFFCUT')),
    width_mm        NUMERIC(8,1) NOT NULL CHECK (width_mm > 0),
    height_mm       NUMERIC(8,1) NOT NULL CHECK (height_mm > 0),
    quantity        NUMERIC(16,3) NOT NULL CHECK (quantity > 0 AND quantity = trunc(quantity)),
    -- The parent-and-size item this output is (section 3).
    item_id         UUID NOT NULL REFERENCES item(id),
    UNIQUE (document_id, line_no)
);

CREATE INDEX cutting_order_output_item ON cutting_order_output (item_id);

-- ---------------------------------------------------------------------
-- 3. Cut sizes as items.
--
-- One item per parent sheet and exact size (the longer side first), coded
-- <parent code>-R-<long>x<short>, e.g. GL-6CLR-R-1200x800. It is GLASS,
-- of the parent's colour and thickness, counted in the parent's unit, with
-- is_remnant set and cut_from_item_id naming the parent. Cutting an
-- off-cut again files what comes of it under the same parent, so a code
-- never grows a chain.
-- ---------------------------------------------------------------------

-- The smallest side an off-cut may have and still be kept, in mm.
-- Changed by migration.
CREATE OR REPLACE FUNCTION cut_offcut_minimum_mm() RETURNS NUMERIC AS $$
    SELECT 300::numeric;
$$ LANGUAGE sql IMMUTABLE;

-- The sheet a cut size is filed under: the item itself, or, for an
-- off-cut, the sheet it came from.
CREATE OR REPLACE FUNCTION cut_parent_of(p_item UUID) RETURNS UUID AS $$
    SELECT CASE WHEN i.is_remnant THEN i.cut_from_item_id ELSE i.id END FROM item i WHERE i.id = p_item;
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION cut_size_text(p_mm NUMERIC) RETURNS TEXT AS $$
    SELECT trim_scale(p_mm)::text;
$$ LANGUAGE sql IMMUTABLE;

CREATE OR REPLACE FUNCTION cut_item_code(p_parent UUID, p_width NUMERIC, p_height NUMERIC) RETURNS TEXT AS $$
    SELECT i.item_code || '-R-' || cut_size_text(greatest(round(p_width, 1), round(p_height, 1)))
                       || 'x'   || cut_size_text(least(round(p_width, 1), round(p_height, 1)))
      FROM item i WHERE i.id = p_parent;
$$ LANGUAGE sql STABLE;

-- The item for a cut size of p_parent, found, or made the first time that
-- size is cut. Serialised on the code, so two orders cutting the same new
-- size at once make one item, not two.
CREATE OR REPLACE FUNCTION cut_item_for(p_parent UUID, p_width NUMERIC, p_height NUMERIC) RETURNS UUID AS $$
DECLARE
    par   RECORD;
    code  TEXT;
    hit   RECORD;
    made  UUID;
    w     NUMERIC := greatest(round(p_width, 1), round(p_height, 1));
    h     NUMERIC := least(round(p_width, 1), round(p_height, 1));
BEGIN
    SELECT i.id, i.item_code, i.description, i.product_type, i.colour, i.thickness_mm, i.base_uom_id,
           i.is_remnant, i.is_active INTO par
      FROM item i WHERE i.id = p_parent;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation', MESSAGE = 'There is no such sheet to cut from.';
    END IF;
    IF par.is_remnant THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('%s is itself a cut size. What is cut from it is filed under the sheet it came from.', par.item_code);
    END IF;
    IF par.product_type <> 'GLASS' THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('%s is not glass. Only glass is cut to size.', par.item_code);
    END IF;
    IF w <= 0 OR h <= 0 THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation', MESSAGE = 'A cut size has a width and a height above zero.';
    END IF;

    code := cut_item_code(p_parent, w, h);
    IF length(code) > 40 THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('The code for this cut size, %s, is longer than an item code may be (40). Shorten the parent''s code %s.',
                               code, par.item_code);
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('cutitem:' || code, 0));

    SELECT i.id, i.is_remnant, i.cut_from_item_id, i.width_mm, i.height_mm, i.is_active INTO hit
      FROM item i WHERE i.item_code = code;
    IF FOUND THEN
        IF NOT (hit.is_remnant AND hit.cut_from_item_id = p_parent AND hit.width_mm = w AND hit.height_mm = h) THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Item code %s is already used by an item that is not this cut size of %s.', code, par.item_code);
        END IF;
        IF NOT hit.is_active THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Cut size %s is deactivated. Reactivate it on the item master before cutting it again.', code);
        END IF;
        RETURN hit.id;
    END IF;

    INSERT INTO item (item_code, description, product_type, colour, thickness_mm, width_mm, height_mm,
                      base_uom_id, is_remnant, cut_from_item_id)
    VALUES (code,
            left(par.description, 200) || ' · cut ' || cut_size_text(w) || ' × ' || cut_size_text(h) || ' mm',
            'GLASS', par.colour, par.thickness_mm, w, h, par.base_uom_id, TRUE, p_parent)
    RETURNING id INTO made;
    RETURN made;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION cut_item_for(UUID, NUMERIC, NUMERIC) IS
  'The one item for a cut size of a parent sheet (longer side first), made the first time that size is cut. The code is <parent>-R-<long>x<short>.';

-- ---------------------------------------------------------------------
-- 4. Content rules. Details and lines change only while a draft, and say
--    nothing the order cannot do.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION cut_header_guard() RETURNS TRIGGER AS $$
DECLARE
    doc  RECORD;
    loc  RECORD;
    cust RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'CUT', 'details');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.document_id <> OLD.document_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A cutting order''s details cannot be moved to another document.';
    END IF;
    PERFORM assert_document_is_draft_of(NEW.document_id, 'CUT', 'details');

    SELECT d.serial_no, d.branch_id, b.is_bonded AS branch_bonded INTO doc
      FROM document d JOIN branch b ON b.id = d.branch_id WHERE d.id = NEW.document_id;
    SELECT l.code, l.branch_id, l.location_type, l.is_bonded, l.is_active INTO loc
      FROM location l WHERE l.id = NEW.location_id;
    SELECT c.name, c.is_active, c.is_blocked INTO cust FROM customer c WHERE c.id = NEW.customer_id;

    IF loc.branch_id <> doc.branch_id THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Location %s is not at the branch %s belongs to. Glass is cut where the branch keeps it.',
                               loc.code, doc.serial_no);
    END IF;
    IF NOT loc.is_active THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Location %s is deactivated.', loc.code);
    END IF;
    IF loc.location_type NOT IN ('WAREHOUSE', 'BONDED', 'CUTTING') THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Glass is cut in a warehouse, bonded or cutting location; %s is %s.', loc.code, loc.location_type);
    END IF;
    IF NOT cust.is_active THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Customer %s is deactivated and cannot be cut for.', cust.name);
    END IF;
    IF cust.is_blocked THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Customer %s is blocked. Nothing is cut for, or released to, a blocked customer.', cust.name);
    END IF;
    IF (loc.is_bonded OR doc.branch_bonded) AND NEW.customs_reference IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('%s cuts bonded stock and needs a customs reference. Duty-suspended glass stays accountable to Customs when it is cut and when it leaves.',
                               doc.serial_no);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER cutting_order_content
    BEFORE INSERT OR UPDATE OR DELETE ON cutting_order
    FOR EACH ROW EXECUTE FUNCTION cut_header_guard();

CREATE OR REPLACE FUNCTION cut_sheet_guard() RETURNS TRIGGER AS $$
DECLARE
    h    RECORD;
    it   RECORD;
    bin  RECORD;
    other TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'CUT', 'lines');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.document_id <> OLD.document_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A cutting order''s sheet cannot be moved to another document.';
    END IF;
    PERFORM assert_document_is_draft_of(NEW.document_id, 'CUT', 'lines');

    SELECT d.serial_no, c.location_id INTO h
      FROM document d JOIN cutting_order c ON c.document_id = d.id WHERE d.id = NEW.document_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = 'Record the customer and the place before the sheets.';
    END IF;
    SELECT i.item_code, i.product_type, i.is_active, i.width_mm, i.height_mm, u.code AS uom INTO it
      FROM item i JOIN uom u ON u.id = i.base_uom_id WHERE i.id = NEW.item_id;
    IF NOT it.is_active THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Item %s is deactivated and cannot be cut.', it.item_code);
    END IF;
    IF it.product_type <> 'GLASS' OR it.uom <> 'SHEET' THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Item %s is not glass counted by the sheet. A cutting order cuts whole sheets of glass.', it.item_code);
    END IF;
    IF it.width_mm IS NULL OR it.height_mm IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('Item %s has no sheet size on the item master. Without it the cut cannot be checked against the sheet, nor its cost split by area.', it.item_code);
    END IF;
    SELECT i.item_code INTO other
      FROM cutting_order_sheet s JOIN item i ON i.id = s.item_id
     WHERE s.document_id = NEW.document_id AND s.id <> NEW.id AND s.item_id <> NEW.item_id
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation',
              MESSAGE = format('%s already cuts %s. One cutting order cuts sheets of one item; raise another for %s.',
                               h.serial_no, other, it.item_code);
    END IF;
    IF NEW.storage_bin_id IS NOT NULL THEN
        SELECT sb.location_id, sb.bin_code, sb.is_active INTO bin FROM storage_bin sb WHERE sb.id = NEW.storage_bin_id;
        IF bin.location_id <> h.location_id THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Bin %s is not in the place %s cuts at.', bin.bin_code, h.serial_no);
        END IF;
        IF NOT bin.is_active THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation', MESSAGE = format('Bin %s is deactivated.', bin.bin_code);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER cutting_order_sheet_content
    BEFORE INSERT OR UPDATE OR DELETE ON cutting_order_sheet
    FOR EACH ROW EXECUTE FUNCTION cut_sheet_guard();

-- Why an output does not fit the order's sheet, or NULL: the item must be
-- this size of the sheet's parent, the size must fit on the sheet (either
-- way round), and a kept off-cut must not be waste.
CREATE OR REPLACE FUNCTION cut_output_problem(p_doc UUID, p_line SMALLINT, p_kind TEXT, p_width NUMERIC,
                                              p_height NUMERIC, p_item UUID) RETURNS TEXT AS $$
DECLARE
    sh  RECORD;
    it  RECORD;
    w   NUMERIC := greatest(p_width, p_height);
    h   NUMERIC := least(p_width, p_height);
BEGIN
    SELECT i.id, i.item_code, cut_parent_of(i.id) AS parent,
           greatest(i.width_mm, i.height_mm) AS long_mm, least(i.width_mm, i.height_mm) AS short_mm INTO sh
      FROM cutting_order_sheet s JOIN item i ON i.id = s.item_id
     WHERE s.document_id = p_doc ORDER BY s.line_no LIMIT 1;
    IF NOT FOUND THEN
        RETURN 'Record the sheet being cut before what is cut from it.';
    END IF;
    SELECT i.item_code, i.is_remnant, i.cut_from_item_id, i.width_mm, i.height_mm, i.is_active INTO it
      FROM item i WHERE i.id = p_item;
    IF NOT (it.is_remnant AND it.cut_from_item_id = sh.parent AND it.width_mm = w AND it.height_mm = h) THEN
        RETURN format('Line %s names item %s, which is not the %s x %s mm cut size of the sheet being cut. Each size is filed as its own item, made for it.',
                      p_line, it.item_code, cut_size_text(w), cut_size_text(h));
    END IF;
    IF NOT it.is_active THEN
        RETURN format('Cut size %s is deactivated.', it.item_code);
    END IF;
    IF w > sh.long_mm OR h > sh.short_mm THEN
        RETURN format('Line %s, %s x %s mm, does not fit on a %s x %s mm sheet of %s.',
                      p_line, cut_size_text(w), cut_size_text(h), cut_size_text(sh.long_mm), cut_size_text(sh.short_mm), sh.item_code);
    END IF;
    IF p_kind = 'OFFCUT' AND h < cut_offcut_minimum_mm() THEN
        RETURN format('Line %s keeps a %s x %s mm off-cut. An off-cut under %s mm on either side is waste: it is not kept as stock, and its area counts as waste.',
                      p_line, cut_size_text(w), cut_size_text(h), cut_size_text(cut_offcut_minimum_mm()));
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

CREATE OR REPLACE FUNCTION cut_output_guard() RETURNS TRIGGER AS $$
DECLARE
    problem TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'CUT', 'lines');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.document_id <> OLD.document_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A cutting order''s line cannot be moved to another document.';
    END IF;
    PERFORM assert_document_is_draft_of(NEW.document_id, 'CUT', 'lines');
    problem := cut_output_problem(NEW.document_id, NEW.line_no, NEW.kind, NEW.width_mm, NEW.height_mm, NEW.item_id);
    IF problem IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'check_violation', MESSAGE = problem;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER cutting_order_output_content
    BEFORE INSERT OR UPDATE OR DELETE ON cutting_order_output
    FOR EACH ROW EXECUTE FUNCTION cut_output_guard();

-- The order's areas, in mm²: the sheets', the pieces' and off-cuts', and
-- the waste between them.
CREATE OR REPLACE FUNCTION cut_areas(p_doc UUID)
RETURNS TABLE (sheet_area NUMERIC, output_area NUMERIC, waste_area NUMERIC) AS $$
    WITH s AS (SELECT COALESCE(SUM(sl.quantity * i.width_mm * i.height_mm), 0) AS a
                 FROM cutting_order_sheet sl JOIN item i ON i.id = sl.item_id WHERE sl.document_id = p_doc),
         o AS (SELECT COALESCE(SUM(ol.quantity * ol.width_mm * ol.height_mm), 0) AS a
                 FROM cutting_order_output ol WHERE ol.document_id = p_doc)
    SELECT s.a, o.a, s.a - o.a FROM s, o;
$$ LANGUAGE sql STABLE;

-- Why the order as a whole cannot go forward, or NULL: its details, a
-- sheet, a piece for the customer, every line consistent with the sheet
-- as it now stands, every bin in the place, and no more glass cut than
-- the sheets hold. Asked at submission and again at posting.
CREATE OR REPLACE FUNCTION cut_order_problem(p_doc UUID) RETURNS TEXT AS $$
DECLARE
    serial TEXT;
    ln     RECORD;
    ar     RECORD;
    problem TEXT;
BEGIN
    SELECT serial_no INTO serial FROM document WHERE id = p_doc;
    IF NOT EXISTS (SELECT 1 FROM cutting_order WHERE document_id = p_doc) THEN
        RETURN format('%s has no customer or place yet.', serial);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM cutting_order_sheet WHERE document_id = p_doc) THEN
        RETURN format('%s names no sheet to cut.', serial);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM cutting_order_output WHERE document_id = p_doc AND kind = 'PIECE') THEN
        RETURN format('%s cuts no piece for the customer. A cutting order is raised for a customer''s pieces.', serial);
    END IF;
    IF EXISTS (SELECT 1 FROM cutting_order_sheet s JOIN cutting_order c ON c.document_id = s.document_id
                 JOIN storage_bin sb ON sb.id = s.storage_bin_id
                WHERE s.document_id = p_doc AND sb.location_id <> c.location_id) THEN
        RETURN format('A sheet of %s is taken from a bin outside the place it cuts at.', serial);
    END IF;
    FOR ln IN SELECT * FROM cutting_order_output WHERE document_id = p_doc ORDER BY line_no LOOP
        problem := cut_output_problem(p_doc, ln.line_no, ln.kind, ln.width_mm, ln.height_mm, ln.item_id);
        IF problem IS NOT NULL THEN
            RETURN problem;
        END IF;
    END LOOP;
    SELECT * INTO ar FROM cut_areas(p_doc);
    IF ar.waste_area < 0 THEN
        RETURN format('%s cuts %s mm² of pieces and off-cuts from %s mm² of sheet. No more glass can be cut than the sheets hold.',
                      serial, cut_size_text(ar.output_area), cut_size_text(ar.sheet_area));
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

CREATE OR REPLACE FUNCTION cut_submit_guard() RETURNS TRIGGER AS $$
DECLARE
    problem TEXT;
BEGIN
    IF EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'CUT') THEN
        problem := cut_order_problem(NEW.id);
        IF problem IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02', MESSAGE = problem || ' It cannot be submitted.';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_cut_submit
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (OLD.status = 'DRAFT' AND NEW.status = 'PENDING')
    EXECUTE FUNCTION cut_submit_guard();

-- Posting (APPROVED -> POSTED) is judged by V11's rule: a fully signed
-- chain, a poster who neither raised nor signed it. This asks the order's
-- own question again. Named to fire before V11's document_lifecycle.
CREATE OR REPLACE FUNCTION cut_post_guard() RETURNS TRIGGER AS $$
DECLARE
    problem TEXT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'CUT') THEN
        RETURN NEW;
    END IF;
    problem := cut_order_problem(NEW.id);
    IF problem IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02', MESSAGE = problem || ' It cannot be posted.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_cut_post
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (NEW.status = 'POSTED' AND OLD.status IS DISTINCT FROM 'POSTED')
    EXECUTE FUNCTION cut_post_guard();

-- ---------------------------------------------------------------------
-- 5. What a posted order must have written, checked at commit.
--
-- Stock: a CUT_CONSUME movement OUT for every sheet line and a CUT_OUTPUT
-- movement IN for every piece and off-cut line.
-- Value: the sheets leave at the ledger's average cost, and that value,
-- exactly, comes back in, split by area (cut_output_shares): each line's
-- share rounded to the franc, the line with the most area taking what the
-- rounding leaves, so the shares sum to what left. Waste carries none.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION cut_consumed_value(p_doc UUID) RETURNS NUMERIC AS $$
    SELECT COALESCE(SUM(m.value), 0)
      FROM transaction_ticket t
      JOIN stock_movement m ON m.document_id = t.document_id AND m.reverses_movement_id IS NULL
     WHERE t.source_document_id = p_doc AND t.movement_type = 'CUT_CONSUME';
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION cut_output_shares(p_doc UUID)
RETURNS TABLE (line_no SMALLINT, area NUMERIC, expected NUMERIC) AS $$
    WITH v AS (SELECT cut_consumed_value(p_doc) AS consumed),
         o AS (SELECT ol.line_no, ol.quantity * ol.width_mm * ol.height_mm AS area
                 FROM cutting_order_output ol WHERE ol.document_id = p_doc),
         t AS (SELECT SUM(o.area) AS total FROM o),
         r AS (SELECT o.line_no, o.area,
                      round(v.consumed * o.area / NULLIF(t.total, 0), 2) AS share,
                      row_number() OVER (ORDER BY o.area DESC, o.line_no DESC) AS rank
                 FROM o, v, t)
    SELECT r.line_no, r.area,
           CASE WHEN r.rank = 1
                THEN (SELECT consumed FROM v) - COALESCE((SELECT SUM(x.share) FROM r x WHERE x.rank > 1), 0)
                ELSE r.share END
      FROM r
     ORDER BY r.line_no;
$$ LANGUAGE sql STABLE;

COMMENT ON FUNCTION cut_output_shares(UUID) IS
  'What each piece and off-cut line of a cutting order is worth: the value its sheets left with, split by area, rounded to the franc, the largest line taking the rounding. The posting writes these values; the commit check recomputes them.';

CREATE OR REPLACE FUNCTION cut_posted_must_have_moved_stock() RETURNS TRIGGER AS $$
DECLARE
    ln RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'CUT') THEN
        RETURN NULL;
    END IF;
    FOR ln IN SELECT line_no FROM cutting_order_sheet WHERE document_id = NEW.id ORDER BY line_no LOOP
        IF NOT EXISTS (
               SELECT 1 FROM transaction_ticket t
                 JOIN ticket_line tl   ON tl.ticket_id = t.document_id AND tl.line_no = ln.line_no
                 JOIN stock_movement m ON m.ticket_line_id = tl.id AND m.reverses_movement_id IS NULL
                WHERE t.source_document_id = NEW.id AND t.movement_type = 'CUT_CONSUME') THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is posted but sheet line %s never left the ledger. A cutting order is posted together with its tickets and stock movements, or not at all.',
                                   NEW.serial_no, ln.line_no);
        END IF;
    END LOOP;
    FOR ln IN SELECT line_no FROM cutting_order_output WHERE document_id = NEW.id ORDER BY line_no LOOP
        IF NOT EXISTS (
               SELECT 1 FROM transaction_ticket t
                 JOIN ticket_line tl   ON tl.ticket_id = t.document_id AND tl.line_no = ln.line_no
                 JOIN stock_movement m ON m.ticket_line_id = tl.id AND m.reverses_movement_id IS NULL
                WHERE t.source_document_id = NEW.id AND t.movement_type = 'CUT_OUTPUT') THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is posted but cut line %s never came into the ledger. A cutting order is posted together with its tickets and stock movements, or not at all.',
                                   NEW.serial_no, ln.line_no);
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER document_cut_posted_moved_stock
    AFTER UPDATE ON document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION cut_posted_must_have_moved_stock();

CREATE OR REPLACE FUNCTION cut_value_must_follow_rule() RETURNS TRIGGER AS $$
DECLARE
    sh  RECORD;
    got NUMERIC;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'CUT') THEN
        RETURN NULL;
    END IF;
    FOR sh IN SELECT * FROM cut_output_shares(NEW.id) LOOP
        SELECT m.value INTO got
          FROM transaction_ticket t
          JOIN ticket_line tl   ON tl.ticket_id = t.document_id AND tl.line_no = sh.line_no
          JOIN stock_movement m ON m.ticket_line_id = tl.id AND m.reverses_movement_id IS NULL
         WHERE t.source_document_id = NEW.id AND t.movement_type = 'CUT_OUTPUT';
        CONTINUE WHEN got IS NULL;
        IF got <> sh.expected THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s line %s comes in at %s, but its share by area of the %s its sheets left with is %s. Cut glass carries the cost of the sheet it was cut from, split by area, no more and no less.',
                                   NEW.serial_no, sh.line_no, got, cut_consumed_value(NEW.id), sh.expected);
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER document_cut_value_rule
    AFTER UPDATE ON document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION cut_value_must_follow_rule();

-- ---------------------------------------------------------------------
-- 6. Tickets and ledger, widened for CUT.
--
-- A cutting order posts two tickets: CUT_CONSUME OUT of its place, one
-- line per sheet line, numbered as it, in the sheet's bin; and CUT_OUTPUT
-- IN to its place, one line per piece and off-cut line, numbered as it,
-- unbinned. Both under the order's customs reference. The three V15
-- functions are replaced whole with what a cutting order adds, and the
-- delivery note's branch reads its authority, which may now be a cutting
-- order (section 7).
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX transaction_ticket_one_cut_leg_per_source
    ON transaction_ticket (source_document_id, movement_type)
    WHERE movement_type IN ('CUT_CONSUME', 'CUT_OUTPUT') AND source_document_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 7. The gate. A delivery note answers to a delivery authorization (V12)
--    or, now, to a posted cutting order: one or the other, fixed for life,
--    and each note line to a line of that same authority, an
--    authorization line or a piece of the order. Off-cuts stay in stock.
-- ---------------------------------------------------------------------
ALTER TABLE delivery_note
    ALTER COLUMN authorization_id DROP NOT NULL,
    ADD COLUMN cutting_order_id UUID REFERENCES cutting_order(document_id),
    ADD CONSTRAINT dn_one_authority CHECK (num_nonnulls(authorization_id, cutting_order_id) = 1);

CREATE INDEX delivery_note_cutting_order ON delivery_note (cutting_order_id) WHERE cutting_order_id IS NOT NULL;

ALTER TABLE delivery_note_line
    ALTER COLUMN authorization_line_id DROP NOT NULL,
    ADD COLUMN cutting_output_id UUID REFERENCES cutting_order_output(id),
    ADD CONSTRAINT dn_line_one_authority_line CHECK (num_nonnulls(authorization_line_id, cutting_output_id) = 1);

CREATE INDEX delivery_note_line_cut_output ON delivery_note_line (cutting_output_id) WHERE cutting_output_id IS NOT NULL;

COMMENT ON TABLE delivery_note IS
  'Subtype of document (type DN). No chain of its own: it answers to its delivery authorization or its posted cutting order (document_authority). Posted by the warehouse at the gate, which moves the stock OUT.';

-- What every reader of a note needs from its authority, whichever kind it
-- is: the customer, the place the goods leave from, the customs reference.
CREATE OR REPLACE VIEW delivery_note_authority AS
SELECT n.document_id                                       AS note_id,
       COALESCE(n.authorization_id, n.cutting_order_id)    AS authority_id,
       CASE WHEN n.authorization_id IS NOT NULL THEN 'DAO' ELSE 'CUT' END AS authority_kind,
       COALESCE(a.customer_id, c.customer_id)              AS customer_id,
       COALESCE(a.location_id, c.location_id)              AS location_id,
       CASE WHEN n.authorization_id IS NOT NULL THEN a.customs_reference ELSE c.customs_reference END
                                                           AS customs_reference
  FROM delivery_note n
  LEFT JOIN delivery_authorization a ON a.document_id = n.authorization_id
  LEFT JOIN cutting_order c          ON c.document_id = n.cutting_order_id;

COMMENT ON VIEW delivery_note_authority IS
  'A delivery note''s authority (its delivery authorization or its cutting order) with the customer, the place and the customs reference the goods leave under.';

-- The lines a note may load, whichever its authority: an authorization's
-- lines, or a cutting order's pieces in the cut item's own unit.
CREATE OR REPLACE VIEW delivery_authority_line AS
SELECT l.id, l.document_id, l.line_no, l.item_id, l.uom_id, l.quantity, l.qty_base_uom, 'DAO' AS authority_kind
  FROM delivery_authorization_line l
UNION ALL
SELECT o.id, o.document_id, o.line_no, o.item_id, i.base_uom_id, o.quantity, o.quantity, 'CUT'
  FROM cutting_order_output o JOIN item i ON i.id = o.item_id
 WHERE o.kind = 'PIECE';

COMMENT ON VIEW delivery_authority_line IS
  'What a delivery note may load: the lines of a delivery authorization, or the pieces (not the off-cuts) of a cutting order.';

-- Which document answers for which (V12 s.4): a note answers to its
-- authority, whichever kind. A cutting order answers to nothing: it has
-- its own chain, and its tickets answer to it through their source.
CREATE OR REPLACE VIEW document_support_link AS
SELECT t.document_id AS supported_id, t.source_document_id AS supporting_id
  FROM transaction_ticket t WHERE t.source_document_id IS NOT NULL
UNION ALL
SELECT n.document_id, COALESCE(n.authorization_id, n.cutting_order_id)
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

-- Section 6's three functions, replaced whole (V15 plus a cutting order).
CREATE OR REPLACE FUNCTION ticket_guard() RETURNS TRIGGER AS $$
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
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s cannot be supported by %s: a ticket answers only to a goods received note, a delivery note, a transfer, a transfer receipt, a return and damage report, a stock count or a cutting order. An authorization or any other document does not move stock by itself.', tdoc.serial_no, src.serial_no);
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
    cl RECORD;
    ct RECORD;
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
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s has no receipt, delivery, transfer, transfer receipt, report, count or cutting order behind it, so it can carry no lines.', tk.serial_no);
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
                    WHERE s.id = tk.source_document_id AND st.code IN ('GRN', 'DN', 'TRF', 'TRR', 'DMG', 'CNT', 'CUT')) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s answers to no goods received note, delivery note, transfer, transfer receipt, return and damage report, stock count or cutting order, so its stock does not move. An authorization alone lets nothing leave.', tdoc.serial_no);
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
$$ LANGUAGE plpgsql;

-- The note's details: bound to one authority for life; against a delivery
-- authorization only once it is released (V12), against a cutting order
-- only once it is posted (the pieces exist in the ledger only then); one
-- live note per authority; the note at the authority's branch.
CREATE OR REPLACE FUNCTION dn_header_guard() RETURNS TRIGGER AS $$
DECLARE
    dn    RECORD;
    auth  RECORD;
    gap   TEXT;
    auth_id UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'DN', 'details');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.document_id <> OLD.document_id
                             OR NEW.authorization_id IS DISTINCT FROM OLD.authorization_id
                             OR NEW.cutting_order_id IS DISTINCT FROM OLD.cutting_order_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A delivery note is bound to one authorization for life, or to one cutting order; it cannot be moved to another.';
    END IF;
    PERFORM assert_document_is_draft_of(NEW.document_id, 'DN', 'details');
    auth_id := COALESCE(NEW.authorization_id, NEW.cutting_order_id);

    -- One load per authority, and never against one being cancelled: the
    -- lock the cancel guard (V12 s.6) also takes for an authorization is
    -- held BEFORE the authority is read, so two notes raised at once cannot
    -- each miss the other.
    IF TG_OP = 'INSERT' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended('dn:' || auth_id::text, 0));
    END IF;

    SELECT d.serial_no, d.branch_id INTO dn FROM document d WHERE d.id = NEW.document_id;
    SELECT d.serial_no, d.status, d.branch_id INTO auth FROM document d WHERE d.id = auth_id;

    IF NEW.authorization_id IS NOT NULL THEN
        IF auth.branch_id <> dn.branch_id THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s and the authorization %s belong to different branches. Goods leave against an authorization of the branch that holds them.',
                                   dn.serial_no, auth.serial_no);
        END IF;
        IF auth.status <> 'APPROVED' THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Authorization %s is %s. A delivery note is raised only against an authorization that is fully signed, including the Internal Controller''s release.',
                                   auth.serial_no, auth.status);
        END IF;
        gap := document_approval_gap(NEW.authorization_id);
        IF gap IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = gap || ' A delivery note is raised only against a fully signed authorization.';
        END IF;
    ELSE
        IF NOT EXISTS (SELECT 1 FROM cutting_order WHERE document_id = NEW.cutting_order_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s names no cutting order.', dn.serial_no);
        END IF;
        IF auth.branch_id <> dn.branch_id THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s and the cutting order %s belong to different branches. Cut glass leaves from the branch that cut it.',
                                   dn.serial_no, auth.serial_no);
        END IF;
        IF auth.status <> 'POSTED' THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Cutting order %s is %s. Its pieces leave only once it is released by the Internal Controller and posted: until then they are not in the ledger.',
                                   auth.serial_no, auth.status);
        END IF;
        gap := document_approval_gap(NEW.cutting_order_id);
        IF gap IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = gap || ' Cut glass leaves only against a fully signed cutting order.';
        END IF;
    END IF;

    -- One load per authority: one live note (serialised by the lock above).
    IF TG_OP = 'INSERT' THEN
        IF EXISTS (SELECT 1 FROM delivery_note n JOIN document d ON d.id = n.document_id
                    WHERE COALESCE(n.authorization_id, n.cutting_order_id) = auth_id
                      AND n.document_id <> NEW.document_id AND d.status <> 'CANCELLED') THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s already has a live delivery note. One authorization, or one cutting order, is one load; cancel that note first if the load is being redone.',
                                   auth.serial_no);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- A note line serves a line of the note's own authority: an authorization
-- line (V12), or a piece of the cutting order. Never an off-cut: it stays.
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

    SELECT d.serial_no, n.authorization_id, n.cutting_order_id, na.location_id INTO dn
      FROM document d
      JOIN delivery_note n            ON n.document_id = d.id
      JOIN delivery_note_authority na ON na.note_id = n.document_id
     WHERE d.id = NEW.document_id;
    SELECT i.item_code, i.product_type INTO it FROM item i WHERE i.id = NEW.item_id;

    IF NEW.authorization_line_id IS NOT NULL THEN
        SELECT l.document_id, l.item_id, l.uom_id, l.line_no INTO al
          FROM delivery_authorization_line l WHERE l.id = NEW.authorization_line_id;
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
    ELSE
        SELECT o.document_id, o.item_id, i.base_uom_id AS uom_id, o.line_no, o.kind INTO al
          FROM cutting_order_output o JOIN item i ON i.id = o.item_id WHERE o.id = NEW.cutting_output_id;
        IF al.document_id IS DISTINCT FROM dn.cutting_order_id THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Line %s of %s serves a line of a different cutting order. A note loads only what its own order cut.',
                                   NEW.line_no, dn.serial_no);
        END IF;
        IF al.kind <> 'PIECE' THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Line %s of %s loads an off-cut. Off-cuts stay in stock; only the customer''s pieces leave on the note.',
                                   NEW.line_no, dn.serial_no);
        END IF;
        IF NEW.item_id <> al.item_id OR NEW.uom_id <> al.uom_id THEN
            RAISE EXCEPTION USING ERRCODE = 'check_violation',
                  MESSAGE = format('Line %s of %s differs from cut line %s in item or unit. What is loaded is what was cut.',
                                   NEW.line_no, dn.serial_no, al.line_no);
        END IF;
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
                  MESSAGE = format('Bin %s is not in the location %s releases from. Stock leaves from the location its authority names.',
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

-- Posting the note at the gate (V12 s.5), against either authority: it is
-- released (an authorization APPROVED; a cutting order POSTED, so its
-- pieces exist), whoever raised it, signed its release or (a cutting
-- order) posted it does not let it out, and the load equals it exactly.
CREATE OR REPLACE FUNCTION dn_post_guard() RETURNS TRIGGER AS $$
DECLARE
    auth RECORD;
    what TEXT;
    gap  TEXT;
    mm   RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'DN') THEN
        RETURN NEW;
    END IF;
    SELECT na.authority_id, na.authority_kind, a.serial_no, a.status, a.created_by, a.posted_by INTO auth
      FROM delivery_note_authority na JOIN document a ON a.id = na.authority_id
     WHERE na.note_id = NEW.id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s has no vehicle, driver or authorization recorded and cannot be posted.', NEW.serial_no);
    END IF;
    what := CASE auth.authority_kind WHEN 'CUT' THEN 'cutting order' ELSE 'authorization' END;
    IF NOT EXISTS (SELECT 1 FROM delivery_note_line WHERE document_id = NEW.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s has no lines: nothing is being loaded, so there is nothing to post.', NEW.serial_no);
    END IF;
    IF NEW.posted_by IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Posting %s must name who records the goods leaving.', NEW.serial_no);
    END IF;
    IF auth.authority_kind = 'DAO' AND auth.status <> 'APPROVED' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Authorization %s is %s, so %s cannot be posted: goods leave only against a fully signed, released authorization.',
                               auth.serial_no, auth.status, NEW.serial_no);
    END IF;
    IF auth.authority_kind = 'CUT' AND auth.status <> 'POSTED' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Cutting order %s is %s, so %s cannot be posted: cut glass leaves only once the order is released and posted.',
                               auth.serial_no, auth.status, NEW.serial_no);
    END IF;
    gap := document_approval_gap(auth.authority_id);
    IF gap IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = gap || ' Goods do not leave until it is.';
    END IF;
    IF NEW.posted_by = auth.created_by THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s cannot be posted by the person who raised %s %s. Whoever authorizes a delivery does not also let it out of the gate.',
                               NEW.serial_no, what, auth.serial_no);
    END IF;
    -- Whoever recorded a cut (posted the cutting order) does not also let its pieces out.
    IF auth.authority_kind = 'CUT' AND NEW.posted_by = auth.posted_by THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s cannot be posted by the person who posted cutting order %s. Whoever records a cut does not also let what was cut out of the gate.',
                               NEW.serial_no, auth.serial_no);
    END IF;
    IF EXISTS (SELECT 1 FROM document_approval da JOIN workflow_step ws ON ws.id = da.workflow_step_id
                WHERE da.document_id = auth.authority_id AND da.actor_user_id = NEW.posted_by
                  AND ws.action_label = 'RELEASE') THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s cannot be posted by the person who signed the release of %s %s. Whoever releases a delivery does not also let it out of the gate. (A verifier who then loads may post; a releaser may not.)',
                               NEW.serial_no, what, auth.serial_no);
    END IF;

    -- One load, exact quantity, per line of the authority, summed over bins.
    SELECT al.line_no, al.quantity AS want, COALESCE(SUM(dl.quantity), 0) AS got INTO mm
      FROM delivery_authority_line al
      LEFT JOIN delivery_note_line dl
             ON dl.document_id = NEW.id
            AND (dl.authorization_line_id = al.id OR dl.cutting_output_id = al.id)
     WHERE al.document_id = auth.authority_id
     GROUP BY al.id, al.line_no, al.quantity, al.qty_base_uom
    HAVING COALESCE(SUM(dl.quantity), 0) <> al.quantity
        OR COALESCE(SUM(dl.qty_base_uom), 0) <> al.qty_base_uom
     ORDER BY al.line_no
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Line %s of %s %s allows %s and %s loads %s. The load must equal the %s exactly. A short load means cancelling this note and authorizing again.',
                               mm.line_no, what, auth.serial_no, mm.want, NEW.serial_no, mm.got, what);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- 8. Returns of cut glass. Whoever let the goods out does not take them
--    back (V14), and behind a note against a cutting order stand its
--    raiser, its signers and whoever recorded the cut.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION delivery_party_role(p_person UUID, p_note UUID) RETURNS TEXT AS $$
    SELECT CASE
             WHEN d.posted_by  = p_person THEN 'released'
             WHEN a.created_by = p_person THEN 'authorized'
             WHEN a.posted_by  = p_person THEN 'cut'
             WHEN d.created_by = p_person THEN 'loaded'
             WHEN EXISTS (SELECT 1 FROM document_approval da
                           WHERE da.document_id = a.id AND da.actor_user_id = p_person) THEN 'approved'
           END
      FROM document d
      JOIN delivery_note n ON n.document_id = d.id
      JOIN document a ON a.id = COALESCE(n.authorization_id, n.cutting_order_id)
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
             WHEN 'cut'        THEN 'the person who recorded the cutting behind'
             WHEN 'approved'   THEN 'a person who signed the authorization behind' END;
$$ LANGUAGE sql IMMUTABLE;

-- A return inherits the customs reference its delivery left under, from
-- either kind of authority (V14 s.2, otherwise unchanged).
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
        SELECT na.customs_reference INTO inherited
          FROM delivery_note_authority na WHERE na.note_id = NEW.delivery_note_id;
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
        RAISE EXCEPTION 'V18 cannot apply: the cutting rights leave someone in conflict. %', msg;
    END IF;
END $$;
