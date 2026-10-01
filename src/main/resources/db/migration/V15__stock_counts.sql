-- =====================================================================
-- V15 — Stock counts and variances: blind count, independent
--       verification count, adjustment tickets
--
-- SRS: FR-CNT-01..09 (counts and variances), FR-WF-03..07, FR-SEC-07,
-- FR-IN-10 (the ledger). Form CSH-012. Inventory Policy s.15: blind count,
-- independent verification count, joint sign-off.
--
-- A count (document type CNT) counts ONE location, either in FULL (every
-- place holding stock there) or PARTIALly (the items chosen, a cycle
-- count). Its life:
--
--   DRAFT    The database writes the sheet: one line per (item, bin) the
--            ledger shows holding stock, each line's BOOK quantity, value
--            and unit cost snapshotted from the ledger as the line is
--            written. Never supplied, never shown on a count page while
--            counting. Counters (count.enter) enter what they find; a
--            place found holding stock the sheet does not list is added as
--            a line of its own, weighed against its own book.
--   submit   Step 1 (WH Manager, PREPARE). Every line must be counted. The
--            database then chooses the lines the Internal Controller must
--            recount: every line whose first count differs from the book,
--            and a random sample of those that agree (count_sample_share),
--            chosen only now so no counter can know in advance which.
--   PENDING  The verification count. Whoever holds the role of the chain's
--            VERIFY step recounts, blind to the book AND to the first
--            count, and signs. A verified line's verification count
--            prevails over its first count. No line differing from the book
--            is approved on one person's count.
--   APPROVE  Finance approves the variance adjustment.
--   POST     A second Finance officer (count.post) who neither raised,
--            signed, counted nor verified posts it: ADJUSTMENT tickets move
--            the difference between what was found and the book.
--
-- Who judges a count took no part in it: whoever took part in the first
-- count signs no step after the first, approving or rejecting, nor takes
-- the verification count; whoever took the verification count signs no
-- step but the verification. And once the verification is signed the count
-- is never cancelled: what an independent recount confirmed ends posted,
-- or rejected by a signer who took no part in it.
--
-- Which lines were chosen for the verification count is the verifier's
-- alone until it is signed: the choice is every line that differs from the
-- book, so to a counter it would read the book line by line. No refusal
-- names a chosen line (refusals are on the count's trail), and the
-- application shows the choice to nobody else (CountService.mapLine).
--
-- THE FREEZE. From the moment a count opens until its verification is
-- signed (or it is cancelled or rejected), the ledger refuses any movement
-- of a counted item at the counted location: a FULL count freezes the
-- whole location, a PARTIAL count the items on its sheet. Stock moving
-- while it is being counted would make the count wrong whichever way it
-- went. Opening a line takes the same per-place lock a movement takes, so
-- a movement and a snapshot never cross. And one location carries at most
-- one live count (DRAFT, PENDING or APPROVED): a second would weigh the
-- same stock against a book the first has not yet adjusted.
--
-- Valuation. A shortage leaves at the ledger's average cost, as any issue
-- (as a write-off does). A surplus enters at the unit cost its line's book
-- carried when the line was written: the place's own average, else the
-- location's, else the item's last receipt cost at the branch, else
-- anywhere, else zero. That cost is stored on the line by the database and
-- the posted value is checked against it at commit, so the poster cannot
-- choose what found stock is worth.
--
-- DECISIONS taken here, for the client to confirm:
--   - The poster. Finance signs the chain's last step (APPROVE), and nobody
--     posts what they signed (V11), so count.post goes to Finance and a
--     SECOND Finance officer posts. A branch with one Finance officer needs
--     one covering from another branch.
--   - The verification sample: one line in ten of those agreeing with the
--     book (at least one), plus every line that does not. Changed here,
--     by migration, never at runtime.
--   - Tolerance thresholds for count variances are an open question: until
--     they are set every count takes all three signatures, however small
--     its variance, the strictest reading.
--   - Cancellation. A count may be cancelled while it is counted or awaits
--     its verification (it shows no book then), never after: the general
--     question of who may cancel a document others have signed is the
--     client's, but for a count the answer cannot be "its custodian".
--   - VR (Inventory Variance Report, VR-010) is not used as a document: a
--     count carries its own variance, signed down its own chain, and the
--     variance report is a read of the counts (/variances).
--
-- What answers to what. A CNT has its own chain, so it is its own
-- authority, like a DMG: its ADJUSTMENT tickets name it as source, and the
-- ledger asks that it be fully signed and POSTED by someone independent.
-- It relates to no other document, so document_support_link needs no new
-- branch: the ticket -> count link is the view's first branch already.
-- CNT is now a stock-moving type (V5 seeded it as not): a posted count is
-- never cancelled, and its tickets move stock only once it is POSTED.
--
-- Contents
--   1. Rights; CNT moves stock
--   2. The count and its lines
--   3. The book, the freeze, and the helpers that read them
--   4. Header and line guards: what may change, when, and by whom
--   5. Lifecycle: submit, signatures, approval, cancellation, posting,
--      commit-time
--   6. Tickets and ledger, widened for CNT; the freeze at the ledger
-- =====================================================================

SET LOCAL highbytes.migration = 'on';

-- ---------------------------------------------------------------------
-- 1. Rights, placed on the role whose step each one serves.
--
--   WH_MANAGER      PREPARE  count.create, count.enter, count.view (V5)
--   INTERNAL_CTRL   VERIFY   count.verify, count.view (V5): the
--                   verification count is its own right. It NEVER carries
--                   count.post or count.enter: it does not record, or
--                   first-count, what it tests.
--   FINANCE         APPROVE  count.approve, count.view (V5); count.post
--                   (new). Finance signs APPROVE, so the officer who posts
--                   is a second one: V11 refuses a poster who signed.
--
-- The Assistant Warehouse Manager "assists counts" (V5) by counting on the
-- floor; entering what was counted stays with the Warehouse Manager, so
-- count.enter keeps one policy holder and still stands for the Warehouse
-- Manager's side of every segregation rule (V10, right_stands_for).
-- ---------------------------------------------------------------------
INSERT INTO permission (code, module, action, description) VALUES
    ('count.post', 'count', 'POST', 'Post an approved stock count''s adjustment to the ledger');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM role r, permission p
 WHERE r.code = 'FINANCE' AND p.code = 'count.post';

UPDATE document_type SET moves_stock = TRUE WHERE code = 'CNT';

-- ---------------------------------------------------------------------
-- 2. The count and its lines.
-- ---------------------------------------------------------------------
CREATE TABLE stock_count (
    document_id        UUID PRIMARY KEY REFERENCES document(id),
    location_id        UUID NOT NULL REFERENCES location(id),
    scope              VARCHAR(8) NOT NULL CHECK (scope IN ('FULL', 'PARTIAL')),
    customs_reference  VARCHAR(80),
    CONSTRAINT cnt_customs_reference_not_blank
        CHECK (customs_reference IS NULL OR btrim(customs_reference) <> '')
);

CREATE INDEX stock_count_location ON stock_count (location_id);

COMMENT ON TABLE stock_count IS
  'Subtype of document (type CNT). One location, FULL or PARTIAL, fixed when opened. While its count and verification are open the ledger refuses movements of what it counts there.';

CREATE TABLE stock_count_line (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id      UUID NOT NULL REFERENCES stock_count(document_id),
    line_no          SMALLINT NOT NULL CHECK (line_no > 0),
    item_id          UUID NOT NULL REFERENCES item(id),
    storage_bin_id   UUID REFERENCES storage_bin(id),
    -- TRUE for a line the sheet listed when the item or location was put on
    -- it; FALSE for a place a counter found and added. Informational: what
    -- protects a line is its book (a line whose place holds stock on the
    -- book is never removed).
    on_sheet         BOOLEAN NOT NULL DEFAULT FALSE,

    -- The book, from the ledger, written by the database when the line is
    -- added and never changed. In the item's base unit.
    book_qty         NUMERIC(16,3) NOT NULL,
    book_value       NUMERIC(18,2) NOT NULL,
    unit_cost        NUMERIC(18,4) NOT NULL CHECK (unit_cost >= 0),
    booked_at        TIMESTAMPTZ NOT NULL,

    -- The first count, blind to the book. counted_by, like damage lines'
    -- entered_by, is SUPPLIED BY THE APPLICATION (the current user); the
    -- stamp is the database's.
    counted_qty      NUMERIC(16,3) CHECK (counted_qty IS NULL OR counted_qty >= 0),
    counted_by       UUID REFERENCES app_user(id),
    counted_at       TIMESTAMPTZ,
    note             VARCHAR(240),

    -- Chosen by the database at submission (section 5): a line whose first
    -- count differs from the book, or one of the random sample.
    verify_required  BOOLEAN NOT NULL DEFAULT FALSE,
    -- The verification count, blind to the book and to the first count. Its
    -- author must hold the role of the chain's VERIFY step (checked here).
    verified_qty     NUMERIC(16,3) CHECK (verified_qty IS NULL OR verified_qty >= 0),
    verified_by      UUID REFERENCES app_user(id),
    verified_at      TIMESTAMPTZ,

    -- What the count says is there: the verification count where there is
    -- one, else the first count. The variance is what the posting adjusts.
    final_qty        NUMERIC(16,3) GENERATED ALWAYS AS (COALESCE(verified_qty, counted_qty)) STORED,
    variance_qty     NUMERIC(16,3) GENERATED ALWAYS AS (COALESCE(verified_qty, counted_qty) - book_qty) STORED,

    CONSTRAINT cnt_line_count_has_author        CHECK ((counted_qty IS NULL) = (counted_by IS NULL)),
    CONSTRAINT cnt_line_verification_has_author CHECK ((verified_qty IS NULL) = (verified_by IS NULL)),
    CONSTRAINT cnt_line_one_per_place UNIQUE NULLS NOT DISTINCT (document_id, item_id, storage_bin_id),
    UNIQUE (document_id, line_no)
);

CREATE INDEX stock_count_line_item ON stock_count_line (item_id);

COMMENT ON TABLE stock_count_line IS
  'One place (item, bin) of a count: its book from the ledger (database-written), the blind first count, the blind verification count, and the variance the posting adjusts.';

-- Counting a location reads its ledger by place; the card index leads with
-- the item.
CREATE INDEX stock_movement_place ON stock_movement (location_id, item_id, storage_bin_id);

-- ---------------------------------------------------------------------
-- 3. The book, the freeze, and the helpers that read them.
-- ---------------------------------------------------------------------

-- The ledger's quantity and value at one place: an item at a location, in
-- one bin or unbinned. The authority, not the stock_balance cache.
CREATE OR REPLACE FUNCTION count_book(p_item UUID, p_location UUID, p_bin UUID)
RETURNS TABLE (qty NUMERIC, value NUMERIC) AS $$
    SELECT COALESCE(SUM(m.signed_quantity), 0),
           COALESCE(SUM(CASE m.direction WHEN 'IN' THEN m.value ELSE -m.value END), 0)
      FROM stock_movement m
     WHERE m.item_id = p_item AND m.location_id = p_location
       AND m.storage_bin_id IS NOT DISTINCT FROM p_bin;
$$ LANGUAGE sql STABLE;

-- The unit cost a surplus at this place enters at: the place's own
-- average, else the location's, else the item's last receipt cost at the
-- branch, else anywhere, else zero. Read once, when the line is written.
CREATE OR REPLACE FUNCTION count_unit_cost(p_item UUID, p_location UUID, p_bin UUID, p_branch UUID) RETURNS NUMERIC AS $$
    SELECT round(COALESCE(
        (SELECT b.value / b.qty FROM count_book(p_item, p_location, p_bin) b WHERE b.qty > 0),
        (SELECT SUM(CASE m.direction WHEN 'IN' THEN m.value ELSE -m.value END) / SUM(m.signed_quantity)
           FROM stock_movement m
          WHERE m.item_id = p_item AND m.location_id = p_location
         HAVING SUM(m.signed_quantity) > 0),
        (SELECT m.unit_cost FROM stock_movement m
          WHERE m.item_id = p_item AND m.branch_id = p_branch AND m.direction = 'IN'
          ORDER BY m.id DESC LIMIT 1),
        (SELECT m.unit_cost FROM stock_movement m
          WHERE m.item_id = p_item AND m.direction = 'IN'
          ORDER BY m.id DESC LIMIT 1),
        0), 4);
$$ LANGUAGE sql STABLE;

-- Every place at a location (of one item, or all) that holds stock on the
-- book: what a sheet must list.
CREATE OR REPLACE FUNCTION count_sheet_places(p_location UUID, p_item UUID DEFAULT NULL)
RETURNS TABLE (item_id UUID, storage_bin_id UUID) AS $$
    SELECT m.item_id, m.storage_bin_id
      FROM stock_movement m
     WHERE m.location_id = p_location AND (p_item IS NULL OR m.item_id = p_item)
     GROUP BY m.item_id, m.storage_bin_id
    HAVING SUM(m.signed_quantity) <> 0;
$$ LANGUAGE sql STABLE;

-- Whether a step of the count's chain whose action is VERIFY has signed it.
-- That signature closes the verification count, lifts the freeze and, from
-- then on, lets the book be read.
CREATE OR REPLACE FUNCTION count_verification_signed(p_doc UUID) RETURNS BOOLEAN AS $$
    SELECT EXISTS (SELECT 1 FROM document_approval da
                     JOIN workflow_step ws ON ws.id = da.workflow_step_id
                    WHERE da.document_id = p_doc AND ws.action_label = 'VERIFY'
                      AND da.decision = 'APPROVED');
$$ LANGUAGE sql STABLE;

-- Still blind: counting (DRAFT), or awaiting its verification (PENDING,
-- the VERIFY step unsigned).
CREATE OR REPLACE FUNCTION count_is_blind(p_doc UUID) RETURNS BOOLEAN AS $$
    SELECT d.status = 'DRAFT' OR (d.status = 'PENDING' AND NOT count_verification_signed(p_doc))
      FROM document d WHERE d.id = p_doc;
$$ LANGUAGE sql STABLE;

-- Whether anyone may read the count's book and variances: once its
-- verification is signed, or it reached APPROVED or POSTED. A count
-- cancelled or rejected before that never shows its book, so cancelling a
-- count is no way to read the book for the next one.
CREATE OR REPLACE FUNCTION count_book_visible(p_doc UUID) RETURNS BOOLEAN AS $$
    SELECT count_verification_signed(p_doc)
        OR EXISTS (SELECT 1 FROM document WHERE id = p_doc AND status IN ('APPROVED', 'POSTED'));
$$ LANGUAGE sql STABLE;

-- The serial of the count freezing this item at this location, or NULL.
CREATE OR REPLACE FUNCTION count_freezing(p_item UUID, p_location UUID) RETURNS TEXT AS $$
    SELECT d.serial_no
      FROM stock_count c
      JOIN document d ON d.id = c.document_id
     WHERE c.location_id = p_location
       AND d.status IN ('DRAFT', 'PENDING')
       AND count_is_blind(d.id)
       AND (c.scope = 'FULL'
            OR EXISTS (SELECT 1 FROM stock_count_line l WHERE l.document_id = c.document_id AND l.item_id = p_item))
     ORDER BY d.created_at
     LIMIT 1;
$$ LANGUAGE sql STABLE;

-- The share of lines agreeing with the book that the verification count
-- samples, at random, on top of every line that disagrees. THE CLIENT IS TO
-- SET IT; changed by migration.
CREATE OR REPLACE FUNCTION count_sample_share() RETURNS NUMERIC AS $$
    SELECT 0.10::numeric;
$$ LANGUAGE sql IMMUTABLE;

-- Writes a line for every place at the count's location that holds stock
-- on the book, of one item or (p_item NULL) of every item, skipping places
-- already on the sheet. An item chosen for a cycle count that holds nothing
-- there is still counted: it gets one unbinned line, its book zero, unless
-- p_hold_empty is FALSE (an item a counter found: the line is the found
-- place's own). Lines are numbered after the last, by item code then bin.
-- Returns how many it wrote.
CREATE OR REPLACE FUNCTION count_add_places(p_doc UUID, p_item UUID DEFAULT NULL, p_hold_empty BOOLEAN DEFAULT TRUE)
RETURNS INT AS $$
DECLARE
    loc     UUID;
    next_no INT;
    n       INT;
BEGIN
    SELECT location_id INTO loc FROM stock_count WHERE document_id = p_doc;
    IF loc IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02', MESSAGE = 'That count has no location, so it has no sheet to write.';
    END IF;
    SELECT COALESCE(MAX(line_no), 0) INTO next_no FROM stock_count_line WHERE document_id = p_doc;
    INSERT INTO stock_count_line (document_id, line_no, item_id, storage_bin_id, on_sheet)
    SELECT p_doc, next_no + ROW_NUMBER() OVER (ORDER BY i.item_code, sb.bin_code NULLS FIRST),
           p.item_id, p.storage_bin_id, TRUE
      FROM count_sheet_places(loc, p_item) p
      JOIN item i ON i.id = p.item_id
 LEFT JOIN storage_bin sb ON sb.id = p.storage_bin_id
     WHERE NOT EXISTS (SELECT 1 FROM stock_count_line l
                        WHERE l.document_id = p_doc AND l.item_id = p.item_id
                          AND l.storage_bin_id IS NOT DISTINCT FROM p.storage_bin_id);
    GET DIAGNOSTICS n = ROW_COUNT;
    IF p_item IS NOT NULL AND p_hold_empty
       AND NOT EXISTS (SELECT 1 FROM stock_count_line WHERE document_id = p_doc AND item_id = p_item) THEN
        INSERT INTO stock_count_line (document_id, line_no, item_id, storage_bin_id, on_sheet)
        VALUES (p_doc, next_no + n + 1, p_item, NULL, TRUE);
        n := n + 1;
    END IF;
    RETURN n;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- 4. Header and line guards.
--
-- The header: one location at the count's branch where stock is kept
-- (not a transit or van location: those are accounted for by the transfer
-- or delivery that moved the goods), one live count per location, a
-- customs reference when bonded stock or a bonded branch is counted.
-- Location and scope are fixed when the count opens: its lines were
-- weighed against that location's book.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION cnt_header_guard() RETURNS TRIGGER AS $$
DECLARE
    doc   RECORD;
    loc   RECORD;
    other RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'CNT', 'details');
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.document_id <> OLD.document_id OR NEW.location_id <> OLD.location_id
                             OR NEW.scope <> OLD.scope) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'A count''s location and scope are fixed when it is opened: its lines were weighed against that location''s book. Cancel it and open another.';
    END IF;
    PERFORM assert_document_is_draft_of(NEW.document_id, 'CNT', 'details');

    SELECT d.serial_no, d.branch_id, b.is_bonded AS branch_bonded INTO doc
      FROM document d JOIN branch b ON b.id = d.branch_id WHERE d.id = NEW.document_id;
    SELECT l.code, l.branch_id, l.location_type, l.is_active, l.is_bonded INTO loc
      FROM location l WHERE l.id = NEW.location_id;
    IF loc.branch_id <> doc.branch_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
              MESSAGE = format('Location %s is not at the branch %s belongs to.', loc.code, doc.serial_no);
    END IF;
    IF NOT loc.is_active THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = format('Location %s is deactivated.', loc.code);
    END IF;
    IF loc.location_type NOT IN ('WAREHOUSE', 'BONDED', 'QUARANTINE', 'CUTTING') THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
              MESSAGE = format('Stock is counted where it is kept: a warehouse, bonded, quarantine or cutting location. %s is %s: goods in transit or on a van are accounted for by the transfer or delivery that moved them.',
                               loc.code, loc.location_type);
    END IF;
    IF (loc.is_bonded OR doc.branch_bonded) AND NEW.customs_reference IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
              MESSAGE = format('%s counts bonded stock or stock at a bonded branch and needs a customs reference. Duty-suspended goods are accountable to Customs, and a count may adjust them.',
                               doc.serial_no);
    END IF;

    IF TG_OP = 'INSERT' THEN
        -- Two counts opened at one location together must see each other.
        PERFORM pg_advisory_xact_lock(hashtextextended('count:' || NEW.location_id::text, 0));
        SELECT d.serial_no, d.status INTO other
          FROM stock_count c JOIN document d ON d.id = c.document_id
         WHERE c.location_id = NEW.location_id AND c.document_id <> NEW.document_id
           AND d.status IN ('DRAFT', 'PENDING', 'APPROVED')
         ORDER BY d.created_at LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s cannot count %s: %s (%s) is already counting it. One count at a time per location: a second would weigh the same stock against a book the first has not yet adjusted.',
                                   doc.serial_no, loc.code, other.serial_no, other.status);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER stock_count_content
    BEFORE INSERT OR UPDATE OR DELETE ON stock_count
    FOR EACH ROW EXECUTE FUNCTION cnt_header_guard();

