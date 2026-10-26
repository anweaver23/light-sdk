# Scriptures tool — project guide

This file records the Scriptures tool's decisions, structure and conventions. Read it before making changes.
Update it in the same commit as any decision or structural change. The task backlog lives in
**GitHub issues** on `anweaver23/light-sdk` (the fork), grouped by label. This file holds the *why* and the
rules; issues hold the *what's next*.

A reader for the LDS standard works and hymnbooks on the Light Phone III, with the user's own audio.
It's a calm, intentional reading tool and not a feature-for-feature Gospel Library clone. User-facing docs are
in `tool/README.md`.

## Roadmap (phases run in order)

1. **Reading:** a fully fleshed-out, refined reading experience. *(current)*
   Home grid, bottom bar (search / bookmarks / annotations), footnote links + modal, select → highlight / note /
   define, Webster's 1828 dictionary, Strong's Hebrew/Greek original words, then refinement.
2. **Hymns:** audio, lyrics and sheet music support, refined.
3. **Audio:** scripture chapter audio fully refined. Conference talks only if a legally open source exists.

When an idea comes up for a later phase, open an issue in that phase's milestone rather than building it now.
Issue labels: `phase-1-reading`, `phase-2-hymns`, `phase-3-audio`, plus `refine`, `data`, `bug`.

## Decisions (newest last; add a date)

- **2026-10-14 Home is a 2-column grid of book-shaped tiles:** the 5 volumes + Hymns. Bottom bar:
  Search · Bookmarks · Annotations.
- **2026-10-14 Bookmarks:** multiple manual bookmarks are allowed. An automatic **Continue reading** entry
  (last chapter read) is always pinned at the top of the Bookmarks tab.
- **2026-10-14 Annotations tab:** a plain list of highlights and notes for now. Visualization comes later (issue).
- **2026-10-14 Footnotes are plumbing only:** the Church's footnotes are copyrighted, so we don't bundle them.
  We build the data format, the in-text markers and the modal, and ship no footnote data. Choosing a source
  (public-domain 1879/1920 editions, Treasury of Scripture Knowledge, OpenBible.info) is a separate issue.
  Markers render only where data exists.
