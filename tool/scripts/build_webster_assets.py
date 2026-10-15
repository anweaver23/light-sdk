#!/usr/bin/env python3
"""Build the bundled Webster's 1828 dictionary shards from akaitsurugi/webster1828.

Usage:
    python3 -I build_webster_assets.py <webster1828.csv> <words-in-progress.csv> <output-dir>

Example:
    base=https://raw.githubusercontent.com/akaitsurugi/webster1828/b52403774b5b24d01377bbab29f642be226e9bb4
    curl -LO $base/webster1828.csv -LO $base/words-in-progress.csv
    python3 -I tool/scripts/build_webster_assets.py webster1828.csv words-in-progress.csv         tool/src/main/assets/dictionary

Input: a two-column CSV (index word, HTML definition). Each row holds one or more printed entries as
<p> paragraphs: a head paragraph "<b>HEADWORD</b>, <i>noun</i> [etymology] text", then numbered senses
"<b>1.</b> text", with quotations and scripture references in their own unnumbered paragraphs.
words-in-progress.csv is upstream's list of ~590 entries not yet merged into the main file (it includes
common words such as "might", "meat", "meek" and "fountain"). Its rows are rougher (raw "<DD>1." numbering,
some rows broken across columns); we use them only for words the main file lacks.

Output (UTF-8, LF line endings), read by core/WebsterLookup.kt:
    <out>/index.txt     Two-letter key prefixes that are split further by a third letter, one per line.
    <out>/shard-<shard>.txt
                        One printed entry per line, sorted by key. (The "shard-" prefix keeps names
                        such as con, aux, nul and prn, which Windows and git reject, off the disk.)
                            key TAB headword TAB part-of-speech TAB sense TAB sense ...
                        A key with several entries (noun and verb, say) has several consecutive lines.
                        A line with only two fields, "alias TAB key", redirects a closed compound to the
                        hyphenated form Webster printed ("firstborn" -> "first-born").

A key's shard is its first two characters, or three when the two-letter prefix is listed in index.txt.
Characters other than a-z become "_" and short keys are padded with "_" ("a" -> "a_"). This rule is
duplicated in WebsterLookup.shardFor; keep the two in step.

Cleaning: HTML tags and entities are removed; the stress marks (A'BLE) are dropped from headwords; the
expanded part-of-speech labels ("verb transitive") are turned back into Webster's printed abbreviations
("v.t."); leading etymology brackets are dropped (their Hebrew/Greek words were lost in transcription)
unless they are a cross-reference ("[See Nuisance.]"); quotations are folded into the sense they follow.
"""
import csv
import html
import os
import re
import sys
from collections import defaultdict

# Split a two-letter group into three-letter shards when its text is bigger than this (bytes).
SPLIT_THRESHOLD = 120_000

# Expanded labels in the source -> Webster's printed abbreviations.
POS_ABBREV = {
    "noun": "n.",
    "adjective": "a.",
    "verb transitive": "v.t.",
    "verb intransitive": "v.i.",
    "verb": "v.",
    "v": "v.",
    "participle present tense": "ppr.",
    "ppr": "ppr.",
    "participle passive": "pp.",
    "pp": "pp.",
    "preterit tense": "pret.",
    "adverb": "adv.",
    "adv": "adv.",
    "plural": "pl.",
    "preposition": "prep.",
    "pronoun": "pron.",
    "exclamation": "exclam.",
    "conjunction": "conj.",
    "comparative": "comp.",
    "superlative": "superl.",
}
# Labels that introduce inflected forms ("pret. gave; pp. given") rather than name the part of speech.
FORM_LABELS = {"pret.", "pp.", "ppr.", "comp.", "superl."}
# Of those, the ones followed by the inflected word itself ("pret. gave").
VERB_FORM_LABELS = {"pret.", "pp.", "ppr."}

SENSE_RE = re.compile(r"^\s*<b>\s*(\d+)\.\s*</b>\s*(.*)$", re.S)
# A bold run at the start of a paragraph is a new entry only when punctuation or a label follows it
# ("<b>NUSANCE</b>. [See", "<b>GIVE</b>, <i>verb"), not a word ("<b>GIVE</b> us of your oil").
HEAD_RE = re.compile(
    r"^\s*<b>([^<]*[A-Za-z][^<]*)</b>\s*"
    r"(?=$|[,.;:(\[]|<i>|(?:n|a|v\.t|v\.i|v|adv|pp|ppr|prep|pron|conj|exclam|pl)\.(?:\s|$|<))(.*)$",
    re.S,
)
ITALIC_RUN_RE = re.compile(r"^\s*,?\s*((?:<i>[^<]*</i>\s*)+)", re.S)
ITALIC_RE = re.compile(r"<i>([^<]*)</i>")
TAG_RE = re.compile(r"<[^>]+>")


