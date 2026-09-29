-- =====================================================================
-- V5 — Reference data and both approval chains
--
-- Seeds the permission catalogue, the roles from the 2026 policy and the
-- 2027 restructuring, the segregation rules from Board paper Table 6, the
-- two branches, and — the point of this migration — BOTH approval chains
-- as effective-dated workflow definitions.
--
-- On 1 January 2027 the chain changes because a date passes, not because
-- anyone deploys.
-- =====================================================================

-- ---- Units ----------------------------------------------------------
INSERT INTO uom (code, name, decimal_places) VALUES
    ('SHEET', 'Sheet',        0),
    ('SQM',   'Square metre', 3),
    ('BOX',   'Box',          0),
    ('PC',    'Piece',        0),
    ('KG',    'Kilogram',     2),
    ('L',     'Litre',        2);

-- ---- Branches -------------------------------------------------------
INSERT INTO branch (code, name, branch_type, city, is_bonded, customs_regime) VALUES
    ('KGL', 'Gahanga Main Warehouse',      'MAIN',   'Kigali', FALSE, NULL),
    ('RBV', 'BBF Public Bonded Warehouse', 'BONDED', 'Rubavu', TRUE,  'Public bonded warehouse');

-- ---- Locations ------------------------------------------------------
INSERT INTO location (branch_id, code, name, location_type, is_bonded, is_sellable)
SELECT b.id, v.code, v.name, v.ltype, v.bonded, v.sellable
  FROM branch b
  JOIN (VALUES
        ('KGL', 'KGL-MAIN',  'Gahanga main store',     'WAREHOUSE',  FALSE, TRUE),
        ('KGL', 'KGL-CUT',   'Cutting floor',          'CUTTING',    FALSE, TRUE),
        ('KGL', 'KGL-QUAR',  'Quarantine — Gahanga',   'QUARANTINE', FALSE, FALSE),
        ('KGL', 'KGL-TRAN',  'Goods in transit out',   'TRANSIT',    FALSE, FALSE),
        ('RBV', 'RBV-BOND',  'BBF bonded store',       'BONDED',     TRUE,  TRUE),
        ('RBV', 'RBV-QUAR',  'Quarantine — Rubavu',    'QUARANTINE', FALSE, FALSE),
        ('RBV', 'RBV-TRAN',  'Goods in transit out',   'TRANSIT',    FALSE, FALSE)
       ) AS v(bcode, code, name, ltype, bonded, sellable)
    ON v.bcode = b.code;

-- ---- Permissions ----------------------------------------------------
INSERT INTO permission (code, module, action, description) VALUES
    ('receiving.view',    'receiving', 'VIEW',    'View goods received notes'),
    ('receiving.create',  'receiving', 'CREATE',  'Raise a goods received note'),
    ('receiving.verify',  'receiving', 'VERIFY',  'Independently verify a receipt'),
    ('receiving.post',    'receiving', 'POST',    'Post a receipt to the ledger'),

    ('dispatch.view',     'dispatch',  'VIEW',    'View delivery authorizations'),
    ('dispatch.create',   'dispatch',  'CREATE',  'Raise a delivery authorization'),
    ('dispatch.verify',   'dispatch',  'VERIFY',  'Verify quantity and specification before loading'),
    ('dispatch.approve',  'dispatch',  'APPROVE', 'Approve a delivery authorization'),
    ('dispatch.release',  'dispatch',  'RELEASE', 'Release goods from the premises'),

    ('transfer.view',     'transfer',  'VIEW',    'View inter-branch transfers'),
    ('transfer.create',   'transfer',  'CREATE',  'Request a transfer'),
    ('transfer.approve',  'transfer',  'APPROVE', 'Authorise a transfer'),
    ('transfer.receive',  'transfer',  'POST',    'Confirm receipt of a transfer'),

    ('cutting.view',      'cutting',   'VIEW',    'View cutting orders'),
    ('cutting.create',    'cutting',   'CREATE',  'Raise a cutting order'),
    ('cutting.verify',    'cutting',   'VERIFY',  'Verify cut dimensions and quantity'),
    ('cutting.release',   'cutting',   'RELEASE', 'Release cut glass to a customer'),

    ('damage.view',       'damage',    'VIEW',    'View returns and damage reports'),
    ('damage.create',     'damage',    'CREATE',  'Report damaged or returned stock'),
    ('damage.approve',    'damage',    'APPROVE', 'Approve a write-off or disposal'),

    ('count.view',        'count',     'VIEW',    'View stock counts'),
    ('count.create',      'count',     'CREATE',  'Open a stock count'),
    ('count.enter',       'count',     'AMEND',   'Enter counted quantities'),
    ('count.verify',      'count',     'VERIFY',  'Perform the independent verification count'),
    ('count.approve',     'count',     'APPROVE', 'Approve a variance adjustment'),

    ('ticket.view',       'ticket',    'VIEW',    'View transaction tickets'),
    ('ticket.create',     'ticket',    'CREATE',  'Raise a transaction ticket'),
    ('ticket.verify',     'ticket',    'VERIFY',  'Verify a ticket against the physical movement'),
    ('ticket.countersign','ticket',    'APPROVE', 'Countersign that a ticket answers to a real order'),
    ('ticket.post',       'ticket',    'POST',    'Post a ticket to the ledger'),

    ('stock.view',        'inventory', 'VIEW',    'View stock balances and movements'),
    ('item.view',         'masterdata','VIEW',    'View the item master'),
    ('item.manage',       'masterdata','CONFIGURE','Create and amend items'),
    ('location.manage',   'masterdata','CONFIGURE','Create and amend locations and bins'),
    ('partner.manage',    'masterdata','CONFIGURE','Create and amend customers and suppliers'),

    ('close.view',        'reporting', 'VIEW',    'View the daily close'),
    ('close.reconcile',   'reporting', 'APPROVE', 'Reconcile and sign the daily close'),
    ('close.lock',        'reporting', 'POST',    'Lock a business date'),
    ('report.view',       'reporting', 'VIEW',    'View reports and KPIs'),

    ('admin.users',       'admin',     'CONFIGURE','Create, amend and deactivate users'),
    ('admin.roles',       'admin',     'CONFIGURE','Create roles and assign permissions'),
    ('admin.workflow',    'admin',     'CONFIGURE','Maintain approval chains'),
    ('admin.branches',    'admin',     'CONFIGURE','Create and configure branches'),
    ('audit.view',        'audit',     'VIEW',    'Read the audit log');