-- A FULL count's sheet is written by the database as the count opens, so
-- no place holding stock can be left off it.
CREATE OR REPLACE FUNCTION cnt_write_full_sheet() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.scope = 'FULL' THEN
        PERFORM count_add_places(NEW.document_id, NULL);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER stock_count_full_sheet
    AFTER INSERT ON stock_count
    FOR EACH ROW EXECUTE FUNCTION cnt_write_full_sheet();

-- ---------------------------------------------------------------------
-- The lines.
--   INSERT  only while DRAFT; the bin is the counted location's; the book
--           is the ledger's, read under the same per-place lock a movement
--           takes (LedgerService and V12), so a snapshot and a movement
--           never cross; a line starts with no verification.
--   UPDATE  the place and its book never change. The first count and the
--           note change only while DRAFT. The verification count changes
--           only while the count awaits its verification, and only by
--           someone who holds the role of the chain's VERIFY step today at
--           the count's branch, who took no part in the first count (of any
--           line: a recount by one of the counters is not independent),
--           did not raise the count and has not signed it. Stamps are the
--           database's.
--   DELETE  only while DRAFT, and never a line whose place holds stock on
--           the book: a count does not drop a place it must count.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION cnt_line_guard() RETURNS TRIGGER AS $$
DECLARE
    h         RECORD;
    bin       RECORD;
    book      RECORD;
    vstep     RECORD;
    vname     TEXT;
    counting  BOOLEAN;
    verifying BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM assert_document_is_draft_of(OLD.document_id, 'CNT', 'lines');
        IF OLD.book_qty <> 0 THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Line %s of %s cannot be removed: the book holds stock there, so it must be counted. A count never drops a place that holds stock; enter 0 if nothing is there.',
                                   OLD.line_no, (SELECT serial_no FROM document WHERE id = OLD.document_id));
        END IF;
        RETURN OLD;
    END IF;

    SELECT d.serial_no, d.status, d.branch_id, d.created_by, c.location_id INTO h
      FROM document d JOIN stock_count c ON c.document_id = d.id WHERE d.id = NEW.document_id;

    IF TG_OP = 'INSERT' THEN
        PERFORM assert_document_is_draft_of(NEW.document_id, 'CNT', 'lines');
        IF NEW.storage_bin_id IS NOT NULL THEN
            SELECT sb.location_id, sb.bin_code INTO bin FROM storage_bin sb WHERE sb.id = NEW.storage_bin_id;
            IF bin.location_id <> h.location_id THEN
                RAISE EXCEPTION USING ERRCODE = '23514',
                      MESSAGE = format('Bin %s is not in the location %s counts.', bin.bin_code, h.serial_no);
            END IF;
        END IF;
        IF NEW.verified_qty IS NOT NULL OR NEW.verified_by IS NOT NULL OR NEW.verify_required THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Line %s of %s starts with no verification: the verification count is taken after the count is submitted.',
                                   NEW.line_no, h.serial_no);
        END IF;
        PERFORM pg_advisory_xact_lock(hashtextextended('stock:' || NEW.item_id::text || ':' || h.location_id::text, 0));
        SELECT b.qty, b.value INTO book FROM count_book(NEW.item_id, h.location_id, NEW.storage_bin_id) b;
        NEW.book_qty    := book.qty;
        NEW.book_value  := book.value;
        NEW.unit_cost   := count_unit_cost(NEW.item_id, h.location_id, NEW.storage_bin_id, h.branch_id);
        NEW.booked_at   := now();
        NEW.counted_at  := CASE WHEN NEW.counted_qty IS NULL THEN NULL ELSE now() END;
        NEW.verified_at := NULL;
        RETURN NEW;
    END IF;

    IF (NEW.document_id, NEW.line_no, NEW.item_id, NEW.storage_bin_id, NEW.on_sheet,
        NEW.book_qty, NEW.book_value, NEW.unit_cost, NEW.booked_at)
       IS DISTINCT FROM
       (OLD.document_id, OLD.line_no, OLD.item_id, OLD.storage_bin_id, OLD.on_sheet,
        OLD.book_qty, OLD.book_value, OLD.unit_cost, OLD.booked_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Line %s of %s: the place it counts and the book it is weighed against are fixed when it is added. The book is the ledger''s and is never entered.',
                               OLD.line_no, h.serial_no);
    END IF;

    counting  := (NEW.counted_qty, NEW.counted_by, NEW.note, NEW.verify_required)
                 IS DISTINCT FROM (OLD.counted_qty, OLD.counted_by, OLD.note, OLD.verify_required);
    verifying := (NEW.verified_qty, NEW.verified_by) IS DISTINCT FROM (OLD.verified_qty, OLD.verified_by);

    IF counting THEN
        PERFORM assert_document_is_draft_of(NEW.document_id, 'CNT', 'first count');
    END IF;
    NEW.counted_at := CASE WHEN (NEW.counted_qty, NEW.counted_by) IS NOT DISTINCT FROM (OLD.counted_qty, OLD.counted_by)
                           THEN OLD.counted_at
                           WHEN NEW.counted_qty IS NULL THEN NULL
                           ELSE now() END;

    IF verifying THEN
        IF h.status <> 'PENDING' OR count_verification_signed(NEW.document_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is %s: the verification count is taken while the count awaits its verification, and is fixed once the verification is signed.',
                                   h.serial_no, CASE WHEN h.status = 'PENDING' THEN 'verified' ELSE h.status END);
        END IF;
        IF NEW.verified_by IS NOT NULL THEN
            SELECT ws.required_role_id, r.name AS role_name INTO vstep
              FROM document d
              JOIN workflow_step ws ON ws.workflow_definition_id = d.workflow_definition_id
              JOIN role r ON r.id = ws.required_role_id
             WHERE d.id = NEW.document_id AND ws.action_label = 'VERIFY'
             ORDER BY ws.sequence_no LIMIT 1;
            IF NOT FOUND THEN
                RAISE EXCEPTION USING ERRCODE = '23Z02',
                      MESSAGE = format('The approval chain of %s has no verification step, so nobody can take its verification count.', h.serial_no);
            END IF;
            SELECT full_name INTO vname FROM app_user WHERE id = NEW.verified_by;
            IF NOT EXISTS (
                    SELECT 1 FROM user_role ur
                      JOIN app_user u ON u.id = ur.user_id AND u.is_active
                      JOIN role r ON r.id = ur.role_id AND r.is_active
                     WHERE ur.user_id = NEW.verified_by AND ur.role_id = vstep.required_role_id
                       AND ur.revoked_at IS NULL
                       AND ur.valid_from <= kigali_today()
                       AND (ur.valid_to IS NULL OR ur.valid_to >= kigali_today())
                       AND (ur.branch_id IS NULL OR ur.branch_id = h.branch_id)) THEN
                RAISE EXCEPTION USING ERRCODE = '23Z02',
                      MESSAGE = format('%s does not hold %s today at this branch, so cannot take the verification count of %s. It is taken by whoever signs its verification step.',
                                       COALESCE(vname, 'That person'), vstep.role_name, h.serial_no);
            END IF;
            IF EXISTS (SELECT 1 FROM stock_count_line x
                        WHERE x.document_id = NEW.document_id AND x.counted_by = NEW.verified_by) THEN
                RAISE EXCEPTION USING ERRCODE = '23Z02',
                      MESSAGE = format('%s took part in the first count of %s and cannot take its verification count. The verification count is an independent person''s.',
                                       vname, h.serial_no);
            END IF;
            IF NEW.verified_by = h.created_by THEN
                RAISE EXCEPTION USING ERRCODE = '23Z02',
                      MESSAGE = format('%s raised %s and cannot take its verification count.', vname, h.serial_no);
            END IF;
            IF EXISTS (SELECT 1 FROM document_approval WHERE document_id = NEW.document_id AND actor_user_id = NEW.verified_by) THEN
                RAISE EXCEPTION USING ERRCODE = '23Z02',
                      MESSAGE = format('%s signed %s and cannot take its verification count.', vname, h.serial_no);
            END IF;
        END IF;
    END IF;
    NEW.verified_at := CASE WHEN (NEW.verified_qty, NEW.verified_by) IS NOT DISTINCT FROM (OLD.verified_qty, OLD.verified_by)
                            THEN OLD.verified_at
                            WHEN NEW.verified_qty IS NULL THEN NULL
                            ELSE now() END;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER stock_count_line_content
    BEFORE INSERT OR UPDATE OR DELETE ON stock_count_line
    FOR EACH ROW EXECUTE FUNCTION cnt_line_guard();

