## Release

Version: <!-- x.y.z -->

## Changelog

<!-- Grouped by type from `git log main..dev --oneline`: Features, Fixes, Other. -->

## Release gate

- [ ] `release-gate` workflow green (desktop ×3 OS, contract, Android e2e, iOS e2e, packaging)
- [ ] No `Proposed` ADR and no `[!]` task in the released milestones
- [ ] Tech-debt with trigger "before release" paid or consciously re-triggered

## After merge

- [ ] Tag `vX.Y.Z` on `main`
- [ ] GitHub Release with packages
- [ ] SDKs published (Maven Central, SwiftPM tag)
