-- =====================================================================
-- V17 — The administration screens' rules: approval chains and branches
--
-- SRS: FR-WF-01..03 (approval chains, bound by creation date), FR-ADM
-- (branches), FR-SEC-05 (the audit trail, read on its own screen).
--
-- APPROVAL CHAINS. Until now nothing in the database stopped an UPDATE to a
-- chain's steps, even on a chain documents were halfway through: a
-- document "finishes under the chain it began with" only as long as nobody
-- edited that chain. The System Administrator holds admin.workflow and by
-- invariant 8 takes no part in operations; letting that office rewrite who
-- approves stock leaving would be the Board's finding in another form. So:
--
--   - A chain's steps, their roles and their order change only by a
--     reviewed migration, and never once a document is bound to the chain,
--     migration or not. A different chain is a new version, from a date.
--   - A chain is added or removed only by a reviewed migration. One that
--     documents are bound to is never removed (the foreign key already
--     says so; this says why).
--   - Outside a migration the only change is when one version gives way to
--     the next, and only in the future: a date that has passed is history,
--     and a chain is never put in force, or out of it, for a day already
--     begun. A chain that follows no other, or gives way to none, keeps its
--     start or its end: whether a type has a chain at all is a migration's
--     decision. Since
--     every document is dated the day it is created (V11), a document
--     already bound stays inside its chain's dates.
--   - A switchover moves both chains together: at commit, no day between
--     two versions of a chain is left with neither in force (V11 would
--     refuse every document of that type raised on it).
--
-- BRANCHES. A branch is configuration, never a code change; it is also
-- what serials, the ledger, the daily close and every grant are keyed on.
--
--   - A branch's code never changes: every serial raised there carries it.
--   - Its type and whether it is bonded are fixed once a document has been
--     raised there or stock has moved there: the customs reference a
--     bonded branch demands was judged on each document as it was then.
--     A bonded-type branch is bonded. There is one main branch.
--   - A branch is deactivated, never deleted, and only once it is finished
--     with: it holds no stock, no document there is still open, no
--     transfer to it is still to arrive, and every day on which stock moved
--     there is locked. The main branch stays active.
--   - Nothing new starts at an inactive branch: no document, no location,
--     no role granted there, no transfer addressed to it. Reactivating it
--     is configuration again.
--   - Branch names are Latin script and distinct, as role names are
--     (V10): a branch is named as text in the audit trail, and a Cyrillic
--     "а" would make a second branch that reads exactly like the first.
--   - Whether a branch is active changes where people may work, so it
--     restamps everyone: each session picks it up on its next request.
--
-- DECISIONS taken here, for the client to confirm:
--   - Moving a switchover is the System Administrator's (admin.workflow),
--     needs a reason, and is recorded; the chain's steps are not.
--   - A branch closes only once its days are locked: a branch closes its
--     books before it closes.
--
-- Contents
--   1. Approval chains: steps frozen, definitions dated forward only
--   2. Branches: what is fixed, when one may close, nothing new at a
--      closed one
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Approval chains.
-- ---------------------------------------------------------------------

-- Whether any document is bound to the chain: it then finishes under it.
CREATE OR REPLACE FUNCTION workflow_definition_bound(p_def UUID) RETURNS BOOLEAN AS $$
    SELECT EXISTS (SELECT 1 FROM document WHERE workflow_definition_id = p_def);
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION workflow_definition_label(p_def UUID) RETURNS TEXT AS $$
    SELECT format('%s v%s', dt.name, wd.version)
      FROM workflow_definition wd
      JOIN document_type dt ON dt.id = wd.document_type_id
     WHERE wd.id = p_def;
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION workflow_step_guard() RETURNS TRIGGER AS $$
DECLARE
    def UUID;
BEGIN
    FOR def IN
        SELECT DISTINCT d
          FROM unnest(ARRAY[CASE WHEN TG_OP <> 'INSERT' THEN OLD.workflow_definition_id END,
                            CASE WHEN TG_OP <> 'DELETE' THEN NEW.workflow_definition_id END]) AS d
         WHERE d IS NOT NULL
    LOOP
        IF workflow_definition_bound(def) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Documents are bound to the %s chain, so its steps never change: a document '
                                   'finishes under the chain it began with. A different chain is a new version, '
                                   'in force from a date to come.', workflow_definition_label(def));
        END IF;
    END LOOP;
    IF NOT in_migration() THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = 'An approval chain''s steps, the roles that sign them and their order change only by a reviewed migration.';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workflow_step_guard
    BEFORE INSERT OR UPDATE OR DELETE ON workflow_step
    FOR EACH ROW EXECUTE FUNCTION workflow_step_guard();

