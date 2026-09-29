-- =====================================================================
-- V2 — Master data: items with glass attributes, locations, partners
--
-- SRS: FR-MD-01..10
--
-- Glass is not a flat SKU. Thickness, colour and sheet dimensions are the
-- attributes the Internal Controller physically verifies on every dispatch,
-- so they are structured columns, not free text in a description.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Units of measure. A sheet cut into square metres must still reconcile
-- to one base unit, or the daily close will not balance.
-- ---------------------------------------------------------------------
CREATE TABLE uom (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(12)  NOT NULL UNIQUE,   -- SHEET, SQM, BOX, KG, PC
    name            VARCHAR(60)  NOT NULL,
    decimal_places  SMALLINT     NOT NULL DEFAULT 2
                    CHECK (decimal_places BETWEEN 0 AND 6),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE item_category (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(24)  NOT NULL UNIQUE,
    name        VARCHAR(120) NOT NULL,
    parent_id   UUID REFERENCES item_category(id),
    is_active   BOOLEAN NOT NULL DEFAULT TRUE
);

-- ---------------------------------------------------------------------
-- Items. Shared across every branch; stock is not.
-- ---------------------------------------------------------------------
CREATE TABLE item (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_code          VARCHAR(40)  NOT NULL UNIQUE,
    description        VARCHAR(240) NOT NULL,
    category_id        UUID         REFERENCES item_category(id),
    product_type       VARCHAR(24)  NOT NULL
                       CHECK (product_type IN ('GLASS','SILICONE','STEEL',
                                               'HARDWARE','CONSUMABLE','OTHER')),

    -- Glass attributes. Verified with the approved measuring device on
    -- every receipt and every dispatch (FR-IN-03, FR-OUT-05).
    colour             VARCHAR(40),
    thickness_mm       NUMERIC(6,2) CHECK (thickness_mm IS NULL OR thickness_mm > 0),
    width_mm           NUMERIC(8,1) CHECK (width_mm  IS NULL OR width_mm  > 0),
    height_mm          NUMERIC(8,1) CHECK (height_mm IS NULL OR height_mm > 0),

    base_uom_id        UUID NOT NULL REFERENCES uom(id),

    -- Off-cuts from a cutting order re-enter stock as items in their own
    -- right, linked to the sheet they came from (FR-MD-04, FR-CUT-04).
    is_remnant         BOOLEAN NOT NULL DEFAULT FALSE,
    cut_from_item_id   UUID REFERENCES item(id),

    reorder_level      NUMERIC(14,3),
    max_stock_level    NUMERIC(14,3),
    costing_method     VARCHAR(20) NOT NULL DEFAULT 'WEIGHTED_AVERAGE'
                       CHECK (costing_method IN ('WEIGHTED_AVERAGE')),

    -- Deactivated, never deleted, once transacted (FR-MD-10).
    is_active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT item_glass_needs_thickness
        CHECK (product_type <> 'GLASS' OR thickness_mm IS NOT NULL),
    CONSTRAINT item_remnant_has_parent
        CHECK (is_remnant = FALSE OR cut_from_item_id IS NOT NULL)
);

CREATE INDEX item_active_by_type ON item (product_type) WHERE is_active;
CREATE INDEX item_remnant_parent ON item (cut_from_item_id) WHERE is_remnant;
CREATE INDEX item_search ON item USING gin (to_tsvector('simple', item_code || ' ' || description));

COMMENT ON COLUMN item.is_remnant IS
  'A usable off-cut posted back into stock with its actual dimensions. Without this it is untracked value sitting in a warehouse.';

CREATE TABLE item_uom_conversion (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id         UUID NOT NULL REFERENCES item(id),
    uom_id          UUID NOT NULL REFERENCES uom(id),
    factor_to_base  NUMERIC(18,8) NOT NULL CHECK (factor_to_base > 0),
    UNIQUE (item_id, uom_id)
);

-- ---------------------------------------------------------------------
-- Locations. Typed, so a van in Phase 4 is the same entity as a warehouse
-- and needs no schema change (FR-MD-05).
-- ---------------------------------------------------------------------
CREATE TABLE location (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id       UUID NOT NULL REFERENCES branch(id),
    code            VARCHAR(32)  NOT NULL,
    name            VARCHAR(120) NOT NULL,
    location_type   VARCHAR(20)  NOT NULL
                    CHECK (location_type IN ('WAREHOUSE','BONDED','QUARANTINE',
                                             'TRANSIT','VAN','CUTTING')),
    -- Bonded stock keeps a segregated ledger and needs a customs reference
    -- on every movement in or out.
    is_bonded       BOOLEAN NOT NULL DEFAULT FALSE,
    -- Transit and quarantine hold stock that is real but not saleable;
    -- keeping them as locations means the ledger balances at every moment.
    is_sellable     BOOLEAN NOT NULL DEFAULT TRUE,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (branch_id, code)
);

COMMENT ON TABLE location IS
  'Total stock = every location including TRANSIT and QUARANTINE. A consignment that never arrives is a balance sitting in transit, not an absence nobody notices.';

CREATE TABLE storage_bin (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id  UUID NOT NULL REFERENCES location(id),
    bin_code     VARCHAR(24) NOT NULL,
    zone         VARCHAR(40),
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (location_id, bin_code)
);

-- ---------------------------------------------------------------------
-- Customers and suppliers. Shared company-wide. Credit limits are carried
-- but unenforced until Phase 2 brings the order desk.
-- ---------------------------------------------------------------------
CREATE TABLE customer (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(24)  NOT NULL UNIQUE,
    name            VARCHAR(200) NOT NULL,
    tin             VARCHAR(24),
    market          VARCHAR(24)  NOT NULL DEFAULT 'DOMESTIC'
                    CHECK (market IN ('DOMESTIC','EXPORT_BUKAVU','EXPORT_GOMA')),
    address         VARCHAR(300),
    phone           VARCHAR(40),
    email           VARCHAR(160),
    credit_limit    NUMERIC(18,2),      -- enforced from Phase 2
    payment_terms_days SMALLINT,
    is_blocked      BOOLEAN NOT NULL DEFAULT FALSE,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE supplier (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code          VARCHAR(24)  NOT NULL UNIQUE,
    name          VARCHAR(200) NOT NULL,
    tin           VARCHAR(24),
    country_code  CHAR(2)      NOT NULL DEFAULT 'RW',
    is_foreign    BOOLEAN      NOT NULL DEFAULT FALSE,
    address       VARCHAR(300),
    phone         VARCHAR(40),
    email         VARCHAR(160),
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
