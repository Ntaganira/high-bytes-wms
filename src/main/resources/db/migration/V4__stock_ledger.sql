-- =====================================================================
-- V4 — The stock ledger and the daily close
--
-- SRS: FR-IN-10, FR-OUT-11, FR-CNT-10..12, FR-SEC-07
--
-- The ledger is the only writer of stock. Three guarantees are structural:
--
--   1. Append-only. No UPDATE, no DELETE. A mistake is corrected by a
--      reversing movement that references the original.
--   2. No movement without a document. Every row carries a document_id.
--   3. A locked business date refuses any movement dated inside it —
--      refused at the ledger, not discouraged in the interface.
--
-- stock_balance is a derived aggregate. It can always be rebuilt from
-- stock_movement, and a rebuild that disagrees with it is a bug worth
-- knowing about.
-- =====================================================================

-- ---------------------------------------------------------------------
-- The transaction ticket. Present from day one even though the 2026 chain
-- does not name it: under that chain it is generated behind the GRN or the
-- delivery authorization; under the 2027 chain it is raised explicitly and
-- countersigned. Same table, different workflow definition.
-- ---------------------------------------------------------------------
CREATE TABLE transaction_ticket (
    document_id          UUID PRIMARY KEY REFERENCES document(id),
    movement_type        VARCHAR(24) NOT NULL
                         CHECK (movement_type IN ('RECEIPT','TRANSFER_OUT','TRANSFER_IN',
                                                  'ISSUE_TO_VAN','DELIVERY','RETURN',
                                                  'DAMAGE','CUT_CONSUME','CUT_OUTPUT',
                                                  'ADJUSTMENT')),
    direction            VARCHAR(4) NOT NULL CHECK (direction IN ('IN','OUT')),
    from_location_id     UUID REFERENCES location(id),
    to_location_id       UUID REFERENCES location(id),
    -- The commercial document this ticket answers to. A ticket no document
    -- supports is not countersigned, and the stock does not move.
    source_document_id   UUID REFERENCES document(id),
    customs_reference    VARCHAR(80),
    total_value          NUMERIC(18,2),
    CONSTRAINT ticket_has_a_side
        CHECK (from_location_id IS NOT NULL OR to_location_id IS NOT NULL)
);

CREATE TABLE ticket_line (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id      UUID NOT NULL REFERENCES transaction_ticket(document_id),
    line_no        SMALLINT NOT NULL,
    item_id        UUID NOT NULL REFERENCES item(id),
    quantity       NUMERIC(16,3) NOT NULL CHECK (quantity > 0),
    uom_id         UUID NOT NULL REFERENCES uom(id),
    qty_base_uom   NUMERIC(16,3) NOT NULL CHECK (qty_base_uom > 0),
    unit_value     NUMERIC(18,4),
    total_value    NUMERIC(18,2),
    storage_bin_id UUID REFERENCES storage_bin(id),
    UNIQUE (ticket_id, line_no)
);

-- ---------------------------------------------------------------------
-- The daily close. A locked date is the backbone of the whole system:
-- without it, every other control can be undone the following morning.
-- ---------------------------------------------------------------------
CREATE TABLE daily_close (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id                UUID NOT NULL REFERENCES branch(id),
    business_date            DATE NOT NULL,
    status                   VARCHAR(12) NOT NULL DEFAULT 'OPEN'
                             CHECK (status IN ('OPEN','RECONCILED','LOCKED')),
    opening_value            NUMERIC(18,2),
    receipts_value           NUMERIC(18,2),
    dispatches_value         NUMERIC(18,2),
    adjustments_value        NUMERIC(18,2),
    closing_value            NUMERIC(18,2),
    movement_count           INTEGER,
    reconciled_by            UUID REFERENCES app_user(id),
    reconciled_at            TIMESTAMPTZ,
    internal_controller_id   UUID REFERENCES app_user(id),
    controller_signed_at     TIMESTAMPTZ,
    locked_at                TIMESTAMPTZ,
    exceptions_note          VARCHAR(1000),
    UNIQUE (branch_id, business_date),
    CONSTRAINT daily_close_locked_has_stamp
        CHECK (status <> 'LOCKED' OR locked_at IS NOT NULL)
);

CREATE INDEX daily_close_open ON daily_close (branch_id, business_date DESC)
    WHERE status <> 'LOCKED';

COMMENT ON TABLE daily_close IS
  'FR-CNT-10..12. Opening + receipts - dispatches = closing, per branch per day, signed jointly by the Warehouse Manager and the Internal Controller.';