-- ---------------------------------------------------------------------
-- 5. Lifecycle.
--
-- Submit (DRAFT -> PENDING): the sheet has lines, every one counted, and
-- every place holding stock that it must list is on it; then the database
-- chooses the lines to verify. Named to fire before V11's
-- document_lifecycle, as V14's submit guard does, so it runs while the
-- lines may still change.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION cnt_submit_guard() RETURNS TRIGGER AS $$
DECLARE
    h       RECORD;
    missing RECORD;
    bad     INT;
    agree   INT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'CNT') THEN
        RETURN NEW;
    END IF;
    SELECT c.location_id, c.scope, l.code, l.branch_id INTO h
      FROM stock_count c JOIN location l ON l.id = c.location_id WHERE c.document_id = NEW.id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s has no location yet and cannot be submitted.', NEW.serial_no);
    END IF;
    IF h.branch_id <> NEW.branch_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s counts %s, which is not at its branch.', NEW.serial_no, h.code);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM stock_count_line WHERE document_id = NEW.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s has no lines and cannot be submitted: there would be nothing to sign.', NEW.serial_no);
    END IF;
    SELECT l.line_no INTO bad FROM stock_count_line l
     WHERE l.document_id = NEW.id AND l.counted_qty IS NULL ORDER BY l.line_no LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Line %s of %s has not been counted. Every line is counted before the count is submitted; enter 0 where nothing was found.',
                               bad, NEW.serial_no);
    END IF;

    -- The location is frozen, so the book now is the book the lines hold.
    SELECT i.item_code, sb.bin_code INTO missing
      FROM count_sheet_places(h.location_id) p
      JOIN item i ON i.id = p.item_id
 LEFT JOIN storage_bin sb ON sb.id = p.storage_bin_id
     WHERE (h.scope = 'FULL'
            OR EXISTS (SELECT 1 FROM stock_count_line x WHERE x.document_id = NEW.id AND x.item_id = p.item_id))
       AND NOT EXISTS (SELECT 1 FROM stock_count_line x
                        WHERE x.document_id = NEW.id AND x.item_id = p.item_id
                          AND x.storage_bin_id IS NOT DISTINCT FROM p.storage_bin_id)
     ORDER BY i.item_code, sb.bin_code NULLS FIRST LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s %s holds stock on the book at %s and is not on the sheet of %s. Add it and count it: a count weighs every place of what it counts.',
                               missing.item_code, COALESCE('in bin ' || missing.bin_code, 'unbinned'), h.code, NEW.serial_no);
    END IF;

    -- Which lines the verification count must cover: every line whose first
    -- count differs from the book, and a random sample of those that agree,
    -- chosen now, when no counter can change a count any more.
    UPDATE stock_count_line SET verify_required = (counted_qty <> book_qty) WHERE document_id = NEW.id;
    SELECT COUNT(*) INTO agree FROM stock_count_line WHERE document_id = NEW.id AND counted_qty = book_qty;
    IF agree > 0 THEN
        UPDATE stock_count_line SET verify_required = TRUE
         WHERE id IN (SELECT id FROM stock_count_line
                       WHERE document_id = NEW.id AND counted_qty = book_qty
                       ORDER BY random()
                       LIMIT GREATEST(1, ceil(agree * count_sample_share()))::int);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_cnt_submit
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (OLD.status = 'DRAFT' AND NEW.status = 'PENDING')
    EXECUTE FUNCTION cnt_submit_guard();

