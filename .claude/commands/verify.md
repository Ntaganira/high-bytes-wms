---
description: Run the control invariant checks and report any weakened control
---

Run the control invariants against the database:

```bash
psql "${DB_URL:-postgresql://highbytes:highbytes@localhost:5432/highbytes_wms}" -f tools/verify-controls.sql
```

Every line must read `ok`. For any `FAIL`:

1. Name the invariant from `CLAUDE.md` that has been weakened.
2. Find the migration or code change that did it (`git log -p` on the
   relevant migration, or the trigger definition in the database).
3. Say what an operator could now do that they could not do before.

Do not fix it without telling me first — a weakened control is sometimes a
deliberate decision I have made and forgotten to record.
