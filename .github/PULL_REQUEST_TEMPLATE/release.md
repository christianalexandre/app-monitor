## Release

Version: <!-- x.y.z -->

## Changelog

<!-- Grouped by type from `git log main..dev --oneline`: Features, Fixes, Other. -->

## Release gate

- [ ] `release-gate` workflow green (desktop ×3 OS, contract, Android e2e, iOS e2e, packaging)
- [ ] No `Proposed` ADR and no `[!]` task in the released milestones
- [ ] Tech-debt with trigger "before release" paid or consciously re-triggered

## DEBT triggers

<!-- Each DEBT `make debt-check` lists: paid, re-triggered, or not fired and why. "None fired" otherwise. -->

## After merge

- [ ] Tag `X.Y.Z` on `main` (no `v`): `git tag X.Y.Z origin/main && git push origin X.Y.Z`
- [ ] `Publish` workflow green: SDKs on Maven Central, GitHub Release (ADR-0217)
- [ ] `scripts/consumers_check.sh X.Y.Z` green
- [ ] Back-merge PR `main` → `dev`, merge commit, then `dev` on the next `-SNAPSHOT` (ADR-0219)
