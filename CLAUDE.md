# HIGH BYTES WMS

Warehouse management system for HIGH BYTES Ltd (a BLUEROCK HOLDINGS
subsidiary), trading glass, silicones, stainless steel and construction
materials in Rwanda and Eastern DRC.

Phase 1 of four: inventory control at **Gahanga** (Kigali, main) and the
**BBF Public Bonded Warehouse** (Rubavu). More branches are expected — a
branch is configuration, never a code change.

## Why this system exists

A twelve-month Board review found that one office authorised, executed, held
custody of, recorded *and* reconciled the same transactions. The client's
losses come from stock leaving without approval, not from slow paperwork.

**Every design decision here serves control, not convenience.** When a
change would make the system easier to use but a control weaker, the control
wins, and the UI explains why rather than hiding the rule.

## Non-negotiable invariants

These are enforced by database triggers and constraints, not by convention.
Never work around them; if one blocks you, the design is wrong somewhere
else.

1. **The stock ledger is append-only.** `UPDATE` and `DELETE` on
   `stock_movement` raise an exception. Corrections are reversing movements
   carrying `reverses_movement_id`.
2. **No stock movement without a document.** Every ledger row carries
   `document_id`.
3. **A locked business date refuses movements inside it.** Once
   `daily_close.status = 'LOCKED'`, an insert dated into that day at that
   branch is refused at the ledger, and so is one dated before it or into a
   reconciled day (V16). A day is locked only by the Internal Controller
   countersigning Finance's reconciliation, days lock in order, and a locked
   day is never reopened, changed or deleted.
4. **A posted document cannot be altered.** Only cancellation columns change,
   and a posted document whose type moves stock cannot be cancelled at all:
   the ledger would still carry the stock while the document read
   CANCELLED. Posted stock is corrected by a reversing document (V11).
5. **Approvals are immutable, and no user signs twice on one document.**
   Unique index on `(document_id, actor_user_id)` — this *is* the Board's
   central finding, encoded.
6. **Serials are never reused.** Cancellation keeps the serial; a gap is a
   reportable incident.
7. **The audit log is append-only**, with actor name, username, role and
   branch captured as text at write time. Never resolve those by join at
   read time: renaming a role must not rewrite history.
8. **Whoever manages access takes no part in operations.** The `admin`
   account cannot post, approve, release or edit master data. That is
   deliberate. V9 enforces it for every role, including ones created at
   runtime: nobody holds an `admin.*` permission over overlapping dates with
   a transactional or master-data permission, or with a role that signs an
   approval step, and no role carries both (`access_conflict()`).
9. **Access is history, and checked when granted.** A role assignment is
   revoked, never edited, deleted or backdated, and names who granted and
   who revoked it. Nobody grants or revokes their own roles. A grant that
   breaks a `sod_rule` BLOCK pair or invariant 8 is refused at commit,
   whatever code path made it. Segregation follows the rights, not the
   name: a runtime role carrying a right only one side of a pair has counts
   as that side. The Internal Controller (an assurance role) holds no
   transactional or master-data right beyond its own. A transactional right
   no policy role carries cannot be handed out at runtime: the rules cannot
   see it. What the policy's roles permit, their names, the `sod_rule` rows
   and the permission catalogue change by migration only (V9, V10).

## The two approval chains

Board Paper HB/BD/2026/09-05 replaces the 2026 Inventory Policy chain from
**1 January 2027**. Both live as rows in `workflow_definition`,
effective-dated, with a GiST exclusion constraint preventing overlap.

A document binds to the definition live on its creation date and **finishes
under that definition** even if the effective date passes mid-process.

| Document date | Delivery authorization chain |
| --- | --- |
| to 2026-12-31 | Finance → Asst WH Manager → WH Manager → Internal Controller |
| from 2027-01-01 | Inventory Transactions Officer → Director Supply Chain → Director Commercial → Internal Controller |

