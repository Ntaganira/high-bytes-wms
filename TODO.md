# Where this project stands

Reviewed 5 October 2026. `CLAUDE.md` carries the domain, the invariants and the
conventions; this file carries only what is left to do, in the order to do it.

## Built and verified

Phase 1 is complete: all nine modules, plus the cutover document.

- Master data, document spine, the ledger (the only writer of `stock_movement`)
- Goods Received · Delivery Authorization + Delivery Note · Transfers ·
  Cutting · Returns & Damage · Counts + Variances · Daily Close ·
  Reports & KPIs · Admin screens
- Opening balances (V19) — the cutover from QuickBooks
- Profile photos (V20)
- Reversing documents (V21) — goods received, opening balances, count
  adjustments, write-offs and quarantine releases, undone whole by their
  exact mirror
- Schema V1–V21, verified from an empty database
- `tools/verify-controls.sql` — **622 checks, 0 failures** (5 October 2026)
- The full Maven suite green on 5 October 2026, reversals included

## The order of work

### 1. ~~Get it compiling and green~~ — done 4–5 October 2026

The opening-balance module and the `inventory/lookup` + `inventory/gate`
restructure compiled as delivered. One test read the audit trail by
`document_id`, which the audit service never fills; it reads `entity_id` now.

The control checks are run on a fresh database (V1 to the latest applied in
order, then the script): a dev database with a live count refuses the
script's own fixtures at the counted place, which is the freeze working.

### 2. ~~Reversing documents~~ — done for the single-place documents (V21)

A reversing document (REV) undoes a whole posted document: one REVERSAL
ticket per ticket, one mirror per movement, the same item, place, bin,
quantity and value the other way. The chain is a proposal (Warehouse Manager,
Internal Controller, Managing Director, posted by Finance), recorded in
CLAUDE.md's open questions with what the client is asked.

Still to come, each with rules for what its reversal changes elsewhere:
delivery notes (what the authorization may still release), transfers and
their receipts (goods in transit), cutting orders (the pieces and the cost
split), transit losses and customer returns (the transfer or note behind
them). `reversal_target_guard` refuses them until then.

### 3. The QuickBooks extract

The cutover's other half: item master, suppliers, customers, then one opening
balance per location through `/opening`. The mapping is the easy part; the
document and its controls were the hard part and are done.

**Do this after item 2.** If the extract loads a wrong baseline and no reversal
exists, the only fix is dropping the database and reloading — survivable before
go-live, not after.

### 4. A settings table for thresholds

Count variance, damage and write-off tolerances have nowhere configurable to
live, and the 300 mm off-cut minimum is a constant in `cut_offcut_minimum_mm()`.
Blocked on client decisions 3 and 4.

### 5. Offline buffering for count entry

`static/js/app.js` has no `localStorage`, no queue and no `navigator.onLine`.
Scope it to the count entry page only. Needed before the first real stocktake:
a dropped tally costs trust in every figure after it.

## Known gaps, smaller

- No `EXPORT` action in the audit log, so report downloads are not recorded
  (needs a migration to widen the `audit_log.action` CHECK)
- `spring-boot-starter-data-jpa` is declared and unused — Hibernate boots with
  zero entities. Harmless, but `ddl-auto: validate` means the **first**
  `@Entity` anyone adds must match the Flyway schema exactly or the app will
  not start
- `count → transfer.TransferPosting` and `damage → transfer.TransferPosting`
  are the last of the shared-type drift the 5 October restructure cleaned up.
  Move it when you next touch those modules

## Client decisions

20 are open, listed under "Open questions for the client" in `CLAUDE.md`. Most
are not undecided — they are decided by default and unratified, with the chosen
default named. Two get expensive once there is production data: the
opening-balance signatories, and off-cut identity.

## Habits worth keeping

Commit before each module, so a `/verify` failure tells you which change did
it. Run `/verify` after any schema change. Hand anything touching documents,
approvals, the ledger or permissions to the `control-auditor` agent before
calling it done.

**When a change would weaken a control**, the answer is almost always no, and
`CLAUDE.md` says why. The one legitimate case is a client decision that changes
the rule, and that belongs in a migration with a comment recording who decided
it.
