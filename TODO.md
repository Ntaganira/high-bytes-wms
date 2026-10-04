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
- [x] Cutting (V18, `inventory/cutting/`): Finance raises an order for a customer, the sheets of one glass item at
      one place and the sizes cut from them (the customer's pieces, the off-cuts kept); the Warehouse Manager
      verifies, the Internal Controller releases, a second Finance officer posts. Posting takes the sheets out at
      the ledger's average cost (CUT_CONSUME) and brings the cut in at its share by area (CUT_OUTPUT,
      `cut_output_shares`, checked at commit); what is neither is waste. Each size is one item per parent sheet and
      exact size, made the first time it is cut and audited. The pieces leave on a delivery note against the
      posted order: the gate's rules (not its raiser, not its releaser, one load, exact quantity) apply as to an
      authorization, and off-cuts never load. A return of cut glass is not taken by its raiser, signers or whoever
      recorded the cut
- [ ] Cutting, left for later: one sheet item and one bin per order on the form (the database allows several sheet
      lines of one item); the cut comes in unbinned; no cutting drawing attached; no reversal of a posted order;
      a piece cut for a customer can still leave under an ordinary authorization, which then takes the full chain;
      the 300 mm minimum and the area split are the defaults the client is to confirm; no ceiling on waste (an
      open question); at most 50 sizes an order, and a size made for a draft that is never posted stays on the
      item master (deactivate it there)
- [ ] Found reviewing cutting, older than it: the ledger's "not enough stock" message names what a place holds,
      bin by bin, before the freeze trigger refuses a counted place, so posting a delivery note or a damage
      report at a counted place that is short reads its book back (and the refusal is audited with it). Cutting
      refuses a counted place first; `LedgerService.shortage` should do the same for everyone. And the dispatch
      form's stock lookup (`DispatchLookupService.stockAt`) is not judged by the location's branch, as the cutting
      lookups now are
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
- [x] Admin screens — branches, Workflow Definitions, the audit log (V17, `admin/branch`, `admin/workflow`,
      `admin/audit`). A chain's steps change only by migration and never once a document is bound to it; outside
      a migration only a switchover still to come moves, both chains together, with a reason. A branch keeps its
      code, its kind once it has a past, and closes only once finished with (no stock, nothing open, no transfer to
      arrive, every day locked); nothing new starts at a closed one. The audit log reads only the entries recorded
      at branches where the reader holds `audit.view`; entries at no branch need the right everywhere
- [x] Stock screens (`inventory/stock/`): balances by item at the working branch, one item place by place (the
      cache, with the ledger read beside it and a difference flagged), and the ledger of movements with the
      document each answers to, filtered and paged. A place under a live count shows no quantity, value or
      movement, and is left out of every total; the dashboard, the item list and the document forms' stock
      lookups now leave it out too
- [ ] Stock screens, left for later: no export; one branch at a time (the branch switcher); a place in the
      ledger with no balance row at all is not listed (the daily close's CACHE check reports it)
- [x] Approval queue (`approval/`, `/approvals`): every PENDING document whose next step the reader could sign now,
      at every branch, by the rules signing applies (the step's role and right at the document's branch, not
      signed already, not its raiser after step 1, on a count no part in it), longest waiting first, a step past
      its `escalate_after_hours` flagged. Read-only: signing stays on each document's page. A document at a branch
      whose screens the reader's rights here do not reach offers the branch switch, which then opens it
      (`/branch/switch` follows `next` only to `/documents/{id}`). The sidebar carries it with a badge, the bell
      names it, and the dashboard's panel reads the same service (its old query checked neither the step's right
      nor a count's counters)
- [ ] Approval queue, left for later: no queue of approved documents awaiting posting (each type's poster rules
      differ: V14's parties, V15's counters); nothing escalates by itself or notifies anyone, an overdue step is
      only shown; the badge's count runs on every page, as the navigation's counts do (cache it if it shows)
- [ ] The blind book, what still shows it (accepted for now, 2 October 2026): a location or bin refuses a type
      change or deactivation while it holds stock, so probing that refusal says whether a counted place holds
      anything; the movements list pages by ledger id, so the gaps hint how many movements are hidden (movements
      at other branches leave gaps too); the daily close's movements and exceptions (the open question above);
      and a count cancelled before its verification lifts the hiding, though the book was readable before the
      count opened anyway. Frozen stock disappears from the quarantine and document-form lists without a note.
      Each document's own page still shows what it moved (only a GRN's or DN's balance after is withheld), so
      a counter who opens every receipt, delivery, transfer and damage report at a place can still add up its
      book; the ticket register, which lists them all, shows nothing of a counted place
- [x] Transaction tickets (`inventory/ticket/`, `/tickets`): the register of the tickets the working branch's
      documents wrote, filtered by date, movement, direction and serial (the ticket's, its document's or the
      customs reference) and paged; one ticket with its lines, the ledger movements it wrote, the document it
      answers to and that document's signatures, so who authorised, executed and recorded one transaction are on
      one page. `/documents/{id}` of a ticket opens it there for whoever holds `ticket.view`, and its document
      otherwise
- [x] Documents (`/documents`, `DocumentListService`): one register of every document the reader may read, of every
      type and at every branch, listed only where they hold the type's view right at the document's branch;
      filtered by type, status, branch, date, serial or reference and "raised by me", and paged. Each row says
      where the document stands (the step awaited and its role, posted when and by whom, a cancellation's
      reason) and never what it carries. A cancelled serial stays listed. A document whose screens the reader's
      rights where they work do not reach offers the branch switch, as the approval queue does
- [ ] Documents, left for later: no export; no serial-gap report (a missing number is an incident: the register
      shows cancelled serials, not numbers never issued)
- [x] Search (`search/`, `/search`): the top bar's box finds documents by serial or reference (through the
      documents register, so only where the reader holds the type's view right at the document's branch), items
      and locations (behind `item.view`), suppliers and customers (behind `partner.manage`). A section the reader
      may not read is not searched at all. A serial typed whole opens its document at once. The reader's % and _
      are characters, not wildcards