-- ---- Roles ----------------------------------------------------------
INSERT INTO role (code, name, structure, is_protected, description) VALUES
    -- Present in both structures
    ('WH_MANAGER',      'Warehouse & Inventory Manager', 'BOTH', TRUE,
     'Safeguards stock at the branch, supervises loading and unloading, leads counts.'),
    ('INTERNAL_CTRL',   'Internal Controller & Compliance', 'BOTH', TRUE,
     'Independent verification. Full read access, no posting rights — granting them would defeat the control it exists to provide.'),
    ('FINANCE',         'Finance Department', 'BOTH', TRUE,
     'Issues delivery authorizations, posts valuations, performs monthly reconciliation.'),
    ('SALES',           'Sales Department', 'BOTH', FALSE,
     'Raises customer orders and returns signed delivery acknowledgements.'),
    ('MANAGING_DIR',    'Managing Director', 'BOTH', TRUE,
     'Approves write-offs and disposals above threshold.'),
    ('SYS_ADMIN',       'System Administrator', 'BOTH', TRUE,
     'Manages users and roles. Holds no transactional rights: an administrator must not be able to grant themselves a posting right, use it, and remove it again.'),

    -- 2026 policy only
    ('ASST_WH_MANAGER', 'Assistant Warehouse Manager', 'POLICY_2026', FALSE,
     'Retrieves and loads goods, records movements, assists counts.'),
    ('HEAD_INVENTORY',  'Head of Inventory', 'POLICY_2026', FALSE,
     'Supervises both warehouses, approves transfers, receives daily reports.'),

    -- 2027 restructuring only
    ('DIR_SUPPLY_CHAIN','Director of Supply Chain & Distribution', 'RESTRUCTURE_2027', FALSE,
     'Verifies transaction tickets against the physical movement; owns stock integrity and landed cost.'),
    ('INV_TX_OFFICER',  'Inventory Transactions Officer', 'RESTRUCTURE_2027', FALSE,
     'Raises every transaction ticket.'),
    ('DIR_COMMERCIAL',  'Director of Commercial', 'RESTRUCTURE_2027', FALSE,
     'Countersigns that a movement answers to a real order or approved load request.'),
    ('COO',             'Chief Operating Officer', 'RESTRUCTURE_2027', FALSE,
     'Approves exceptions and waives the load reconciliation rule.');