CREATE OR REPLACE FUNCTION workflow_definition_guard() RETURNS TRIGGER AS $$
DECLARE
    today DATE := kigali_today();
    label TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF workflow_definition_bound(OLD.id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Documents are bound to the %s chain, so it is never removed.',
                                   workflow_definition_label(OLD.id));
        END IF;
        IF NOT in_migration() THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = 'An approval chain is added or removed only by a reviewed migration.';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NOT in_migration() THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = 'An approval chain is added or removed only by a reviewed migration.';
        END IF;
        RETURN NEW;
    END IF;

    label := workflow_definition_label(OLD.id);

    -- What a bound document points at stays what it was.
    IF (NEW.id, NEW.document_type_id, NEW.version, NEW.basis)
       IS DISTINCT FROM (OLD.id, OLD.document_type_id, OLD.version, OLD.basis)
       AND workflow_definition_bound(OLD.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Documents are bound to the %s chain, so its document type, version and basis never change.', label);
    END IF;

    IF in_migration() THEN
        RETURN NEW;
    END IF;

    IF (NEW.id, NEW.document_type_id, NEW.version, NEW.basis, NEW.notes, NEW.created_at, NEW.created_by)
       IS DISTINCT FROM (OLD.id, OLD.document_type_id, OLD.version, OLD.basis, OLD.notes, OLD.created_at, OLD.created_by) THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('Only the dates the %s chain is in force between change here; the rest only by a reviewed migration.', label);
    END IF;

    IF NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
        -- A switchover moves its two rows one after the other: the chain before
        -- it ends at the old date or already at the new one.
        IF NOT EXISTS (SELECT 1 FROM workflow_definition p
                        WHERE p.document_type_id = OLD.document_type_id AND p.id <> OLD.id
                          AND p.effective_to IN (OLD.effective_from, NEW.effective_from)) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('The %s chain follows no other, so when it begins is decided by a reviewed migration; only a switchover between two chains moves here.', label);
        END IF;
        IF OLD.effective_from <= today THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('The %s chain has been in force since %s. That is history: the date it began does not move.',
                                   label, OLD.effective_from);
        END IF;
        IF NEW.effective_from <= today THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('The %s chain can come into force on %s at the earliest: a chain is never put in force for a day already begun.',
                                   label, today + 1);
        END IF;
    END IF;

    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
        IF OLD.effective_to IS NULL OR NEW.effective_to IS NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Whether the %s chain ends at all is decided by a reviewed migration; only the date of a switchover between two chains moves here.', label);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM workflow_definition n
                        WHERE n.document_type_id = OLD.document_type_id AND n.id <> OLD.id
                          AND n.effective_from IN (OLD.effective_to, NEW.effective_to)) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('The %s chain gives way to no other, so when it ends is decided by a reviewed migration; only a switchover between two chains moves here.', label);
        END IF;
        IF OLD.effective_to <= today THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('The %s chain ended on %s. That is history: the date it ended does not move.',
                                   label, OLD.effective_to - 1);
        END IF;
        IF NEW.effective_to <= today THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('The %s chain can end with %s at the earliest: a chain is never taken out of force for a day already begun.',
                                   label, today);
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workflow_definition_guard
    BEFORE INSERT OR UPDATE OR DELETE ON workflow_definition
    FOR EACH ROW EXECUTE FUNCTION workflow_definition_guard();

-- At commit: between two versions of a chain, no day is left uncovered.
-- Two versions never overlap (V3), so a difference between one's end and
-- the next one's start is a gap.
CREATE OR REPLACE FUNCTION workflow_definition_contiguous() RETURNS TRIGGER AS $$
DECLARE
    gap RECORD;
BEGIN
    IF in_migration() THEN
        RETURN NULL;
    END IF;
    SELECT dt.name, a.effective_to AS ends, nxt.effective_from AS starts INTO gap
      FROM workflow_definition a
      JOIN document_type dt ON dt.id = a.document_type_id
      JOIN LATERAL (SELECT b.effective_from
                      FROM workflow_definition b
                     WHERE b.document_type_id = a.document_type_id
                       AND b.effective_from > a.effective_from
                     ORDER BY b.effective_from
                     LIMIT 1) nxt ON TRUE
     WHERE a.document_type_id = NEW.document_type_id
       AND a.effective_to IS DISTINCT FROM nxt.effective_from
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('No %s chain would be in force from %s to %s, so no such document could be raised then. '
                               'A switchover moves the end of one chain and the start of the next together.',
                               gap.name, gap.ends, gap.starts - 1);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER workflow_definition_contiguous
    AFTER UPDATE ON workflow_definition
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION workflow_definition_contiguous();

