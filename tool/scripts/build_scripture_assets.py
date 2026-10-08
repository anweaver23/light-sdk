#!/usr/bin/env python3
"""Build bundled scripture assets from bcbooks/scriptures-json.

Usage:
    python3 build_scripture_assets.py <scriptures-json-dir> <output-dir>

Example:
    git clone --depth 1 https://github.com/bcbooks/scriptures-json /tmp/src
    python3 -I tool/scripts/build_scripture_assets.py /tmp/src tool/src/main/assets/scriptures

Output:
    <out>/index.json                 volume/book metadata
    <out>/<volumeId>/<bookId>.json   {"c":[[verse, ...], ...]} one inner list per chapter
"""
import json
import os
import sys

# (volume id, title, source file)
VOLUMES = [
    ("ot", "Old Testament", "old-testament.json"),
    ("nt", "New Testament", "new-testament.json"),
    ("bofm", "Book of Mormon", "book-of-mormon.json"),
    ("dc-testament", "Doctrine and Covenants", "doctrine-and-covenants.json"),
    ("pgp", "Pearl of Great Price", "pearl-of-great-price.json"),
]

# book id -> (display title, short abbreviation). Order here defines index order.
BOOKS = {
    "ot": [
        ("gen", "Genesis", "Gen."), ("ex", "Exodus", "Ex."), ("lev", "Leviticus", "Lev."),
        ("num", "Numbers", "Num."), ("deut", "Deuteronomy", "Deut."), ("josh", "Joshua", "Josh."),
        ("judg", "Judges", "Judg."), ("ruth", "Ruth", "Ruth"), ("1-sam", "1 Samuel", "1 Sam."),
        ("2-sam", "2 Samuel", "2 Sam."), ("1-kgs", "1 Kings", "1 Kgs."), ("2-kgs", "2 Kings", "2 Kgs."),
        ("1-chr", "1 Chronicles", "1 Chr."), ("2-chr", "2 Chronicles", "2 Chr."), ("ezra", "Ezra", "Ezra"),
        ("neh", "Nehemiah", "Neh."), ("esth", "Esther", "Esth."), ("job", "Job", "Job"),
        ("ps", "Psalms", "Ps."), ("prov", "Proverbs", "Prov."), ("eccl", "Ecclesiastes", "Eccl."),
        ("song", "Song of Solomon", "Song"), ("isa", "Isaiah", "Isa."), ("jer", "Jeremiah", "Jer."),
        ("lam", "Lamentations", "Lam."), ("ezek", "Ezekiel", "Ezek."), ("dan", "Daniel", "Dan."),
        ("hosea", "Hosea", "Hosea"), ("joel", "Joel", "Joel"), ("amos", "Amos", "Amos"),
        ("obad", "Obadiah", "Obad."), ("jonah", "Jonah", "Jonah"), ("micah", "Micah", "Micah"),
        ("nahum", "Nahum", "Nahum"), ("hab", "Habakkuk", "Hab."), ("zeph", "Zephaniah", "Zeph."),
        ("hag", "Haggai", "Hag."), ("zech", "Zechariah", "Zech."), ("mal", "Malachi", "Mal."),
    ],
    "nt": [
        ("matt", "Matthew", "Matt."), ("mark", "Mark", "Mark"), ("luke", "Luke", "Luke"),
        ("john", "John", "John"), ("acts", "Acts", "Acts"), ("rom", "Romans", "Rom."),
        ("1-cor", "1 Corinthians", "1 Cor."), ("2-cor", "2 Corinthians", "2 Cor."),
        ("gal", "Galatians", "Gal."), ("eph", "Ephesians", "Eph."), ("philip", "Philippians", "Philip."),
        ("col", "Colossians", "Col."), ("1-thes", "1 Thessalonians", "1 Thes."),
        ("2-thes", "2 Thessalonians", "2 Thes."), ("1-tim", "1 Timothy", "1 Tim."),
        ("2-tim", "2 Timothy", "2 Tim."), ("titus", "Titus", "Titus"), ("philem", "Philemon", "Philem."),
        ("heb", "Hebrews", "Heb."), ("james", "James", "James"), ("1-pet", "1 Peter", "1 Pet."),
        ("2-pet", "2 Peter", "2 Pet."), ("1-jn", "1 John", "1 Jn."), ("2-jn", "2 John", "2 Jn."),
        ("3-jn", "3 John", "3 Jn."), ("jude", "Jude", "Jude"), ("rev", "Revelation", "Rev."),
    ],
    "bofm": [
        ("1-ne", "1 Nephi", "1 Ne."), ("2-ne", "2 Nephi", "2 Ne."), ("jacob", "Jacob", "Jacob"),
        ("enos", "Enos", "Enos"), ("jarom", "Jarom", "Jarom"), ("omni", "Omni", "Omni"),
        ("w-of-m", "Words of Mormon", "W of M"), ("mosiah", "Mosiah", "Mosiah"), ("alma", "Alma", "Alma"),
        ("hel", "Helaman", "Hel."), ("3-ne", "3 Nephi", "3 Ne."), ("4-ne", "4 Nephi", "4 Ne."),
        ("morm", "Mormon", "Morm."), ("ether", "Ether", "Ether"), ("moro", "Moroni", "Moro."),
    ],
    "dc-testament": [
        ("dc", "Doctrine and Covenants", "D&C"),
    ],
    "pgp": [
        ("moses", "Moses", "Moses"), ("abr", "Abraham", "Abr."),
        ("js-m", "Joseph Smith—Matthew", "JS—M"),
        ("js-h", "Joseph Smith—History", "JS—H"),
        ("a-of-f", "Articles of Faith", "A of F"),
    ],
}