-- Signatures on a count, approvals and rejections alike.
--
-- Independence. Whoever took part in the first count signs no step but the
-- chain's first: preparing the count is the custodian's, judging it is not.
-- No segregation rule pairs the Warehouse Manager with Finance, so one
-- person may hold both; that person may count, but then neither approves
-- nor rejects the variance their count produced. Whoever took the
-- verification count signs no step but the verification.
--
-- The verification signature: nobody signs a count's VERIFY step while a
-- line chosen for the verification count, or a line whose first count
-- differs from the book, has not been verified. The refusal names no line:
-- it is on the count's trail, and which lines were chosen is not for the
-- counters to learn.
--
-- Named to fire after V11's document_approval_signing_rules, so an
-- out-of-order or wrong-role signature still meets that trigger's message
-- first.
CREATE OR REPLACE FUNCTION cnt_signature_guard() RETURNS TRIGGER AS $$
DECLARE
    doc   RECORD;
    step  RECORD;
    first INT;
    who   TEXT;
BEGIN
    SELECT d.serial_no, d.workflow_definition_id INTO doc
      FROM document d JOIN document_type dt ON dt.id = d.document_type_id AND dt.code = 'CNT'
     WHERE d.id = NEW.document_id;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;
    SELECT ws.sequence_no, ws.action_label INTO step FROM workflow_step ws WHERE ws.id = NEW.workflow_step_id;
    SELECT MIN(sequence_no) INTO first FROM workflow_step WHERE workflow_definition_id = doc.workflow_definition_id;
    who := COALESCE((SELECT full_name FROM app_user WHERE id = NEW.actor_user_id), 'That person');

    IF step.sequence_no <> first
       AND EXISTS (SELECT 1 FROM stock_count_line
                    WHERE document_id = NEW.document_id AND counted_by = NEW.actor_user_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s took part in the first count of %s and cannot sign its %s step. A count is prepared by those who took it and judged by those who did not.',
                               who, doc.serial_no, lower(step.action_label));
    END IF;
    IF step.action_label <> 'VERIFY'
       AND EXISTS (SELECT 1 FROM stock_count_line
                    WHERE document_id = NEW.document_id AND verified_by = NEW.actor_user_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s took the verification count of %s, so the only step of it they sign is the verification.',
                               who, doc.serial_no);
    END IF;

    IF NEW.decision = 'APPROVED' AND step.action_label = 'VERIFY'
       AND EXISTS (SELECT 1 FROM stock_count_line l
                    WHERE l.document_id = NEW.document_id AND l.verified_qty IS NULL
                      AND (l.verify_required OR l.counted_qty IS DISTINCT FROM l.book_qty)) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s still has lines chosen for the verification count that have not been recounted. The verification is signed only once every chosen line has been counted again.',
                               doc.serial_no);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_approval_verification_counted
    BEFORE INSERT ON document_approval
    FOR EACH ROW EXECUTE FUNCTION cnt_signature_guard();

