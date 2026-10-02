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

- [x] `goods_received_note` subtype table + lines (migration V11), with the
      view and action permissions its chain signers need, placed on the
      Board roles that sign each step (the migration must
      `SET LOCAL highbytes.migration = 'on'` to change a policy role, and
      fails if the result leaves anyone in conflict)
- [x] Serial allocation from `serial_sequence`, concurrency-safe
      (`UPDATE ... RETURNING`)
- [x] `ReceivingService`: draft, submit, approve, post
- [x] Workflow binding by **creation date**, not `now()`
- [x] Approval guards: not the raiser, not already signed
- [x] Posting writes the transaction ticket + ledger rows in one transaction
- [x] Landed cost capture (freight, duty, clearing, demurrage) with
      allocation across lines
- [x] Measured thickness per line, refused empty for glass
- [x] Customs reference required when receiving into a bonded location
- [x] List / form / view templates, line items via htmx
- [x] Testcontainers test: post a GRN, assert the ledger moved exactly right
- [x] `/verify` clean (149 ok), `control-auditor` clean after one round of
      fixes (2026-09-30); its low findings are under "Then"

## Then

- [x] Delivery Authorization + Delivery Note — the release gate (V12, customers
      master, the release banner fragment `ui :: releaseGate`, the gate
      screens). `/verify` clean (227 ok), `control-auditor` clean after two
      rounds of fixes (2026-09-30)
- [x] A deadlock or serialization failure (40P01/40001) is answered "try
      again" and logged, not audited as a REJECT (`ContentionException`)
- [x] A document action refused by `CurrentUser.requireAt` (wrong branch)
      is recorded as REJECT (`DocumentService.requireRight`). Refused reads
      (opening a form) are not recorded, so a prefetched or crawled link
      cannot write audit rows in someone's name
- [ ] Reversals: a ticket's source must be a GRN (RECEIPT/IN) or a DN
      (DELIVERY/OUT), so no reversing ticket can exist yet. The reversal
      module widens `ticket_guard` and `document_support_link` together
- [ ] The DN-versus-DAO-cancel race and the negative-stock lock are proved
      by reading and by `pg_locks`, not by two live sessions racing
- [ ] Signed delivery acknowledgement (Sales returns it): attach the scan to
      the delivery note (`attachment` table exists, storage does not)
- [ ] The 2027 load-reconciliation waiver (COO) is not built: a short load
      is cancelled and authorized again
- [x] Transfers — dispatch relieves to TRANSIT, receipt clears it (V13, `inventory/transfer/`; a
      shortfall stays in transit until a damage or loss document exists)