def clean(text):
    """HTML fragment -> one line of plain text."""
    text = TAG_RE.sub("", text)
    text = html.unescape(text)
    text = text.replace("\t", " ").replace("\r", " ").replace("\n", " ")
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(r"\s+([,.;:!?])", r"\1", text)  # "ongean ," -> "ongean,"
    text = re.sub(r"([,;])(?=[A-Za-z])", r"\1 ", text)
    return text


def display_headword(raw):
    """"ADMONI'TION" -> "ADMONITION"; keeps the possessive in "CHARLES'S-WAIN". First form only."""
    hw = clean(raw).strip(" ,.;:")
    hw = re.split(r",\s*|\s+or\s+|\s+OR\s+|\s+and\s+", hw)[0].strip(" ,.;:")
    hw = re.sub(r"'(?!S\b)", "", hw).replace("’", "'")
    return hw.upper()


def key_of(word):
    return word.lower().replace("’", "'").strip()


def take_pos(rest):
    """Splits "<i>verb transitive</i> <i>preterit tense</i> gave; <i>pp</i> given. [Heb..." into
    ("v.t.; pret. gave; pp. given", "[Heb...")."""
    m = ITALIC_RUN_RE.match(rest)
    if not m:
        return None, rest
    labels = [POS_ABBREV.get(clean(x).lower()) for x in ITALIC_RE.findall(m.group(1))]
    known = []
    for lab in labels:
        if lab is None:
            break
        known.append(lab)
    if not known:
        return None, rest
    # Consume exactly the italic runs we recognised.
    pos_end = 0
    label_re = re.compile(r"\s*,?\s*<i>[^<]*</i>\s*")
    for _ in known:
        pos_end = label_re.match(rest, pos_end).end()
    after = rest[pos_end:]
    # The part of speech is the labels before the first form label; a lone "pp." is itself the label.
    n_main = next((i for i, lab in enumerate(known) if lab in FORM_LABELS), len(known)) or 1
    pos = " ".join(known[:n_main])
    tail = known[n_main:]
    if tail and not any(lab in VERB_FORM_LABELS for lab in tail):
        pos = " ".join(known)  # "a. superl." with the definition straight after
    elif tail:
        # Inflected forms follow, up to the first full stop or bracket:
        # "gave; <i>participle passive</i> given. [Heb. ..."
        fm = re.match(r"([^.\[]*?)(\.|(?=\[)|$)", after, re.S)
        forms = clean(ITALIC_RE.sub(lambda x: POS_ABBREV.get(clean(x.group(1)).lower(), x.group(1)), fm.group(1)))
        pos = f"{pos}; " + " ".join(tail + ([forms] if forms else []))
        after = after[fm.end():]
    return pos, after


def strip_etymology(text):
    """Drops a leading [ ... ] etymology; its Hebrew and Greek words were lost in transcription.
    Keeps the bracket when it's a cross-reference ("[See Obeisance.]") or the entry's only text."""
    t = text.lstrip(" ,.;:")
    if not t.startswith("["):
        return t
    depth = 0
    for i, ch in enumerate(t):
        depth += {"[": 1, "]": -1}.get(ch, 0)
        if depth == 0:
            inner, rest = t[1:i].strip(), t[i + 1:].strip(" ,.;:")
            if inner.lower().startswith("see ") or not rest:
                return t
            return rest
    return t


def split_numbered(text):
    """"1. In law, ... 2. In popular language, ..." -> the senses, when the source left the numbers
    unbolded inside running text. Returns [] when [text] doesn't start with a sense number."""
    m = re.match(r"(\d{1,2})\.\s+", text)
    if not m:
        return []
    n, at, senses = int(m.group(1)), m.end(), []
    while True:
        nxt = re.compile(rf"(?<=[.;:!?\]\)])\s*{n + 1}\.\s+(?=[A-Z\[])").search(text, at)
        if not nxt:
            senses.append(text[at:].strip())
            return senses
        senses.append(text[at:nxt.start()].strip())
        at, n = nxt.end(), n + 1