-- Approval (PENDING -> APPROVED): the same rule again, whatever the chain
-- looks like, so a variance is never approved on one person's count even
-- under a chain with no VERIFY step. Named to fire after V11's
-- document_lifecycle and V12's document_lifecycle_cancel.
CREATE OR REPLACE FUNCTION cnt_approve_guard() RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'CNT') THEN
        RETURN NEW;
    END IF;
    -- No line is named: before approval the book may still be unread.
    IF EXISTS (SELECT 1 FROM stock_count_line l
                WHERE l.document_id = NEW.id
                  AND (l.counted_qty IS NULL
                       OR (l.verified_qty IS NULL AND (l.verify_required OR l.counted_qty <> l.book_qty)))) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s cannot be approved: a line that differs from the book on its first count, or was chosen for the verification count, has not been counted again. A variance is never approved on one person''s count.',
                               NEW.serial_no);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_lifecycle_count_verified
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (OLD.status = 'PENDING' AND NEW.status = 'APPROVED')
    EXECUTE FUNCTION cnt_approve_guard();

-- Cancellation (-> CANCELLED): never once the verification is signed. From
-- then on the count's variances are known, confirmed by an independent
-- recount, and readable by whoever cancels it. Withdrawn, they would leave
-- the books, the open variances and the dashboard as though never found,
-- and the book they showed would be the next count's answer sheet. A count
-- whose verification is signed ends posted, or rejected by a later signer
-- who took no part in it (section 5, signatures). Before then a cancelled
-- count never shows its book. Named to fire after V11's document_lifecycle
-- and V12's document_lifecycle_cancel, so an illegal move or a posted count
-- meets their messages first.
CREATE OR REPLACE FUNCTION cnt_cancel_guard() RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'CNT') THEN
        RETURN NEW;
    END IF;
    IF count_verification_signed(NEW.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s cannot be cancelled: its verification count is signed, so what it found is known and confirmed. It ends by being posted, or rejected by a signer who took no part in it; withdrawing it would drop differences an independent recount confirmed.',
                               NEW.serial_no);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_lifecycle_cancel_count
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (OLD.status <> 'CANCELLED' AND NEW.status = 'CANCELLED')
    EXECUTE FUNCTION cnt_cancel_guard();