- [ ] Search, left for later: no search of people, branches or the audit log (each is on its own admin screen);
      the item list's own search box still treats % and _ as wildcards
- [x] Reports & KPIs (`reporting/report/`, `/reports`): the standard set (stock valuation, movement register,
      dispatches, write-offs/losses/returns, count variances, daily closes), each on screen and as PDF (OpenPDF)
      and Excel (Apache POI) drawn from one table, at the working branch, a period of at most a year (the month to
      date by default). Each report needs its own screen's right besides `report.view`. KPIs over 90 days: inventory
      accuracy, stock turnover, time to release, time at the gate, cutting waste, signatures overdue. Places under a
      live count are left out of every stock and movement figure; a count's variances are reported once posted
- [ ] Reports, left for later: exports are not recorded in the audit log (`audit_log.action` has no EXPORT; adding it
      is a migration); no scheduled or e-mailed reports; one branch at a time; at most 5,000 rows a report (then no
      total); names of posters read by join, so a renamed user reads under the new name; the daily-close report
      shows the signed figures, counted places included, to `close.view` holders as the close screen does
- [ ] Tickets, left for later: no export; a document's own page names its ticket without linking it; one branch at
      a time
- [ ] Admin screens, left for later: no export of the audit log; a document's audit entry links to no page
      (each type has its own); locations at an inactive branch are still offered on the location form, which the
      database then refuses
- [ ] Access review for the Internal Controller: who holds what, read-only,
      without admin rights
- [ ] Second person for access changes (see the open decision below):
      grants of operational roles, and password resets of accounts holding
      them, wait for a co-signature
- [ ] Production database roles: Flyway runs as an owner role, the
      application as a role with DML only (no TRUNCATE, no trigger DDL), and
      `in_migration()` also requires the owner. Today one superuser does both,
      so the migration flag guards against mistakes, not against the app
- [x] Before the workflow editor: guard `workflow_step` changes (V17: by
      migration only, never once bound). Date changes no longer decide who is
      operational (V10 made signing any chain operational), so they need no
      access check
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

- [ ] **Cutting's defaults** (built 2 October 2026, to confirm): one item per parent sheet and exact size; cost by
      area; off-cuts under 300 mm are waste; a second Finance officer posts; the pieces leave on a delivery note
      against the posted order
- [ ] Tolerance thresholds: count variance, damage, write-off approval
- [ ] **Who posts a count's adjustment.** Finance approves the count, so a second Finance officer posts it
      (V15, `count.post`). A branch with one Finance officer needs cover from another branch. Confirm
- [ ] The verification sample: every differing line plus one in ten of the rest (V15, `count_sample_share`)
- [ ] Whether the daily close hides a place under a live count too: the stock screens, the dashboard, the item
      list and the document forms now leave it out; the close's movements and exceptions still show it
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