# Alternative spellings printed between the headword and its label: ", or ABSORPT'," / ", and A'BLEST,".
ALT_FORMS_RE = re.compile(r"^(?:\s*,?\s*(?:or|OR|and)\s+(?:<b>)?[A-Z][A-Z' \-]*(?:</b>)?\s*,?)+")
# A label printed as plain text rather than italics: "a. [See Calx.] Having lime ...".
RAW_POS_RE = re.compile(r"^(n|a|v\.t|v\.i|v|adv|pp|ppr|prep|pron|conj|exclam|pl)\.(?:\s+|$)")
# A pronunciation respelling before the definition: "giv. To yield", "resl."
RESPELLING_RE = re.compile(r"^[a-z][a-z']*\.(?:\s+(?=[A-Z\[])|$)")


def is_upper_head(raw):
    text = clean(raw)
    return bool(re.search("[A-Z]", text)) and text.upper() == text


def parse_head(para, fallback_headword):
    """Head paragraph -> (headword, pos, leading text)."""
    hm = HEAD_RE.match(para)
    if hm:
        raw_head, rest = hm.group(1), hm.group(2)
    else:
        # Unbolded head: "A'BLER, and A'BLEST, <i>comparative</i> ..."
        m = re.match(r"^\s*((?:<b>|</b>|[^<,])*)(.*)$", para, re.S)
        raw_head, rest = m.group(1), m.group(2)
        if not is_upper_head(raw_head):
            # Prose with no printed headword: "<b>A</b> is the first letter of the Alphabet ..."
            raw_head, rest = fallback_headword, para
    rest = ALT_FORMS_RE.sub("", rest)
    pos, rest = take_pos(rest)
    text = clean(rest).lstrip(" ,;:")
    if pos is None:
        rm = RAW_POS_RE.match(text)
        if rm:
            pos, text = rm.group(0).strip(), text[rm.end():]
    text = RESPELLING_RE.sub("", text).strip()
    return display_headword(raw_head), pos, text


def parse_row(html_text, fallback_headword):
    """One CSV row -> list of entries {headword, pos, senses}."""
    entries = []
    cur = None
    # Paragraphs, plus any text the source left outside <p> tags.
    blocks = [b for b in re.split(r"</?p>", html_text) if b.strip()]
    for i, para in enumerate(blocks):
        sm = SENSE_RE.match(para)
        hm = None if sm else HEAD_RE.match(para)
        if i == 0 or (hm and is_upper_head(hm.group(1))):
            headword, pos, text = parse_head(para, fallback_headword)
            cur = {"headword": headword, "pos": pos, "senses": [], "lead": text}
            entries.append(cur)
        elif sm:
            cur["senses"].append(clean(sm.group(2)))
        else:
            # A quotation, scripture reference or broken-off continuation: fold into what precedes it.
            text = clean(para)
            if not text:
                continue
            numbered = split_numbered(text)
            if numbered:
                cur["senses"].extend(numbered)
            elif cur["senses"]:
                cur["senses"][-1] = join(cur["senses"][-1], text)
            else:
                cur["lead"] = join(cur["lead"], text)
    out = []
    for e in entries:
        senses = [s for s in e["senses"] if s]
        lead = RESPELLING_RE.sub("", strip_etymology(e["lead"])).strip()
        if senses and lead.startswith("[") and not lead.lower().startswith("[see "):
            lead = ""  # etymology only; the numbered senses carry the meaning
        if lead and not senses:
            senses = [lead]
        elif lead and senses and len(lead.split()) > 3:
            # Text between the label and sense 1 that reads as a definition (a general sense or a
            # cross-reference). Shorter leftovers are respellings and stray marks, and are dropped.
            senses = [lead] + senses
        if not senses:
            continue
        out.append({"headword": e["headword"], "pos": e["pos"], "senses": senses})
    return out


def join(a, b):
    if not a:
        return b
    return a + " " + b


def shard_for(key, split):
    def norm(k, n):
        k = "".join(c if "a" <= c <= "z" else "_" for c in k[:n])
        return k.ljust(n, "_")

    p2 = norm(key, 2)
    return norm(key, 3) if p2 in split else p2