-- ---- Segregation of duties (Board paper Table 6) --------------------
INSERT INTO sod_rule (role_a_id, role_b_id, enforcement, source_ref, rationale)
SELECT a.id, b.id, 'BLOCK', v.src, v.why
  FROM (VALUES
    ('INTERNAL_CTRL', 'WH_MANAGER',   'Board paper Table 6',
     'Assurance cannot test work it performed itself.'),
    ('INTERNAL_CTRL', 'FINANCE',      'Board paper Table 6',
     'Assurance cannot test work it performed itself.'),
    ('INTERNAL_CTRL', 'INV_TX_OFFICER', 'Board paper Table 6',
     'The officer raising tickets cannot also independently verify them.'),
    ('SYS_ADMIN',     'FINANCE',      'SRS FR-SEC-13',
     'Whoever manages access must hold no transactional rights.'),
    ('SYS_ADMIN',     'WH_MANAGER',   'SRS FR-SEC-13',
     'Whoever manages access must hold no transactional rights.'),
    ('SYS_ADMIN',     'INTERNAL_CTRL','SRS FR-SEC-13',
     'Whoever manages access must hold no transactional rights.'),
    ('INV_TX_OFFICER','DIR_SUPPLY_CHAIN', 'Board paper §7.4',
     'The ticket is raised by one officer and verified by another.'),
    ('DIR_COMMERCIAL','DIR_SUPPLY_CHAIN', 'Board paper §7.4',
     'Custody and commercial authorisation are held apart.')
  ) AS v(role_a, role_b, src, why)
  JOIN role a ON a.code = v.role_a
  JOIN role b ON b.code = v.role_b;

-- ---- Document types -------------------------------------------------
INSERT INTO document_type (code, name, form_reference, moves_stock, sort_order) VALUES
    ('GRN', 'Goods Received Note',            'GRN-001', TRUE,  10),
    ('DAO', 'Delivery Authorization Order',   'DAO-004', FALSE, 20),
    ('DN',  'Delivery Note',                  'DN-005',  TRUE,  30),
    ('TRF', 'Inter-Warehouse Transfer',       'TRF-007', TRUE,  40),
    ('CUT', 'Retail Cutting Order',           'CUT-006', TRUE,  50),
    ('DMG', 'Return & Damage Report',         'DMG-009', TRUE,  60),
    ('CNT', 'Physical Stock Count',           'CSH-012', FALSE, 70),
    ('VR',  'Inventory Variance Report',      'VR-010',  FALSE, 80),
    ('TT',  'Transaction Ticket',             'F-05',    TRUE,  90);

-- ---- Serial sequences ----------------------------------------------
INSERT INTO serial_sequence (document_type_id, branch_id, year, prefix)
SELECT dt.id, b.id, 2026, dt.code || '-' || b.code || '-2026'
  FROM document_type dt CROSS JOIN branch b;
INSERT INTO serial_sequence (document_type_id, branch_id, year, prefix)
SELECT dt.id, b.id, 2027, dt.code || '-' || b.code || '-2027'
  FROM document_type dt CROSS JOIN branch b;

-- =====================================================================
-- Both approval chains.
--
-- The 2026 policy routes a dispatch through Finance, the Assistant
-- Warehouse Manager, the Warehouse Manager and the Internal Controller
-- — the four mandatory signatures of the Inventory Policy §8.4.
--
-- The 2027 restructuring routes it through the Transaction Ticket:
-- raised by the Inventory Transactions Officer, verified by the Director
-- of Supply Chain, countersigned by the Director of Commercial, posted
-- by Finance.
--
-- Both are rows. Nothing is deployed on 1 January 2027.
-- =====================================================================

-- ---- Delivery authorization: the 2026 chain -------------------------
WITH wd AS (
    INSERT INTO workflow_definition (document_type_id, version, effective_from, effective_to, basis, notes)
    SELECT id, 1, DATE '2026-07-01', DATE '2027-01-01', 'POLICY_2026',
           'Inventory Management & Internal Control Policy v1.0 §8.4 — four mandatory signatures before release.'
      FROM document_type WHERE code = 'DAO'
    RETURNING id
)
INSERT INTO workflow_step (workflow_definition_id, sequence_no, required_role_id, action_label, is_mandatory, blocks_release, escalate_after_hours)
SELECT wd.id, v.seq, r.id, v.act, TRUE, TRUE, v.esc
  FROM wd
  JOIN (VALUES
        (1, 'FINANCE',         'PREPARE',     24),
        (2, 'ASST_WH_MANAGER', 'VERIFY',       8),
        (3, 'WH_MANAGER',      'VERIFY',       8),
        (4, 'INTERNAL_CTRL',   'RELEASE',     24)
       ) AS v(seq, role_code, act, esc) ON TRUE
  JOIN role r ON r.code = v.role_code;

