#!/usr/bin/env python3
# Usage: site_src.py ROOT OUT
# Writes the docs site's source to OUT: README.md as index.md, docs/guide/*.md under guide/, and the images they
# show. A relative link to a page or image in the site follows it there; any other goes to its GitHub page (ADR-0234).
import re
import shutil
import sys
from pathlib import Path

REPO = "https://github.com/christianalexandre/app-monitor/blob/main/"
LINK = re.compile(r"(!?\[(?:[^\]\[]|\[[^\]]*\])*\]\(\s*)([^)\s]+)(\s*(?:\"[^\"]*\")?\s*\))")
FENCE = re.compile(r"^\s*(```|~~~)")


def pages(root):
    out = {root / "README.md": Path("index.md")}
    for md in sorted((root / "docs" / "guide").glob("*.md")):
        out[md] = Path("guide") / ("index.md" if md.name == "README.md" else md.name)
    return out


def rewrite(source, dest, root, site, images):
    lines, fenced = [], False
    for line in source.read_text(encoding="utf-8").splitlines():
        if FENCE.match(line):
            fenced = not fenced
        if fenced or FENCE.match(line):
            lines.append(line)
            continue

        def follow(m):
            target = m.group(2)
            if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I) or target.startswith("#"):
                return m.group(0)
            file_part, hashed, anchor = target.partition("#")
            path = (source.parent / file_part).resolve()
            rel = path.relative_to(root)
            if path in site:
                new = site[path]
            elif path.suffix.lower() in {".png", ".gif", ".jpg", ".svg"} and m.group(1).startswith("!"):
                new = Path("assets") / rel.name
                images[path] = new
            else:
                return m.group(1) + REPO + rel.as_posix() + hashed + anchor + m.group(3)
            link = Path(*[".."] * (len(dest.parts) - 1), new).as_posix()
            return m.group(1) + link + hashed + anchor + m.group(3)

        lines.append(LINK.sub(follow, line))
    return "\n".join(lines) + "\n"


def main():
    root, out = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
    if out.exists():
        shutil.rmtree(out)
    site = {src.resolve(): dest for src, dest in pages(root).items()}
    images = {}
    for src, dest in site.items():
        target = out / dest
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(rewrite(src, dest, root, site, images), encoding="utf-8")
    for src, dest in images.items():
        (out / dest).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, out / dest)
    print(f"site-src: {len(site)} pages, {len(images)} images in {out}")


if __name__ == "__main__":
    main()
