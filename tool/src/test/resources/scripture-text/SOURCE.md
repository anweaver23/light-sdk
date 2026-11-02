# Scripture text source (test data)

**Test data only.** The app no longer ships verse text: it imports the Church's downloads (see
`tool/CLAUDE.md`, "Text from the Church's downloads"). This public-domain copy of the 2013 text stays here
because it has the same words as the Church's, so tests of search, Strong's alignment and word anchors
run against real text. `index.json` (the volumes, books and chapter counts) is still bundled, in
`src/main/assets/scriptures`, and comes from the same source.

Verse text in this directory is generated from
[bcbooks/scriptures-json](https://github.com/bcbooks/scriptures-json) by Ben Crowder,
commit `3bda76e40add4582165340ea6b1198dc6ad26ae1`.

License: the upstream README states "These files are in the public domain."
(its package.json declares `CC0-1.0`). The upstream data excludes copyrighted
material (footnotes, chapter summaries, introductions, and the Official Declarations).

Text notes (from upstream): small caps are rendered as all caps, italics are not
distinguished, and curly quotes are straightened.

Regenerate with:

    git clone https://github.com/bcbooks/scriptures-json /path/to/src
    git -C /path/to/src checkout 3bda76e40add4582165340ea6b1198dc6ad26ae1
    python3 -I tool/scripts/build_scripture_assets.py /path/to/src tool/src/main/assets/scriptures

## Book of Mormon front matter (`bofm/front.json`)

The Title Page and the Testimonies of the Three and of the Eight Witnesses (1830 text) come from
**Project Gutenberg eBook #17, *The Book of Mormon***, plain text,
<https://www.gutenberg.org/cache/epub/17/pg17.txt> ("Most recently updated: October 11, 2025"),
downloaded 2026-11-02, 1518918 bytes, SHA-256
`ac4bbea7d6f19905cf10d21465e0f491a41e2dd3cc2a64e9ee622b1f4a7a6882`.

License: the text is public domain in the US (first published 1830). Only the work's text is used; the
Project Gutenberg header, licence and name are dropped, as the Project Gutenberg licence allows for
public-domain texts. The modern Introduction and Brief Explanation are copyrighted and not included.
The Testimony of the Prophet Joseph Smith is an excerpt of Joseph Smith—History, so its entry links to
JS—H 1:29 instead of repeating text.

Regenerate with:

    python3 -I tool/scripts/build_front_matter.py pg17.txt tool/src/main/assets/scriptures/bofm/front.json