-- Posting (-> POSTED): V11 refuses a poster who raised or signed the count;
-- this adds that whoever counted or verified a line of it does not post it
-- either. Named to fire before V11's document_lifecycle, as V14's does.
CREATE OR REPLACE FUNCTION cnt_post_guard() RETURNS TRIGGER AS $$
DECLARE
    h   RECORD;
    who RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM document_type WHERE id = NEW.document_type_id AND code = 'CNT') THEN
        RETURN NEW;
    END IF;
    SELECT c.location_id, l.code, l.branch_id INTO h
      FROM stock_count c JOIN location l ON l.id = c.location_id WHERE c.document_id = NEW.id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s has no location recorded and cannot be posted.', NEW.serial_no);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM stock_count_line WHERE document_id = NEW.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s has no lines and cannot be posted.', NEW.serial_no);
    END IF;
    IF h.branch_id <> NEW.branch_id THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s counts %s, which is not at its branch.', NEW.serial_no, h.code);
    END IF;
    IF NEW.posted_by IS NOT NULL THEN
        SELECT l.line_no, CASE WHEN l.counted_by = NEW.posted_by THEN 'counted' ELSE 'verified' END AS what INTO who
          FROM stock_count_line l
         WHERE l.document_id = NEW.id AND NEW.posted_by IN (l.counted_by, l.verified_by)
         ORDER BY l.line_no LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s cannot be posted by the person who %s line %s of it. Whoever counted the stock does not also record the adjustment.',
                                   NEW.serial_no, who.what, who.line_no);
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_cnt_post
    BEFORE UPDATE ON document
    FOR EACH ROW WHEN (NEW.status = 'POSTED' AND OLD.status IS DISTINCT FROM 'POSTED')
    EXECUTE FUNCTION cnt_post_guard();

