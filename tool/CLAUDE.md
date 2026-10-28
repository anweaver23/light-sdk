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
- **2026-10-14 Bookmarks:** multiple manual bookmarks are allowed. *(The pinned Continue reading entry is
  gone, see 2026-10-26 Reopen where you left off.)*
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
- **2026-10-14 Bookmark star** *(the star is gone, see 2026-10-26 Reader menu; the toggle rule stands)*:
  fills when the chapter has any bookmark. Tapping it adds one at the top
  visible verse, or removes the one nearest it, so two taps undo each other. Continue reading also
  remembers the top verse (`ProgressStore.lastReadVerse`).
- **2026-10-14 Footnote format:** documented in `src/main/assets/footnotes/README.md`.
- **2026-10-26 Home repo is the fork:** Light's docs build tools from forks of `lightphone/light-sdk`, so the
  tool lives on branch `andy/scripture-app` of the fork `anweaver23/light-sdk`, next to `andy/chess-app`.
  The standalone private `anweaver23/light-scriptures` copy is retired. The fork is public (GitHub allows no
  private forks of public repos), which Light requires for release anyway.
- **2026-10-26 Tool id `com.andyweaver.scriptures`:** matches the chess tool's `com.andyweaver.chess`.
  The Kotlin package stays `io.github.anweaver23.scriptures`; only the id is user-visible and permanent.
  The generated `R` class follows the id: import `com.andyweaver.scriptures.R`.
- **2026-10-26 Never a star for bookmarks:** bookmarks always use our `ic_bookmark` glyph, everywhere.
- **2026-10-26 Every landing page has a "…" menu** *(gone since 2026-10-28, see No "…" menu)* (`LightIcons.ELLIPSES`, top right) built with
  `pageMenu(items)` in `ui/MenuScreen.kt`. Each page passes its own items, and **Add audio** is always
  appended, then **Display** and **Add audio**. An item that `opens` a screen opens it on top of the menu
  (BACK returns to the menu); an action (`onSelect`) closes the menu and runs on the opening page. Home: nothing extra. Reader: Bookmark / Remove bookmark, Find in chapter. Bookmarks /
  Annotations: Edit … (DONE then takes the top-right slot). Sub-views (Define, footnote, highlight detail, editors, Add audio itself) have none.
- **2026-10-26 Find in chapter:** case-insensitive, verse numbers skipped, footnote markers ignored
  (`core/Find.kt`). Matches get a translucent background (`FIND_ALPHA`, current `FIND_CURRENT_ALPHA`);
  a find bar (close · PREV · n OF m · NEXT) replaces the bottom bar and scrolls only when the match is
  off screen. Changing chapter closes it.
- **2026-10-26 Selection actions are icons:** close, highlight (`ic_highlight`), note (`ic_note`), define
  (`ic_define`). Custom icons are single white vectors tinted to the theme by `tintedIcon()`; no
  white/black pairs.
- **2026-10-26 Text size setting** *(now part of Display, see 2026-10-27)*: `core/TextSize` (Small 0.85×
  … Largest 1.5×), stored by `SettingsStore`, shared as `AppGraph.display`.
- **2026-10-26 Home tiles are square**, 2 columns (fixed; home doesn't follow Display; 3 was tried on
  2026-10-27 and dropped the next day). Home bar:
  Settings · Search · Bookmarks · Annotations (`ic_notebook`), via `navBar()`, which every browse page
  (books, chapters, hymnbooks, hymn lists) shows too. Home is books only: no Add audio or Resume
  listening rows. Add audio lives in the "…" menus and Settings.
- **2026-10-26 Note editor is full screen, like LightOS Notes:** reading-size text fills the page (no
  field underline), scrolls to keep the caret in view, Return is a new line, SAVE returns the text. It
  hosts its own LP3 keyboard view model in the screen's store (`ui/NoteEditorScreen.kt`), because
  `LightTextInputEditor` only draws heading-size underlined text.
- **2026-10-26 Removing a highlight or note asks first** (`ConfirmScreen`), from the highlight screen
  and from the Annotations list.
- **2026-10-26 Chapter number grid rows are taller** (`NUMBER_ROW_UNITS` 4.5) so rows don't crowd.
- **2026-10-26 Reopen where you left off:** no Continue reading button anywhere. On launch, home opens the
  reader at the last chapter and top verse (`ProgressStore.lastRead`/`lastReadVerse`) once per process
  (`AppGraph.resumedReading`), scrolled there without the focus mark. Back returns to home.
