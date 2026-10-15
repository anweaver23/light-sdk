# Dictionary source

Noah Webster, *An American Dictionary of the English Language* (1828).

## Where the text comes from

[akaitsurugi/webster1828](https://github.com/akaitsurugi/webster1828), commit
`b52403774b5b24d01377bbab29f642be226e9bb4` (2026-10-01):

- `webster1828.csv`: 62,391 index words with HTML definitions.
  sha256 `5ba9750ae13e3938441721d00677f8eacf45b63bd631639de247155892e54fb0`.
- `words-in-progress.csv`: upstream's ~590 entries not yet merged into the main file. They include common
  Bible words such as *might*, *meat*, *meek*, *fountain* and *repair*. We use 586 of them, only for words the
  main file lacks. Upstream hasn't proofread these yet, so expect a little more noise in them.
  sha256 `c5e074825ed52e527ed8c1281f2f1ae7b22341a6130b135eb61af82feeb7c3c9`.

## License

- **The 1828 text is public domain.** It was published in 1828, so any US copyright term has long expired.
- **The digital edition is CC BY-SA 4.0.** akaitsurugi distributes its transcription and markup under the
  [Creative Commons Attribution-ShareAlike 4.0](https://creativecommons.org/licenses/by-sa/4.0/) license.
  The files in this directory are an adaptation of that edition. They are therefore licensed **CC BY-SA 4.0**
  too, with attribution to "Webster's Dictionary 1828, digital edition by akaitsurugi
  (github.com/akaitsurugi/webster1828)". The share-alike term covers these data files only. It doesn't extend
  to the app's code.

## Changes from the source

- HTML is turned into plain text.
- Stress marks are removed from headwords (ADMONI'TION becomes ADMONITION).
- Part-of-speech labels the source spelled out ("verb transitive") are turned back into Webster's printed
  abbreviations (`v.t.`). Inflected forms are kept with the label, for example `v.t.; pret. gave; pp. given`.
- Leading etymology brackets are dropped, because their Hebrew and Greek words were lost in transcription.
  Cross-references such as "[See Obeisance.]" are kept.
- Quotations and scripture references are joined onto the sense they illustrate.
- Neighbouring entries that the source repeats inside another word's row are skipped. Each of those words
  still has its own row.
- Index words that the source truncated at 18 characters are restored from the headword.
- 5,533 aliases point closed compounds at Webster's hyphenated spelling, for example `firstborn` to
  `first-born`.

## Format

Read by `core/WebsterLookup.kt`. All files are UTF-8 with LF line endings.

- `index.txt` lists the two-letter prefixes that are big enough to split into three-letter shards.
- `shard-<prefix>.txt` holds one printed entry per line:
  `key TAB HEADWORD TAB part-of-speech TAB sense TAB sense ...`. A key with several entries has several lines.
  - An alias line has two fields: `alias TAB key`.
  - The `shard-` prefix is there because git on Windows rejects reserved names such as `con.txt`.
- A key's prefix is its first two letters. When `index.txt` lists that prefix, it's the first three letters
  instead.
  - Characters outside a-z become `_`, and short keys are padded with `_`. For example, `a` lives in
    `shard-a_.txt`.

Size: 709 shards plus the index. That's 12.6 MB raw and 4.9 MB zipped. The largest shard, `shard-con.txt`, is
268 KB.

## Regenerating

```
base=https://raw.githubusercontent.com/akaitsurugi/webster1828/b52403774b5b24d01377bbab29f642be226e9bb4
mkdir /tmp/webster && cd /tmp/webster && curl -LO $base/webster1828.csv -LO $base/words-in-progress.csv
python3 -I tool/scripts/build_webster_assets.py /tmp/webster/webster1828.csv \
    /tmp/webster/words-in-progress.csv tool/src/main/assets/dictionary
```

Then run `./gradlew :tool:testDebugUnitTest`. `WebsterLookupTest` checks every shard line and alias against the
lookup code.

## Sources considered and not used

- `sktzofrenic/webster1828` (`app/data/dictionary.json`): despite the name, its entries are the 1913
  *Webster's Revised Unabridged*. They quote Wordsworth and M. Arnold, for example.
- `kayson-argyle/websters_1828`: a 1828 text from the Ellen G. White Estate scan, with a hand-made lemma list
  for the standard works. The repo has no license, so we can't bundle its lemma list. Its text is also OCR
  page lines, with the dictionary printed twice and no paragraph structure.
- `CrossCrusaders/Websters1828API` (MIT): about 1,000 words, one JSON file each. That's too few for a dictionary.
- `mnjrupp/Android_SQLite_Dictionary_1828`: an unlicensed SQLite database inside an old app.
