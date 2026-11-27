---
id: PLAN-0022
title: The repository's front page
status: Active # Draft | Active | Done | Abandoned
milestone: M12
started: 2026-11-27
finished:
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
- [ ] **M12.3 `CONTRIBUTING.md`.** How to build and test from a clean clone (JDK, Android SDK,
  Xcode and XcodeGen where needed), the `make` targets that matter, the branch and commit
  rules, where decisions and debt are written, and how a PR is reviewed. It points to
  `AGENTS.md` and `docs/guidelines/` instead of copying them.
  Accept: a fresh worktree following it runs `make check` green; `make docs-check`.
- [ ] **M12.4 A docs site.** The guides and the README published as a static site on GitHub
  Pages, built from `docs/guide/` by a tool chosen in an ADR. A pull request that changes the
  guides builds it in strict mode, so a broken link fails; `main` deploys it.
  Accept: the strict build green locally and on its PR; after the next merge to `main`, the
  site live. Without Pages enabled on the repository: stop and ask the owner.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