-- ---- Delivery authorization: the 2027 chain -------------------------
WITH wd AS (
    INSERT INTO workflow_definition (document_type_id, version, effective_from, effective_to, basis, notes)
    SELECT id, 2, DATE '2027-01-01', NULL, 'RESTRUCTURE_2027',
           'Board Paper HB/BD/2026/09-05 §7.4 — the Transaction Ticket chain.'
      FROM document_type WHERE code = 'DAO'
    RETURNING id
)
INSERT INTO workflow_step (workflow_definition_id, sequence_no, required_role_id, action_label, is_mandatory, blocks_release, escalate_after_hours)
SELECT wd.id, v.seq, r.id, v.act, TRUE, TRUE, v.esc
  FROM wd
  JOIN (VALUES
        (1, 'INV_TX_OFFICER',   'PREPARE',     24),
        (2, 'DIR_SUPPLY_CHAIN', 'VERIFY',       8),
        (3, 'DIR_COMMERCIAL',   'COUNTERSIGN',  8),
        (4, 'INTERNAL_CTRL',    'RELEASE',     24)
       ) AS v(seq, role_code, act, esc) ON TRUE
  JOIN role r ON r.code = v.role_code;

-- ---- Goods received note: the 2026 chain ----------------------------
WITH wd AS (
    INSERT INTO workflow_definition (document_type_id, version, effective_from, effective_to, basis, notes)
    SELECT id, 1, DATE '2026-07-01', DATE '2027-01-01', 'POLICY_2026',
           'Inventory Policy §6 — receive, document, independently inspect, register.'
      FROM document_type WHERE code = 'GRN'
    RETURNING id
)
INSERT INTO workflow_step (workflow_definition_id, sequence_no, required_role_id, action_label, is_mandatory, blocks_release, escalate_after_hours)
SELECT wd.id, v.seq, r.id, v.act, TRUE, TRUE, v.esc
  FROM wd
  JOIN (VALUES
        (1, 'ASST_WH_MANAGER', 'PREPARE', 24),
        (2, 'WH_MANAGER',      'VERIFY',   8),
        (3, 'INTERNAL_CTRL',   'APPROVE', 24)
       ) AS v(seq, role_code, act, esc) ON TRUE
  JOIN role r ON r.code = v.role_code;

-- ---- Goods received note: the 2027 chain ----------------------------
WITH wd AS (
    INSERT INTO workflow_definition (document_type_id, version, effective_from, effective_to, basis, notes)
    SELECT id, 2, DATE '2027-01-01', NULL, 'RESTRUCTURE_2027',
           'Board Paper §7.4 — receipt raised on a ticket and verified by the Director of Supply Chain.'
      FROM document_type WHERE code = 'GRN'
    RETURNING id
)
INSERT INTO workflow_step (workflow_definition_id, sequence_no, required_role_id, action_label, is_mandatory, blocks_release, escalate_after_hours)
SELECT wd.id, v.seq, r.id, v.act, TRUE, TRUE, v.esc
  FROM wd
  JOIN (VALUES
        (1, 'WH_MANAGER',       'PREPARE', 24),
        (2, 'INV_TX_OFFICER',   'VERIFY',   8),
        (3, 'DIR_SUPPLY_CHAIN', 'APPROVE', 24),
        (4, 'INTERNAL_CTRL',    'APPROVE', 24)
       ) AS v(seq, role_code, act, esc) ON TRUE
  JOIN role r ON r.code = v.role_code;

-- ---- Cutting order: unchanged by the restructuring ------------------
-- The Internal Controller's release authorization is an absolute rule in
-- both structures (Inventory Policy §10), so one definition with no end
-- date covers both.
WITH wd AS (
    INSERT INTO workflow_definition (document_type_id, version, effective_from, effective_to, basis, notes)
    SELECT id, 1, DATE '2026-07-01', NULL, 'POLICY_2026',
           'Inventory Policy §10. No cut glass is released without the Internal Controller signed release authorization — unchanged by the restructuring.'
      FROM document_type WHERE code = 'CUT'
    RETURNING id
)
INSERT INTO workflow_step (workflow_definition_id, sequence_no, required_role_id, action_label, is_mandatory, blocks_release, escalate_after_hours)
SELECT wd.id, v.seq, r.id, v.act, TRUE, TRUE, v.esc
  FROM wd
  JOIN (VALUES
        (1, 'FINANCE',       'PREPARE', 24),
        (2, 'WH_MANAGER',    'VERIFY',   8),
        (3, 'INTERNAL_CTRL', 'RELEASE', 24)
       ) AS v(seq, role_code, act, esc) ON TRUE
  JOIN role r ON r.code = v.role_code;

