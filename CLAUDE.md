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
4. **A posted document cannot be altered.** Only cancellation columns change.
5. **Approvals are immutable, and no user signs twice on one document.**
   Unique index on `(document_id, actor_user_id)` — this *is* the Board's
   central finding, encoded.
6. **Serials are never reused.** Cancellation keeps the serial; a gap is a
   reportable incident.
7. **The audit log is append-only**, with actor name, username, role and
   branch captured as text at write time. Never resolve those by join at
   read time: renaming a role must not rewrite history.
8. **Whoever manages access holds no transactional rights.** The `admin`
   account cannot post, approve or release anything. That is deliberate.

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
│   └── web/       GlobalModelAdvice, LoginController
├── branch/
├── security/      AppUserDetails, AppUserDetailsService
├── dashboard/
├── masterdata/
│   ├── item/      item master, glass attributes
│   └── location/  locations, types, bins
├── document/      NOT BUILT — the spine + workflow engine
├── inventory/     NOT BUILT — receiving, dispatch, transfer, cutting, count
└── reporting/     NOT BUILT — daily close, KPIs, exports
```

Schema for the unbuilt modules is already in place (V3, V4).

## Conventions

- **Authorities are permission codes** (`dispatch.release`), never role
  names. Roles group permissions and are created at runtime.
- `sec:authorize` in templates **hides**; `@PreAuthorize` on the service
  method **authorizes**. Always do both — a hidden button stops nobody
  holding a URL.
- Deactivate, never delete, anything that has been transacted.
- Every mutating service method writes an `AuditSnapshot` through
  `AuditService`. Unchanged fields are omitted from the diff automatically.
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
  bug. Create a Warehouse Manager account for testing.
- **Thickness is locked once stock has moved** against an item. Past
  dispatches record it as verified at the gate.
- **A location holding stock cannot change branch or type.**

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
2. **Goods Received** — first document that posts to the ledger; sets the
   pattern for the other eight
3. Delivery Authorization + Delivery Note — the release gate
4. Transfers — adds goods-in-transit
5. Cutting — needs the off-cut identity decision first (see Open questions)
6. Returns & Damage — quarantine, write-off approval
7. Counts + Variances — blind entry, adjustment tickets
8. Daily Close
9. Admin screens — users, roles, workflow editor

## Open questions for the client

- **Off-cut identity**: a distinct item code per remnant (traceable, grows
  the item master fast) or a remnant pool per parent item (lighter, weaker
  traceability)? Blocks the cutting module.
- Tolerance thresholds for count variances, damage and write-off approval.
- Hosting: on-premises at Gahanga or cloud — decides whether bonded stock
  data leaves Rwanda.

## Source documents

The SRS derives from five client documents: the Inventory Management and
Internal Control Policy v1.0, the Inventory Forms deck (GRN-001…CSH-012),
the Process Flow deck (PF-01…PF-08), Board Paper HB/BD/2026/09-05 Rev 5,
and the Controlled Forms Manual HB/OPS/FORMS/2027 Rev 2.
