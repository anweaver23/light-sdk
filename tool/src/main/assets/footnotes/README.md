# Footnotes (format only; no data is bundled)

The Church's footnotes are copyrighted, so none ship. This folder defines where footnote data goes
once an openly licensed source is chosen. A book with no file simply shows no footnotes.

One file per book: `footnotes/<volumeId>/<bookId>.json`, e.g. `footnotes/bofm/alma.json`.

```json
{
  "version": 1,
  "verses": {
    "32:21": [
      {"word": 3, "marker": "a", "text": "Heb. 11:1.", "refs": ["nt/heb/11/1", "bofm/ether/12/6"]}
    ]
  }
}
```

- Keys of `verses` are `"chapter:verse"`.
- `word` is the index of the word the marker follows, counted by `core/Words.spans` (0-based). Word
  indexes are the shared anchor for all study data, so generate them with the same tokenizer.
- `marker` is the letter drawn after the word. `text` is the note. `refs` are verse keys
  (`volumeId/bookId/chapter/verse`) that open the reader at that verse; unknown keys are ignored.

Parsed by `core/Footnotes.kt` (`BookFootnotes`), loaded by `data/AssetFootnotes.kt`. Record the
source and license in a `SOURCE.md` here when real data is added. The unit-test fixture lives in
`src/test/resources/footnotes/` and is not real data.
