# HIGH BYTES WMS

Warehouse management system for HIGH BYTES Ltd — Gahanga (Kigali) and the BBF Public Bonded Warehouse (Rubavu).

Phase 1 of four. See the SRS for scope; this README covers running the thing.

## Stack

| Layer | Choice |
| --- | --- |
| Runtime | Java 21 LTS, Spring Boot 3.3.5 |
| Build | Maven, single module, package-by-feature |
| Database | PostgreSQL 16 |
| Schema | Flyway |
| Persistence | Spring Data JPA (documents) + JdbcTemplate (stock ledger) |
| Security | Spring Security, session-based, BCrypt |
| Views | Thymeleaf + Bootstrap 5.3, assets served from the jar |
| Interactivity | htmx for partials, vanilla JS for count entry |
| Jobs | Quartz with a JDBC job store |
| Reports | JasperReports (PDF), Apache POI (Excel) |
| Tests | JUnit 5, Testcontainers |

Front-end assets are WebJars rather than a CDN: the Gahanga site may run without reliable internet, and an on-premises deployment cannot depend on jsdelivr being reachable.

## Running it

```bash
docker compose up -d db          # PostgreSQL 16 on 5432
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Flyway applies the schema on first start. To run everything in containers:

```bash
docker compose --profile full up --build
```

## Three rules the schema enforces

These are not conventions. They are triggers and constraints, so a bug in
Java cannot bypass them.

**1. The stock ledger is append-only.** `UPDATE` and `DELETE` on
`stock_movement` raise an exception. A mistake is corrected by a reversing
movement carrying `reverses_movement_id`. `stock_balance` is a cache of
`stock_balance_from_ledger`, which is the authority and can rebuild it.

**2. A locked business date refuses any movement inside it.** Once
`daily_close.status = 'LOCKED'`, an insert dated into that day at that
branch is refused at the ledger, not discouraged in the interface. Without
this, every other control can be undone the next morning.

**3. A posted document cannot be altered.** Only the cancellation columns
change after posting. A correction is a new document referencing the
original.

Two more worth knowing: an approval is immutable once recorded, and no user
may sign two steps on the same document — the second is a unique index on
`(document_id, actor_user_id)`, which is exactly the concentration of
duties the Board's review found.

## The two approval chains

Board Paper HB/BD/2026/09-05 replaces the Inventory Policy's four-signature
chain from 1 January 2027. Both exist as rows in `workflow_definition`,
effective-dated and non-overlapping (a GiST exclusion constraint enforces
that). A document is bound to the definition live on its creation date and
finishes under it.

| Document date | Chain |
| --- | --- |
| to 31 Dec 2026 | Finance → Asst WH Manager → WH Manager → Internal Controller |
| from 1 Jan 2027 | Inventory Transactions Officer → Director Supply Chain → Director Commercial → Internal Controller |

Moving the restructuring date is an `UPDATE` on one row, not a release.
`highbytes.restructuring-effective-date` in `application.yml` is what the
seed migration and the UI read; it does not itself switch anything.

## Layout

```
src/main/java/heritier/ntaganira/highbytes/wms/
├── config/        security, web, quartz
├── common/        base entities, exceptions, web helpers
├── branch/
├── security/      users, roles, permissions, SoD, audit
├── masterdata/    item, location, customer, supplier
├── document/      the spine + workflow engine
├── inventory/     ledger, receiving, dispatch, transfer, cutting, damage, count
└── reporting/     daily close, KPIs, exports
```

Package-by-feature, single module. Phase 3 (the QuickBooks replacement)
can split these into Maven modules along the existing package seams
without moving code between them.

## Migrations

| File | Contents |
| --- | --- |
| `V1__security_and_branches.sql` | Branches, users, roles, permissions, SoD rules, audit log |
| `V2__master_data.sql` | Items with glass attributes, UoM conversions, locations, bins, partners |
| `V3__document_spine.sql` | Document types, serials, effective-dated workflow, approvals, attachments |
| `V4__stock_ledger.sql` | Transaction tickets, append-only ledger, daily close, balances |
| `V5__reference_data_and_workflows.sql` | Both branches, both role sets, both approval chains |

Never edit an applied migration. Add a new one.

## Testing

Ledger and workflow rules are tested against real PostgreSQL via
Testcontainers, not H2 — the triggers, the GiST exclusion constraint and
the partial indexes have no H2 equivalent, so an H2 test would pass while
production fails.

```bash
./mvnw test
```

## Not in Phase 1

Order entry, EBM fiscalisation, invoicing, credit control, the general
ledger, van stock. Each has a seam left for it: `customer.credit_limit`
exists but is unenforced, `location.location_type` already accepts `VAN`,
and the ledger carries value so the GL has something to post against.