Never hard-code either chain. The date is data: Workflow Definitions moves a
switchover still to come, both rows together, with a reason (V17). A chain's
steps, roles and order change only by migration, and never once a document
is bound to it; no chain is put in force, or out of it, for a day begun.

## Stack

Java 21 · Spring Boot 3.3.5 · Maven single module, package-by-feature ·
PostgreSQL 16 · Flyway · Spring Security (session-based, **not** JWT —
this is server-rendered) · Thymeleaf + Bootstrap 5.3 · htmx for partials ·
Quartz (JDBC store) · JasperReports + Apache POI · JUnit 5 + Testcontainers.

**Persistence rule:** `JdbcClient` with explicit SQL for reads, aggregates
and the ledger. JPA is available for document aggregates where dirty
checking and optimistic locking earn their keep. Do not put the ledger in
JPA — every read is an aggregate and Hibernate turns those into N+1s.

## Layout

```
src/main/java/heritier/ntaganira/highbytes/wms/
├── config/        SecurityConfig, WebConfig
├── common/
│   ├── audit/     AuditService, AuditSnapshot, AuditEntry, AuditAction
│   ├── db/        DbRefusal (the database's own reason), KigaliTime
│   └── web/       GlobalModelAdvice, LoginController
├── branch/
├── security/      AppUserDetails, AccountStateFilter, SignInEvents, PasswordRules
├── admin/
│   ├── user/      users, role grants and revocations, password reset, unlock
│   ├── role/      roles, the permission matrix, segregation rules (read-only)
│   ├── branch/    branches: create, amend, deactivate once finished with
│   ├── workflow/  the approval chains read whole; a switchover to come moved
│   └── audit/     the audit log, searched, as far as the reader's right reaches
├── profile/       My profile, change password (forced for temporary ones)
├── dashboard/
├── masterdata/
│   ├── item/      item master, glass attributes
│   ├── location/  locations, types, bins
│   ├── supplier/  suppliers (Finance, partner.manage)
│   └── customer/  customers, blocking (Finance, partner.manage)
├── document/      the spine: serials, chain binding, submit, sign, cancel; the register of every document
├── approval/      the approval queue: what waits on the reader's signature, every branch
├── search/        the search box: documents and master data, as far as the reader's rights reach
├── inventory/
│   ├── lookup/    the option types every form's pickers return, and StockAt
│   ├── gate/      GateSteps, ReleaseGate — what a held release says, shared
│   ├── ledger/    the only writer of stock_movement and stock_balance
│   ├── opening/   the cutover: what each place already held, once per place
│   ├── receiving/ Goods Received Notes
│   ├── dispatch/  Delivery Authorizations, Delivery Notes (the gate)
│   ├── transfer/  inter-branch transfers and their receipts (via TRANSIT)
│   ├── damage/    write-offs, transit losses, customer returns, quarantine
│   ├── count/     stock counts (blind, verified, frozen) and the variance report
│   ├── cutting/   cutting orders: sheets cut to a customer's sizes, off-cuts kept,
│                  the pieces out through the gate
│   ├── stock/     stock balances by item and place, and the ledger, read-only
│   └── ticket/    transaction tickets read: the register, one ticket and the signatures behind it
└── reporting/
    ├── close/     the daily close: reconciled by Finance, countersigned and
    │              locked by the Internal Controller; the nightly Quartz job
    └── report/    the standard reports and the KPIs, on screen, as PDF and Excel
```

The spine and the ledger (V3, V4) serve every module. A document module's
own tables come with its own migration (V11–V15, V18 for cutting). V4
made `daily_close`; V16 gave it its rules.

## Conventions

- **Authorities are permission codes** (`dispatch.release`), never role
  names. Roles group permissions and are created at runtime.
- **A role granted at one branch applies only there.** The authorities are
  the permissions at the branch the user is working in. For a record that
  belongs to another branch, check `CurrentUser.requireAt(permission,
  branchId)` in the service, as `LocationService` does.