-- A posted count must have adjusted the ledger by every variance, checked
-- at commit: each line whose variance is not zero has its ADJUSTMENT
-- movement, IN for a surplus, OUT for a shortage. A line with no variance
-- has none (its ticket line could not carry a zero quantity).
CREATE OR REPLACE FUNCTION cnt_posted_must_have_moved_stock() RETURNS TRIGGER AS $$
DECLARE
    ln RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM stock_count WHERE document_id = NEW.id) THEN
        RETURN NULL;
    END IF;
    FOR ln IN SELECT line_no, variance_qty FROM stock_count_line
               WHERE document_id = NEW.id AND variance_qty <> 0 ORDER BY line_no LOOP
        IF NOT EXISTS (
               SELECT 1
                 FROM transaction_ticket t
                 JOIN ticket_line tl   ON tl.ticket_id = t.document_id AND tl.line_no = ln.line_no
                 JOIN stock_movement m ON m.ticket_line_id = tl.id AND m.reverses_movement_id IS NULL
                WHERE t.source_document_id = NEW.id AND t.movement_type = 'ADJUSTMENT'
                  AND t.direction = CASE WHEN ln.variance_qty > 0 THEN 'IN' ELSE 'OUT' END) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is posted but line %s never adjusted the ledger by its variance of %s. A count is posted together with its adjustment, or not at all.',
                                   NEW.serial_no, ln.line_no, ln.variance_qty);
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER document_cnt_posted_moved_stock
    AFTER UPDATE ON document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION cnt_posted_must_have_moved_stock();

