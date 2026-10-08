# Scriptures (Light Phone tool)

A reader for the LDS standard works and hymnbooks on the Light Phone III, with your own audio.

- **Text included:** Old Testament, New Testament, Book of Mormon, Doctrine and Covenants, Pearl of Great Price. Public domain text from [bcbooks/scriptures-json](https://github.com/bcbooks/scriptures-json) (see `src/main/assets/scriptures/SOURCE.md`). No footnotes, chapter headings or Official Declarations, since those are under copyright.
- **Hymns included:** numbers and titles only for *Hymns* (1985) and *Hymns—For Home and Church*. Most lyrics are under copyright, so you upload your own words if you want them.
- **Audio not included.** Church recordings are copyrighted, so you bring your own files. The tool sorts them for you.

## Adding audio in bulk

1. On the phone, start the Tool Manager. On a computer on the same Wi-Fi, open the address it shows.
2. Open **Scriptures**. There are three folders:
   - **Scripture audio**: chapter recordings
   - **Hymn audio**: hymn recordings
   - **Hymn lyrics**: plain `.txt` files with a hymn's words
3. Drag files in. Whole folders and `.zip` files work.
4. The tool sorts everything as soon as the upload finishes (or the next time you open it). Anything it can't place shows up under **Add audio → Needs review**, where you can pick the chapter or hymn by hand or delete the file.

### What names it understands

The matcher reads the file name, then the folder names, then the file's title/album tags. Tested examples:

| File | Becomes |
|---|---|
| `2015-11-1270-alma-32-male-voice-64k-eng.mp3` (Church website download) | Alma 32 |
| `2015-11-0040-section-04-female-voice-64k-eng.mp3` | D&C 4 |
| `2015-11-0150-joseph-smith-history-male-voice-64k-eng.mp3` | Joseph Smith—History |
| `Alma 32.mp3`, `alma32.mp3`, `bofm_alma_032_eng.mp3` | Alma 32 |
| `Book of Mormon/Alma/032.mp3`, `Alma.zip` containing `032.mp3` | Alma 32 |
| `1 John 3.mp3` / `John 3.mp3` / `Third Nephi 11.mp3` | 1 John 3 / John 3 / 3 Nephi 11 |
| `track07.mp3` tagged "Helaman 5" | Helaman 5 |
| `the_morning_breaks_accompaniment_eng.mp3` | Hymn 1 (1985) |
| `2001-01-0300-come-come-ye-saints-instrumental-192k-eng.mp3` | Hymn 30 (1985) |
| `it_is_well_with_my_soul.mp3` | Hymn 1003 (Home and Church) |
| `Hymn 85.mp3`, `85.txt` | Hymn 85 |

If two files match the same chapter (say, the male and female voice versions), the first is kept and the other goes to review, where **Delete duplicates** clears them in one tap. Uploading a file for something that already has audio replaces the old one.

Note: audio downloaded *inside* the Gospel Library app is stored privately by that app and can't be copied out. Use the **Download** option on the Church website instead.

## Code layout

```
src/main/kotlin/io/github/anweaver23/scriptures/
  core/   Catalog models, FileMatcher, ImportPlanner. Plain Kotlin, no Android.
  data/   AppGraph (singletons), Importer, AudioLibrary (files on disk), ProgressStore (DataStore)
  ui/     Screens: Home, BookList, ChapterGrid, Reader, Hymns, Player, AddAudio, Review, TargetPicker
  ToolEntryPoint.kt   Tool Manager folders + sorting after an upload
src/main/assets/scriptures/   index.json + one JSON per book: {"c": [[verse, ...], ...]}
src/main/assets/hymns/        hymns-1985.json, hymns-home-church.json (numbers and titles only)
scripts/build_scripture_assets.py   Rebuilds the scripture assets from bcbooks/scriptures-json
```

Sorted files live in the tool's private storage as `library/audio/<key>.<ext>` and `library/lyrics/<key>.txt`, where the key is something like `scriptures/bofm/alma/32` or `hymns/hymns-1985/85`. The folder tree is the index; there's no database.

Playback uses the SDK's detached player (`detached-audio` capability), so it keeps going after you leave the tool. It queues every chapter of the book (or every hymn in the hymnbook) that has audio, so it rolls on to the next one. Position is saved every few seconds for resume.

## Tests

```bash
./gradlew :tool:testDebugUnitTest
```

`FileMatcherTest`, `ChurchFilenamesTest` and `ImporterTest` run against the real bundled catalog.
