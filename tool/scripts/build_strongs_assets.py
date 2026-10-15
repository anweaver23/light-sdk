#!/usr/bin/env python3
"""Build the bundled Strong's assets: per-word Hebrew/Greek tags for our OT/NT text, plus
Strong's Hebrew and Greek dictionaries.

Usage:
    python3 -I build_strongs_assets.py <usfm-dir> <hebrew-xml> <greek-xml> <scriptures-dir> <out-dir>
    python3 -I build_strongs_assets.py --selfcheck

    <usfm-dir>        unzipped eng-kjv2006_usfm.zip from ebible.org (KJV with Strong's numbers)
    <hebrew-xml>      openscriptures/strongs  hebrew/StrongHebrewG.xml
    <greek-xml>       openscriptures/strongs  greek/StrongsGreekDictionaryXML_1.4/strongsgreek.xml
    <scriptures-dir>  tool/src/main/assets/scriptures (our verse text; tags are aligned to it)
    <out-dir>         tool/src/main/assets/strongs

See tool/src/main/assets/strongs/SOURCE.md for exact versions and licenses.

How alignment works
-------------------
The tags have to point at OUR words, numbered exactly like core/Words.spans numbers them, not at the
tagged KJV's words. So for every verse:

1. The tagged KJV verse (USFM) is flattened to plain text, remembering which character ranges sat
   inside a \\w ...|strong="..."\\w* element. Footnotes, headings and Psalm titles are dropped;
   translator-supplied words (\\add) stay in the text but carry no tag.
2. Both texts are tokenized with `spans` below, a line-for-line port of Words.spans. A source token
   takes the tags of every tagged character range it overlaps, so a multi-word rendering such as
   "four months" puts the same number on both words.
3. The two token lists are aligned on a normalized form (case, apostrophes, spelling variants)
   with difflib. Equal runs map 1:1. A differing run is only mapped when it is provably the same
   letters split differently ("to day" / "today", "Beth-el" / "Bethel"); anything else stays
   untagged. A singleton "equal" match stranded between differences is dropped unless the word is
   unique in both verses, so a tag is never shifted onto the wrong occurrence.

Output
------
    <out>/<ot|nt>/<book>.json  {"tok": <crc32>, "c": [[[w, n, w, n, ...], ...verses], ...chapters]}
        w = word index (Words.spans), n = Strong's number without prefix (H for ot, G for nt).
        A word with several numbers repeats its index. "tok" is a CRC-32 of every token of the book
        (verses joined by "\\n", tokens by " ") so a unit test can prove Kotlin tokenizes identically.
    <out>/dict/<H|G>/<n // 100>.json  {"7225": {"l","x","p","d","r","k"}, ...}
        l lemma, x transliteration, p pronunciation, d definition, r derivation, k KJV usage.
"""
import difflib
import glob
import json
import os
import re
import sys
import unicodedata
import xml.etree.ElementTree as ET
import zlib

# --------------------------------------------------------------------------------------------
# Tokenizer: a faithful port of core/Words.kt. Kotlin's Char is a UTF-16 code unit, so a
# character outside the BMP is two surrogates there, neither a letter nor a digit; mirror that.

_LETTER = {"Lu", "Ll", "Lt", "Lm", "Lo"}
_JOINERS = {"'", "\u2019", "-"}


def _is_letter(ch):
    return ord(ch) <= 0xFFFF and unicodedata.category(ch) in _LETTER


def _is_letter_or_digit(ch):
    return ord(ch) <= 0xFFFF and unicodedata.category(ch) in (_LETTER | {"Nd"})


def spans(verse):
    """[(start, end)] word ranges, exactly as Words.spans produces them."""
    out = []
    i, n = 0, len(verse)
    while i < n:
        if not _is_letter_or_digit(verse[i]):
            i += 1
            continue
        start = i
        while i < n:
            c = verse[i]
            if _is_letter_or_digit(c):
                i += 1
            elif c in _JOINERS and i + 1 < n and _is_letter(verse[i + 1]) and _is_letter(verse[i - 1]):
                i += 1
            else:
                break
        out.append((start, i))
    return out


