# 02 — Comments

What a comment in source code may say.

## Rules

- **A comment answers what breaks if someone touches this.** The *why* of a
  decision lives in an ADR or in the SDD.
- **At most 4 lines per contiguous block**, counting `/**`, `*/` and the
  reference line. More than that is rationale: move it to the docs.
- **Cite as `ADR-NNNN` or `DEBT-NNNN`**, inline is fine:
  `// Keeps the socket alive on iOS background (ADR-0004).`
  Only cite ids that exist in `docs/adr/` or `docs/tech-debt/`.
- **Do not paraphrase** the ADR you cite. A reference is a pointer.
- **No history** ("renamed because…", "found by reading the lib source") and no
  meta-comments about commenting.
- **Public SDK API gets KDoc / DocC**: one sentence of what it does, and the
  parameters when not obvious. This is documentation, not a comment, and the
  4-line limit does not apply.
- **A fact removed from source is not lost**: it becomes a tech-debt item, an
  SDD paragraph or a guideline, in the same PR.

## Examples

| Do | Don't |
| --- | --- |
| `// Must run before any URLSession is created (ADR-0004).` | Ten lines explaining how URLProtocol registration works |
| `// DEBT-NNNN` | `// TODO: fix later` |
