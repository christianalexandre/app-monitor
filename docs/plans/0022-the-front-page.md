---
id: PLAN-0022
title: The repository's front page
status: Done # Draft | Active | Done | Abandoned
milestone: M12
started: 2026-11-27
finished: 2026-11-27
---

# PLAN-0022 — The repository's front page (M12)

## Goal

Someone who has never seen App Monitor understands from the README, in under a minute,
what it does and how it looks, installs it from a few steps, and finds the rest on a docs
site. A contributor builds and tests it from `CONTRIBUTING.md` alone.

Constraints this plan follows:

- **Nothing private** (standing decision): every capture comes from the samples or invented
  data, never from a field test.
- **The README is for app developers, `docs/guide/` holds the details** (ADR-0220): the site
  publishes the guides, it does not fork them.
- **Short setup** (standing decision): the steps a developer copies do not grow.

## Tasks

- [x] **M12.1 Animated captures.** (`docs/animated-captures`) A Gradle task in `desktop/app` renders scripted scenes
  offscreen, as the screenshot tests do (ADR-0145), from invented traffic: requests streaming
  in, a request's detail tabs, a mock answering a call. It writes each scene as an animated
  GIF to `docs/screenshots/` with the JDK's `ImageIO`, no new dependency. A size budget per
  file keeps the repository small.
  Accept: the task writes the GIFs, each under its budget; they are committed; the task is in
  `AGENTS.md`'s commands.
- [x] **M12.2 A README for a newcomer.** (`docs/readme-for-newcomers`) The front page opens with a GIF and what App Monitor
  does in one sentence, then a short tour (capture, mocks and breakpoints, history, MCP) with
  M12.1's captures, then getting started as today. The desktop's install names Desktop
  `1.1.0`. Every relative link and image in the README and `docs/guide/` resolves, checked by
  `make docs-check`.
  Accept: `make docs-check` with the link check, which fails on a broken link in its test.
- [x] **M12.3 `CONTRIBUTING.md`.** (`docs/contributing-guide`) How to build and test from a clean clone (JDK, Android SDK,
  Xcode and XcodeGen where needed), the `make` targets that matter, the branch and commit
  rules, where decisions and debt are written, and how a PR is reviewed. It points to
  `AGENTS.md` and `docs/guidelines/` instead of copying them.
  Accept: a fresh worktree following it runs `make check` green; `make docs-check`.
- [x] **M12.4 A docs site.** (`docs/docs-site`) The guides and the README published as a static site on GitHub
  Pages, built from `docs/guide/` by a tool chosen in an ADR. A pull request that changes the
  guides builds it in strict mode, so a broken link fails; `main` deploys it.
  Accept: the strict build green locally and on its PR; after the next merge to `main`, the
  site live. Without Pages enabled on the repository: stop and ask the owner.
  Done with Pages enabled at the owner's go-ahead and the `github-pages` environment moved
  from `dev` to `main`. The strict build passed locally and on #456; the deploy job runs on
  `main` only, so the site goes live at the next release, not checked here.

## Outcome

### M12 milestone review

`code-review` at `medium` of `a279548..3cb02de` (#453 to #456): a static read of the diff, 25
files, no test run by it. Ten findings, each then read in the code here; `grep` on the README
and the guides found none of the patterns findings 1 to 6 need. No task: none is hit by
today's docs. Findings 1 to 5 would show a broken page or image after a future edit; they are
correctness findings left open (DEBT-0142), so the owner merges this PR.

| # | Finding | Normal use hits it? | Where |
| --- | --- | --- | --- |
| 1 | `site_src.py` fails with a `ValueError` on a link outside the repository. | No: no such link | DEBT-0142 |
| 2 | Two images with one file name overwrite each other in the site's `assets/`. | No: every image name is unique | DEBT-0142 |
| 3 | A relative HTML `<img>` passes the link check but is not rewritten for the site. | No: no HTML image | DEBT-0142 |
| 4 | Links inside inline code are rewritten on the site. | No: none in inline code | DEBT-0142 |
| 5 | A `.webp` or `.jpeg` image goes to its GitHub page, not to `assets/`. | No: none | DEBT-0142 |
| 6 | The link check skips reference-style links, `%20` and `)` in targets. | No: none used | DEBT-0142 |
| 7 | `site.yml`'s PR paths leave out the `Makefile`. | No: a broken `site` target, then `main`'s run catches it | DEBT-0142 |
| 8 | `GifWriter.write()` deletes before writing, without a `finally`. | No: needs a failing encoder | DEBT-0142 |
| 9 | `DemoCaptures`' comment on the fixed time contradicts SDD 02 on the saved run's date. | A written rule | Fixed in this PR |
| 10 | Comments cite `PLAN-0022` and `AGENTS.md`, not ADR ids. | A written rule (guideline 02) | Fixed in this PR, ADR-0233 cited |

- **Shipped**: M12.1 the README's animated captures, `demoGifs` and `GifWriter` (#453).
  M12.2 the README for a newcomer, with a tour in GIFs, and the link check in
  `make docs-check` (#454). M12.3 `CONTRIBUTING.md`, checked from a fresh clone (#455). M12.4
  the docs site on GitHub Pages, `make site` and the `Site` workflow (#456). The close and
  findings 9 and 10 (this PR). Plan PR #452.
- **Decisions**: ADR-0233 (the README's tour and the link check), ADR-0234 (Zensical, the
  site's source generated from the README and the guides, deployed from `main`).
- **Debt**: Created DEBT-0142 (this review). None paid.
- **Deviations**: none in the tasks. M12.4 needed two repository settings, both at the
  owner's go-ahead: Pages enabled, and its environment's branch moved from `dev` to `main`.
- **Lessons**: two checks of the same links, `link_check.py` and Zensical's strict build, see
  different things (HTML images); one parser shared by the link check and the site's
  generator would have kept them equal.
