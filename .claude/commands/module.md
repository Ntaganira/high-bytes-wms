---
description: Build a document module end to end
argument-hint: [GRN|DAO|DN|TRF|CUT|DMG|CNT]
---

Build the document module for type `$1`, end to end, using the
`module-builder` agent.

Before starting, tell me in three or four lines:
- which document type this is and what it does in the business
- which approval chain(s) apply to it, and whether they differ across the
  2027 restructuring
- whether posting it moves stock, and in which direction
- anything in `CLAUDE.md`'s open questions that blocks it

Then build it. When finished, run `/verify` and hand the diff to the
`control-auditor` agent.