def words(verse):
    return [verse[s:e] for s, e in spans(verse)]


def selfcheck():
    """The cases from WordsTest.kt, plus a few edge cases; keeps the port honest."""
    cases = [
        ("Behold, it came to pass that I, Enos, knowing my father\u2014",
         ["Behold", "it", "came", "to", "pass", "that", "I", "Enos", "knowing", "my", "father"]),
        ("man's Beth-el LORD\u2019s", ["man's", "Beth-el", "LORD\u2019s"]),
        ("brethren' -saints-", ["brethren", "saints"]),
        ("1-2 a-1 x--y 12th", ["1", "2", "a", "1", "x", "y", "12th"]),
        ("Jehovah\u2013nissi", ["Jehovah", "nissi"]),
    ]
    for text, want in cases:
        got = words(text)
        assert got == want, f"spans({text!r}) = {got}, want {want}"
    assert spans("In the beginning")[2] == (7, 16)
    print("selfcheck ok")


# --------------------------------------------------------------------------------------------
# Book ids, in the same order as build_scripture_assets.py.

USFM_BOOKS = {
    "ot": ["GEN", "EXO", "LEV", "NUM", "DEU", "JOS", "JDG", "RUT", "1SA", "2SA", "1KI", "2KI", "1CH",
           "2CH", "EZR", "NEH", "EST", "JOB", "PSA", "PRO", "ECC", "SNG", "ISA", "JER", "LAM", "EZK",
           "DAN", "HOS", "JOL", "AMO", "OBA", "JON", "MIC", "NAM", "HAB", "ZEP", "HAG", "ZEC", "MAL"],
    "nt": ["MAT", "MRK", "LUK", "JHN", "ACT", "ROM", "1CO", "2CO", "GAL", "EPH", "PHP", "COL", "1TH",
           "2TH", "1TI", "2TI", "TIT", "PHM", "HEB", "JAS", "1PE", "2PE", "1JN", "2JN", "3JN", "JUD", "REV"],
}
OUR_BOOKS = {
    "ot": ["gen", "ex", "lev", "num", "deut", "josh", "judg", "ruth", "1-sam", "2-sam", "1-kgs", "2-kgs",
           "1-chr", "2-chr", "ezra", "neh", "esth", "job", "ps", "prov", "eccl", "song", "isa", "jer", "lam",
           "ezek", "dan", "hosea", "joel", "amos", "obad", "jonah", "micah", "nahum", "hab", "zeph", "hag",
           "zech", "mal"],
    "nt": ["matt", "mark", "luke", "john", "acts", "rom", "1-cor", "2-cor", "gal", "eph", "philip", "col",
           "1-thes", "2-thes", "1-tim", "2-tim", "titus", "philem", "heb", "james", "1-pet", "2-pet",
           "1-jn", "2-jn", "3-jn", "jude", "rev"],
}
PREFIX = {"ot": "H", "nt": "G"}

# --------------------------------------------------------------------------------------------
# USFM: flatten each verse to (plain text, [(start, end, strongs-number)]).

# Whole lines that are never verse text: book headers, section headings (Ps 119 acrostic),
# and Psalm titles (\d). The LDS edition prints Psalm titles as headings outside verse 1.
_SKIP_LINE = re.compile(r"^\\(id|h|toc\d|mt\d|s\d|d|ms\d?|r)\b")
_NOTE = re.compile(r"\\(f|x) .*?\\\1\*", re.S)
_WORD = re.compile(r'\\(\+?)w (.*?)\|strong="([^"]*)"\\\1w\*', re.S)
_MARKER = re.compile(r"\\\+?[a-z]+\d*\*|\\\+?[a-z]+\d* ?")


def _strip_markers(s):
    return _MARKER.sub("", s).replace("\u00b6", "")


