---
id: DEBT-0142
title: Findings of the M12 milestone review that today's docs do not hit
status: Open
date: 2026-11-27
trigger: The next PR that touches `site_src.py`, `link_check.py`, `site.yml` or `GifWriter.kt`, or a README or guide edit that adds an HTML image, a `.webp` or `.jpeg`, a reference-style link or a link outside the repository
paid_date:
---

# DEBT-0142 — Findings of the M12 milestone review that today's docs do not hit

## What is missing

Findings of PLAN-0022's review (its *M12 milestone review*), each read in the code:

1. `scripts/site_src.py` calls `relative_to(root)` on every relative target: a link that
   leaves the repository (`../../../other/x.md`) fails `make site` with a `ValueError`.
2. It copies every image to `assets/<file name>`: two images with the same name in different
   folders overwrite each other on the site.
3. It rewrites Markdown links only. A relative `<img src>` or `<a href>`, which
   `scripts/link_check.py` accepts, reaches the site unchanged and breaks there; `--strict`
   does not see raw HTML.
4. It rewrites links inside inline code, so a code sample showing a link changes on the site,
   and an example image path that does not exist fails the copy.
5. Only `.png`, `.gif`, `.jpg` and `.svg` count as images: a `.webp` or `.jpeg` goes to its
   GitHub page, an HTML page, and shows broken.
6. `scripts/link_check.py` does not check reference-style links (`[x][ref]`), does not decode
   `%20`, and cuts a target at a `)`.
7. `.github/workflows/site.yml`'s pull request paths leave out the `Makefile` that holds
   `make site`: a PR that breaks the target builds no site until `main`.
8. `GifWriter.write()` deletes the GIF before writing it and disposes the writer outside a
   `finally`: a failure midway leaves the committed GIF missing.

Also: `demoGifs` draws times in the machine's time zone, so the GIFs differ between machines.

## Why it was accepted

Today's README and guides have none of these: no HTML image, no `.webp` or `.jpeg`, no two
images with one name, no link in inline code or outside the repository, no reference-style
link (checked with `grep` on 2026-11-27). Each needs a future edit first.

## Trigger

The next PR that touches `site_src.py`, `link_check.py`, `site.yml` or `GifWriter.kt`, or a
README or guide edit that adds one of the patterns above. That PR fixes the items its change
can hit.

## How it was paid

> Filled when closing.