-- ---------------------------------------------------------------------
-- 2. Branches.
-- ---------------------------------------------------------------------
ALTER TABLE branch
    ADD CONSTRAINT branch_bonded_type CHECK (branch_type <> 'BONDED' OR is_bonded);

ALTER TABLE branch ADD CONSTRAINT branch_name_readable
    CHECK (name ~ '^[A-Za-z0-9À-ɏ &()''.,/–—-]+$'
           AND name = btrim(name) AND name !~ '  ')
    NOT VALID;

CREATE UNIQUE INDEX branch_name_unique ON branch (lower(name));

-- One main branch: users with no branch of their own land there.
CREATE UNIQUE INDEX branch_one_main ON branch ((TRUE)) WHERE branch_type = 'MAIN';

-- Whether the branch has a past: a document raised there, or stock moved there.
CREATE OR REPLACE FUNCTION branch_has_history(p_branch UUID) RETURNS BOOLEAN AS $$
    SELECT EXISTS (SELECT 1 FROM document WHERE branch_id = p_branch)
        OR EXISTS (SELECT 1 FROM stock_movement WHERE branch_id = p_branch)
        OR EXISTS (SELECT 1 FROM stock_movement m JOIN location l ON l.id = m.location_id
                    WHERE l.branch_id = p_branch);
$$ LANGUAGE sql STABLE;

-- Why the branch cannot be deactivated yet, or NULL when it can. Names no
-- quantity: the book of a count is on no page (V15).
CREATE OR REPLACE FUNCTION branch_deactivation_blocker(p_branch UUID) RETURNS TEXT AS $$
DECLARE
    b RECORD;
    r RECORD;
BEGIN
    SELECT name, branch_type, is_active INTO b FROM branch WHERE id = p_branch;
    IF NOT FOUND THEN
        RETURN 'There is no such branch.';
    END IF;
    IF b.branch_type = 'MAIN' THEN
        RETURN format('%s is the main branch, where anyone with no branch of their own works, so it stays active.', b.name);
    END IF;

    SELECT l.code AS location, i.item_code AS item INTO r
      FROM stock_movement m
      JOIN location l ON l.id = m.location_id
      JOIN item i     ON i.id = m.item_id
     WHERE l.branch_id = p_branch
     GROUP BY l.code, i.item_code
    HAVING SUM(m.signed_quantity) <> 0
     ORDER BY l.code, i.item_code
     LIMIT 1;
    IF FOUND THEN
        RETURN format('%s still holds stock (%s at %s, perhaps more). It is moved out, or written off, first.',
                      b.name, r.item, r.location);
    END IF;

    SELECT d.serial_no, d.status INTO r
      FROM document d
      JOIN document_type dt ON dt.id = d.document_type_id
     WHERE d.branch_id = p_branch
       AND (d.status IN ('DRAFT', 'PENDING') OR (d.status = 'APPROVED' AND dt.moves_stock))
     ORDER BY d.created_at
     LIMIT 1;
    IF FOUND THEN
        RETURN format('%s has documents still open (%s is %s, perhaps more). Each is finished, or cancelled, first.',
                      b.name, r.serial_no, lower(r.status));
    END IF;

    SELECT d.serial_no INTO r
      FROM transfer_order t
      JOIN document d ON d.id = t.document_id
      JOIN location l ON l.id = t.to_location_id
     WHERE l.branch_id = p_branch
       AND (d.status IN ('DRAFT', 'PENDING', 'APPROVED')
            OR (d.status = 'POSTED'
                AND NOT EXISTS (SELECT 1 FROM transfer_receipt tr
                                  JOIN document rd ON rd.id = tr.document_id AND rd.status = 'POSTED'
                                 WHERE tr.transfer_id = t.document_id)))
     ORDER BY d.created_at
     LIMIT 1;
    IF FOUND THEN
        RETURN format('Transfer %s is still to arrive at %s. It is received, or cancelled, first.', r.serial_no, b.name);
    END IF;

    SELECT days.business_date INTO r
      FROM (SELECT DISTINCT business_date FROM stock_movement WHERE branch_id = p_branch) days
     WHERE NOT EXISTS (SELECT 1 FROM daily_close c
                        WHERE c.branch_id = p_branch AND c.business_date = days.business_date
                          AND c.status = 'LOCKED')
     ORDER BY days.business_date
     LIMIT 1;
    IF FOUND THEN
        RETURN format('Stock moved at %s on %s and that day is not locked. A branch closes its books before it closes.',
                      b.name, r.business_date);
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;   -- volatile: each query reads what has committed since, see branch_guard

