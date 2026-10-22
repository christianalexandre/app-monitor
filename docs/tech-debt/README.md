# Tech debt

What is known to be missing, accepted consciously. Every item has a
**trigger**: the event that forces it to be paid.

Rules:

- **Next number**: the highest in this folder + 1, four digits. Copy
  `_template.md` to `NNNN-kebab-slug.md`.
- **No trigger, no debt.** "When there is time" is not a trigger; "before the
  first public release" is.
- Paid or dropped: set `status` and `paid_date`, fill "How it was paid", and
  remove `DEBT-NNNN` references from code in the same PR.
- Check for an equivalent item before creating a new one.
- Add the row to the index below.

## Index

| Debt | Title | Trigger | Status |
| --- | --- | --- | --- |
| [0001](0001-jmdns-pinned-to-3-6-3.md) | JmDNS pinned to 3.6.3 | A JmDNS release newer than 3.6.4, or a security advisory for 3.6.3 | Open |