def flatten(raw):
    """USFM verse body -> (text, tags) with tags as [(start, end, 'H7225')]."""
    raw = _NOTE.sub("", raw)
    text, tags, pos = [], [], 0
    length = 0
    for m in _WORD.finditer(raw):
        before = _strip_markers(raw[pos:m.start()])
        text.append(before)
        length += len(before)
        inner = _strip_markers(m.group(2))
        for num in m.group(3).split():
            tags.append((length, length + len(inner), num))
        text.append(inner)
        length += len(inner)
        pos = m.end()
    text.append(_strip_markers(raw[pos:]))
    return "".join(text), tags


def parse_usfm(path):
    """{(chapter, verse): (text, tags)}"""
    verses, chapter, verse, buf = {}, 0, 0, []

    def flush():
        if chapter and verse:
            verses[(chapter, verse)] = flatten(" ".join(buf))

    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            line = line.strip()
            if not line or _SKIP_LINE.match(line):
                continue
            m = re.match(r"^\\c (\d+)", line)
            if m:
                flush()
                chapter, verse, buf = int(m.group(1)), 0, []
                continue
            # A line can hold several verses; split on \v markers.
            parts = re.split(r"\\v (\d+) ?", line)
            if parts[0].strip():
                buf.append(parts[0])
            for k in range(1, len(parts), 2):
                flush()
                verse, buf = int(parts[k]), [parts[k + 1]]
    flush()
    return verses


def source_tokens(text, tags):
    """[(token, [numbers])] for one flattened source verse."""
    out = []
    for s, e in spans(text):
        nums = []
        for ts, te, num in tags:
            if ts < e and s < te and num not in nums:
                nums.append(num)
        out.append((text[s:e], nums))
    return out


# --------------------------------------------------------------------------------------------
# Alignment.

# Spelling differences between the tagged source (Oxford 1769 spellings) and the LDS edition, which
# modernizes a few words. Source form -> our form, both normalized. Every pair here came out of the
# diff report this script prints; it is an explicit list on purpose, since a fuzzy "looks similar"
# rule could pair two genuinely different words. "\u00e6" is folded to "ae" in norm(), which covers the
# Greek names (C\u00e6sar, Jud\u00e6a, Alph\u00e6us...).
VARIANTS = {
    "enquire": "inquire", "enquired": "inquired", "enquirest": "inquirest", "enquiry": "inquiry",
    "intreat": "entreat", "intreated": "entreated", "intreaties": "entreaties", "intreaty": "entreaty",
    "vail": "veil", "vails": "veils",
    "jubile": "jubilee",
    "bason": "basin", "basons": "basins",
    "morter": "mortar",
    "stedfast": "steadfast", "stedfastly": "steadfastly", "stedfastness": "steadfastness",
    "plaister": "plaster", "plaistered": "plastered",
    "cloke": "cloak",
    "rereward": "rearward",
    "graff": "graft", "graffed": "grafted",
    "cieled": "ceiled", "cieling": "ceiling",
    "pourtray": "portray", "pourtrayed": "portrayed",
    "platted": "plaited",
    "spunge": "sponge",
    "asswage": "assuage", "asswaged": "assuaged",
    "chesnut": "chestnut",
    "aul": "awl",
    "lunatick": "lunatic",
    "havock": "havoc",
    "pluckt": "plucked",
    "mixt": "mixed",
    "abidah": "abida",
    "marishes": "marshes",
    "instructers": "instructors",
}


def norm(tok):
    t = tok.lower().replace("\u2019", "'").replace("\u00e6", "ae").replace("\u0153", "oe")
    return VARIANTS.get(t, t)


def squash(tok):
    """Letters only, for 'same letters, different split' checks."""
    return re.sub(r"[-']", "", norm(tok))