def wip_html(row):
    """A words-in-progress row -> HTML in the main file's shape. Prefers the curated "<div>" column.

    Unquoted rows were split at every comma in the text. Then the pieces sit in the "string" column
    and from the "content" column (index 4) on, with the unrelated _word/heading columns between.
    """
    cells = list(row)
    while cells and not cells[-1].strip():
        cells.pop()
    div = next((i for i, c in enumerate(cells) if c.startswith("<div>")), None)
    if div is not None:
        body = ",".join(cells[div:])
    else:
        body = ",".join(cells[1:2] + cells[4:])
    body = body.replace("<div>", "").replace("</div>", "")
    body = re.sub(r"<p>\s*(?:<DD>\s*)+(\d+)\.", r"<p><b>\1.</b>", body)
    return body.replace("<DD>", "")


def read_rows(main_csv, wip_csv):
    csv.field_size_limit(10**8)
    with open(main_csv, encoding="utf-8", newline="") as f:
        rows = [(r[0], r[1]) for r in csv.reader(f)]
    have = {key_of(w) for w, _ in rows}
    with open(wip_csv, encoding="utf-8", newline="") as f:
        wip = list(csv.reader(f))[1:]  # header: word,string,_word,heading,content,...
    added = 0
    for r in wip:
        if len(r) < 2 or not r[0].strip() or key_of(r[0]) in have:
            continue
        rows.append((r[0].strip(), wip_html(r)))
        have.add(key_of(r[0]))
        added += 1
    return rows, added


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    rows, wip_added = read_rows(sys.argv[1], sys.argv[2])
    out = sys.argv[3]

    row_keys = set()
    for word, _ in rows:
        row_keys.add(key_of(word))

    by_key = defaultdict(list)
    leaked = 0
    for word, body in rows:
        key = key_of(word)
        if key in ("false", "true") and word.isupper():
            key = word.lower()  # spreadsheet booleans in the source
        entries = parse_row(body, word.upper())
        if not entries:
            continue
        first = key_of(entries[0]["headword"])
        # The source truncates index words at 18 characters ("calcario-sulphurou").
        if len(key) == 18 and first.startswith(key):
            key = first
        for e in entries:
            hk = key_of(e["headword"])
            same = hk in (key, first) or hk.replace("-", "") == key.replace("-", "")
            if same or e is entries[0]:
                by_key[key].append(e)
            elif hk not in row_keys:
                by_key[hk].append(e)  # an entry with no row of its own
            else:
                leaked += 1  # a neighbouring entry repeated in this row; its own row has it

    # Drop exact duplicate entries under one key.
    for k, es in by_key.items():
        seen, uniq = set(), []
        for e in es:
            sig = (e["headword"], e["pos"], tuple(e["senses"]))
            if sig not in seen:
                seen.add(sig)
                uniq.append(e)
        by_key[k] = uniq

    def line(k, e):
        fields = [k, e["headword"], e["pos"] or ""] + e["senses"]
        assert all("\t" not in x and "\n" not in x for x in fields), fields
        return "\t".join(fields)

    lines = {k: [line(k, e) for e in by_key[k]] for k in by_key}
    # Scripture prints many compounds closed ("firstborn") that Webster hyphenated ("first-born").
    aliases = 0
    for k in sorted(by_key):
        closed = k.replace("-", "")
        if closed != k and " " not in k and closed not in lines:
            lines[closed] = [f"{closed}\t{k}"]
            aliases += 1

    group_size = defaultdict(int)
    for k, ls in lines.items():
        group_size[shard_for(k, set())] += sum(len(x.encode("utf-8")) + 1 for x in ls)
    split = sorted(g for g, n in group_size.items() if n > SPLIT_THRESHOLD)

    shards = defaultdict(list)
    for k in sorted(lines):
        shards[shard_for(k, set(split))].extend(lines[k])

    os.makedirs(out, exist_ok=True)
    for name in os.listdir(out):
        if name.startswith("shard-") and name.endswith(".txt"):
            os.remove(os.path.join(out, name))
    for name, ls in shards.items():
        with open(os.path.join(out, f"shard-{name}.txt"), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(ls) + "\n")
    with open(os.path.join(out, "index.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(split) + "\n")

    n_entries = sum(len(v) for v in by_key.values())
    print(f"{len(rows)} rows ({wip_added} from words-in-progress) -> {len(by_key)} keys, {n_entries} entries, "
          f"{aliases} compound aliases, {len(shards)} shards ({len(split)} split prefixes), "
          f"{leaked} repeated neighbour entries skipped")


if __name__ == "__main__":
    main()