-- ---- Transfer, damage, count: single chains for Phase 1 -------------
WITH wd AS (
    INSERT INTO workflow_definition (document_type_id, version, effective_from, effective_to, basis, notes)
    SELECT id, 1, DATE '2026-07-01', NULL, 'POLICY_2026',
           'Inventory Policy §11 — authorised by the Head of Inventory or Managing Director.'
      FROM document_type WHERE code = 'TRF'
    RETURNING id
)
INSERT INTO workflow_step (workflow_definition_id, sequence_no, required_role_id, action_label, is_mandatory, blocks_release)
SELECT wd.id, v.seq, r.id, v.act, TRUE, TRUE
  FROM wd
  JOIN (VALUES
        (1, 'WH_MANAGER',    'PREPARE'),
        (2, 'HEAD_INVENTORY','APPROVE'),
        (3, 'INTERNAL_CTRL', 'VERIFY')
       ) AS v(seq, role_code, act) ON TRUE
  JOIN role r ON r.code = v.role_code;

WITH wd AS (
    INSERT INTO workflow_definition (document_type_id, version, effective_from, effective_to, basis, notes)
    SELECT id, 1, DATE '2026-07-01', NULL, 'POLICY_2026',
           'Inventory Policy §13 — no disposal or write-off without prior written management approval.'
      FROM document_type WHERE code = 'DMG'
    RETURNING id
)
INSERT INTO workflow_step (workflow_definition_id, sequence_no, required_role_id, action_label, is_mandatory, blocks_release)
SELECT wd.id, v.seq, r.id, v.act, TRUE, TRUE
  FROM wd
  JOIN (VALUES
        (1, 'WH_MANAGER',    'PREPARE'),
        (2, 'INTERNAL_CTRL', 'VERIFY'),
        (3, 'MANAGING_DIR',  'APPROVE')
       ) AS v(seq, role_code, act) ON TRUE
  JOIN role r ON r.code = v.role_code;

WITH wd AS (
    INSERT INTO workflow_definition (document_type_id, version, effective_from, effective_to, basis, notes)
    SELECT id, 1, DATE '2026-07-01', NULL, 'POLICY_2026',
           'Inventory Policy §15 — blind count, independent verification count, joint sign-off.'
      FROM document_type WHERE code = 'CNT'
    RETURNING id
)
INSERT INTO workflow_step (workflow_definition_id, sequence_no, required_role_id, action_label, is_mandatory, blocks_release)
SELECT wd.id, v.seq, r.id, v.act, TRUE, TRUE
  FROM wd
  JOIN (VALUES
        (1, 'WH_MANAGER',    'PREPARE'),
        (2, 'INTERNAL_CTRL', 'VERIFY'),
        (3, 'FINANCE',       'APPROVE')
       ) AS v(seq, role_code, act) ON TRUE
  JOIN role r ON r.code = v.role_code;

-- ---- Role permissions ----------------------------------------------
-- The Internal Controller reads everything and posts nothing.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
 WHERE r.code = 'INTERNAL_CTRL'
   AND (p.action IN ('VIEW','VERIFY') OR p.code IN ('count.verify','audit.view'));

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
 WHERE r.code = 'WH_MANAGER'
   AND p.code IN ('receiving.view','receiving.create','receiving.verify',
                  'dispatch.view','dispatch.verify',
                  'transfer.view','transfer.create','transfer.receive',
                  'cutting.view','cutting.verify',
                  'damage.view','damage.create',
                  'count.view','count.create','count.enter',
                  'ticket.view','stock.view','item.view','close.view','report.view');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
 WHERE r.code = 'FINANCE'
   AND p.code IN ('dispatch.view','dispatch.create','dispatch.approve',
                  'cutting.view','cutting.create',
                  'receiving.view','receiving.post',
                  'ticket.view','ticket.post',
                  'count.view','count.approve',
                  'stock.view','item.view','partner.manage',
                  'close.view','close.reconcile','report.view');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
 WHERE r.code = 'SYS_ADMIN'
   AND p.code IN ('admin.users','admin.roles','admin.workflow','admin.branches','audit.view');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
 WHERE r.code = 'MANAGING_DIR'
   AND (p.action = 'VIEW' OR p.code IN ('damage.approve','close.lock'));