def align_verse(src, ours):
    """src: [(token, nums)], ours: [token]. Returns ({our index: [nums]}, [diff descriptions])."""
    a = [norm(t) for t, _ in src]
    b = [norm(t) for t in ours]
    result, diffs = {}, []

    def give(oi, nums):
        lst = result.setdefault(oi, [])
        for n in nums:
            if n not in lst:
                lst.append(n)

    if a == b:
        for i, (_, nums) in enumerate(src):
            if nums:
                give(i, nums)
        return result, diffs

    ops = difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes()
    for k, (op, i1, i2, j1, j2) in enumerate(ops):
        if op == "equal":
            # A lone equal word wedged between two differences may be a coincidental match on a
            # different occurrence ("of", "God"...). Trust it only if the word is unique in both.
            lone = (i2 - i1 == 1 and 0 < k < len(ops) - 1
                    and ops[k - 1][0] != "equal" and ops[k + 1][0] != "equal")
            if lone and (a.count(a[i1]) > 1 or b.count(b[j1]) > 1):
                diffs.append(("lone", a[i1], b[j1]))
                continue
            for d in range(i2 - i1):
                if src[i1 + d][1]:
                    give(j1 + d, src[i1 + d][1])
            continue
        if op == "replace":
            groups = split_groups(src[i1:i2], ours[j1:j2])
            if groups is not None:
                for sgrp, ogrp in groups:
                    nums = [n for s in sgrp for n in src[i1 + s][1]]
                    for o in ogrp:
                        if nums:
                            give(j1 + o, nums)
                continue
        diffs.append((op, " ".join(a[i1:i2]), " ".join(b[j1:j2])))
    return result, diffs


def split_groups(src_run, our_run):
    """Pair up two runs whose letters match exactly but are split differently, e.g.
    ["to", "day"] / ["today"]. Returns [([src idx], [our idx])] or None if the letters differ."""
    sa = [squash(t) for t, _ in src_run]
    sb = [squash(t) for t in our_run]
    if "".join(sa) != "".join(sb):
        return None
    groups, i, j = [], 0, 0
    while i < len(sa) and j < len(sb):
        gi, gj = [i], [j]
        la, lb = len(sa[i]), len(sb[j])
        i += 1
        j += 1
        while la != lb:
            if la < lb:
                gi.append(i)
                la += len(sa[i])
                i += 1
            else:
                gj.append(j)
                lb += len(sb[j])
                j += 1
        groups.append((gi, gj))
    return groups


# --------------------------------------------------------------------------------------------
# Dictionaries.

_OSIS = "{http://www.bibletechnologies.net/2003/OSIS/namespace}"


def _clean(s):
    """Collapse whitespace; drop the trailing ';' or ',' Strong's uses to chain fields."""
    s = re.sub(r"\s+", " ", s or "").strip()
    s = re.sub(r"\s*[;,]$", "", s)
    return s or None


def _hebrew_text(el):
    """Text of an OSIS note, with <w src="N"> references rendered as HN."""
    parts = [el.text or ""]
    for child in el:
        if child.tag == _OSIS + "w" and child.get("src"):
            parts.append("H" + str(int(child.get("src"))))
        else:
            parts.append("".join(child.itertext()))
        parts.append(child.tail or "")
    return _clean("".join(parts))


def load_hebrew(path):
    entries = {}
    root = ET.parse(path).getroot()
    for div in root.iter(_OSIS + "div"):
        if div.get("type") != "entry":
            continue
        w = div.find(_OSIS + "w")
        if w is None or not (w.get("ID") or "").startswith("H"):
            continue
        num = int(re.sub(r"\D", "", w.get("ID")))
        notes = {n.get("type"): n for n in div.findall(_OSIS + "note")}
        e = {
            "l": w.get("lemma") or "",
            "x": w.get("xlit") or "",
            "p": _clean(w.get("POS")),
            "d": _hebrew_text(notes["explanation"]) if "explanation" in notes else None,
            "r": _hebrew_text(notes["exegesis"]) if "exegesis" in notes else None,
            "k": _hebrew_text(notes["translation"]) if "translation" in notes else None,
        }
        entries[num] = e
    return entries


