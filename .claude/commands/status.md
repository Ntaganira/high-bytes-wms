---
description: Where the project stands — built, in flight, next
---

Report where Phase 1 stands. Be concrete and check rather than assume:

1. Which modules exist under `src/main/java/heritier/ntaganira/highbytes/wms/`
   and which of the nine in `CLAUDE.md`'s build order are still missing.
2. The highest applied migration, and whether the local database matches
   (`SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5`).
3. Any `TODO`, `FIXME` or `NOT BUILT` markers in the source.
4. The open questions in `CLAUDE.md` that are still blocking something.

Finish with the single next thing worth doing, and why that one.
