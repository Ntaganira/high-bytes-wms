-- =====================================================================
-- V3 — The document spine and the configurable approval engine
--
-- SRS: FR-WF-01..10
--
-- Every controlled form — GRN, delivery authorization, transaction ticket,
-- count sheet — shares one lifecycle, one serial sequence and one approval
-- mechanism. Each form's own fields live in a subtype table keyed by the
-- same id, so a receipt cannot exist outside the document register.
--
-- Workflow definitions are effective-dated. The 2026 policy chain and the
-- 2027 restructured chain both exist as rows; 1 January 2027 is a date in
-- a table, not a code release.
-- =====================================================================

CREATE TABLE document_type (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                VARCHAR(16)  NOT NULL UNIQUE,   -- GRN, DAO, DN, TT, TRF, CUT, DMG, CNT, VR
    name                VARCHAR(120) NOT NULL,
    form_reference      VARCHAR(24),                    -- 'GRN-001' / 'F-05'
    requires_approval   BOOLEAN NOT NULL DEFAULT TRUE,
    -- Does posting this document move stock? Count sheets do not; the
    -- adjustment ticket they raise does.
    moves_stock         BOOLEAN NOT NULL DEFAULT FALSE,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order          SMALLINT NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------
-- Serial sequences, per type, per branch, per year. A missing serial is
-- an incident reported to the Internal Controller (FR-SEC-08, FR-SEC-10).
-- ---------------------------------------------------------------------
CREATE TABLE serial_sequence (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_type_id  UUID NOT NULL REFERENCES document_type(id),
    branch_id         UUID NOT NULL REFERENCES branch(id),
    year              SMALLINT NOT NULL,
    prefix            VARCHAR(24) NOT NULL,        -- 'GRN-KGL-2026'
    next_number       BIGINT NOT NULL DEFAULT 1 CHECK (next_number > 0),
    UNIQUE (document_type_id, branch_id, year)
);

-- ---------------------------------------------------------------------
-- Workflow definitions. Selected by the document's creation date, so a
-- document opened under one definition finishes under it (FR-WF-03).
-- ---------------------------------------------------------------------
CREATE TABLE workflow_definition (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_type_id   UUID NOT NULL REFERENCES document_type(id),
    version            SMALLINT NOT NULL,
    effective_from     DATE NOT NULL,
    effective_to       DATE,
    basis              VARCHAR(24) NOT NULL
                       CHECK (basis IN ('POLICY_2026','RESTRUCTURE_2027')),
    notes              VARCHAR(400),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         UUID REFERENCES app_user(id),
    UNIQUE (document_type_id, version),
    CONSTRAINT workflow_definition_dates
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- No two definitions for one document type may cover the same day.
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE workflow_definition
    ADD CONSTRAINT workflow_definition_no_overlap
    EXCLUDE USING gist (
        document_type_id WITH =,
        daterange(effective_from, effective_to, '[)') WITH &&
    );

CREATE TABLE workflow_step (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_definition_id   UUID NOT NULL REFERENCES workflow_definition(id) ON DELETE CASCADE,
    sequence_no              SMALLINT NOT NULL CHECK (sequence_no > 0),
    required_role_id         UUID NOT NULL REFERENCES role(id),
    action_label             VARCHAR(20) NOT NULL
                             CHECK (action_label IN ('PREPARE','VERIFY','COUNTERSIGN',
                                                     'APPROVE','RELEASE','POST')),
    is_mandatory             BOOLEAN NOT NULL DEFAULT TRUE,
    -- A step that blocks release is why a delivery authorization sits HELD.
    blocks_release           BOOLEAN NOT NULL DEFAULT TRUE,
    escalate_after_hours     SMALLINT,
    UNIQUE (workflow_definition_id, sequence_no)
);

-- ---------------------------------------------------------------------
-- The document itself.
-- ---------------------------------------------------------------------
CREATE TABLE document (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_type_id         UUID NOT NULL REFERENCES document_type(id),
    branch_id                UUID NOT NULL REFERENCES branch(id),
    serial_no                VARCHAR(48) NOT NULL UNIQUE,
    status                   VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
                             CHECK (status IN ('DRAFT','PENDING','REJECTED',
                                               'APPROVED','POSTED','CANCELLED')),
    workflow_definition_id   UUID REFERENCES workflow_definition(id),
    document_date            DATE NOT NULL DEFAULT CURRENT_DATE,
    reference                VARCHAR(120),
    notes                    VARCHAR(1000),

    created_by               UUID NOT NULL REFERENCES app_user(id),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at             TIMESTAMPTZ,
    approved_at              TIMESTAMPTZ,
    posted_at                TIMESTAMPTZ,
    posted_by                UUID REFERENCES app_user(id),

    -- Cancellation keeps the serial. A missing number is an incident.
    cancelled_at             TIMESTAMPTZ,
    cancelled_by             UUID REFERENCES app_user(id),
    cancel_reason            VARCHAR(400),

    -- A correction is a new document referencing the original, never an edit.
    supersedes_document_id   UUID REFERENCES document(id),
    reverses_document_id     UUID REFERENCES document(id),

    version                  INTEGER NOT NULL DEFAULT 0,   -- optimistic locking

    CONSTRAINT document_cancelled_has_reason
        CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL),
    CONSTRAINT document_posted_has_stamp
        CHECK (status <> 'POSTED' OR (posted_at IS NOT NULL AND posted_by IS NOT NULL))
);

CREATE INDEX document_by_branch_status ON document (branch_id, status, document_date DESC);
CREATE INDEX document_by_type          ON document (document_type_id, document_date DESC);
CREATE INDEX document_open             ON document (branch_id, document_type_id)
                                       WHERE status IN ('DRAFT','PENDING');
CREATE INDEX document_creator          ON document (created_by, created_at DESC);

COMMENT ON TABLE document IS
  'The spine. Each controlled form is a subtype table whose primary key IS this id, so an orphan receipt with no serial, status or approval chain cannot exist.';

-- A posted document is never edited (FR-SEC-07). Only the cancellation
-- columns and the optimistic-lock version may change after posting.
CREATE OR REPLACE FUNCTION document_posted_is_immutable() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'POSTED' AND NEW.status = 'POSTED' THEN
        IF (NEW.document_type_id, NEW.branch_id, NEW.serial_no, NEW.document_date,
            NEW.created_by, NEW.posted_at, NEW.posted_by)
           IS DISTINCT FROM
           (OLD.document_type_id, OLD.branch_id, OLD.serial_no, OLD.document_date,
            OLD.created_by, OLD.posted_at, OLD.posted_by)
        THEN
            RAISE EXCEPTION
              'Document % is posted and cannot be altered. Raise a reversing document instead.',
              OLD.serial_no;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_immutable_once_posted
    BEFORE UPDATE ON document
    FOR EACH ROW EXECUTE FUNCTION document_posted_is_immutable();

-- ---------------------------------------------------------------------
-- Approvals. Immutable once recorded (FR-WF-07).
-- ---------------------------------------------------------------------
CREATE TABLE document_approval (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id        UUID NOT NULL REFERENCES document(id),
    workflow_step_id   UUID NOT NULL REFERENCES workflow_step(id),
    actor_user_id      UUID NOT NULL REFERENCES app_user(id),
    -- Captured at signing time: a role renamed later must not rewrite history.
    actor_name         VARCHAR(160) NOT NULL,
    actor_role_label   VARCHAR(120) NOT NULL,
    decision           VARCHAR(10) NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
    decided_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    comment            VARCHAR(1000),
    client_address     VARCHAR(60),
    -- One signature per step per document (FR-WF-04).
    UNIQUE (document_id, workflow_step_id)
);

CREATE INDEX document_approval_by_doc   ON document_approval (document_id);
CREATE INDEX document_approval_by_actor ON document_approval (actor_user_id, decided_at DESC);

-- FR-WF-05: no user may sign two steps on the same document. Unique on
-- (document, step) is not enough — this catches the same person at a
-- different step, which is exactly the concentration the Board found.
CREATE UNIQUE INDEX document_approval_one_signature_per_user
    ON document_approval (document_id, actor_user_id);

CREATE OR REPLACE FUNCTION document_approval_is_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'An approval is a signature and cannot be % once recorded', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_approval_no_change
    BEFORE UPDATE OR DELETE ON document_approval
    FOR EACH ROW EXECUTE FUNCTION document_approval_is_immutable();

-- ---------------------------------------------------------------------
-- Attachments: cutting drawings, signed delivery notes, supplier invoices.
-- ---------------------------------------------------------------------
CREATE TABLE attachment (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id    UUID NOT NULL REFERENCES document(id),
    file_name      VARCHAR(240) NOT NULL,
    content_type   VARCHAR(120) NOT NULL,
    size_bytes     BIGINT NOT NULL CHECK (size_bytes > 0),
    storage_key    VARCHAR(400) NOT NULL,
    sha256         CHAR(64),
    uploaded_by    UUID NOT NULL REFERENCES app_user(id),
    uploaded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at     TIMESTAMPTZ,
    removed_by     UUID REFERENCES app_user(id)
);

CREATE INDEX attachment_by_document ON attachment (document_id) WHERE removed_at IS NULL;
