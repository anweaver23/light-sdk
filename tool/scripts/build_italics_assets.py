#!/usr/bin/env python3
"""Build the bundled KJV italics: which of OUR words the KJV translators supplied (printed in italics).

Usage:
    python3 -I build_italics_assets.py <usfm-dir> <scriptures-dir> <out-dir>

    <usfm-dir>        unzipped eng-kjv2006_usfm.zip from ebible.org (see assets/italics/SOURCE.md)
    <scriptures-dir>  tool/src/main/assets/scriptures (our verse text; italics are aligned to it)
    <out-dir>         tool/src/main/assets/italics

The source marks supplied words with \\add ... \\add* (also nested as \\+add inside a \\w, or wrapping
\\+w elements). Each source verse is flattened to plain text with a per-character italic flag; a token
is italic when any of its letters is. The tokens are then aligned onto our words with the same
alignment the Strong's tags use (build_strongs_assets.align_verse), so a word only gets italics when
the alignment is sure of it.

Output:
    <out>/<ot|nt>/<book>.json  {"tok": <crc32>, "c": [[[first, last, first, last, ...], ...verses], ...]}
        Inclusive word-index runs (core/Words.spans numbering). "tok" is the same token CRC the Strong's
        files carry, so a unit test proves Kotlin tokenizes identically.
"""
import glob
import importlib.util
import json
import os
import re
import sys
import zlib

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("strongs", os.path.join(_HERE, "build_strongs_assets.py"))
strongs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(strongs)

_NOTE = re.compile(r"\\(f|x) .*?\\\1\*", re.S)
_ATTR = re.compile(r'\|[a-z-]+="[^"]*"')
# An opening marker swallows the one space after it; a closing marker ("\add*") never does.
_MARK = re.compile(r"\\(\+?)([a-z]+\d*)(\*| ?)")


def flatten_italics(raw):
    """USFM verse body -> (text, [bool per char: inside \\add])."""
    raw = _ATTR.sub("", _NOTE.sub("", raw))
    text, flags, depth, pos = [], [], 0, 0
    for m in _MARK.finditer(raw):
        chunk = raw[pos:m.start()].replace("\u00b6", "")
        text.append(chunk)
        flags += [depth > 0] * len(chunk)
        if m.group(2) == "add":
            depth += -1 if m.group(3) == "*" else 1
            depth = max(depth, 0)
        pos = m.end()
    chunk = raw[pos:].replace("\u00b6", "")
    text.append(chunk)
    flags += [depth > 0] * len(chunk)
    return "".join(text), flags


def parse(path):
    """{(chapter, verse): raw body}, splitting lines on \\c / \\v like build_strongs_assets."""
    verses, chapter, verse, buf = {}, 0, 0, []

    def flush():
        if chapter and verse:
            verses[(chapter, verse)] = " ".join(buf)

    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            line = line.strip()
            if not line or strongs._SKIP_LINE.match(line):
                continue
            m = re.match(r"^\\c (\d+)", line)
            if m:
                flush()
                chapter, verse, buf = int(m.group(1)), 0, []
                continue
            parts = re.split(r"\\v (\d+) ?", line)
            if parts[0].strip():
                buf.append(parts[0])
            for k in range(1, len(parts), 2):
                flush()
                verse, buf = int(parts[k]), [parts[k + 1]]
    flush()
    return verses


def runs(indexes):
    out = []
    for i in sorted(indexes):
        if out and out[-1] == i - 1:
            out[-1] = i
        else:
            out += [i, i]
    return out


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    usfm_dir, scr_dir, out_dir = sys.argv[1:]
    strongs.selfcheck()
    sys.stdout.reconfigure(encoding="utf-8")
    files = {}
    for f in glob.glob(os.path.join(usfm_dir, "*.usfm")):
        m = re.match(r"\d+-(\w{3})", os.path.basename(f))
        files[m.group(1)] = f

    src_italic = placed = verses_with = 0
    lost = []
    for vol in ("ot", "nt"):
        os.makedirs(os.path.join(out_dir, vol), exist_ok=True)
        for code, book in zip(strongs.USFM_BOOKS[vol], strongs.OUR_BOOKS[vol]):
            src = parse(files[code])
            with open(os.path.join(scr_dir, vol, book + ".json"), encoding="utf-8") as f:
                chapters = json.load(f)["c"]
            out_chapters, book_tokens = [], []
            for ci, verses in enumerate(chapters, start=1):
                out_verses = []
                for vi, text in enumerate(verses, start=1):
                    ours = strongs.words(text)
                    book_tokens.append(" ".join(ours))
                    raw = src.get((ci, vi))
                    if raw is None:
                        out_verses.append([])
                        continue
                    flat, flags = flatten_italics(raw)
                    stoks = [(flat[s:e], ["I"] if any(flags[s:e]) else []) for s, e in strongs.spans(flat)]
                    n = sum(1 for _, t in stoks if t)
                    mapping, _ = strongs.align_verse(stoks, ours)
                    got = sorted(mapping)
                    src_italic += n
                    placed += len(got)
                    if len(got) < n:
                        lost.append(f"{book} {ci}:{vi}: {n - len(got)}")
                    if got:
                        verses_with += 1
                    out_verses.append(runs(got))
                out_chapters.append(out_verses)
            crc = zlib.crc32("\n".join(book_tokens).encode("utf-8"))
            with open(os.path.join(out_dir, vol, book + ".json"), "w", encoding="utf-8") as f:
                json.dump({"tok": crc, "c": out_chapters}, f, separators=(",", ":"))
    print(f"italic source words: {src_italic}, placed on our words: {placed} "
          f"({100.0 * placed / max(src_italic, 1):.2f}%), verses with italics: {verses_with}")
    print(f"verses losing some italics: {len(lost)}")
    for line in lost[:40]:
        print("  " + line)


if __name__ == "__main__":
    main()
