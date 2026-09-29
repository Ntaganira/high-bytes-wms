# Phase 1 — remaining work

Track this however you like; it is here so a fresh Claude Code session can
pick up the thread without being re-briefed.

Marked `[x]` is built and verified against PostgreSQL. Nothing below is
compiled-and-run verified except where it says so.

## Done

- [x] Project scaffold, Docker Compose, Dockerfile
- [x] Schema V1–V8, whole chain verified from empty
- [x] Both approval chains seeded, effective-dated, switch verified
- [x] Session auth, permission loading, segregation rules
- [x] Thymeleaf shell: layout, sidebar, topbar, UI fragments
- [x] Dashboard reading the ledger
- [x] Audit service with field-level before/after
- [x] Item master — list, create, edit, view, deactivate
- [x] Locations and bins
- [x] `tools/verify-controls.sql` — 12 invariants, all passing

## Next: Goods Received (GRN)

The first document that posts to the ledger. Everything after it copies this
shape, so it is worth building carefully.

- [ ] `goods_received_note` subtype table + lines (migration V9)
- [ ] Serial allocation from `serial_sequence`, concurrency-safe
      (`UPDATE ... RETURNING`)
- [ ] `ReceivingService`: draft, submit, approve, post
- [ ] Workflow binding by **creation date**, not `now()`
- [ ] Approval guards: not the raiser, not already signed
- [ ] Posting writes the transaction ticket + ledger rows in one transaction
- [ ] Landed cost capture (freight, duty, clearing, demurrage) with
      allocation across lines
- [ ] Measured thickness per line, refused empty for glass
- [ ] Customs reference required when receiving into a bonded location
- [ ] List / form / view templates, line items via htmx
- [ ] Testcontainers test: post a GRN, assert the ledger moved exactly right
- [ ] `/verify` clean, `control-auditor` clean

## Then

- [ ] Delivery Authorization + Delivery Note — the release gate. The
      blocked-release banner is the most important screen in the system.
- [ ] Transfers — dispatch relieves to TRANSIT, receipt clears it
- [ ] Cutting — **blocked** on the off-cut identity decision
- [ ] Returns & Damage — quarantine location, write-off approval
- [ ] Counts + Variances — blind entry (book quantity absent from the page,
      not hidden), adjustment tickets
- [ ] Daily Close — reconcile and lock, with the Quartz job
- [ ] Admin screens — users, roles, workflow editor, audit log viewer

## Known gaps

- [ ] **Not compiled.** Written without a build (Maven Central blocked in
      the authoring environment). Expect import and signature fixes on the
      first `mvn compile`.
- [ ] No password-change screen, though `must_change_password` is set on the
      seed admin
- [ ] `DashboardService.kpis()` returns a placeholder for inventory accuracy
      until count lines exist
- [ ] No tests yet
- [ ] `spring.jpa.hibernate.ddl-auto: validate` with zero `@Entity` classes —
      harmless today, but the first entity must match the schema exactly

## Client decisions still open

- [ ] **Off-cut identity** — item code per remnant, or remnant pool per
      parent? Blocks cutting.
- [ ] Tolerance thresholds: count variance, damage, write-off approval
- [ ] Hosting: on-premises at Gahanga or cloud (bonded data residency)
- [ ] Whether the Board knows QuickBooks is being replaced rather than
      configured — resolutions 11 and the roadmap still say configured
