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
| `TODO.md` | What is left, in the order to do it. Rewritten 5 Oct 2026 |
| `tools/verify-controls.sql` | 595 control checks, all currently passing |
| `.claude/agents/control-auditor.md` | Reviews changes against the control rules |
| `.claude/agents/migration-writer.md` | Writes and proves Flyway migrations |
| `.claude/agents/module-builder.md` | Builds a document module end to end |
| `.claude/commands/verify.md` | `/verify` — run the invariant checks |
| `.claude/commands/module.md` | `/module REV` — build a document module |
| `.claude/commands/status.md` | `/status` — where things stand |

## The first prompt

Paste this:

> Read CLAUDE.md and TODO.md. Then do item 1 only: get the project compiling
> and the tests green. Two pieces of work have never been through a compiler —
> the opening-balance module and the inventory/lookup + inventory/gate
> restructure — so expect import and signature errors.
>
> Rules while you do it: do not change behaviour to make something compile. If
> something looks wrong rather than merely uncompilable, tell me instead of
> redesigning it. If a control seems to be in the way, stop and explain what
> you think it is blocking — do not work around it.
>
> When `mvn -q compile` and `mvn -q test` are clean, run
> `psql "$DB_URL" -f tools/verify-controls.sql` and confirm 595 ok, 0 FAIL.
> Then stop and report: what you changed, what you had to touch that you did
> not expect, and anything you think is wrong but left alone. Do not start
> item 2.

Then, once that is green, item 2 in its own session:

> Read CLAUDE.md and TODO.md, then build item 2: reversing documents. V19
> (opening balances) is the closest model — same shape, most recently built.
>
> Who may undo a posted movement is a client decision, not yours or mine.
> Propose a chain, label it a proposal in the migration comment, and add it to
> the open questions in CLAUDE.md.
>
> Prove the migration against a live PostgreSQL before you hand it back, add
> its checks to tools/verify-controls.sql, and give the diff to the
> control-auditor agent. Tell me what you proved and what you only intended.

## The commands

```
/status                 where things stand
/verify                 after any schema change
/module REV             build the reversing document (item 2)
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

## What to watch

- **The `admin` account cannot open `/items`.** That is invariant 8, not a bug
- `spring.jpa.hibernate.ddl-auto: validate` with zero entities is fine today;
  the first `@Entity` must match the schema exactly or startup fails
- `V7__quartz_tables.sql` — if your database already applied *your* version,
  keep yours; Flyway compares checksums, not behaviour
