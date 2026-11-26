---
id: ADR-0229
title: A Homebrew cask and a Scoop manifest, written to one repository by the release
status: Accepted
date: 2026-11-26
superseded_by:
---

# ADR-0229 — A Homebrew cask and a Scoop manifest, written to one repository by the release

## Context

PLAN-0021 M11.3. The owner decided on 2026-11-26 that a single repository,
`christianalexandre/homebrew-tap`, holds the Homebrew cask and the Scoop bucket. Linux gets
its `.deb` and `.rpm` from the release (ADR-0227). The owner created the repository and the
`HOMEBREW_TAP_TOKEN` secret, a fine-grained token with *Contents: write* on that repository
only.

What was measured with Homebrew 7.0.13 (2026-11-26), on a local tap holding the cask this
ADR writes and the `.dmg` built here:

- `brew style` asked for `depends_on :macos`. With it, no offence.
- `brew audit --cask --strict` refused to load the cask from an untrusted tap. Once
  trusted, it exited 0.
- `brew install --cask <user>/<tap>/<cask>` installed it without a separate `brew trust`,
  and added the cask to `~/.homebrew/trust.json` by itself.
- The installed app has the `com.apple.quarantine` attribute, and `spctl --assess` rejects
  it. The first launch takes *Open Anyway*, as the guide says (ADR-0227).
- Homebrew 5.0 deprecated `--no-quarantine`
  ([Homebrew 5.0.0](https://brew.sh/2025/11/12/homebrew-5.0.0)).

## Options

1. **The publish workflow writes both files and pushes them.** After the release has its
   packages, a `tap` job runs `scripts/tap_manifests.sh`, which writes
   `Casks/app-monitor.rb`, `bucket/app-monitor.json` and a README with the hashes of that
   release's `.dmg` and Windows `.zip`. The job pushes them to `homebrew-tap` with the
   token. On a pull request it only writes them, as an artifact.
2. **Each manager updates itself.** The cask's `livecheck` and the manifest's `autoupdate`
   can follow releases, but only when someone runs `brew bump` or Scoop's `checkver -u`. With
   one owner, that someone is the owner, every release.
3. **A postflight that clears quarantine in the cask.** The first launch would not ask, but
   it works around Gatekeeper for the user, which Homebrew is moving away from. It is also a
   security choice the owner has not made.

## Decision

Option 1. The cask also keeps `livecheck` and the manifest keeps `checkver` and
`autoupdate`, so the managers' own tools still work. No quarantine workaround: the cask's
`caveats` say how to open the app the first time.

## Consequences

- A tag publishes the libraries, the packages and the two managers' files in one run.
- The token expires. Then the `tap` job fails at its push, the release is already published,
  and a new token followed by a re-run of that job brings the tap up to date.
- The Scoop manifest has not been checked against Scoop's schema, or installed by Scoop:
  both need Windows (PLAN-0021 M11.5, on the owner's machine).
- `brew audit` with no cask named needs `brew trust christianalexandre/tap` first, after
  Homebrew 7's change.
