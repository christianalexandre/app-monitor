#!/usr/bin/env python3
# Usage: link_check.py [ROOT]
# Every relative link and image in ROOT's README.md, CONTRIBUTING.md and docs/guide/*.md resolves: the file
# exists, and a #anchor names a heading of it as GitHub slugs it. External links are not fetched (ADR-0233).
import re
import sys
from pathlib import Path

LINK = re.compile(r"!?\[(?:[^\]\[]|\[[^\]]*\])*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
HTML = re.compile(r"""<(?:img|a)\b[^>]*?\b(?:src|href)=["']([^"']+)["']""", re.I)
FENCE = re.compile(r"^\s*(```|~~~)")


def text_outside_code(path):
    lines, fenced = [], False
    for line in path.read_text(encoding="utf-8").splitlines():
        if FENCE.match(line):
            fenced = not fenced
            continue
        if not fenced:
            lines.append(re.sub(r"`[^`]*`", "", line))
    return lines


def slugs(path):
    seen, out = {}, set()
    fenced = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if FENCE.match(line):
            fenced = not fenced
            continue
        m = None if fenced else re.match(r"^#{1,6}\s+(.*?)\s*#*\s*$", line)
        if not m:
            continue
        title = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", m.group(1)).replace("`", "")
        slug = re.sub(r"[^\w\- ]", "", title.strip().lower()).replace(" ", "-")
        n = seen.get(slug, 0)
        seen[slug] = n + 1
        out.add(slug if n == 0 else f"{slug}-{n}")
    return out


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    files = [root / "README.md", root / "CONTRIBUTING.md", *sorted((root / "docs" / "guide").glob("*.md"))]
    errors = 0
    for md in (f for f in files if f.is_file()):
        for number, line in enumerate(text_outside_code(md), 1):
            for target in LINK.findall(line) + HTML.findall(line):
                if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I):
                    continue
                file_part, _, anchor = target.partition("#")
                dest = md if not file_part else (md.parent / file_part).resolve()
                where = f"{md.relative_to(root)}: {target}"
                if not dest.exists():
                    print(f"link-check: {where}: no such file", file=sys.stderr)
                    errors += 1
                elif anchor and dest.suffix == ".md" and anchor.lower() not in slugs(dest):
                    print(f"link-check: {where}: no heading #{anchor}", file=sys.stderr)
                    errors += 1
    if errors:
        sys.exit(1)
    print("link-check: ok")


if __name__ == "__main__":
    main()