-- ---------------------------------------------------------------------
-- The ledger.
-- ---------------------------------------------------------------------
CREATE TABLE stock_movement (
    id                    BIGSERIAL PRIMARY KEY,
    ticket_line_id        UUID REFERENCES ticket_line(id),
    document_id           UUID NOT NULL REFERENCES document(id),
    branch_id             UUID NOT NULL REFERENCES branch(id),
    item_id               UUID NOT NULL REFERENCES item(id),
    location_id           UUID NOT NULL REFERENCES location(id),
    storage_bin_id        UUID REFERENCES storage_bin(id),

    direction             VARCHAR(4) NOT NULL CHECK (direction IN ('IN','OUT')),
    quantity_base_uom     NUMERIC(16,3) NOT NULL CHECK (quantity_base_uom > 0),
    -- Signed quantity: the column every aggregate sums.
    signed_quantity       NUMERIC(16,3) NOT NULL,

    unit_cost             NUMERIC(18,4) NOT NULL DEFAULT 0,
    value                 NUMERIC(18,2) NOT NULL DEFAULT 0,

    -- The running balance at this location after this movement, so a
    -- stock card reads without a window function over the whole table.
    running_balance       NUMERIC(16,3) NOT NULL,

    business_date         DATE NOT NULL,
    movement_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    posted_by             UUID NOT NULL REFERENCES app_user(id),

    -- A correction references what it reverses. Never an edit.
    reverses_movement_id  BIGINT REFERENCES stock_movement(id),

    CONSTRAINT stock_movement_signed_matches_direction
        CHECK ((direction = 'IN'  AND signed_quantity =  quantity_base_uom)
            OR (direction = 'OUT' AND signed_quantity = -quantity_base_uom))
);

CREATE INDEX stock_movement_card    ON stock_movement (item_id, location_id, movement_at DESC);
CREATE INDEX stock_movement_day     ON stock_movement (branch_id, business_date);
CREATE INDEX stock_movement_doc     ON stock_movement (document_id);
CREATE INDEX stock_movement_item    ON stock_movement (item_id, business_date);
CREATE UNIQUE INDEX stock_movement_one_reversal
    ON stock_movement (reverses_movement_id) WHERE reverses_movement_id IS NOT NULL;

COMMENT ON TABLE stock_movement IS
  'Append-only. The only writer of stock in the system. UPDATE and DELETE are refused by trigger, not by convention.';

-- Guarantee 1: append-only.
CREATE OR REPLACE FUNCTION stock_movement_is_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
      'The stock ledger is append-only: % is not permitted. Post a reversing movement instead.',
      TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER stock_movement_no_update
    BEFORE UPDATE OR DELETE ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION stock_movement_is_append_only();

-- Guarantee 3: a locked business date refuses any movement inside it.
CREATE OR REPLACE FUNCTION stock_movement_respects_close() RETURNS TRIGGER AS $$
DECLARE
    close_status VARCHAR(12);
BEGIN
    SELECT status INTO close_status
      FROM daily_close
     WHERE branch_id = NEW.branch_id
       AND business_date = NEW.business_date;

    IF close_status = 'LOCKED' THEN
        RAISE EXCEPTION
          'Business date % is closed at this branch. A movement cannot be backdated into a locked day.',
          NEW.business_date
          USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER stock_movement_not_into_locked_day
    BEFORE INSERT ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION stock_movement_respects_close();

-- ---------------------------------------------------------------------
-- Derived balance. Rebuildable from the ledger at any time.
-- ---------------------------------------------------------------------
CREATE TABLE stock_balance (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id             UUID NOT NULL REFERENCES item(id),
    location_id         UUID NOT NULL REFERENCES location(id),
    storage_bin_id      UUID REFERENCES storage_bin(id),
    qty_on_hand         NUMERIC(16,3) NOT NULL DEFAULT 0,
    -- Reserved when a delivery authorization is approved but not released.
    -- Carried now, meaningful when the order desk arrives in Phase 2.
    qty_reserved        NUMERIC(16,3) NOT NULL DEFAULT 0,
    average_unit_cost   NUMERIC(18,4) NOT NULL DEFAULT 0,
    total_value         NUMERIC(18,2) NOT NULL DEFAULT 0,
    last_movement_at    TIMESTAMPTZ,
    last_counted_at     TIMESTAMPTZ,
    UNIQUE (item_id, location_id, storage_bin_id)
);

CREATE INDEX stock_balance_by_location ON stock_balance (location_id)
    WHERE qty_on_hand <> 0;
CREATE INDEX stock_balance_below_reorder ON stock_balance (item_id, qty_on_hand);

-- A view that recomputes balances straight from the ledger. Reconciling
-- this against stock_balance is how a drift is caught.
CREATE VIEW stock_balance_from_ledger AS
SELECT m.item_id,
       m.location_id,
       SUM(m.signed_quantity)                       AS qty_on_hand,
       SUM(m.value * CASE WHEN m.direction = 'IN' THEN 1 ELSE -1 END) AS total_value,
       MAX(m.movement_at)                           AS last_movement_at,
       COUNT(*)                                     AS movement_count
  FROM stock_movement m
 GROUP BY m.item_id, m.location_id;

COMMENT ON VIEW stock_balance_from_ledger IS
  'The authority. stock_balance is a cache of this; a nightly job compares the two and reports any difference.';