def _greek_text(el, skip_kjv_lead=False):
    parts = [el.text or ""]
    for child in el:
        if child.tag == "strongsref":
            lang = "H" if child.get("language", "").upper().startswith("HEB") else "G"
            parts.append(lang + str(int(child.get("strongs"))))
        elif child.tag == "greek":
            parts.append(child.get("unicode") or "")
        elif child.tag == "pronunciation":
            parts.append(child.get("strongs") or "")
        else:
            parts.append(_greek_text(child))
        parts.append(child.tail or "")
    s = "".join(parts)
    if skip_kjv_lead:
        s = re.sub(r"^\s*:?--\s*", "", s)
    return _clean(s)


def load_greek(path):
    entries = {}
    root = ET.parse(path).getroot()
    for entry in root.iter("entry"):
        num = int(entry.get("strongs"))
        greek = entry.find("greek")
        pron = entry.find("pronunciation")
        der = entry.find("strongs_derivation")
        dfn = entry.find("strongs_def")
        kjv = entry.find("kjv_def")
        # Loose text after the KJV list ("Compare G2799.") is a cross-reference: keep it with
        # the derivation, where the other number links live.
        extra = []
        seen_kjv = False
        for child in entry:
            if child is kjv:
                seen_kjv = True
                extra.append(child.tail or "")
                continue
            if seen_kjv and child.tag == "strongsref":
                extra.append(("H" if child.get("language", "").upper().startswith("HEB") else "G")
                             + str(int(child.get("strongs"))))
                extra.append(child.tail or "")
        tail = _clean("".join(extra))
        derivation = _greek_text(der) if der is not None else None
        if tail:
            derivation = f"{derivation}. {tail}" if derivation else tail
        entries[num] = {
            "l": greek.get("unicode") if greek is not None else "",
            "x": greek.get("translit") if greek is not None else "",
            "p": _clean(pron.get("strongs")) if pron is not None else None,
            "d": _greek_text(dfn) if dfn is not None else None,
            "r": derivation,
            "k": _greek_text(kjv, skip_kjv_lead=True) if kjv is not None else None,
        }
    return entries


def write_dictionary(entries, prefix, out_dir):
    shards = {}
    for num, e in entries.items():
        # NFC so lemmas compare equal to typed text: the Greek source uses oxia (U+1F79) where
        # NFC has tonos (U+03CC), and Hebrew vowel/shin-dot order varies.
        shards.setdefault(num // 100, {})[str(num)] = {
            k: unicodedata.normalize("NFC", v) for k, v in e.items() if v}
    d = os.path.join(out_dir, "dict", prefix)
    os.makedirs(d, exist_ok=True)
    for shard, body in shards.items():
        with open(os.path.join(d, f"{shard}.json"), "w", encoding="utf-8") as f:
            json.dump(dict(sorted(body.items(), key=lambda kv: int(kv[0]))), f,
                      ensure_ascii=False, separators=(",", ":"))
    return len(shards)


# --------------------------------------------------------------------------------------------


