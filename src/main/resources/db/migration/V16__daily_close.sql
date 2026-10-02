-- =====================================================================
-- V16 — The daily close: reconciled by Finance, countersigned and locked
--       by the Internal Controller
--
-- SRS: FR-CNT-10..12 (the daily close), FR-IN-10 (the ledger), FR-SEC-07.
-- Board Paper HB/BD/2026/09-05: one office authorised, executed, held
-- custody of, recorded AND reconciled the same transactions. The close is
-- where the last two are pulled apart.
--
-- A close is one branch's business day. Its life:
--
--   OPEN        Prepared by the nightly job (or by the reconciler) for a
--               day that is over. Carries nothing: no figure, no signature.
--   RECONCILED  A Finance officer (close.reconcile) has reviewed the day's
--               ledger and signed it. The DATABASE writes the figures as it
--               signs: the value the branch opened the day with, the
--               receipts, dispatches and adjustments, the closing value,
--               the number of movements, and how many exceptions it found
--               (close_exceptions). A day with exceptions is reconciled
--               only with a note saying what they are and why it closes.
--   LOCKED      The Internal Controller (close.lock) has countersigned it,
--               and that signature locks the date: the ledger refuses any
--               movement dated into it, or before it. The figures are
--               recomputed at that moment and must read as reconciled.
--               A locked day is never reopened, changed or deleted.
--
-- The Internal Controller may instead RETURN a reconciled close, with a
-- reason, to OPEN: it is reconciled again, by whoever holds the right.
-- Nobody both reconciles and countersigns one day.
--
-- Days close in order. A day is reconciled, and locked, only once every
-- earlier day on which stock moved at the branch is locked: otherwise a
-- later lock would seal figures whose opening still depends on an open day.
-- A day on which nothing moved needs no close (it may have one).
--
-- The ledger already dates every movement the day it is recorded (V11),
-- so a past day's figures do not change once it is over. The lock is the
-- second wall: whatever path writes a movement, nothing is dated into a
-- reconciled or locked day, or before the last one, at that branch.
--
-- DECISIONS taken here, for the client to confirm:
--   - Finance reconciles and the Internal Controller countersigns, which
--     locks the day (the client's choice, 1 October 2026). V5 had given
--     close.lock to the Managing Director; it moves to the Internal
--     Controller, relabelled VERIFY: locking a day posts nothing, and the
--     Internal Controller still holds no posting right.
--   - The reconciler may have posted some of the day's movements: a branch
--     with one Finance officer could otherwise never close a day on which
--     it posted. It is an exception (RECONCILER_POSTED), so the day is
--     signed only with a note on it, and the Internal Controller, who
--     countersigns, reads it. Whether to forbid it outright is open.
--   - A day is not locked automatically, however long it stays open: a
--     machine never signs. The nightly job prepares the closes; overdue
--     ones show on the screens.
--
-- Contents
--   1. Rights: close.lock to the Internal Controller
--   2. The close's new columns and its shape
--   3. Who holds a right; the day's figures; its exceptions; order
--   4. The close guard: what may change, when, and by whom
--   5. The ledger: nothing dated into or before a signed day
-- =====================================================================

SET LOCAL highbytes.migration = 'on';

-- ---------------------------------------------------------------------
-- 1. Rights.
--
--   FINANCE         close.reconcile (V5): reviews and signs the day.
--   INTERNAL_CTRL   close.lock (moved from the Managing Director):
--                   countersigns, which locks the date. Relabelled VERIFY,
--                   its duty unchanged (TRANSACT, V9): the Internal
--                   Controller carries it itself, so holding it breaks no
--                   assurance rule, and it posts nothing.
-- ---------------------------------------------------------------------
UPDATE permission
   SET action = 'VERIFY',
       description = 'Countersign the daily close, which locks the business date'
 WHERE code = 'close.lock';

UPDATE permission
   SET description = 'Reconcile the daily close: review the day''s ledger and sign it'
 WHERE code = 'close.reconcile';

DELETE FROM role_permission
 WHERE role_id = (SELECT id FROM role WHERE code = 'MANAGING_DIR')
   AND permission_id = (SELECT id FROM permission WHERE code = 'close.lock');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM role r, permission p
 WHERE r.code = 'INTERNAL_CTRL' AND p.code = 'close.lock';

-- ---------------------------------------------------------------------
-- 2. The close. V4 made the table; what it lacked was any rule.
-- ---------------------------------------------------------------------
ALTER TABLE daily_close
    -- How many exceptions the database found when it was reconciled.
    ADD COLUMN exception_count INTEGER,
    -- The last time the Internal Controller sent it back (the audit trail
    -- keeps every time). Supplied by the application, checked here.
    ADD COLUMN returned_by     UUID REFERENCES app_user(id),
    ADD COLUMN returned_at     TIMESTAMPTZ,
    ADD COLUMN return_reason   VARCHAR(400),
    ADD CONSTRAINT daily_close_signed_has_figures
        CHECK (status = 'OPEN'
               OR (reconciled_by IS NOT NULL AND reconciled_at IS NOT NULL
                   AND opening_value IS NOT NULL AND receipts_value IS NOT NULL
                   AND dispatches_value IS NOT NULL AND adjustments_value IS NOT NULL
                   AND closing_value IS NOT NULL AND movement_count IS NOT NULL
                   AND exception_count IS NOT NULL)),
    ADD CONSTRAINT daily_close_locked_is_countersigned
        CHECK (status <> 'LOCKED' OR (internal_controller_id IS NOT NULL AND controller_signed_at IS NOT NULL)),
    ADD CONSTRAINT daily_close_note_not_blank
        CHECK (exceptions_note IS NULL OR btrim(exceptions_note) <> ''),
    ADD CONSTRAINT daily_close_return_has_reason
        CHECK ((returned_by IS NULL) = (return_reason IS NULL)
               AND (return_reason IS NULL OR btrim(return_reason) <> ''));

COMMENT ON TABLE daily_close IS
  'FR-CNT-10..12. One branch''s business day: opening + receipts - dispatches + adjustments = closing, written from the ledger by the database when Finance reconciles it, countersigned by the Internal Controller, which locks the date. Days close in order; a locked day is never reopened.';

-- ---------------------------------------------------------------------
-- 3. Helpers.
-- ---------------------------------------------------------------------

-- Whether a user holds a right at a branch today: an active user, through
-- an active role granted there (or everywhere), live today, not revoked.
CREATE OR REPLACE FUNCTION user_holds_right_at(p_user UUID, p_right TEXT, p_branch UUID) RETURNS BOOLEAN AS $$
    SELECT EXISTS (
        SELECT 1
          FROM user_role ur
          JOIN app_user u         ON u.id = ur.user_id AND u.is_active
          JOIN role r             ON r.id = ur.role_id AND r.is_active
          JOIN role_permission rp ON rp.role_id = r.id
          JOIN permission p       ON p.id = rp.permission_id AND p.code = p_right
         WHERE ur.user_id = p_user
           AND ur.revoked_at IS NULL
           AND ur.valid_from <= kigali_today()
           AND (ur.valid_to IS NULL OR ur.valid_to >= kigali_today())
           AND (ur.branch_id IS NULL OR ur.branch_id = p_branch));
$$ LANGUAGE sql STABLE;

-- The day's figures, from the ledger: the authority, not stock_balance.
-- Every movement of the branch falls in exactly one bucket, so
-- opening + receipts - dispatches + adjustments = closing always holds.
--   adjustments  damage, count adjustments, cutting: signed, IN less OUT
--   receipts     every other movement IN (receipts, returns, transfers in,
--                and the transit leg of a transfer out)
--   dispatches   every other movement OUT (deliveries, transfers out)
-- Values are the ledger's, IN positive and OUT negative.
CREATE OR REPLACE FUNCTION close_figures(p_branch UUID, p_date DATE)
RETURNS TABLE (opening_value NUMERIC, receipts_value NUMERIC, dispatches_value NUMERIC,
               adjustments_value NUMERIC, closing_value NUMERIC, movement_count INTEGER) AS $$
    WITH m AS (
        SELECT m.business_date,
               m.direction,
               CASE m.direction WHEN 'IN' THEN m.value ELSE -m.value END AS signed_value,
               COALESCE(t.movement_type, '') IN ('DAMAGE', 'ADJUSTMENT', 'CUT_CONSUME', 'CUT_OUTPUT') AS adjusting
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

-- What the reconciler must account for, each with a sentence saying why:
--   OPENING     the ledger opens the day at another value than the last
--               locked close closed at: something was written into a
--               closed day (the lock should have made that impossible)
--   NEGATIVE    a place (item, location, bin) below zero on the book at
--               the end of the day
--   NOT_POSTED  a stock-moving document fully approved by the end of the
--               day and not on the ledger by then: signed for, not
--               recorded (a cut-off item). Tickets are left out: they post
--               with the document they answer to
--   CACHE       stock_balance disagreeing with the ledger at a place of the
--               branch, read NOW (it holds no history): a fault to report
--   RECONCILER_POSTED
--               the person reconciling (p_reconciler, when named) posted
--               some of the day's movements: the day is signed by someone
--               who recorded part of it, which the Board's finding is about
-- The end of a day is midnight in Kigali.
CREATE OR REPLACE FUNCTION close_exceptions(p_branch UUID, p_date DATE, p_reconciler UUID DEFAULT NULL)
RETURNS TABLE (kind TEXT, detail TEXT) AS $$
DECLARE
    day_end TIMESTAMPTZ := (p_date + 1)::timestamp AT TIME ZONE 'Africa/Kigali';
    f       RECORD;
    prev    RECORD;
BEGIN
    SELECT * INTO f FROM close_figures(p_branch, p_date);
    SELECT c.business_date, c.closing_value INTO prev
      FROM daily_close c
     WHERE c.branch_id = p_branch AND c.status = 'LOCKED' AND c.business_date < p_date
     ORDER BY c.business_date DESC LIMIT 1;
    IF FOUND AND prev.closing_value IS DISTINCT FROM f.opening_value THEN
        kind := 'OPENING';
        detail := format('The ledger opens the day at %s, but the close of %s locked at %s: something was written into a closed day.',
                         f.opening_value, to_char(prev.business_date, 'DD Mon YYYY'), prev.closing_value);
        RETURN NEXT;
    END IF;

    RETURN QUERY
    SELECT 'NEGATIVE'::text,
           format('%s at %s%s stood at %s at the end of the day: below zero on the book.',
                  i.item_code, l.code, COALESCE(' in bin ' || sb.bin_code, ''), q.qty)
      FROM (SELECT m.item_id, m.location_id, m.storage_bin_id, SUM(m.signed_quantity) AS qty
              FROM stock_movement m
             WHERE m.branch_id = p_branch AND m.business_date <= p_date
             GROUP BY m.item_id, m.location_id, m.storage_bin_id
            HAVING SUM(m.signed_quantity) < 0) q
      JOIN item i     ON i.id = q.item_id
      JOIN location l ON l.id = q.location_id
 LEFT JOIN storage_bin sb ON sb.id = q.storage_bin_id
     ORDER BY i.item_code, l.code, sb.bin_code NULLS FIRST;

    RETURN QUERY
    SELECT 'NOT_POSTED'::text,
           format('%s (%s) was approved on %s and was not on the ledger by the end of the day.',
                  d.serial_no, dt.name, to_char(d.approved_at AT TIME ZONE 'Africa/Kigali', 'DD Mon YYYY HH24:MI'))
      FROM document d
      JOIN document_type dt ON dt.id = d.document_type_id AND dt.moves_stock AND dt.code <> 'TT'
     WHERE d.branch_id = p_branch
       AND d.approved_at IS NOT NULL AND d.approved_at < day_end
       AND (d.posted_at IS NULL OR d.posted_at >= day_end)
       AND (d.status <> 'CANCELLED' OR d.cancelled_at >= day_end)
     ORDER BY d.approved_at, d.serial_no;

    RETURN QUERY
    SELECT 'CACHE'::text,
           format('The balance kept for %s at %s%s reads %s (value %s); the ledger reads %s (value %s). Checked when reconciled, not as at the day: report it.',
                  i.item_code, l.code, COALESCE(' in bin ' || sb.bin_code, ''),
                  COALESCE(c.qty, 0), COALESCE(c.val, 0), COALESCE(g.qty, 0), COALESCE(g.val, 0))
      FROM (SELECT m.item_id, m.location_id, m.storage_bin_id,
                   SUM(m.signed_quantity) AS qty,
                   SUM(CASE m.direction WHEN 'IN' THEN m.value ELSE -m.value END) AS val
              FROM stock_movement m JOIN location lx ON lx.id = m.location_id AND lx.branch_id = p_branch
             GROUP BY m.item_id, m.location_id, m.storage_bin_id) g
      FULL JOIN
           (SELECT b.item_id, b.location_id, b.storage_bin_id, b.qty_on_hand AS qty, b.total_value AS val
              FROM stock_balance b JOIN location lx ON lx.id = b.location_id AND lx.branch_id = p_branch) c
        ON c.item_id = g.item_id AND c.location_id = g.location_id
       AND c.storage_bin_id IS NOT DISTINCT FROM g.storage_bin_id
      JOIN item i     ON i.id = COALESCE(g.item_id, c.item_id)
      JOIN location l ON l.id = COALESCE(g.location_id, c.location_id)
 LEFT JOIN storage_bin sb ON sb.id = COALESCE(g.storage_bin_id, c.storage_bin_id)
     WHERE COALESCE(c.qty, 0) <> COALESCE(g.qty, 0) OR COALESCE(c.val, 0) <> COALESCE(g.val, 0)
     ORDER BY i.item_code, l.code;

    IF p_reconciler IS NOT NULL THEN
        RETURN QUERY
        SELECT 'RECONCILER_POSTED'::text,
               format('%s posted %s of the day''s %s movements and reconciles it: the day is signed by someone who recorded part of it.',
                      COALESCE((SELECT full_name FROM app_user WHERE id = p_reconciler), 'The reconciler'),
                      x.mine, x.total)
          FROM (SELECT COUNT(*) FILTER (WHERE m.posted_by = p_reconciler) AS mine, COUNT(*) AS total
                  FROM stock_movement m
                 WHERE m.branch_id = p_branch AND m.business_date = p_date) x
         WHERE x.mine > 0;
    END IF;
END;
$$ LANGUAGE plpgsql STABLE;

-- Every reconciled or locked close (of one branch, or all) whose ledger no
-- longer reads as it was signed: its closing value or its number of
-- movements differs from the ledger now. The lock makes that impossible
-- except for someone who can set the ledger's triggers aside, so a row here
-- means exactly that. Read by the register and by the nightly job.
CREATE OR REPLACE FUNCTION close_drift(p_branch UUID DEFAULT NULL)
RETURNS TABLE (close_id UUID, branch_id UUID, business_date DATE, status TEXT,
               signed_closing NUMERIC, ledger_closing NUMERIC, signed_count INTEGER, ledger_count INTEGER) AS $$
    WITH per_day AS (
        SELECT m.branch_id, m.business_date,
               SUM(CASE m.direction WHEN 'IN' THEN m.value ELSE -m.value END) AS v,
               COUNT(*)::int AS n
          FROM stock_movement m
         WHERE p_branch IS NULL OR m.branch_id = p_branch
         GROUP BY m.branch_id, m.business_date),
    run AS (
        SELECT d.branch_id, d.business_date, d.n,
               SUM(d.v) OVER (PARTITION BY d.branch_id ORDER BY d.business_date) AS closing
          FROM per_day d)
    SELECT c.id, c.branch_id, c.business_date, c.status::text,
           c.closing_value, COALESCE(r.closing, 0)::numeric(18,2), c.movement_count, COALESCE(dn.n, 0)
      FROM daily_close c
 LEFT JOIN LATERAL (SELECT run.closing FROM run
                     WHERE run.branch_id = c.branch_id AND run.business_date <= c.business_date
                     ORDER BY run.business_date DESC LIMIT 1) r ON TRUE
 LEFT JOIN run dn ON dn.branch_id = c.branch_id AND dn.business_date = c.business_date
     WHERE c.status IN ('RECONCILED', 'LOCKED')
       AND (p_branch IS NULL OR c.branch_id = p_branch)
       AND (c.closing_value IS DISTINCT FROM COALESCE(r.closing, 0)::numeric(18,2)
            OR c.movement_count IS DISTINCT FROM COALESCE(dn.n, 0));
$$ LANGUAGE sql STABLE;

-- The first day before p_date on which stock moved at the branch and
-- which is not locked, or NULL. Days with movements lock in order, so only
-- the days after the last lock need reading.
CREATE OR REPLACE FUNCTION close_unlocked_day_before(p_branch UUID, p_date DATE) RETURNS DATE AS $$
    SELECT MIN(m.business_date)
      FROM stock_movement m
     WHERE m.branch_id = p_branch
       AND m.business_date < p_date
       AND m.business_date > COALESCE((SELECT MAX(c.business_date) FROM daily_close c
                                        WHERE c.branch_id = p_branch AND c.status = 'LOCKED'
                                          AND c.business_date < p_date), '-infinity'::date)
       AND NOT EXISTS (SELECT 1 FROM daily_close c
                        WHERE c.branch_id = p_branch AND c.business_date = m.business_date AND c.status = 'LOCKED');
$$ LANGUAGE sql STABLE;

-- ---------------------------------------------------------------------
-- 4. The close guard.
--
--   INSERT  OPEN and empty, for a day that is over.
--   UPDATE  OPEN -> RECONCILED by a holder of close.reconcile at the
--           branch, once every earlier day with movements is locked; the
--           figures and the exception count are the database's; a note
--           when there are exceptions.
--           RECONCILED -> LOCKED by a holder of close.lock who did not
--           reconcile it, the figures still reading as reconciled.
--           RECONCILED -> OPEN (returned) by a holder of close.lock, with
--           a reason; the reconciliation is cleared.
--           Nothing else: an open close changes only by being reconciled,
--           a reconciled one only by being locked or returned, a locked
--           one never.
--   DELETE  never: a close is history.
--
-- A signature takes the branch's close lock; every movement takes it
-- shared (section 5), so figures are never read while a movement into
-- the branch is still in flight.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION close_guard() RETURNS TRIGGER AS $$
DECLARE
    br   TEXT;
    on_day TEXT;
    f    RECORD;
    gap  DATE;
    n    INT;
    who  TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('The close of %s is history and is never deleted.', to_char(OLD.business_date, 'DD Mon YYYY'));
    END IF;

    SELECT name INTO br FROM branch WHERE id = NEW.branch_id;
    on_day := to_char(NEW.business_date, 'DD Mon YYYY');

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN'
           OR (NEW.opening_value, NEW.receipts_value, NEW.dispatches_value, NEW.adjustments_value, NEW.closing_value,
               NEW.movement_count, NEW.exception_count, NEW.reconciled_by, NEW.reconciled_at,
               NEW.internal_controller_id, NEW.controller_signed_at, NEW.locked_at, NEW.exceptions_note,
               NEW.returned_by, NEW.returned_at, NEW.return_reason) IS DISTINCT FROM
              (NULL::numeric, NULL::numeric, NULL::numeric, NULL::numeric, NULL::numeric,
               NULL::int, NULL::int, NULL::uuid, NULL::timestamptz,
               NULL::uuid, NULL::timestamptz, NULL::timestamptz, NULL::text,
               NULL::uuid, NULL::timestamptz, NULL::text) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('The close of %s at %s starts OPEN and empty: its figures are the ledger''s, written when it is reconciled, and its signatures are given, never inserted.', on_day, br);
        END IF;
        IF NEW.business_date >= kigali_today() THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is not over yet in Kigali, so it cannot be closed at %s.', on_day, br);
        END IF;
        RETURN NEW;
    END IF;

    IF (NEW.id, NEW.branch_id, NEW.business_date) IS DISTINCT FROM (OLD.id, OLD.branch_id, OLD.business_date) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'Which branch and which day a close is for never changes.';
    END IF;
    IF OLD.status = 'LOCKED' THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is locked at %s: a locked day is never reopened or changed. What was wrong is corrected by a document dated today.', on_day, br);
    END IF;
    IF NEW IS NOT DISTINCT FROM OLD THEN
        RETURN NEW;
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended('close:' || NEW.branch_id::text, 0));

    -- Reconciled: Finance signs, the database writes the figures.
    IF OLD.status = 'OPEN' AND NEW.status = 'RECONCILED' THEN
        IF NEW.business_date >= kigali_today() THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s is not over yet in Kigali, so it cannot be reconciled at %s.', on_day, br);
        END IF;
        IF (NEW.internal_controller_id, NEW.controller_signed_at, NEW.locked_at)
           IS DISTINCT FROM (NULL::uuid, NULL::timestamptz, NULL::timestamptz)
           OR (NEW.returned_by, NEW.returned_at, NEW.return_reason)
           IS DISTINCT FROM (OLD.returned_by, OLD.returned_at, OLD.return_reason) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Reconciling the close of %s signs it as reconciled, and nothing else: the countersignature is the Internal Controller''s.', on_day);
        END IF;
        who := COALESCE((SELECT full_name FROM app_user WHERE id = NEW.reconciled_by), 'Nobody');
        IF NEW.reconciled_by IS NULL OR NOT user_holds_right_at(NEW.reconciled_by, 'close.reconcile', NEW.branch_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s does not hold close.reconcile at %s today, so cannot reconcile its close.', who, br);
        END IF;
        gap := close_unlocked_day_before(NEW.branch_id, NEW.business_date);
        IF gap IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s cannot be reconciled at %s before %s is locked: stock moved that day. Days close in order, so that no lock seals figures that still rest on an open day.',
                                   on_day, br, to_char(gap, 'DD Mon YYYY'));
        END IF;
        SELECT * INTO f FROM close_figures(NEW.branch_id, NEW.business_date);
        NEW.opening_value     := f.opening_value;
        NEW.receipts_value    := f.receipts_value;
        NEW.dispatches_value  := f.dispatches_value;
        NEW.adjustments_value := f.adjustments_value;
        NEW.closing_value     := f.closing_value;
        NEW.movement_count    := f.movement_count;
        SELECT COUNT(*) INTO n FROM close_exceptions(NEW.branch_id, NEW.business_date, NEW.reconciled_by);
        NEW.exception_count   := n;
        IF n > 0 AND (NEW.exceptions_note IS NULL OR btrim(NEW.exceptions_note) = '') THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('The close of %s at %s has %s exception%s. Reconciling it says what %s and why the day still closes: write that in the note.',
                                   on_day, br, n, CASE WHEN n = 1 THEN '' ELSE 's' END, CASE WHEN n = 1 THEN 'it is' ELSE 'they are' END);
        END IF;
        NEW.reconciled_at := now();
        RETURN NEW;
    END IF;

    -- Locked: the Internal Controller countersigns, the date locks.
    IF OLD.status = 'RECONCILED' AND NEW.status = 'LOCKED' THEN
        who := COALESCE((SELECT full_name FROM app_user WHERE id = NEW.internal_controller_id), 'Nobody');
        IF NEW.internal_controller_id IS NULL
           OR NOT user_holds_right_at(NEW.internal_controller_id, 'close.lock', NEW.branch_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s does not hold close.lock at %s today, so cannot countersign its close.', who, br);
        END IF;
        IF NEW.internal_controller_id = OLD.reconciled_by THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s reconciled the close of %s and cannot also countersign it. The day is locked by someone who did not reconcile it.', who, on_day);
        END IF;
        IF (NEW.opening_value, NEW.receipts_value, NEW.dispatches_value, NEW.adjustments_value, NEW.closing_value,
            NEW.movement_count, NEW.exception_count, NEW.reconciled_by, NEW.reconciled_at, NEW.exceptions_note,
            NEW.returned_by, NEW.returned_at, NEW.return_reason)
           IS DISTINCT FROM
           (OLD.opening_value, OLD.receipts_value, OLD.dispatches_value, OLD.adjustments_value, OLD.closing_value,
            OLD.movement_count, OLD.exception_count, OLD.reconciled_by, OLD.reconciled_at, OLD.exceptions_note,
            OLD.returned_by, OLD.returned_at, OLD.return_reason) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('The close of %s is countersigned as it was reconciled: its figures, its note and its reconciler do not change.', on_day);
        END IF;
        gap := close_unlocked_day_before(NEW.branch_id, NEW.business_date);
        IF gap IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s cannot be locked at %s before %s is: stock moved that day. Days close in order.',
                                   on_day, br, to_char(gap, 'DD Mon YYYY'));
        END IF;
        SELECT * INTO f FROM close_figures(NEW.branch_id, NEW.business_date);
        IF (f.opening_value, f.receipts_value, f.dispatches_value, f.adjustments_value, f.closing_value, f.movement_count)
           IS DISTINCT FROM
           (OLD.opening_value, OLD.receipts_value, OLD.dispatches_value, OLD.adjustments_value, OLD.closing_value, OLD.movement_count) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('The ledger for %s at %s no longer reads as it was reconciled (closing %s, now %s; %s movements, now %s). Return it to be reconciled again.',
                                   on_day, br, OLD.closing_value, f.closing_value, OLD.movement_count, f.movement_count);
        END IF;
        NEW.controller_signed_at := now();
        NEW.locked_at := now();
        RETURN NEW;
    END IF;

    -- Returned: the Internal Controller sends it back to be reconciled again.
    IF OLD.status = 'RECONCILED' AND NEW.status = 'OPEN' THEN
        who := COALESCE((SELECT full_name FROM app_user WHERE id = NEW.returned_by), 'Nobody');
        IF NEW.returned_by IS NULL OR NOT user_holds_right_at(NEW.returned_by, 'close.lock', NEW.branch_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s does not hold close.lock at %s today. A reconciled close is returned by whoever would countersign it.', who, br);
        END IF;
        IF NEW.returned_by = OLD.reconciled_by THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('%s reconciled the close of %s and cannot also return it.', who, on_day);
        END IF;
        IF NEW.return_reason IS NULL OR btrim(NEW.return_reason) = '' THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Returning the close of %s needs a reason: say what is to be looked at again.', on_day);
        END IF;
        IF (NEW.internal_controller_id, NEW.controller_signed_at, NEW.locked_at)
           IS DISTINCT FROM (NULL::uuid, NULL::timestamptz, NULL::timestamptz) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('A returned close of %s carries no countersignature.', on_day);
        END IF;
        NEW.returned_at       := now();
        NEW.reconciled_by     := NULL;
        NEW.reconciled_at     := NULL;
        NEW.opening_value     := NULL;
        NEW.receipts_value    := NULL;
        NEW.dispatches_value  := NULL;
        NEW.adjustments_value := NULL;
        NEW.closing_value     := NULL;
        NEW.movement_count    := NULL;
        NEW.exception_count   := NULL;
        NEW.exceptions_note   := NULL;
        RETURN NEW;
    END IF;

    IF OLD.status = NEW.status THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('The close of %s at %s is %s and changes only by being %s.', on_day, br, OLD.status,
                               CASE OLD.status WHEN 'OPEN' THEN 'reconciled' ELSE 'countersigned, or returned' END);
    END IF;
    RAISE EXCEPTION USING ERRCODE = '23Z02',
          MESSAGE = format('A close goes OPEN, RECONCILED, LOCKED, or back from RECONCILED to OPEN when returned. %s to %s is not a step.',
                           OLD.status, NEW.status);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER daily_close_guard
    BEFORE INSERT OR UPDATE OR DELETE ON daily_close
    FOR EACH ROW EXECUTE FUNCTION close_guard();

-- ---------------------------------------------------------------------
-- 5. The ledger. V4 refused a movement dated into a LOCKED day. A movement
--    is now refused dated into, or before, the branch's last reconciled or
--    locked day: a reconciled day's figures are signed, and every day
--    before a signed one is closed or held nothing. The trigger keeps its
--    name and its SQLSTATE (restrict_violation), so it still speaks first.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION stock_movement_respects_close() RETURNS TRIGGER AS $$
DECLARE
    signed RECORD;
BEGIN
    -- Shared: movements do not wait for each other, only for a close being signed.
    PERFORM pg_advisory_xact_lock_shared(hashtextextended('close:' || NEW.branch_id::text, 0));
    SELECT c.business_date, c.status INTO signed
      FROM daily_close c
     WHERE c.branch_id = NEW.branch_id
       AND c.status IN ('RECONCILED', 'LOCKED')
       AND c.business_date >= NEW.business_date
     ORDER BY c.business_date DESC
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = 'restrict_violation',
              MESSAGE = format('Business date %s is closed at this branch: the close of %s is %s. A movement cannot be backdated into a closed day, or before one.',
                               NEW.business_date, signed.business_date, lower(signed.status));
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
    msg TEXT := access_conflict_anywhere();
BEGIN
    IF msg IS NOT NULL THEN
        RAISE EXCEPTION 'V16 leaves someone in conflict: %', msg;
    END IF;
END $$;