- **2026-10-26 Chapters turn by swiping:** no Prev/Next. The page follows the finger (`pageSwipe`, read
  in the Initial pass on a frame that doesn't move, so it beats tap/long-press/scroll); released past
  20% of the width it slides off and the next chapter slides in, otherwise it springs back. Claimed only
  when the drag passes slop mostly sideways (1.5×) before the long-press timeout, and never while a
  selection is active. Past the first/last chapter it only gives a little (`EDGE_RESISTANCE`). The bottom bar only
  shows when the chapter has audio (Listen).
- **2026-10-26 Home tiles are plain outlines:** the spine (second line) is gone; the outline is
  `contentSecondary` (lighter grey) so it frames the title without competing.
- **2026-10-26 Search reads references** (`core/References.kt`): "genesis" lists the book (opens its
  chapter grid), "genesis 1" the chapter, "1 ne 3:7" the verse; "jude 5" in a one-chapter book is a
  verse. Titles and abbreviations match exactly, or by title prefix (3+ letters); exact beats prefix.
  References list above the full-text verse results, which still run. The results title is the search
  term in quotes.
- **2026-10-26 Lists and menus follow LightOS** *(sizes superseded 2026-10-27: Heading, 5 units)*:
  rows are `ROW_TEXT` text, `ROW_UNITS` tall,
  a value sits dimmed on the right at the same size (`ListRow`), the current choice in a picker is
  underlined (`SelectableRow`, never a checkmark), on/off uses `LightIcons.TOGGLE_STATE_ON/OFF`, and
  there are **no icons in menus or lists**. `ScreenFrame` leaves one unit under the top bar.
  Confirmations: message centred, bar `[ —, CONFIRM-LABEL, ✕ ]`. Text size is a picker screen
  (`TextSizeScreen`), not inline radio rows.
- **2026-10-26 Note tools:** holding anywhere in the note (empty space included) replaces the keys with
  Copy (whole note) / Paste (at caret) / Clear, using the LP3 keyboard's `overlay` slot; its close arrow
  brings the keys back. The keyboard has no clipboard support of its own. Labels are Button type (caps).
  `onOverlayDismissed` is passed only while the tools show, or the keyboard's own down arrow (numbers,
  emoji) stops working.
- **2026-10-27 Display settings** (`DisplayScreen`, from Settings and every "…" menu): text size (picker),
  line spacing (slider over `LineSpacing.STEPS`, snapping, no drawn notches, a haptic per step) and
  invert colours (Light toggle). Size and spacing pickers show a preview. Stored in `SettingsStore`,
  shared as `AppGraph.display`.
- **2026-10-27 Line spacing is for reading text only** (scripture and notes, via `readerTextStyle`); lists
  and labels follow text size alone. Between verses the gap is `VERSE_GAP_UNITS` plus whatever line
  spacing adds, so a verse break always reads bigger than a line break.
- **2026-10-27 Display is for reading text only:** text size, line spacing and invert colours change
  scripture (reader), hymn words, the note editor and a note's page (`reading = true` on `ScreenFrame` /
  `AppTheme`). Define and footnotes follow size and spacing (`AppText(reading = true)` / `readingStyle`)
  but not invert. Nothing else. Everything else is "system" UI at fixed Light sizes. `AppText` is LightText's look plus
  styled-text support; it never scales.
