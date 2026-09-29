# Moving to Claude Code

Everything Claude Code needs is in the repo. Nothing to configure.

## Setting up

```bash
cd highbytes-wms
git init && git add -A && git commit -m "HIGH BYTES WMS: Phase 1 foundation and master data"
claude
```

Claude Code reads `CLAUDE.md` automatically at the start of every session.
That file carries the domain, the invariants and the conventions, so you
never have to re-explain the project.

## What is in the repo for it

| File | What it does |
| --- | --- |
| `CLAUDE.md` | Project memory. Read every session, automatically |
| `TODO.md` | Where Phase 1 stands and what is next |
| `tools/verify-controls.sql` | 12 control invariants, all currently passing |
| `.claude/agents/control-auditor.md` | Reviews changes against the control rules |
| `.claude/agents/migration-writer.md` | Writes and proves Flyway migrations |
| `.claude/agents/module-builder.md` | Builds a document module end to end |
| `.claude/commands/verify.md` | `/verify` — run the invariant checks |
| `.claude/commands/module.md` | `/module GRN` — build a document module |
| `.claude/commands/status.md` | `/status` — where things stand |

## The first prompt

Paste this:

> Read CLAUDE.md and TODO.md, then get the project compiling. It was written
> without a build available, so expect import and signature errors. Run
> `mvn -q compile`, fix what it reports, and keep going until it builds
> clean. Do not change behaviour while fixing compilation — if something
> looks wrong rather than merely uncompilable, tell me instead of
> redesigning it.
>
> When it compiles, start the app against my local Postgres and confirm the
> dashboard, `/items` and `/locations` render. Then run `/verify`.

That is the right first job: the code has never been through a compiler, and
you want that separated from any new work.

## Then

```
/status                 where things stand
/module GRN             build Goods Received end to end
/verify                 after any schema change
```

The `control-auditor` agent runs on request, and Claude Code will reach for
it on its own after changes that touch documents, approvals, the ledger or
permissions — that is what its description is written to trigger on.

## Two habits worth keeping

**Commit before each module**, so `/verify` failing tells you which change
did it.

**When Claude proposes weakening a control** — making an approval optional,
letting a posted document be edited, giving the admin account a posting
right — the answer is almost always no, and `CLAUDE.md` says why. The one
legitimate case is a client decision that changes the rule, and that belongs
in a migration with a comment recording who decided it.

## What to watch on the first run

- `V7__quartz_tables.sql` — if your database already applied *your* version,
  keep yours; Flyway compares checksums, not behaviour
- The `admin` account cannot open `/items`. That is invariant 8, not a bug
- `spring.jpa.hibernate.ddl-auto: validate` with zero entities is fine
  today; the first `@Entity` must match the schema exactly or startup fails