- **Access changes apply on the user's next request.** `AccountStateFilter`
  compares `security_stamp` and `session_epoch` with the session. V9
  triggers restamp on any grant, revocation or role change; a new column
  that changes what someone may do must restamp too.
- `sec:authorize` in templates **hides**; `@PreAuthorize` on the service
  method **authorizes**. Always do both — a hidden button stops nobody
  holding a URL.
- Deactivate, never delete, anything that has been transacted.
- Every mutating service method writes an `AuditSnapshot` through
  `AuditService`. Unchanged fields are omitted from the diff automatically.
  An access change (users, roles, passwords) uses `recordAccessChange`,
  which joins the change's transaction: the change and its record commit
  together or not at all. A refused access change is recorded as `REJECT`
  with `record`, which survives the rollback.
- Status chips come from `fragments/ui :: chip(status)`. Never hand-write
  chip markup — the palette rules die the moment it appears in twelve
  templates.
- Colours come from `tokens.css`. No literal hex values in `app.css` or
  templates.
- Serials: `<TYPE>-<BRANCH>-<YEAR>-<NNNN>`, e.g. `GRN-KGL-2026-0412`.
- Anything read character by character — serials, item codes, TINs,
  quantities — uses `.mono` and `font-variant-numeric: tabular-nums`.
- Every Java file carries this header between the `package` line and the
  imports. `Date` is the day the file was created; `Desc` is one line on
  what the class is for. The class's own Javadoc still explains the why.

  ```java
  /**
   * <pre>
   * - Project    : HIGH BYTES WMS
   * - Package    : heritier.ntaganira.highbytes.wms.masterdata.item
   * - File       : ItemService.java
   * - Date       : 2026-09-29
   * - Author     : NTAGANIRA Heritier
   * - Desc       : The item master: search, create, update, deactivate and reactivate, audited
   * </pre>
   */
  ```

## Gotchas that have already bitten

- **Quartz needs two things.** `V7__quartz_tables.sql` creates the tables
  (`initialize-schema: never`), and `application.yml` sets
  `org.quartz.jobStore.driverDelegateClass=org.quartz.impl.jdbcjobstore.PostgreSQLDelegate`.
  The standard delegate misreads BYTEA — and that fails when a persisted job
  is *reloaded*, not at startup, so a clean boot proves nothing.
- **Never edit an applied migration.** Flyway compares checksums.
- **The `admin` account cannot open `/items`.** That is invariant 8, not a
  bug. Create a user under Administration → Users and grant it Warehouse
  Manager. It must replace its temporary password at first sign-in, as must
  the seeded `admin` (V6 sets `must_change_password`).
- **Nobody edits a role they hold**, not even its name. With a single
  administrator, the System Administrator role can only change through a
  second administrator. That is the rule working.
- **A role the policy defines is read-only on Roles & Permissions**: every
  role V5 seeded (all protected since V10), any assurance role, and any
  role named by a `sod_rule` or signing a step in any chain
  (`role_is_policy_defined()`). Its permissions, name and description
  change in a migration, which must say so first with
  `SET LOCAL highbytes.migration = 'on';` — so must one that changes a
  `sod_rule` row or a permission's code, module or action. The triggers
  refuse those changes otherwise, and judge the result for every user at
  commit (`access_conflict_anywhere()`), so a migration that makes someone's
  roles conflict fails.
- **Each document module's migration places its rights on the Board role
  that owns them.** Until then a right such as `ticket.create`,
  `ticket.countersign`, or `damage.approve` for a new signer, is carried by no
  policy role, so no role created at runtime may carry it either. Placing it
  is what lets the segregation rules judge who else may hold it. V11–V15
  and V18 placed the receiving, dispatch, transfer, damage, count and
  cutting rights; the COO still holds
  none, and gets its rights the same way. Give a right to the role whose step it
  signs, and ask the client when the chain does not say.
