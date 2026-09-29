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
- [x] Users and roles: grants dated, branch-scoped or as leave cover;
      revocation with a reason; the permission matrix; My profile and
      password change (forced for temporary passwords); lock-out after five
      failures; sign-in audit; access changes applied on the next request
- [x] V9: segregation rules and invariant 8 enforced at grant time,
      segregation judged by right as well as by role, policy roles, rules
      and permissions fixed outside migrations, assignments as history, no
      self-grants
- [x] Access changes commit with their audit rows; refused attempts are
      recorded; My profile shows the holder their sign-ins and account changes
- [x] V10: every seeded role protected; the Internal Controller holds no
      operational right beyond its own; rights no policy role carries cannot
      be handed out; policy changes judged for every user; the audit names
      every role the actor held
- [x] `tools/verify-controls.sql` — all checks passing, 11–18 cover access

## Next: Goods Received (GRN)

The first document that posts to the ledger. Everything after it copies this
shape, so it is worth building carefully.

- [ ] `goods_received_note` subtype table + lines (migration V11), with the
      view and action permissions its chain signers need, placed on the
      Board roles that sign each step (the migration must
      `SET LOCAL highbytes.migration = 'on'` to change a policy role, and
      fails if the result leaves anyone in conflict)
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
- [ ] Admin screens — workflow editor, branches, audit log viewer
- [ ] Access review for the Internal Controller: who holds what, read-only,
      without admin rights
- [ ] Second person for access changes (see the open decision below):
      grants of operational roles, and password resets of accounts holding
      them, wait for a co-signature
- [ ] Production database roles: Flyway runs as an owner role, the
      application as a role with DML only (no TRUNCATE, no trigger DDL), and
      `in_migration()` also requires the owner. Today one superuser does both,
      so the migration flag guards against mistakes, not against the app
- [ ] Before the workflow editor: guard `workflow_step` deletion and
      `workflow_definition` date changes as access changes (they decide who
      signs, and so who is operational)
- [ ] A refusal the database raises at commit (a race the service's own
      check did not see) shows a 500 and leaves no refusal record
- [ ] Access checks re-judge every holder per changed row; with hundreds of
      holders of one role a large permission change takes seconds

## Known gaps

- [ ] **Not compiled.** Written without a build (Maven Central blocked in
      the authoring environment). Expect import and signature fixes on the
      first `mvn compile`.
- [ ] Workflow signer roles seeded with no permissions (Assistant WH
      Manager, Head of Inventory, the three 2027 roles). They are policy
      roles, so they get the view and action permissions of the documents
      they sign in the migration that builds each document
- [ ] `DashboardService.kpis()` returns a placeholder for inventory accuracy
      until count lines exist
- [ ] No tests yet
- [ ] `spring.jpa.hibernate.ddl-auto: validate` with zero `@Entity` classes —
      harmless today, but the first entity must match the schema exactly

## Found in review, older than the access module

- [ ] Item thickness (and probably width, height, stock levels) over the
      column's precision gives HTTP 500: the form has no maximum
- [ ] An item colour over 40 characters sends the form back with no message
- [ ] Location name over 120 characters shows the framework's default message
- [ ] A location holding stock still lets `is_sellable`, `is_bonded` and
      `is_active` change: a quarantine location can be flagged saleable
- [ ] Base unit not locked once stock has moved against an item
- [ ] The dashboard shows stock figures, and links to stock pages, to users
      without `stock.view`; its New document menu opens empty for users with
      no create right
- [ ] `/actuator/metrics` is open to any signed-in user
- [ ] Hand-written badges remain: the dashboard count, the OFF-CUT badge on
      the item page; `app.css` has literal `#fff`
- [ ] The topbar's `<h6>` is the first heading on every page; the location
      edit form repeats the ids `branchId`/`locationType` when it holds stock
- [ ] Bin changes are audited under `storage_bin`, but no page shows them
- [ ] Audit diffs list fields in PostgreSQL's JSON key order, not form order
- [ ] A database migrated at commit d1829c3 fails Flyway validation on V7
      (rewritten in d134f48); the dev database is already repaired

## Client decisions still open

- [ ] **Off-cut identity** — item code per remnant, or remnant pool per
      parent? Blocks cutting.
- [ ] Tolerance thresholds: count variance, damage, write-off approval
- [ ] Hosting: on-premises at Gahanga or cloud (bonded data residency)
- [ ] Whether the Board knows QuickBooks is being replaced rather than
      configured — resolutions 11 and the roadmap still say configured
- [ ] **Which Board role owns each right no role carries yet.** Chain steps
      suggest: release (`dispatch.release`, `cutting.release`) to the
      Internal Controller, who signs RELEASE; `transfer.approve` to the Head
      of Inventory; `ticket.create` to the Inventory Transactions Officer,
      `ticket.verify` to the Director of Supply Chain, `ticket.countersign` to
      the Director of Commercial. Each module's migration places its own;
      until then no role may carry them
- [ ] **Who hands over a temporary password.** Today the administrator who
      creates or resets an account sees the password, so could sign in as
      that user once, or run two accounts of their own. The holder now sees
      their sign-ins and account changes, so it would not go unnoticed, but
      it is not prevented. Options: deliver it out of band (email or SMS to
      the holder; needs a mail or SMS service, and the hosting decision), or
      a second person co-signs grants of operational roles and resets of
      accounts that hold them (the Internal Controller, or a second
      administrator)
