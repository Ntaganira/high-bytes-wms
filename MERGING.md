# Merging this over your working copy

This is the **full project**. You have local changes; two files here may
differ from yours.

## 1. `V7__quartz_tables.sql`

You wrote one; so did I. Both are Quartz 2.3.2's PostgreSQL DDL, but Flyway
compares **checksums**, not behaviour. If your database has already applied
your V7 and you drop mine in, startup fails with a validation error.

**If your database already ran your V7** — keep yours. Overwrite my file
with yours before starting:

    copy <your-V7> src\main\resources\db\migration\V7__quartz_tables.sql

**If you are starting from an empty database** — either works.

**If you already replaced it and Flyway now complains**, one of:

    mvn flyway:repair            # rewrites the stored checksum
    -- or, in psql:
    DELETE FROM flyway_schema_history WHERE version = '7';  -- then let it re-run

The second only if the qrtz_ tables are NOT yet there.

## 2. `application.yml`

Mine now carries the delegate line you added:

    org.quartz.jobStore.driverDelegateClass: org.quartz.impl.jdbcjobstore.PostgreSQLDelegate

If you changed anything else — a port, a log level, a path — diff before
overwriting. Everything else in the file is unchanged from what you have.

## Everything else

New since the last drop, no conflicts expected:

    common/audit/**                  the audit trail
    masterdata/item/**               item master
    masterdata/location/**           locations and bins
    db/migration/V8__item_categories.sql
    templates/masterdata/**
    README.md                        rewritten

`AuditService` supersedes any `auditorAware` bean you may still have lying
around — `WebConfig` holds the only one.