- **A new stock-moving document widens three lists together.** Stock moves
  only through a transaction ticket whose source is a type `ticket_guard`
  handles (OPB, GRN, DN, TRF, TRR, DMG, CNT and CUT today), whose lines
  `ticket_line_guard` can check against that source, and whose type
  `stock_movement_needs_approved_document` admits; the ledger then finds the
  approving document by walking `document_support_link` (V12). A module that
  moves stock restates all three functions in its migration with its own
  branch added, as V15, V18 and V19 did, or its tickets are refused. (The
  view already links every ticket to its source, so it needs no change; a
  document that relates to no other, as a count does, needs no branch of its
  own there either.)
- **A type two modules need does not live in the one that needed it
  first.** `inventory/lookup/` holds the picker option types
  (`LocationOption`, `BinOption`, `ItemOption`, `UnitOption`,
  `CustomerOption`, `StockAt`) and `inventory/gate/` holds `GateSteps` and
  `ReleaseGate`, because a release banner is what the whole system guards
  rather than a dispatch detail. Before 4 October 2026 these were nested in
  `ReceivingLookupService` and `DispatchLookupService`, and six of nine
  modules imported across to reach them. A new shared type goes in one of
  these two packages, never in the module that happens to want it first.
- **Each module asks its own pickers, under its own right.** Every module has
  its own `*LookupService` holding its own SQL and annotated with its own
  `view` permission; they share only the option *types*. So reading the
  cutover form needs `opening.view` and nothing else. Injecting another
  module's lookup service would make one screen's rights depend on another's.
- **An opening balance is the first thing in the ledger at a branch**
  (V19). The cutover from QuickBooks enters as a document because invariant 2
  leaves no other way in. Two controls are its own: at most one *posted*
  opening balance per location, ever (a partial unique index on
  `opening_balance (location_id) WHERE is_posted` — not a count, which two
  concurrent postings would both pass), and none may post once anything
  other than another opening balance has moved stock at the branch. So a
  branch loads its main store and its bonded store separately, and a branch
  that has traded loads nothing: stock found later arrives by count
  adjustment. `OpeningService.obstacleAt` asks both before the form opens, so
  nobody keys three hundred lines to be refused at the end; the database
  remains the authority. A test needs a branch of its own
  (`Fixtures.newBranch`) — the shared KGL and RBV fixtures trade, so a
  cutover test on those is refused by the control rather than by what it
  meant to test.
- **An open count freezes what it counts.** From the moment a count opens
  until its verification is signed, the ledger refuses any movement of a
  counted item at the counted location (a full count: the whole location),
  and a location carries one live count at a time (V15). A test that opens a
  count uses a location of its own (`Fixtures.newLocation`), or every other
  test moving stock there fails. Cancelling the count lifts the freeze.
- **The book of a blind count is on no page.** Until a count's verification
  is signed, its book quantities, values and variances reach no screen, no
  list, no nav badge and no audit entry; the verifier does not see the first
  count either. Nor do the lines chosen for the verification count, or how
  many, reach anyone but the verifier: the choice is every line that differs
  from the book, so it reads the book line by line. Refusals are written to
  the count's trail, which the counters read, so no count refusal names a
  chosen line. `CountService.mapLine` is the one place a sheet is masked:
  a new read of count lines goes through it, or checks
  `count_book_visible()`. Any read of stock quantities, values or movements
  leaves out the places where `count_freezing(item, location)` is not null:
  the stock screens, the dashboard (value, received, the chart, low stock,
  recent movements), the item list's totals, the location pages' item
  counts and value, the document forms' stock lookups, the transaction
  tickets (register and page: no quantity, value or balance), and the balance
  after on a posted GRN or DN. Left out, never subtracted, so no total
  gives the book away. Which places are being counted is read from the
  counts' sheets, never from the book, so an empty place reads like a full
  one. What decides a control (a location or bin holding stock) still
  counts every place.