- [x] Stock left in transit is cleared by a loss report (`transfer_line_position.in_transit_base`; the "Left in
      transit" worklist, `/damage/in-transit`): received + written off never exceeds dispatched, whichever
      comes first, and both take exactly the consignment's dispatch value (one shared rule, `ConsignmentShares`)
- [ ] The 2027 transfer approver (Managing Director) is for the client to confirm; reversing a posted
      transfer or receipt is not built
- [ ] Cutting — **blocked** on the off-cut identity decision
- [x] Returns & Damage — one DMG type, four kinds: write-off, loss in transit, customer return, quarantine
      release (V14, `inventory/damage/`). One chain (Warehouse Manager, Internal Controller, Managing Director),
      Finance posts. **Every report takes the full chain until the client sets the write-off threshold.**
      `/verify` clean (422 ok), `control-auditor` clean after one round of fixes (2026-10-01): a return now
      excludes everyone behind the delivery (the note's author and poster, the authorization's raiser and every
      signer, not only its releaser), and the form's bin and stock lookups read only the working branch
- [ ] Returns & Damage, left for later: no reversal of a posted report (nor of anything posted), no
      quarantine-age or expiry rule, no attachment of the customer's return note or the police report, no
      separate mid-chain threshold route for small write-offs
- [ ] A transit loss may be raised the moment a transfer is dispatched: the destination is never asked to
      confirm non-arrival and no age applies, so a posted loss can refuse the genuine receipt that follows
      ("only X remains"). The full chain still signs it, and its author may not record the arrival
- [ ] The receipt and transfer forms still list the bins of whatever location id is posted
      (`ReceivingController`, `TransferController` → `binsOf`), so another branch's bin codes can be read.
      Codes only, no stock; scope them to the working branch as `DamageLookupService` now does
- [ ] A report's own signers are not judged against what it is about: the Internal Controller who released a
      transfer or an authorization may verify its loss or return. Kept as is for the assurance role; a
      conscious choice, not an oversight
- [x] Counts + Variances — one CNT per location, full or cycle (V15, `inventory/count/`). The database writes the
      sheet and each line's book from the ledger; the book is on no page, list, badge or audit entry until the
      verification is signed. Submission closes the first count and the database chooses the recount: every line
      that differs from the book and a random one in ten of the rest. The Internal Controller recounts blind to
      the book and the first count, and the recount prevails. Nothing counted moves from opening until the
      verification is signed. Finance approves; a second Finance officer posts ADJUSTMENT tickets (shortages at
      average cost, surpluses at the line's stored cost, checked at commit). `/variances` is the VR-010 report.
      Dashboard inventory accuracy now reads posted counts (90 days)
- [x] Counts, from the control review: which lines were chosen for the recount (and how many) reach nobody but
      the verifier until it is signed, and no refusal on the count's trail names one; a count whose verification
      is signed is never cancelled; whoever counted signs no step but the first, approving or rejecting, and
      takes no verification count; whoever verified signs only the verification; a found item brings its places
      on the book onto the sheet, so a found line's book is nothing and removing it says nothing about the book
- [ ] Counts, left for later: no reversal of a posted count; quantities in the base unit only; a cycle count
      blocks any other count at its location, even of other items; `stock_balance.last_counted_at` is not set
      (only `LedgerService` writes `stock_balance`); the VR document type is unused (the count carries its own
      variance); the cut-off list (documents not yet posted at the location) advises, it does not refuse
- [ ] Counts: a full count freezes its location until the verification is signed, so a slow verification
      holds up the warehouse there. No escalation beyond the chain's `escalate_after_hours` (none is set for CNT)
- [ ] Counts: once a verified count is rejected its book stays readable, so the next count of that location is
      not blind to the old book. Rejection is an independent signer's decision, and the random sample is what
      catches a counter who copies the book; the client may want the next count opened by someone else
- [ ] Counts: the trail and the count's view show that a line was added as found, which tells the verifier the
      book held nothing at that place. Kept: knowing a surplus place's book was nothing anchors no recount
- [x] Daily Close — one close per branch per day that is over (V16, `reporting/close/`). Finance reconciles: the
      database writes opening, receipts, dispatches, adjustments, closing and the movement count from the ledger,
      and counts the exceptions (an opening that differs from the last locked close, a place below zero, a
      stock-moving document approved but not on the ledger by the day's end, the balance cache differing from the
      ledger); a day with exceptions needs a note. The Internal Controller countersigns, which locks the day, or
      returns it with a reason. Days close in order; nothing is dated into or before a signed day; a locked day is
      never reopened. A nightly Quartz job (00:15 Kigali) prepares the closes, never signs one, and logs any signed
      day whose ledger no longer reads as signed (the register flags it too). A reconciler who posted some of the
      day's movements is an exception, needing a note; the trail keeps the exceptions excused and the whole note
- [ ] Daily Close, left for later: no escalation or notification for overdue days beyond the register and the
      sidebar badge; a day with no movements has no close of its own (locking a later day closes it); the
      exceptions are read live, so a locked day's CACHE line reflects the cache now, not then
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
      check did not see) shows a 500 and leaves no refusal record on the
      access screens (users, roles). Document actions already translate it
      (`DocumentService.refusedAtCommit`); copy that shape
- [ ] Database time zone: V9's access checks use `CURRENT_DATE` (the
      session's zone), V11 uses `kigali_today()`. They disagree from 00:00 to
      02:00 Kigali if the session runs in UTC. Set the app's connections to
      Africa/Kigali, or move V9's checks to `kigali_today()` in a new migration
- [ ] `stock_balance` is written only by `LedgerService`, but the database
      does not stop anything else writing it, and `stock_balance_from_ledger`
      groups by item and location, not by bin. The daily close now compares
      the cache with the ledger per bin at each reconciliation (CACHE); a
      nightly comparison that alerts on its own is still to build
- [ ] No screen for unit conversions (`item_uom_conversion`): a receipt line
      in a unit other than the item's base unit is refused until one exists
- [ ] Approval queue (`/approvals`) and a transaction ticket screen; today a
      ticket shows only inside its GRN
- [ ] Access checks re-judge every holder per changed row; with hundreds of
      holders of one role a large permission change takes seconds

## Known gaps

- [ ] Workflow signer roles seeded with no permissions: the COO still holds
      none (V11–V13 placed the receiving, dispatch and transfer rights on the
      Assistant WH Manager, the Head of Inventory and the three 2027 roles). They are policy roles, so they
      get the view and action permissions of the documents they sign in the
      migration that builds each document
- [x] `DashboardService.kpis()` reads inventory accuracy from posted counts
      (V15); it shows "—" until a count is posted at the branch
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
- [ ] **Who posts a count's adjustment.** Finance approves the count, so a second Finance officer posts it
      (V15, `count.post`). A branch with one Finance officer needs cover from another branch. Confirm
- [ ] The verification sample: every differing line plus one in ten of the rest (V15, `count_sample_share`)
- [ ] Whether counters lose `stock.view` while a count is open: the book is absent from the count pages, but
      stock figures elsewhere (the dashboard, the daily close's movements and exceptions, the stock screens once
      built) stay readable
- [ ] **A count is never cancelled once its verification is signed** (V15): it is posted, or rejected by a
      signer who took no part in it. Stricter than the other documents, which their creator may cancel until
      posted. Confirm
- [ ] **Who locks the day.** Finance reconciles and the Internal Controller countersigns, which locks it (chosen
      1 October 2026); V16 moved `close.lock` from the Managing Director to the Internal Controller. Confirm
- [ ] Whether the Finance officer who reconciles a day may have posted some of its movements (V16 allows it and
      shows it on the close; forbidding it needs a second Finance officer every day)
- [ ] How long a day may stay unclosed before it is escalated, and to whom
- [ ] No segregation rule pairs the Warehouse Manager with Finance, so one person may hold both. V15 keeps
      such a person from approving or rejecting a count they counted; should the pair be blocked outright?
- [ ] **Returns against 2026 authorizations need a second Warehouse Manager.** The 2026 chain has a
      Warehouse Manager verify every authorization, and nobody who signed one takes back what it let out,
      so the return is raised by another Warehouse Manager or one covering from another branch. The 2027
      chain has no Warehouse Manager step. Confirm the client accepts this, or name who else raises returns
- [ ] When may a transit loss be raised: at once, after the destination confirms non-arrival, or after
      some days in transit?
- [ ] Hosting: on-premises at Gahanga or cloud (bonded data residency)
- [ ] **Who may cancel a document others have signed.** Today: a receipt's
      creator or any `receiving.create` holder at its branch, until posted.
      No stock moves, but one person can veto a signed chain. Kept as is on
      2026-09-30 pending the client
- [ ] Director of Supply Chain + Internal Controller: no segregation rule
      pairs them (one person could hold both; per document they still sign
      only one step). Does Board Table 6 intend a BLOCK pair?
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