- **2026-10-27 System sizes match LightOS** (measured from LightOS Settings on the LP3; 1 design px is
  about 2.07 screen px): list, menu and picker rows are **Heading** text (38), `ROW_UNITS` 5 tall, text
  inset to 2 grid units (`LIST_INSET_UNITS` on top of the frame's 1). Values sit dimmed on the right
  at the same size. The chapter number grid uses the same row. Hints/empty states: Fine, dimmed.
  Measured from LightOS Notes, Podcasts and Messages: two-line rows (`StackRow`) are a Subheading title
  over a white Detail line; the player is Subheading (what's playing) and Detail (time); message-style
  reading text is Paragraph, which is our scripture base size. Home: 2 columns; tile titles start at
  Subheading (30) and shrink together until no word breaks (Subtitle 52 gave one huge column).
- **2026-10-27 Small gap under the top bar:** LightOS content starts just under the 120 px bar, so
  `ScreenFrame` adds only `CONTENT_TOP_UNITS` (0.5) there, and list rows put their text at the top of the
  row with the spare space below (`Alignment.Top`), keeping the 5-unit pitch. The number grid does the
  same. Two-line rows dim their second line.
- **2026-10-27 Notes are for empty / no-data states only,** plus status (Searching…, counts). No
  explanatory blurbs on screens that work (e.g. none on Display, none on the chapter grid).
- **2026-10-27 Hymns have two recordings:** the plain slot is vocal (or unlabelled); a file named
  "accompaniment" or "instrumental" without "vocal" goes to `HymnRef(accompaniment = true)`
  (`…/85/accompaniment.mp3`). The hymn page shows VOCAL and MUSIC ONLY when both exist, else LISTEN;
  the player keeps the chosen version from hymn to hymn. Lists show no audio/words indicators.
  "music only" is accompaniment too; "words and music" (as a phrase) is vocal.
- **2026-10-27 Hymns sharing a title** (`FileMatcher.arrangement`): the 1985 women's (309–318) and men's
  (319–337) arrangements repeat earlier titles, and #173/174, #176/177 are two tunes. "women" / "men"
  in the file name (beyond the title's own words) pick the arrangement, "choir" the later of two in a
  section (#323 men / #324 men's choir), tune names pick #176 Meredith / #177 Hancock / #173 Saul, and a
  file that states its number keeps it. On the user's 667-file Church zip this places 334 of 341; the
  other 7 aren't in it, and the Spanish "musica_solo" files go to review.
- **2026-10-27 A name match beats a tag match** (`ImportPlanner.plan`): files whose names match claim
  their slots first; a match from ID3 tags alone only fills a slot no name claims. The Church's Spanish
  "musica_solo" files carry the English title in their tags and, sorting first, had taken five English
  hymns' slots.
- **2026-10-27 Hymn lists are denser:** `DENSE_ROW_TEXT` (Subheading) on `DENSE_ROW_UNITS` (4) rows, so
  more of each long title shows.
- **2026-10-27 Player** *(the player screen is gone since 2026-10-28; chapters and hymns play on their page)*: the progress bar is always drawn (it flashed while the next track reported its
  length). No speed or stop options. Playing: the full transport bar; paused (for 350 ms or more): just
  play and a stop square, which ends playback and goes back.
- **2026-10-28 Hymns play on the hymn page; no player screen.** The bar is a centred play; with both
  recordings it opens `HymnVersionScreen` (Vocal / Accompaniment, a LightOS picker screen: `LightModal`
  is a self-dismissing 2-second overlay with no BACK, so it can't hold a choice). Playing: the
  transport, and no time or progress bar; paused part way in: stop then play side by side in the
  middle (`[null, STOP, PLAY, null]`), as the LightOS lock screen draws them; the scripture player's
  paused bar matches. Stop empties the queue and forgets the position (`PlayerViewModel.end`). The page's view model is a
  `PlayerViewModel` with `autoStart = false`: it adopts live playback and otherwise waits for `play`.
  Hymns turn by swiping like chapters (`PageSlider` / `pageSwipe` in `ui/PageSwipe.kt`, shared with the
  reader), and when playback moves on, a page that was showing the playing hymn slides along with it.
  Swiping away while something plays just browses; play there replaces the queue.
- **2026-10-28 Volume keys and hold-home-to-return are LightOS's.** A tool can't reach them: volume
  keys go to LightOS (`DeviceKeyEvent`), which steps the ringer; `getSystemService` and a tool-side
  `MediaController` are banned; the session's `setSessionActivity` lives in the SDK's
  `LightAudioService`. The LP3 lock screen already shows stop / play for our detached session.
- **2026-10-28 Autoplay settings** (Settings: Autoplay chapters, Autoplay hymns; both on by default;
  `PlaybackSettings`, `AppGraph.playback`). On, the queue is the rest of the book or hymnbook. Off, the
  queue is just the one item, NEXT / PREVIOUS start the neighbour by hand (`PlayerViewModel.step`),
  and a recording that finished starts over on play (`END_MARGIN_MS`).
- **2026-10-28 Lists keep their scroll position** when you open something and come back: screens hold
  their `ScrollState` / `LazyListState` in a `ScrollMemory` field, not in composition, so the spot is
  there on the first frame (no jump). Keyed where one screen has several lists (picker levels, each
  hymn). Every new scrolling screen should do the same.
- **2026-10-27 Find and back:** Find in chapter *(now reached through Search, see 2026-10-28)*. While a search is active, the reader's back arrow
  reopens the search bar with the term; backing out of that ends the search. `BackHandler` is blocked
  by the SDK's import rules, so this covers the on-screen arrow (the LP3's back) only.
- **2026-10-27 Haptics fallback:** a sideloaded (not Light-signed) tool is refused a LightOS service token,
  so `LocalHapticsEnabled` stays false and every tap is silent. `AppTheme` provides it as
  `sdk || HAPTICS_FALLBACK`, as the chess tool does. Keyboard keys use `HapticFeedbackType.VirtualKey`.
- **2026-10-27 Greek and Hebrew fonts:** Akkurat (LightOS) has 6 of the 133 Greek characters in Strong's,
  no Hebrew points and a few transliteration letters missing. `withScriptFonts()` sets runs of those
  scripts in the LP3's Roboto / Noto Sans Hebrew (`/system/fonts`, falling back to sans-serif). Every
  character in the bundled data was checked against those fonts. Root links in Define are underlined.
- **2026-10-27 Material Symbols when Light has no glyph:** Outlined, weight 300 (matches Light's stroke),
  viewport cropped to the art so it fills the box like Light's glyphs, white and tinted by `tintedIcon()`.
  Apache 2.0; source and licence noted in the drawable.

- **2026-10-28 No "…" menu.** Most of the app shares one bottom bar, `navBar()` (`ui/NavBar.kt`):
  Settings · Search · ▶ (only where there's audio: the reader, a hymn's page) · Bookmarks · Annotations.
  Display and Add audio live in Settings (order: Display, Autoplay chapters, Autoplay hymns, Home, Add
  audio last). From the reader, Search leads with "Find in this chapter" (returns the term to the reader)
  and Bookmarks leads with Add bookmark / Remove bookmark at the top verse (then straight back).
- **2026-10-28 Zen mode:** a double tap on a chapter or a hymn's words hides the top and bottom bars,
  and another brings them back, with no confirmation (`Zen`, `Modifier.doubleTap`, `ScreenFrame(zen)`).
  It's one state for the session. The highlight and find bars still show; swiping still turns pages.
- **2026-10-28 Editing lists like LightOS Notes:** Bookmarks and Annotations put EDIT / DONE in the middle
  of the bottom bar; editing shows Light's circled ✕ (`LightIcons.DELETE`, `RemoveMark`; not `DENY`, a bare ✕) in the inset left of
  each row, and removing asks first (`ConfirmScreen`). Dates are "Oct 28, 2026" (`formatDate`): starting the first sub line in the annotations list
  (`Oct 28, 2026 · “quote”`, as Notes lists notes), as the first (lighter) line of a note's page, under each bookmark.
- **2026-10-28 Highlights take in punctuation:** a highlight, the selection and a quote run over
  punctuation touching their first and last words ("knowledge." keeps its full stop), via
  `Words.punctuationStart/End` and `VerseDisplay.markRange`. Anchors (word indexes) don't change.
- **2026-10-28 A note's quote keeps its verses:** the annotation page rebuilds the quote from the
  chapter text with `quoteVerses`, one verse per paragraph with its number; the stored one-line quote is
  the fallback, and what lists show.
- **2026-10-28 Hymns count as where you left off:** `ProgressStore.lastPage` holds the last chapter or
  hymn; home reopens a hymn's page as it does a chapter.
- **2026-10-28 Home layout setting:** Settings › Home flips between Tiles and List on a tap (two choices,
  no picker).
- **2026-10-28 Search finds hymns:** by number ("85", "#85", "hymn 85") first, then title, then uploaded
  words (`Search.searchHymns`), listed after references and before verses.
- **2026-10-28 Chapters play in place, like hymns:** no player screen. The reader's view model holds a
  `PlayerViewModel` (`outerScope` = its scope, screen events forwarded, `close()` on clear); play in the
  bar starts the chapter, the bar becomes the transport (`transportBar`, shared with the hymn page), and
  the page slides along when playback moves to the next chapter.
- **2026-10-28 Now playing:** while something plays or is paused part way, the top bar's right corner
  shows three thin bars (graphic_eq's stroke, drawn by hand so they can breathe slowly while playing;
  still while paused; `ui/NowPlaying.kt`) on every page you navigate (home, browse, hymns, chapters,
  search, bookmarks, annotations, a note, Define, a footnote, Settings, Add audio), but not on menus and
  pickers (Display, text size, Listen, confirmations) or the note editor. It opens the playing chapter or
  hymn, or turns to it in place on a reader / same-hymnbook page, and is hidden on the page that's
  playing. `NowPlaying.live` is set by whichever player is connected, read from that player's own flows (the view
  model's fall back to "no item" one by one on disconnect, which cleared it on leaving a page) and cleared
  only after a connected player shows no item for `NOTHING_LIVE_MS`; it can lag after autoplay moves
  on with no page open; a page opened from it (`followPlayback`) turns to what's actually live. On launch,
  home briefly connects (`NowPlaying.probe`) and, if something is playing, opens its page instead of
  where you left off. An overlay over LightOS isn't possible for a tool.
- **2026-10-28 Selection and notes icons:** note is Light's own compose glyph
  (`LightIcons.COMPOSE_MESSAGE`, as LightOS Notes starts a note); the rest are Material Symbols (weight
  300, same crop): define `dictionary`, highlight `stylus_highlighter`. Annotations is our own `ic_journal`: Material `menu_book_2`
  without its fork and knife, beside Light's compose pencil (no plus) turned point-up.
- **2026-10-28 Starting playback doesn't flash the bar:** from queueing an item until it's actually
  playing, `PlayerViewModel.starting` pins `current` to it and keeps the paused bar away; the player
  still reports the old track's index and position, and "not playing", until then (`START_SETTLE_MS`).

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
- **`serverPackage`** in `lighttool.toml` is `com.lightos` (the real LP3, and submissions). With the
  emulator's package on a phone, `bindService` fails and every server feature (haptics prefs, audio,
  Tool Manager) silently breaks. Switch to `com.thelightphone.sdk.emulator` only for emulator work, and
  don't commit that.

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
- **Animation trap:** run `Animatable` animations in a composition scope (`rememberCoroutineScope`), never
  `viewModelScope`: it has no `MonotonicFrameClock` and crashes on the first frame.
- **Loading audio without Tool Manager:** debug builds allow `adb shell run-as com.andyweaver.scriptures`.
  Push files to `/data/local/tmp/…`, then `run-as … cp` them into `files/shared/scripture-audio`
  (or `hymn-audio`, `hymn-lyrics`); the importer sorts them when home next shows.
- **Zips from Windows:** Explorer's "Compressed folder" writes Zip64 records that Android's `ZipFile`
  rejects ("invalid CEN header"); the importer falls back to `ZipInputStream`, and logs (tag
  `ScripturesImport`) why a zip couldn't be read.
- **Keyboard down arrow:** on the number/emoji layouts the LP3 keyboard handles it internally and never
  calls our callback, so the note editor buzzes when `layoutFlow` returns to the root layout (deduped
  against a key's own buzz).
- **TextUnit trap:** don't name an extension `times` on `TextUnit`: the member wins and throws on
  `Unspecified`.
- **Screen state trap:** navigating away takes a screen out of composition, so `remember`ed state is
  gone when it returns, and a menu callback that sets it changes a dead copy. Keep state that must
  survive a trip to another screen (e.g. an Edit mode) in a view model or a `mutableStateOf` field on the
  screen.
- **Compose view-model trap:** fields read by a `MutableStateFlow(buildState())` initializer must be declared
  *above* it, or they're null at construction. Unit tests don't catch this; only the emulator does.

## Working with agents

Parallel agents each work in their own git worktree on **disjoint files**. Shared contracts (interfaces, models,
stub screens) are committed first so every worktree compiles. Agents don't edit `AppGraph` or another agent's
files; the coordinator wires implementations in and merges. Every agent diff is re-read and rebuilt before it's
trusted. One emulator is shared, so on-device verification happens after the merge.

## Git

- Branch `andy/scripture-app` lives on the fork `anweaver23/light-sdk` (remote `origin` locally), next to
  the chess app's `andy/chess-app`. One app per branch; GitHub allows only one fork per account.
- SDK updates: `git fetch https://github.com/lightphone/light-sdk.git main`, then merge `FETCH_HEAD` into this branch.
- Never push to the upstream `lightphone/light-sdk` (read-only for us); fetch it by URL for SDK updates.