- **Who judges a count took no part in it** (V15). Whoever counted a line
  signs no step but the first, approving or rejecting, and takes no
  verification count; whoever verified signs only the verification. Once
  the verification is signed the count is never cancelled: it is posted, or
  rejected by a later signer.
- **A day closes once it is over, and in order** (V16). Nothing can close
  today, and the ledger dates every movement today (V11), so a test never
  locks a day it posts into. A test that needs stock on a past day writes it
  with `session_replication_role = replica` (`CloseFlow.stockOn`, and
  `pg_temp.stock_on(..., p_force)` in the verify script), at a branch of its
  own, so the order its days close in rests on nothing another test wrote.
  The close's figures and exceptions are the database's
  (`close_figures`, `close_exceptions`), never supplied.
- **A chain moves only forward, and only as a migration otherwise** (V17).
  Outside a migration a switchover moves to tomorrow at the earliest, both
  chains together (the gap check runs at commit). A test that needs the 2027
  chain in force today brings the switch forward as a migration would:
  `set_config('highbytes.migration', 'on', true)` in one transaction, both
  rows (`ChainSwitchTest.switchOn`, checks 27, 38 and 46). The same goes for
  any fixture that adds a chain or a step.
- **A cut size is an item of its own** (V18). `cut_item_for(parent, w, h)`
  finds or makes the one item for a parent sheet and exact size, the longer
  side first, filed under the parent with `is_remnant`; cutting an off-cut
  again files what comes of it under the same parent. A size made for the
  first time is audited as an item created by the order. A sheet is cut
  only when it is glass counted by the sheet with a size on the item master.
- **A delivery note answers to a delivery authorization or a posted
  cutting order** (V18): `authorization_id` or `cutting_order_id`, one of
  the two, and each line to an authorization line or a piece of the order.
  Read the note's customer, place and customs reference through
  `delivery_note_authority`, and what it may load through
  `delivery_authority_line`; a new join straight to
  `delivery_authorization` misses every cut delivery.
- **A report reaches no further than the screen it summarises.** Besides
  `report.view`, each report needs its own screen's right (`ReportKind.right`:
  `stock.view`, `dispatch.view`, `damage.view`, `count.view`, `close.view`),
  and each KPI the right of what it measures. A report that reads stock or
  movements leaves a counted place out, as the stock screens do; a new one
  does too.
- **A branch closes only once finished with** (V17): no stock, no open
  document, no transfer still to arrive, every day with movements locked;
  the main branch never. Nothing new starts at an inactive branch (document,
  location, grant, transfer to it). Its code never changes, its type is
  fixed once it has a past, and there is one main branch, so a test creates
  BRANCH-type branches with codes of its own.
- **The access checks run at COMMIT** (deferred constraint triggers). A
  service asks `access_conflict()` first so the refusal carries its reason;
  a psql test must `SET CONSTRAINTS ALL IMMEDIATE`, as `verify-controls.sql`
  does.
- **Thickness is locked once stock has moved** against an item. Past
  dispatches record it as verified at the gate.
- **A location holding stock cannot change branch or type.**
- **Cast every nullable parameter tested with `IS NULL`** (`:q::text`,
  `:id::uuid`). PostgreSQL cannot type a bare NULL and fails the whole
  query, which took the item list down whenever the search box was empty.
- **Never put `th:replace` on an element that also has `th:each` or
  `th:if`.** Thymeleaf replaces first, so the loop variable is null and the
  condition is ignored. Put the loop or condition on a wrapping `th:block`.
- **Outside a form's `th:object`, name the object in `#fields`:**
  `#fields.hasErrors('${form}')`. There is no `hasGlobalErrors('form')`.
- **Linked but unbuilt screens are listed in `ErrorPages.PLANNED`**, which
  turns their 404 into a "not built yet" page. Remove a screen's entry when
  you build it. A new top-level path also needs its rule in `SecurityConfig`.

## Running

