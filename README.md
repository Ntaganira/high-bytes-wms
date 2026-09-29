# HIGH BYTES WMS

Warehouse management system for HIGH BYTES Ltd — Gahanga (Kigali) and the
BBF Public Bonded Warehouse (Rubavu).

Phase 1 of four. The SRS holds the scope; this README covers running it.

## Stack

| Layer | Choice |
| --- | --- |
| Runtime | Java 21 LTS, Spring Boot 3.3.5 |
| Build | Maven, single module, package-by-feature |
| Database | PostgreSQL 16 |
| Schema | Flyway |
| Persistence | JdbcClient for reads and ledger writes; JPA available for document aggregates |
| Security | Spring Security, session-based, BCrypt |
| Views | Thymeleaf + Bootstrap 5.3, assets served from the jar |
| Interactivity | htmx for partials, vanilla JS for count entry |
| Jobs | Quartz, JDBC job store |
| Reports | JasperReports (PDF), Apache POI (Excel) |
| Tests | JUnit 5, Testcontainers |

Front-end assets are WebJars rather than a CDN: the Gahanga site may run
without reliable internet, and an on-premises deployment cannot depend on
jsdelivr being reachable.

## Running it

```bash
docker compose up -d db          # PostgreSQL 16 on 5432
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

On Windows with a database on another port:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
$env:DB_URL = "jdbc:postgresql://localhost:5437/highbytes_wms"
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Flyway applies the schema on first start. Sign in as `admin` /
`ChangeMe#2026`.

### The admin account cannot open most screens

It holds five permissions, all administrative, and none transactional. That
is the segregation rule, not a bug: whoever manages access must not be able
to grant themselves a posting right, use it, and take it away again.

For a real setup, create a second user with the Warehouse Manager role. For
a quick look around:

```sql
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
 WHERE r.code = 'SYS_ADMIN'
   AND p.code IN ('item.view','item.manage','location.manage','stock.view');
```

Undo it before the client sees the system.

## What the schema enforces

These are triggers and constraints, not conventions, so a bug in Java
cannot get round them.

**The stock ledger is append-only.** `UPDATE` and `DELETE` on
`stock_movement` raise an exception naming the rule. A mistake is corrected
by a reversing movement carrying `reverses_movement_id`. `stock_balance` is
a cache of the `stock_balance_from_ledger` view, which is the authority and
can rebuild it.

**A locked business date refuses any movement inside it.** Once
`daily_close.status = 'LOCKED'`, an insert dated into that day at that
branch is refused at the ledger. Without this, every other control can be
undone the next morning.

**A posted document cannot be altered.** Only the cancellation columns
change after posting.

**An approval is immutable, and no user may sign twice.** A unique index on
`(document_id, actor_user_id)` — which is exactly the concentration of
duties the Board's review found.

**Serial numbers are never reused.** Cancellation keeps the serial; a
missing number is a reportable incident.

**The audit log is append-only**, with the same trigger treatment.

## The two approval chains

Board Paper HB/BD/2026/09-05 replaces the Inventory Policy's four-signature
chain from 1 January 2027. Both exist as rows in `workflow_definition`,
effective-dated and non-overlapping — a GiST exclusion constraint enforces
that no two definitions for one document type can cover the same day.

| Document date | Delivery authorization chain |
| --- | --- |
| to 31 Dec 2026 | Finance → Asst WH Manager → WH Manager → Internal Controller |
| from 1 Jan 2027 | Inventory Transactions Officer → Director Supply Chain → Director Commercial → Internal Controller |

A document binds to the definition live on its creation date and finishes
under it, even if the effective date passes mid-process. Moving the
restructuring is an `UPDATE` on one row, not a release.

## Layout

```
src/main/java/heritier/ntaganira/highbytes/wms/
├── config/            security, web
├── common/
│   ├── audit/         the audit trail: snapshots, diffs, reads
│   └── web/           global model attributes, login
├── branch/
├── security/          user details, permission loading
├── dashboard/
├── masterdata/
│   ├── item/          item master with glass attributes
│   └── location/      locations, types, bins
├── document/          (next) the spine + workflow engine
├── inventory/         (next) receiving, dispatch, transfer, cutting, count
└── reporting/         (next) daily close, KPIs, exports
```

Package-by-feature, single module. Phase 3 can split these into Maven
modules along the existing package seams.

## Migrations

| File | Contents |
| --- | --- |
| `V1__security_and_branches.sql` | Branches, users, roles, permissions, SoD rules, audit log |
| `V2__master_data.sql` | Items with glass attributes, UoM conversions, locations, bins, partners |
| `V3__document_spine.sql` | Document types, serials, effective-dated workflow, approvals, attachments |
| `V4__stock_ledger.sql` | Transaction tickets, append-only ledger, daily close, balances |
| `V5__reference_data_and_workflows.sql` | Both branches, both role sets, both approval chains |
| `V6__seed_admin.sql` | The first administrator |
| `V7__quartz_tables.sql` | Quartz 2.3.2 scheduler schema |
| `V8__item_categories.sql` | Glass, silicones, steel, hardware, consumables |

Never edit an applied migration. Add a new one.

### Quartz needs two things, and one fails late

`V7` creates the scheduler tables, because `application.yml` sets
`initialize-schema: never`. Without it the scheduler dies at startup on
`qrtz_locks does not exist`.

`application.yml` also sets
`org.quartz.jobStore.driverDelegateClass: org.quartz.impl.jdbcjobstore.PostgreSQLDelegate`.
The standard delegate calls `getBytes()` on the job-data column, which
PostgreSQL stores as BYTEA. That failure appears when a persisted job is
*reloaded*, not at startup — so a clean boot proves nothing.

## What works today

- Sign in, session auth, permission-based authorisation
- Branch switcher, with bonded branches flagged
- Dashboard: KPIs, movement chart, approval queue, low stock — all reading
  the ledger
- Item master: list with search and filters, create, edit, view, deactivate
- Locations and bins: list, create, edit, view, bin management
- Audit trail on every master data change, with field-level before/after

## Not built yet

Goods received, dispatch, transfers, cutting, damage, counts, daily close,
and the admin screens for users, roles and workflows. The schema for all of
them is in place.

## Two rules the UI enforces that the SRS does not state

**Thickness is locked once stock has moved** against an item. Past
dispatches record that specification as verified at the gate by the Internal
Controller; editing it afterwards would make those signatures meaningless.

**A location that holds stock cannot change branch or type.** Either would
move stock between branches, or reclassify duty-suspended goods, without a
document saying so.

## Testing

Ledger and workflow rules are tested against real PostgreSQL via
Testcontainers, not H2 — the triggers, the GiST exclusion constraint and the
partial indexes have no H2 equivalent, so an H2 test would pass while
production failed.

```bash
./mvnw test
```

## Not in Phase 1

Order entry, EBM fiscalisation, invoicing, credit control, the general
ledger, van stock. Each has a seam: `customer.credit_limit` exists but is
unenforced, `location.location_type` already accepts `VAN`, and the ledger
carries value so the GL has something to post against.
