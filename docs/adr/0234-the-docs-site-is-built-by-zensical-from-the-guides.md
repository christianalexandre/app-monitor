---
id: ADR-0234
title: The docs site is built by Zensical from the README and the guides, and main deploys it
status: Accepted
date: 2026-11-27
superseded_by:
---

# ADR-0234 — The docs site is built by Zensical from the README and the guides, and main deploys it

## Context

PLAN-0022 M12.4 publishes the guides as a static site on GitHub Pages. ADR-0220 keeps the
README and `docs/guide/` as the source of what an app developer reads; the site must publish
them, not fork them. The owner enabled Pages with GitHub Actions as its source (2026-11-27);
the site is `https://christianalexandre.github.io/app-monitor/`.

Evidence, read on 2026-11-27:

- **MkDocs** 1.6.1, released 2024-08-30 (PyPI); its repository's last push was 2025-10-20.
- **Material for MkDocs** 9.8.2, 2026-11-19, MIT. Its README now says the team continues
  with *"Zensical, a maintained successor built by the team behind Material for MkDocs"*.
- **Zensical** 0.1.3, 2026-11-20, MIT, Python 3.11 or newer. `zensical build --strict` aborts
  on a warning: on this repository, a link to a missing page or anchor exits 1 with *"anchor
  does not exist"*, and the current docs build with *"No issues found"* in about 0.3 s.
- **Docusaurus** (MIT) needs Node and a React project beside a Gradle and Swift repository.
- **Jekyll** through Pages' own build needs no workflow, but checks no link and themes poorly.

The guides link to files outside them (ADRs, the SDD, `LICENSE`), and the README to
`docs/guide/` and `docs/screenshots/`: a site built from `docs/guide/` as it is would break
those links.

## Options

1. **Zensical**, pinned, over a source generated into `build/site-src` by a script that maps
   the README and the guides to pages and rewrites their links. Maintained, MIT, strict mode
   catches broken links; young (0.1.x), so the pin matters.
2. **MkDocs with Material.** The same configuration model, more plugins; MkDocs itself has no
   release since 2024 and Material's authors point away from it.
3. **Docusaurus.** Mature; a Node toolchain and a React project to maintain for six pages.
4. **Jekyll on Pages.** Nothing to install; no strict build, so a broken link ships.

## Decision

Option 1.

- `scripts/site_src.py` writes `build/site-src`: `README.md` as `index.md`, `docs/guide/*.md`
  under `guide/` (its `README.md` as `guide/index.md`), and the images they show under
  `assets/`. A relative link to one of those follows it; any other goes to its page on
  GitHub's `main`. Fenced code is left alone.
- `zensical.toml` at the root names that source and `build/site`. `scripts/site_requirements.txt`
  pins `zensical==0.1.3`; `make site` (`scripts/site_build.sh`) installs it into
  `build/site-venv` and builds strict.
- `.github/workflows/site.yml` builds strict on a pull request that changes the README, the
  guides, the screenshots or the site's files, and builds and deploys on a push to `main`.
  The `github-pages` environment allows `main` only: the site shows the released docs, as the
  install steps name released versions.

## Consequences

- A broken link or anchor in the README or a guide fails the site's PR check, beside
  `make docs-check`'s own check (ADR-0233).
- The site changes only when `main` does, at a release; `workflow_dispatch` on `main` redeploys
  it without one.
- A Zensical upgrade is a change to `site_requirements.txt`, built strict on its PR.
- `make site` needs Python 3.11 or newer; macOS's `python3` from Xcode is 3.9, so `PYTHON`
  or a Homebrew Python picks it.