```bash
docker compose up -d db
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Sign in `admin` / `ChangeMe#2026`.

## Verifying

The control invariants have a test script. Run it after any schema change:

```bash
psql "$DB_URL" -f tools/verify-controls.sql
```

Every line must print `ok`. A `FAIL` means an invariant has been weakened.

## Build order for the rest of Phase 1

1. ~~Master data~~ — done
2. ~~Goods Received~~ — done; the pattern for the other eight (V11,
   `document/`, `inventory/ledger/`, `inventory/receiving/`)
3. ~~Delivery Authorization + Delivery Note~~ — done; the release gate (V12)
4. ~~Transfers~~ — done; goods in transit, each consignment at its own
   cost (V13)
5. ~~Cutting~~ — done; sheets cut to a customer's sizes, each size an item,
   the cost split by area, the pieces out through the gate (V18)
6. ~~Returns & Damage~~ — done; write-off, transit loss, customer return,
   quarantine release, each through the full chain and posted by Finance (V14)
7. ~~Counts + Variances~~ — done; blind first count, blind verification
   count of every differing line and a random sample, the freeze, adjustment
   tickets posted by a second Finance officer (V15)
8. ~~Daily Close~~ — done; Finance reconciles a day that is over, the
   database writes its figures and exceptions, the Internal Controller's
   countersignature locks it, days close in order; a nightly job prepares
   them (V16)
9. ~~Admin screens~~ — done; users and roles, then branches, Workflow
   Definitions (read whole; only a switchover still to come moves) and the
   audit log viewer (V17)

## Cutover from QuickBooks

0. ~~Opening balances~~ — done; the stock each place already held, in on a
   signed document because invariant 2 admits no other way, once per
   location and only before the branch has traded (V19,
   `inventory/opening/`). Numbered 0 because at a branch it is the first
   document that exists.
1. **The extract itself** — not built. The item master, suppliers and
   customers out of QuickBooks, then one opening balance per location. The
   hard part was the document and its controls, not the mapping.

## Open questions for the client

- **Who signs the opening balance, and does its chain switch in 2027?**
  V19 chose, 4 October 2026, for the client to confirm: Warehouse Manager
  (who counted the floor) → Inventory Transactions Officer (verifying
  independently) → Internal Controller → Managing Director, posted by
  Finance. The Managing Director signs last rather than the Internal
  Controller — the only chain where that is so — because the figures become
  the opening position the Board is shown. The chain is deliberately *not*
  effective-dated into the 2027 restructuring: a cutover is a single event
  in the life of a location, not an operational flow, and a chain ending
  2026-12-31 would leave the module unusable with no chain in force if
  go-live slipped into 2027. If the client wants the switch, add the second
  definition by migration before go-live.
- **Cutting (V18), built on defaults chosen 2 October 2026 for the client
  to confirm.** Off-cut identity: one item per parent sheet and exact size
  (`<parent>-R-<long>x<short>`), reused for the same size, rather than a
  code per piece or a pool per parent. Cost: the sheets' value split across
  the pieces and kept off-cuts by area, kerf and scrap absorbed. An off-cut
  under 300 mm on either side is waste (`cut_offcut_minimum_mm`). A second
  Finance officer posts the order. The pieces leave through the gate on a
  delivery note raised against the posted order, not under a separate
  delivery authorization; whoever raised the order, signed its release or
  posted it does not let them out.
- **How much waste may a cutting order declare?** Waste moves no stock of
  its own: the sheets' cost is carried by what was cut and kept, so an order
  cutting one small piece from a whole sheet loads the sheet's value onto
  that piece. The three signers see the waste in square metres and percent;
  nothing caps it. A ceiling (above it, the remainder recorded as off-cuts or
  written off on a damage report) is the client's threshold to set.
- Tolerance thresholds for count variances, damage and write-off approval.
  Until they are set, every damage report and every count takes all three
  signatures, however small.