def main():
    if sys.argv[1:] == ["--selfcheck"]:
        selfcheck()
        return
    if len(sys.argv) != 6:
        sys.exit(__doc__)
    usfm_dir, heb_xml, grk_xml, scr_dir, out_dir = sys.argv[1:]
    selfcheck()
    sys.stdout.reconfigure(encoding="utf-8")

    dicts = {"H": load_hebrew(heb_xml), "G": load_greek(grk_xml)}
    for p, entries in dicts.items():
        print(f"{p} dictionary: {len(entries)} entries, {write_dictionary(entries, p, out_dir)} shards")

    files = {}
    for f in glob.glob(os.path.join(usfm_dir, "*.usfm")):
        m = re.match(r"\d+-(\w{3})", os.path.basename(f))
        files[m.group(1)] = f

    total_verses = full_verses = 0
    src_tagged = src_placed = 0
    our_tagged_words = 0
    missing_nums = {}
    worst = []
    diff_counter = {}
    verse_mismatch = []
    raw_diff_verses = 0
    case_only = []
    for vol in ("ot", "nt"):
        os.makedirs(os.path.join(out_dir, vol), exist_ok=True)
        for code, book in zip(USFM_BOOKS[vol], OUR_BOOKS[vol]):
            src = parse_usfm(files[code])
            with open(os.path.join(scr_dir, vol, book + ".json"), encoding="utf-8") as f:
                chapters = json.load(f)["c"]
            out_chapters = []
            book_tokens = []
            for ci, verses in enumerate(chapters, start=1):
                out_verses = []
                for vi, text in enumerate(verses, start=1):
                    ours = words(text)
                    book_tokens.append(" ".join(ours))
                    total_verses += 1
                    if (ci, vi) not in src:
                        verse_mismatch.append(f"{book} {ci}:{vi} missing in source")
                        out_verses.append([])
                        continue
                    stoks = source_tokens(*src[(ci, vi)])
                    raw_src = [t for t, _ in stoks]
                    if raw_src != ours:
                        raw_diff_verses += 1
                        if [t.lower() for t in raw_src] == [t.lower() for t in ours]:
                            case_only.append(f"{book} {ci}:{vi}: "
                                             + ", ".join(f"{s}->{o}" for s, o in zip(raw_src, ours) if s != o))
                    mapping, diffs = align_verse(stoks, ours)
                    n_src = sum(1 for _, nums in stoks if nums)
                    src_tagged += n_src
                    if not diffs:
                        full_verses += 1
                        src_placed += n_src
                    else:
                        # Count placed source tokens as the tags that reached at least one word.
                        reached = {n for nums in mapping.values() for n in nums}
                        src_placed += sum(1 for _, nums in stoks if nums and any(n in reached for n in nums))
                        worst.append((len(diffs), f"{book} {ci}:{vi}", diffs))
                        for d in diffs:
                            diff_counter[d] = diff_counter.get(d, 0) + 1
                    flat = []
                    for oi in sorted(mapping):
                        for num in mapping[oi]:
                            p, n = num[0], int(num[1:])
                            if p != PREFIX[vol]:
                                raise ValueError(f"{book} {ci}:{vi}: {num} in {vol}")
                            if n not in dicts[p]:
                                missing_nums[num] = missing_nums.get(num, 0) + 1
                                continue
                            flat += [oi, n]
                    if flat:
                        our_tagged_words += len(set(flat[0::2]))
                    out_verses.append(flat)
                out_chapters.append(out_verses)
            extra = sorted(k for k in src if k[0] > len(chapters) or k[1] > len(chapters[k[0] - 1]))
            for k in extra:
                verse_mismatch.append(f"{book} {k[0]}:{k[1]} only in source")
            crc = zlib.crc32("\n".join(book_tokens).encode("utf-8"))
            with open(os.path.join(out_dir, vol, book + ".json"), "w", encoding="utf-8") as f:
                json.dump({"tok": crc, "c": out_chapters}, f, separators=(",", ":"))

    print(f"\nverses: {total_verses}, fully aligned (no unresolved difference): "
          f"{full_verses} ({100 * full_verses / total_verses:.2f}%)")
    print(f"tagged source tokens placed: {src_placed}/{src_tagged} ({100 * src_placed / src_tagged:.2f}%)")
    print(f"our words carrying a tag: {our_tagged_words}")
    print(f"verses whose raw words differ from the source at all: {raw_diff_verses}; "
          f"case-only differences: {len(case_only)}")
    for line in case_only[:30]:
        print(f"  {line}")
    print(f"verse numbering mismatches: {len(verse_mismatch)} {verse_mismatch[:10]}")
    print(f"tags dropped (number not in dictionary): {sum(missing_nums.values())} {sorted(missing_nums)[:20]}")
    print("\nmost common differences (source -> ours):")
    for d, c in sorted(diff_counter.items(), key=lambda kv: -kv[1])[:60]:
        print(f"  {c:5}  {d[0]:7} {d[1]!r} -> {d[2]!r}")
    print("\nworst verses:")
    for n, ref, diffs in sorted(worst, key=lambda w: -w[0])[:15]:
        print(f"  {ref}: {diffs}")


if __name__ == "__main__":
    main()
