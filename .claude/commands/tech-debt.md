---
description: Records a conscious tech-debt item, trigger required
argument-hint: <what was left missing>
allowed-tools: Bash, Read, Write, Edit, Glob, Grep
---

Follow `docs/tech-debt/README.md`. Subject: **$ARGUMENTS**

1. Look for an equivalent item in `docs/tech-debt/`; if one exists, update it
   instead of duplicating.
2. Number: highest in the folder + 1, four digits.
3. Fill the template. The trigger must be an **event**: "when the second SDK
   needs it" is an event; "when there is time" is an intention in disguise. If
   no honest trigger exists, stop and ask the owner.
4. Add the index row in `docs/tech-debt/README.md`.
5. `make docs-check`.