- **Who posts a count's adjustment?** The count chain ends with Finance
  approving, and nobody posts what they signed, so V15 gives `count.post` to
  Finance and a second Finance officer posts. A branch with one Finance
  officer needs one covering from another branch. The client is to confirm.
- How large is the verification sample? V15 recounts every line whose first
  count differs from the book and one in ten of the rest (at least one),
  chosen at random at submission. Changed by migration.
- The book is absent from the count pages, and every stock screen, the
  dashboard, the item list and the document forms leave a place under a
  live count out (2 October 2026). The daily close's movements and
  exceptions still show them to whoever holds `close.view`, the Warehouse
  Manager included. Should they be hidden there too? The random
  verification sample is what catches a counter who copies the book.
- Does a customer return need a Finance credit note before it is posted, and
  is it raised here or in the accounting system?
- Nobody who signed an authorization raises the return of what it let out,
  and in 2026 a Warehouse Manager signs every authorization: a return then
  needs a second Warehouse Manager or one covering from another branch.
- When may a transit loss be raised: at once, after the destination
  confirms non-arrival, or after some days in transit?
- Hosting: on-premises at Gahanga or cloud — decides whether bonded stock
  data leaves Rwanda.
- **Who approves transfers from 1 January 2027?** The Head of Inventory
  role ends with the 2026 policy. V13 gives the approval to the Managing
  Director, the other role Inventory Policy §11 names; the client is to
  confirm (the Board paper may intend the Director of Supply Chain).
- **Who may cancel a document others have already signed?** Today a
  receipt's creator, or anyone holding `receiving.create` at its branch, may
  cancel it until it is posted. Cancelling moves no stock, but it lets one
  person veto a chain the Directors and the Internal Controller have signed.
  A count answers it for itself (V15): never once its verification is
  signed, since withdrawing it would drop a variance an independent recount
  confirmed. The client is to confirm.
- **Who locks the day?** Finance reconciles and the Internal Controller's
  countersignature locks it (chosen 1 October 2026); V16 moved `close.lock`
  from the Managing Director, where V5 had put it, to the Internal
  Controller. The client is to confirm.
- **May the reconciler have posted the day's movements?** V16 allows it, so a
  branch with one Finance officer can still close a day it posted in, but
  makes it an exception (RECONCILER_POSTED): the day is signed only with a
  note on it, which the Internal Controller reads before countersigning.
  Forbidding it would need a second Finance officer daily.
- How long may a day stay unclosed? Nothing locks a day by itself (a machine
  never signs); overdue days show on the register and in the sidebar.
- No segregation rule pairs the Director of Supply Chain with the Internal
  Controller, so one person may hold both. On one document they can sign
  only one step, but Board Table 6 may intend the pair to be blocked.
- Nor does any pair the Warehouse Manager with Finance. V15 keeps someone
  holding both from approving or rejecting a count they counted; should the
  pair be blocked outright?

- **Who may move a switchover, and how far?** The System Administrator
  (`admin.workflow`), with a reason, to any day still to come (chosen 2
  October 2026; the steps stay migration-only). Nothing bounds the date: one
  administrator could defer the Board's 2027 chain for years, or bring it to
  tomorrow, for every document raised meanwhile. The client may want a
  second signer (the Internal Controller), or a window around the Board's
  date, or the date back in migrations only.
- The audit log shows a reader the entries recorded at the branches where
  they hold `audit.view`; sign-ins and the system's own work, recorded at no
  branch, only to whoever holds it at every branch. Is that the reach the
  client wants for an Internal Controller granted branch by branch?

## Source documents

The SRS derives from five client documents: the Inventory Management and
Internal Control Policy v1.0, the Inventory Forms deck (GRN-001…CSH-012),
the Process Flow deck (PF-01…PF-08), Board Paper HB/BD/2026/09-05 Rev 5,
and the Controlled Forms Manual HB/OPS/FORMS/2027 Rev 2.
