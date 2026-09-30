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
   branch is refused at the ledger.
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

Never hard-code either chain. Moving the date is an `UPDATE` on one row.

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
│   └── role/      roles, the permission matrix, segregation rules (read-only)
├── profile/       My profile, change password (forced for temporary ones)
├── dashboard/
├── masterdata/
│   ├── item/      item master, glass attributes
│   ├── location/  locations, types, bins
│   ├── supplier/  suppliers (Finance, partner.manage)
│   └── customer/  customers, blocking (Finance, partner.manage)
├── document/      the spine: serials, chain binding, submit, sign, cancel
├── inventory/
│   ├── ledger/    the only writer of stock_movement and stock_balance
│   ├── receiving/ Goods Received Notes
│   └── dispatch/  Delivery Authorizations, Delivery Notes (the gate)
│                  NOT BUILT — transfer, cutting, damage, count
└── reporting/     NOT BUILT — daily close, KPIs, exports
```

Schema for the unbuilt modules is already in place (V3, V4).

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
  that owns them.** Until then a right such as `transfer.approve`,
  `cutting.release` or `ticket.create` is carried by no policy role, so no
  role created at runtime may carry it either. Placing it is what lets the
  segregation rules judge who else may hold it. V11 and V12 placed the
  receiving and dispatch rights; the Head of Inventory and the COO still
  hold none, and get theirs the same way. Give a right to the role whose step it
  signs, and ask the client when the chain does not say.
- **A new stock-moving document widens two lists together.** Stock moves
  only through a transaction ticket whose source is a type `ticket_guard`
  handles (GRN and DN today), and the ledger finds the approving document
  by walking `document_support_link` (V12). A module that moves stock adds
  its type to both in its migration, or its tickets are refused.
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
4. Transfers — adds goods-in-transit
5. Cutting — needs the off-cut identity decision first (see Open questions)
6. Returns & Damage — quarantine, write-off approval
7. Counts + Variances — blind entry, adjustment tickets
8. Daily Close
9. Admin screens — ~~users, roles~~ done; workflow editor, branches and
   the audit log viewer remain

## Open questions for the client

- **Off-cut identity**: a distinct item code per remnant (traceable, grows
  the item master fast) or a remnant pool per parent item (lighter, weaker
  traceability)? Blocks the cutting module.
- Tolerance thresholds for count variances, damage and write-off approval.
- Hosting: on-premises at Gahanga or cloud — decides whether bonded stock
  data leaves Rwanda.
- **Who may cancel a document others have already signed?** Today a
  receipt's creator, or anyone holding `receiving.create` at its branch, may
  cancel it until it is posted. Cancelling moves no stock, but it lets one
  person veto a chain the Directors and the Internal Controller have signed.
- No segregation rule pairs the Director of Supply Chain with the Internal
  Controller, so one person may hold both. On one document they can sign
  only one step, but Board Table 6 may intend the pair to be blocked.

## Source documents

The SRS derives from five client documents: the Inventory Management and
Internal Control Policy v1.0, the Inventory Forms deck (GRN-001…CSH-012),
the Process Flow deck (PF-01…PF-08), Board Paper HB/BD/2026/09-05 Rev 5,
and the Controlled Forms Manual HB/OPS/FORMS/2027 Rev 2.