CHAPTER_LABEL = {"ps": "Psalm", "dc": "Section", "od": "Declaration"}


def chapter_verses(chapters, key):
    """Return [[text,...],...] validating chapter/verse numbering is contiguous from 1."""
    out = []
    for i, ch in enumerate(chapters, start=1):
        if ch[key] != i:
            raise ValueError(f"chapter numbering gap at {ch.get('reference')}")
        texts = []
        for j, v in enumerate(ch["verses"], start=1):
            if v["verse"] != j:
                raise ValueError(f"verse numbering gap at {v.get('reference')}")
            if not isinstance(v["text"], str) or not v["text"]:
                raise ValueError(f"empty verse at {v.get('reference')}")
            texts.append(v["text"])
        out.append(texts)
    return out


def load_books(src_dir, vol_id, filename):
    """Return {book_id: chapters-as-lists} for one volume."""
    with open(os.path.join(src_dir, filename), encoding="utf-8") as f:
        data = json.load(f)
    if vol_id == "dc-testament":
        books = {"dc": chapter_verses(data["sections"], "section")}
        # Official Declarations are not in bcbooks/scriptures-json (OD 2 is under
        # copyright); include them only if a future source revision adds them.
        ods = data.get("official_declarations") or data.get("declarations")
        if ods:
            books["od"] = [[v["text"] for v in od["verses"]] for od in ods]
        return books
    return {b["lds_slug"]: chapter_verses(b["chapters"], "chapter") for b in data["books"]}


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    src_dir, out_dir = sys.argv[1], sys.argv[2]
    index = {"volumes": []}
    for vol_id, vol_title, filename in VOLUMES:
        src_books = load_books(src_dir, vol_id, filename)
        meta = list(BOOKS[vol_id])
        if "od" in src_books:
            meta.append(("od", "Official Declarations", "OD"))
        missing = [b[0] for b in meta if b[0] not in src_books]
        extra = sorted(set(src_books) - {b[0] for b in meta})
        if missing or extra:
            raise ValueError(f"{vol_id}: missing={missing} unexpected={extra}")
        os.makedirs(os.path.join(out_dir, vol_id), exist_ok=True)
        vol = {"id": vol_id, "title": vol_title, "books": []}
        for book_id, title, abbrev in meta:
            chapters = src_books[book_id]
            path = os.path.join(out_dir, vol_id, book_id + ".json")
            with open(path, "w", encoding="utf-8") as f:
                json.dump({"c": chapters}, f, ensure_ascii=False, separators=(",", ":"))
            vol["books"].append({
                "id": book_id,
                "title": title,
                "abbrev": abbrev,
                "chapters": len(chapters),
                "chapterLabel": CHAPTER_LABEL.get(book_id, "Chapter"),
            })
        index["volumes"].append(vol)
    with open(os.path.join(out_dir, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    main()