- **2026-10-14 Define a word:** the dictionary is Webster's 1828 (public domain, and period-accurate for
  KJV/Book of Mormon English), bundled offline. For OT/NT we also show the **original** Hebrew/Greek word
  (Strong's). This is NOT a translation of the English word. It comes from a Strong's-tagged KJV aligned
  word-by-word to our text, which is a separate index. Other volumes have no original-language data.
- **2026-10-14 Dictionary source and format:** Webster's 1828 comes from `akaitsurugi/webster1828`
  (pinned commit in `assets/dictionary/SOURCE.md`). It's the only clean, structured, *genuinely 1828* digital
  copy we found: numbered senses and labelled parts of speech. Its digital edition is **CC BY-SA 4.0**, so the
  generated shards are CC BY-SA too, and the app must credit it (the underlying 1828 text is public domain).
  Rejected sources: `sktzofrenic/webster1828` is really the 1913 edition, and `kayson-argyle/websters_1828` is
  unlicensed OCR.
  - Shards are plain TSV text by first 2-3 letters (~700 files, largest ~270 KB), so a lookup reads one small
    file.
  - Archaic and British forms fall back in `core/WebsterLookup` (giveth→give, honour→honor, firstborn→first-born).
  - Coverage of lowercase words in our scripture text: 99.9% of tokens.
- **2026-10-14 Word indexes are the anchor:** highlights, notes, footnotes and Strong's tags all point at
  `(VerseRef, word index)` as produced by `core/Words.spans`. Changing the tokenizer is a data-format change.
- **2026-10-14 Strong's sources and format:** word tags come from eBible.org's `eng-kjv2006` (CrossWire's
  KJV2006 tagging, declared public domain there; CrossWire's own SWORD module is labelled GPL, so we don't use
  it). Dictionaries come from the openscriptures/strongs **XML** (public domain), never its JSON (CC BY-SA).
  `scripts/build_strongs_assets.py` re-anchors the tags onto our text by word index: per-book
  `assets/strongs/<ot|nt>/<book>.json` (flat `[word, number, ...]` per verse) plus 100-entry dictionary shards
  `assets/strongs/dict/<H|G>/<n/100>.json`, so a lookup loads one book and one shard. Spelling variants are an
  explicit list, never fuzzy matching: a wrong tag is worse than a missing one. Each tag file stores a token
  CRC that a unit test recomputes with `Words.spans`, so **a tokenizer or verse-text change fails the build
  until the Strong's assets are regenerated.**
- **2026-10-14 Selection gestures:** the reader draws each verse as one `BasicText` (an `AnnotatedString`
  styled like `LightTextVariant.Paragraph`) and hit-tests its `TextLayoutResult`. One custom gesture
  detector on the chapter column: lift before the long-press timeout = tap; move past touch slop first =
  scroll (not consumed, so `LightScrollView` keeps scrolling); hold = select the word, then the drag is
  consumed and extends word by word. While selecting, tapping a word extends to it and tapping off the
  words clears. Range math (order, per-verse words, merge) is pure in `core/Selection.kt`/`Study.kt`.
- **2026-10-14 Highlight look:** translucent content colour, so it's a soft grey on AMOLED black and works
  on the light theme: highlight `HIGHLIGHT_ALPHA` 0.22, notes add an underline, selection 0.45. Constants
  at the top of `ui/ReaderScreen.kt`.
- **2026-10-14 Highlight merging:** highlighting over or next to plain highlights merges them into one.
  Notes never merge and are never removed by a highlight; a highlight fully inside another is a no-op.
- **2026-10-14 Study storage:** `filesDir/study/annotations.json` and `bookmarks.json`, each
  `{"version":1,...}`, anchors as `{"ref":"bofm/alma/32/21","word":3}`. Written to a temp file then
  renamed. A file that can't be read (or is from a newer version) is set aside as
  `<name>.unreadable-<time>`, never overwritten.
- **2026-10-14 Study sub-views are screens:** Define, the note editor, a highlight's detail and a
  footnote open as their own screens (not overlays), because hardware BACK pops the screen without
  asking the view model (SDK bug), which would otherwise close the reader.
- **2026-10-14 Bookmark star:** fills when the chapter has any bookmark. Tapping it adds one at the top
  visible verse, or removes the one nearest it, so two taps undo each other. Continue reading also
  remembers the top verse (`ProgressStore.lastReadVerse`).
- **2026-10-14 Footnote format:** documented in `src/main/assets/footnotes/README.md`.
- **2026-10-26 Home repo is the fork:** Light's docs build tools from forks of `lightphone/light-sdk`, so the
  tool lives on branch `andy/scripture-app` of the fork `anweaver23/light-sdk`, next to `andy/chess-app`.
  The standalone private `anweaver23/light-scriptures` copy is retired. The fork is public (GitHub allows no
  private forks of public repos), which Light requires for release anyway.
- **2026-10-26 Tool id `com.andyweaver.scriptures`:** matches the chess tool's `com.andyweaver.chess`.
  The Kotlin package stays `io.github.anweaver23.scriptures`; only the id is user-visible and permanent.

## Hard rules

- **Copyright:** bundle only public-domain or openly licensed data (PD, CC0, CC BY; CC BY-SA is acceptable for
  data files, as with Webster's, and its credit must appear in-app; never NonCommercial), and record the source, commit/version and
  license in a `SOURCE.md` next to the asset. Church footnotes, chapter headings, the current hymn lyrics and
  Church audio are copyrighted, so they are not bundled. Users can bring their own audio and lyrics.
- **Only `tool/` ships.** Light's build server extracts `tool/build.gradle.kts`, `tool/lighttool.toml` and
  `tool/src/main/{kotlin,java,res,assets}/**`, then builds against its own pinned SDK. Never depend on
  `sdk/*` edits, `local.properties`, or extra Gradle modules. New dependencies must be on the plugin
  allowlist (`plugin/src/main/resources/.../allowed-dependencies.txt`).
- **Banned APIs fail the build:** `LocalContext`, `android.content.Context`, `startActivity`,
  `getSystemService`, raw Intents. Stay within the SDK (`SealedLightContext`, `LightScreen`, `LightIcons`...).
- **Icons:** use `LightIcons` first. Add a custom vector drawable only when no Light glyph fits.
- **Offline first:** reading, dictionary and Strong's work with no network.
- **`serverPackage`** in `lighttool.toml` is `com.thelightphone.sdk.emulator` for local work. It must be
  `com.lightos` for a real device or a submission.

## Structure

```
src/main/kotlin/io/github/anweaver23/scriptures/
  core/   Plain Kotlin, no Android: catalog models, Targets/VerseRef, Words tokenizer, Lexicon
          interfaces, file matching, selection ranges, study models + JSON format, verse display
          mapping, footnote format. Everything testable on the JVM lives here.
  data/   AppGraph (process singletons), stores (DataStore / JSON files in filesDir), asset loaders,
          importer, audio library.
  ui/     One file per screen or screen family. Shared bits (ScreenFrame, ListRow, Note) in Common.kt.
  ToolEntryPoint.kt   Tool Manager folders + post-upload sorting.
src/main/assets/   Bundled data, one folder per dataset, each with a SOURCE.md.
scripts/           Python generators for the assets (run with `python3 -I`).
```

- **Swappable data behind interfaces.** `core/Lexicon.kt` defines `Dictionary` and `OriginalLanguage`.
  `AppGraph` picks the implementation. Screens never read assets directly.
- **Pure logic in `core/` with unit tests.** UI correctness that a screenshot can't show (selection ranges,
  anchors, alignment) gets pulled into pure functions and tested.
- **User data** (bookmarks, highlights, notes) is versioned and stored in the tool's private `filesDir`, never in
  the Tool Manager shared dir unless it's meant for export.

## Build, run, test

From the **repo root** (never a subdirectory). Confirm `BUILD SUCCESSFUL`, not just the absence of errors:

```
export JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
./gradlew :tool:assembleDebug :tool:testDebugUnitTest
adb -s emulator-5554 install -r tool/build/outputs/apk/debug/tool-debug.apk
adb -s emulator-5554 shell am start -n com.andyweaver.scriptures/com.thelightphone.sdk.LightActivity
```

- **Emulator:** if the LightOS emulator app is missing (it has vanished twice), run
  `./gradlew :sdk:emulator:assembleDebug`, then
  `adb install -r sdk/emulator/build/outputs/apk/debug/emulator-debug.apk` (it lands as uid 1000), then
  `adb shell cmd package set-home-activity com.thelightphone.sdk.emulator/.MainActivity`. Next, as root, write
  `<int name="client_filter_level" value="3" />` into
  `/data/data/com.thelightphone.sdk.emulator/shared_prefs/light_sdk_server.xml` (All Tools).
- **Tool Manager:** open it from the emulator toolbox, run `adb forward tcp:54449 tcp:54449`, then open the
  URL it shows (including the `#token`) in a desktop browser.
- **Prefix `MSYS_NO_PATHCONV=1`** to any adb command that carries a device-absolute path (Git Bash mangles it).
- **Pass `-s <serial>`** whenever the phone and the emulator are both attached.
- **Compose view-model trap:** fields read by a `MutableStateFlow(buildState())` initializer must be declared
  *above* it, or they're null at construction. Unit tests don't catch this; only the emulator does.

## Working with agents

Parallel agents each work in their own git worktree on **disjoint files**. Shared contracts (interfaces, models,
stub screens) are committed first so every worktree compiles. Agents don't edit `AppGraph` or another agent's
files; the coordinator wires implementations in and merges. Every agent diff is re-read and rebuilt before it's
trusted. One emulator is shared, so on-device verification happens after the merge.

## Git

- Branch `andy/scripture-app` lives on the fork `anweaver23/light-sdk` (remote `fork` locally), next to
  the chess app's `andy/chess-app`. One app per branch; GitHub allows only one fork per account.
- SDK updates: `git checkout main && git pull --ff-only origin main`, then merge `main` into this branch.
- Never push to `origin` (the upstream `lightphone/light-sdk`, read-only for us).
