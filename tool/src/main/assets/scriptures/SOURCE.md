# Scripture text source

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