-- Value, checked at commit: a surplus enters at the unit cost its line's
-- book carried when it was written, round(variance x unit cost, 2). A
-- shortage leaves at the ledger's average cost, the ordinary issue, with no
-- stated value to check (as a write-off).
CREATE OR REPLACE FUNCTION cnt_value_must_follow_rule() RETURNS TRIGGER AS $$
DECLARE
    ln RECORD;
    v  NUMERIC;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM stock_count WHERE document_id = NEW.id) THEN
        RETURN NULL;
    END IF;
    FOR ln IN SELECT line_no, variance_qty, unit_cost FROM stock_count_line
               WHERE document_id = NEW.id AND variance_qty > 0 ORDER BY line_no LOOP
        SELECT m.value INTO v
          FROM transaction_ticket t
          JOIN ticket_line tl   ON tl.ticket_id = t.document_id AND tl.line_no = ln.line_no
          JOIN stock_movement m ON m.ticket_line_id = tl.id AND m.reverses_movement_id IS NULL
         WHERE t.source_document_id = NEW.id AND t.movement_type = 'ADJUSTMENT' AND t.direction = 'IN';
        -- A missing movement is the moved-stock trigger's to refuse.
        CONTINUE WHEN v IS NULL;
        IF v <> round(ln.variance_qty * ln.unit_cost, 2) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s line %s brings %s found in at %s, but at the unit cost its book carried when it was counted (%s) it is worth %s. Found stock enters at the cost the count weighed it at, never at a value chosen when posting.',
                                   NEW.serial_no, ln.line_no, ln.variance_qty, v, ln.unit_cost,
                                   round(ln.variance_qty * ln.unit_cost, 2));
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER document_cnt_value_rule
    AFTER UPDATE ON document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM 'POSTED' AND NEW.status = 'POSTED')
    EXECUTE FUNCTION cnt_value_must_follow_rule();

-- ---------------------------------------------------------------------
-- 6. Tickets and ledger, widened for CNT; and the freeze at the ledger.
--
-- A count posts at most two tickets: ADJUSTMENT OUT of the counted
-- location for its shortages and ADJUSTMENT IN to it for its surpluses,
-- one line per line with a variance, numbered as the count's line, in the
-- item's base unit, in the line's bin, under the count's customs reference.
-- The three V14 functions below are replaced whole, with what a count adds;
-- every other source's path is unchanged.
-- ---------------------------------------------------------------------
CREATE UNIQUE INDEX transaction_ticket_one_adjustment_per_source_direction
    ON transaction_ticket (source_document_id, direction)
    WHERE movement_type = 'ADJUSTMENT' AND source_document_id IS NOT NULL;

-- The freeze. Named to fire after V4's stock_movement_not_into_locked_day
-- and before V11's stock_movement_today_only.
CREATE OR REPLACE FUNCTION stock_movement_not_during_count() RETURNS TRIGGER AS $$
DECLARE
    serial TEXT;
    it     TEXT;
    loc    TEXT;
BEGIN
    serial := count_freezing(NEW.item_id, NEW.location_id);
    IF serial IS NOT NULL THEN
        SELECT item_code INTO it FROM item WHERE id = NEW.item_id;
        SELECT code INTO loc FROM location WHERE id = NEW.location_id;
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is being counted (%s), so %s cannot move in or out of it until the count''s verification is signed, or the count is cancelled. Stock that moves while it is being counted makes the count wrong.',
                               loc, serial, it);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER stock_movement_not_while_counted
    BEFORE INSERT ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION stock_movement_not_during_count();

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
    -- receipt leg), a DMG (a write-off, loss, return or release) or a CNT
    -- (an ADJUSTMENT). Any other source, or none, is refused here as well as
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
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s cannot be supported by %s: a ticket answers only to a goods received note, a delivery note, a transfer, a transfer receipt, a return and damage report or a stock count. An authorization or any other document does not move stock by itself.', tdoc.serial_no, src.serial_no);
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
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s has no receipt, delivery, transfer, transfer receipt, report or count behind it, so it can carry no lines.', tk.serial_no);
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
                    WHERE s.id = tk.source_document_id AND st.code IN ('GRN', 'DN', 'TRF', 'TRR', 'DMG', 'CNT')) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Ticket %s answers to no goods received note, delivery note, transfer, transfer receipt, return and damage report or stock count, so its stock does not move. An authorization alone lets nothing leave.', tdoc.serial_no);
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
-- The right placed above is judged for everyone, now, as it will be at
-- commit.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    msg TEXT;
BEGIN
    msg := access_conflict_anywhere();
    IF msg IS NOT NULL THEN
        RAISE EXCEPTION 'V15 cannot apply: the count rights leave someone in conflict. %', msg;
    END IF;
END $$;
