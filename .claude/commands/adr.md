---
description: Writes a new ADR from the template, with the right number
argument-hint: <decision to record or question to decide>
allowed-tools: Bash, Read, Write, Edit, Glob, Grep, WebFetch, WebSearch
---

Follow `docs/adr/README.md` and the "Decisions" section of `AGENTS.md`.
Subject: **$ARGUMENTS**

1. Read `docs/sdd/` and the accepted ADRs that touch the subject. Context must
   state what is **already decided** and not reopened.
2. Number: highest in `docs/adr/` + 1, four digits; slug in kebab-case, 3–6
   words.
3. Fill the template with evidence (links, measurements) for every factual
   claim. At least two real options.
4. Status: `Accepted` if the decision is the agent's to take; `Proposed` with
   an empty Decision if it falls in the four "stop and ask" areas.
5. Add the index row in `docs/adr/README.md`; if `Accepted`, reflect it in
   `docs/sdd/` or `docs/guidelines/` now.
6. `make docs-check`.

End with three lines: the decision (or the question for the owner), and what
changes because of it.
