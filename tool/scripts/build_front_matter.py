#!/usr/bin/env python3
"""Build the Book of Mormon's front matter (Title Page, Testimonies of the Three and Eight Witnesses).

Usage:
    python3 -I build_front_matter.py <pg17.txt> <out-file>

    <pg17.txt>  Project Gutenberg eBook #17, The Book of Mormon, plain text (see SOURCE.md)
    <out-file>  tool/src/main/assets/scriptures/bofm/front.json

Only these 1830 texts are taken. The Gutenberg header, contents and licence are dropped. The modern
Introduction and Brief Explanation are copyrighted and are not in the source anyway. The Testimony of
the Prophet Joseph Smith is an excerpt of Joseph Smith—History, which the app already has, so its page
is a link into it (JS—H 1:29, where the excerpt begins).

Output: {"pages": [{"id", "title", "blocks": [{"t": "heading"|"p"|"sig", "text"}]} | {"id", "title", "ref"}]}
"""
import json
import re
import sys


def paragraphs(text):
    return [re.sub(r"\s+", " ", p).strip() for p in re.split(r"\n\s*\n", text) if p.strip()]


def section(lines, start, end):
    s = next(i for i, l in enumerate(lines) if l.strip() == start)
    e = next(i for i, l in enumerate(lines) if i > s and l.strip() == end)
    return "\n".join(lines[s + 1:e])


def witnesses(body):
    """Testimony paragraphs, then the signatures (one name per line, all caps)."""
    blocks = []
    for p in re.split(r"\n\s*\n", body.strip()):
        rows = [r.strip() for r in p.strip().splitlines() if r.strip()]
        if rows and all(r == r.upper() for r in rows):
            blocks += [{"t": "sig", "text": r.title()} for r in rows]
        else:
            blocks.append({"t": "p", "text": re.sub(r"\s+", " ", p).strip()})
    return blocks


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    src, out = sys.argv[1:]
    text = open(src, encoding="utf-8-sig").read()
    body = text.split("*** START OF THE PROJECT GUTENBERG EBOOK THE BOOK OF MORMON ***", 1)[1]
    lines = body.splitlines()

    title_body = section(lines, "THE BOOK OF MORMON", "TRANSLATED BY JOSEPH SMITH, JUN.")
    title_paras = paragraphs(title_body)
    # The first two lines are the subtitle ("An Account Written BY THE HAND OF MORMON" / "UPON PLATES ...").
    subtitle = " ".join(title_paras[:2])
    subtitle = subtitle.replace("BY THE HAND OF MORMON", "by the Hand of Mormon").replace(
        "UPON PLATES TAKEN FROM THE PLATES OF NEPHI", "upon Plates Taken from the Plates of Nephi")
    title_blocks = [{"t": "heading", "text": "The Book of Mormon"}, {"t": "heading", "text": subtitle}]
    title_blocks += [{"t": "p", "text": p} for p in title_paras[2:]]
    title_blocks.append({"t": "heading", "text": "Translated by Joseph Smith, Jun."})

    three = section(lines, "THE TESTIMONY OF THREE WITNESSES", "THE TESTIMONY OF EIGHT WITNESSES")
    eight = section(lines, "THE TESTIMONY OF EIGHT WITNESSES", "Contents")

    pages = [
        {"id": "title-page", "title": "Title Page", "blocks": title_blocks},
        {"id": "three-witnesses", "title": "Testimony of Three Witnesses", "blocks": witnesses(three)},
        {"id": "eight-witnesses", "title": "Testimony of Eight Witnesses", "blocks": witnesses(eight)},
        {"id": "joseph-smith", "title": "Testimony of the Prophet Joseph Smith", "ref": "pgp/js-h/1/29"},
    ]
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"pages": pages}, f, ensure_ascii=False, indent=1)
    for p in pages:
        print(p["title"], [b["t"] for b in p.get("blocks", [])])


if __name__ == "__main__":
    main()