CREATE OR REPLACE FUNCTION branch_guard() RETURNS TRIGGER AS $$
DECLARE
    msg TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF NOT in_migration() THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('A branch is deactivated, never deleted: %s stays on record.', OLD.name);
        END IF;
        RETURN OLD;
    END IF;

    IF NOT in_migration() THEN
        IF NEW.id <> OLD.id OR NEW.code <> OLD.code THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('A branch''s code never changes: every serial raised at %s carries %s.', OLD.name, OLD.code);
        END IF;
        IF (NEW.branch_type, NEW.is_bonded) IS DISTINCT FROM (OLD.branch_type, OLD.is_bonded)
           AND branch_has_history(OLD.id) THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = format('Documents have been raised, or stock has moved, at %s, so its type and whether it is bonded are fixed: '
                                   'each document was judged against them as they were.', OLD.name);
        END IF;
        IF NEW.created_at <> OLD.created_at THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02',
                  MESSAGE = 'When a branch was created does not change.';
        END IF;
    END IF;

    IF OLD.is_active AND NOT NEW.is_active THEN
        -- Waits for any document being raised there to commit, then judges it.
        msg := branch_deactivation_blocker(OLD.id);
        IF msg IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23Z02', MESSAGE = msg;
        END IF;
    END IF;

    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER branch_guard
    BEFORE UPDATE OR DELETE ON branch
    FOR EACH ROW EXECUTE FUNCTION branch_guard();

-- Where people may work has changed: every session reloads on its next request.
CREATE OR REPLACE FUNCTION branch_restamp() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.is_active IS DISTINCT FROM OLD.is_active THEN
        UPDATE app_user SET security_stamp = gen_random_uuid();
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER branch_security_stamp
    AFTER UPDATE ON branch
    FOR EACH ROW EXECUTE FUNCTION branch_restamp();

-- Nothing new starts at an inactive branch. The share lock makes a
-- deactivation wait for a document being raised there, and the other way
-- round, so neither judges the other by a state about to change.
CREATE OR REPLACE FUNCTION assert_branch_active(p_branch UUID, p_what TEXT) RETURNS VOID AS $$
DECLARE
    b RECORD;
BEGIN
    SELECT name, is_active INTO b FROM branch WHERE id = p_branch FOR SHARE;
    IF FOUND AND NOT b.is_active THEN
        RAISE EXCEPTION USING ERRCODE = '23Z02',
              MESSAGE = format('%s is not active, so %s.', b.name, p_what);
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION document_at_active_branch() RETURNS TRIGGER AS $$
BEGIN
    PERFORM assert_branch_active(NEW.branch_id, 'no document is raised there');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_at_active_branch
    BEFORE INSERT ON document
    FOR EACH ROW EXECUTE FUNCTION document_at_active_branch();

CREATE OR REPLACE FUNCTION location_at_active_branch() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.branch_id IS DISTINCT FROM OLD.branch_id
       OR (NEW.is_active AND NOT OLD.is_active) THEN
        PERFORM assert_branch_active(NEW.branch_id, 'no location is added or reopened there');
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER location_at_active_branch
    BEFORE INSERT OR UPDATE ON location
    FOR EACH ROW EXECUTE FUNCTION location_at_active_branch();

CREATE OR REPLACE FUNCTION user_role_at_active_branch() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.branch_id IS NOT NULL THEN
        PERFORM assert_branch_active(NEW.branch_id, 'no role is granted there');
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER user_role_at_active_branch
    BEFORE INSERT ON user_role
    FOR EACH ROW EXECUTE FUNCTION user_role_at_active_branch();

CREATE OR REPLACE FUNCTION transfer_to_active_branch() RETURNS TRIGGER AS $$
DECLARE
    dest UUID;
BEGIN
    IF TG_OP = 'INSERT' OR NEW.to_location_id IS DISTINCT FROM OLD.to_location_id THEN
        SELECT branch_id INTO dest FROM location WHERE id = NEW.to_location_id;
        PERFORM assert_branch_active(dest, 'no transfer is addressed to it');
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transfer_to_active_branch
    BEFORE INSERT OR UPDATE ON transfer_order
    FOR EACH ROW EXECUTE FUNCTION transfer_to_active_branch();

COMMENT ON FUNCTION branch_deactivation_blocker(UUID) IS
  'Why a branch cannot be deactivated yet (stock held, a document open, a transfer to arrive, a day with movements not locked; the main branch never), or NULL. Names no quantity.';
