# Hymn index sources

These files hold hymn numbers and titles only. They contain no lyrics and no audio.

## hymns-1985.json (341 entries, numbers 1-341)

- Primary: the Church music library metadata for the `hymns` collection
  (https://www.churchofjesuschrist.org/media/music/collections/hymns?lang=eng),
  read from the public mirror `living-music/musicapi`, file `sacredmusic/api/hymns.json`
  (commit 598e4a6, refreshed 2026-10-07): https://github.com/living-music/musicapi
- Cross-check: `danseethaler/hillsborough-seminary` `src/data/hymns.json` (2018), an independent list
  of all 341 numbers and titles: https://github.com/danseethaler/hillsborough-seminary.
  After normalizing punctuation, all 341 titles match the primary source.
- Titles keep the Church's typography, including curly apostrophes (for example "Joseph Smith’s First Prayer").
  Some titles appear twice under different numbers because the book prints separate men's and women's
  arrangements, for example 5 and 333 "High on the Mountain Top", and 323 and 324 "Rise Up, O Men of God".

## hymns-home-church.json (82 entries)

- Primary: the same mirror, `sacredmusic/api/hymns-for-home-and-church.json`, which reflects the Church's
  `hymns-for-home-and-church` collection.
  - The 2026-10-04 refresh of the mirror dropped the Easter and Christmas entries 1203-1210. Those 8 entries
    come from the mirror's 2026-07-25 snapshot (commit a04bc85). The file uses the union of both snapshots.
- Numbering:
  - 1001-1072 are "Sabbath and Weekday".
  - 1201-1210 are "Easter and Christmas".
  - Children's songs, such as 1021 and 1028, sit inside the 1001+ range. They have no separate range.
- asOf: the July 23, 2026 release (the 7th English batch), which brought the total to 82 songs. The count was
  confirmed by news coverage:
  - https://www.thechurchnews.com/members/2026/07/23/10-new-hymns-released-invite-worship-of-heaveny-father-jesus-christ/
  - https://www.deseret.com/faith/2026/07/23/new-songs-added-to-hymns-for-home-and-church-july-2026/
  - Web searches on 2026-10-08 found no later batch.

## childrens-songbook.json (268 entries, numbers 2-299)

- Primary: the Church music library metadata for the `childrens-songbook` collection
  (https://www.churchofjesuschrist.org/media/music/collections/childrens-songbook?lang=eng),
  read from the public mirror `living-music/musicapi`, file `sacredmusic/api/childrens-songbook.json`
  (commit 523d85f, refreshed 2026-11-02): https://github.com/living-music/musicapi
- Cross-checks:
  - The Church's own Gospel Library edition, https://www.churchofjesuschrist.org/study/manual/childrens-songbook?lang=eng
    (read 2026-11-02). Its contents list 268 songs in the same 8 sections, from 2 "I Am a Child of God" to
    299 "Distant Bells", with the same a/b numbering.
  - `madofuller/WardBulletin` `src/data/childrensSongbook.ts` (commit b7665f3, 2025-12-04), an independent list
    of all 268 numbers and titles: https://github.com/madofuller/WardBulletin. After normalizing punctuation,
    all 268 numbers and titles match the primary source.
  - `SQLLord/ward-program` `ward-program-app/src/data/childrensHymns.js` (commit fcfed83, 2026-04-04) has the
    same 268 numbers. It adds subtitles that the Church's titles leave out, such as "(Round)" on 11 songs and
    "Beautiful Savior (Crusader's Hymn)". We don't use those subtitles.
- Numbers are the book's page numbers. A song with no number of its own starts on a page the previous song
  hasn't finished, so the list skips numbers (for example 3, 13, 17).
- Two songs on one page are numbered "a" and "b" (20a "A Song of Thanks", 20b "Thanks to Our Father").
  There are 27 such pages (54 entries), so the 268 songs sit on 241 distinct page numbers. In the bundled
  JSON those carry `label` ("20a") and an internal `n` of page × 1000 + 1 or 2; every other song's `n` is its
  page number. No title repeats.
- Titles keep the Church's typography:
  - curly apostrophes: "A Child’s Prayer"
  - an em dash: 94 "Family History—I Am Doing It"
  - curly quotes: 236 "“Give,” Said the Little Stream"
  - ñ: 282 "Feliz Cumpleaños"
  - The source marks 296 as "Air from <i>Orpheus</i>". The tags are stripped here.
- `files` maps the Church's English audio filenames to the recording they are (`vocal` or `accompaniment`),
  so uploads with names the title matcher can't place still sort (`FileMatcher.knownFiles`). Section and slug
  from the source are not kept. Coverage in the metadata:
  - `vocal` and `accompaniment`: 251 songs each.
  - `instrumental`: the 13 prelude pieces, 288-299. They have no vocal recording, so theirs is stored as the plain (`vocal`) slot.
  - `guitar`: 1 song, 103 (not kept).
  - No audio listed: 201, 229, 238 and 265.
- Audio filename oddities:
  - Many English accompaniment files carry Spanish names, for example 2 `soy_un_hijo_de_diosmusica_solo.mp3`.
  - 144's accompaniment is Portuguese, `brilha_instrumental.mp3`.
  - Other names follow the pattern `<title>music_only.mp3`, `<title>_accompaniment_eng.mp3`,
    `2002-01-…-music-only-192k-eng.mp3` (77, 78, 95), or are hashed `.m4a` (103).
  - Two accompaniment files are named `…restricted_pending_ip_approval.mp3` (20a, 97).
  - Vocal files are mostly `<title>_vocal_accompaniment_eng.mp3`, otherwise `…_words_and_music.mp3`.

## Regenerating

Run the build script. It writes the two hymnbook JSON files and prints any audio filename that does not equal `slug(title)`:

`python3 -I build.py <musicapi>/sacredmusic/api/hymns.json <musicapi>/sacredmusic/api/hymns-for-home-and-church.json <hfhc-2026-07-25.json> <outdir>`

The script lives in the agent scratchpad and is not committed. It reads `songNumber` and `title` from the `data[]` array of each file.

`childrens-songbook.json` was built on 2026-11-02 from the mirror's `sacredmusic/api/childrens-songbook.json`
(`songNumber`, `title`, English `AUDIO_*` assets of `data[]`), by a scratchpad script that is not committed,
then reshaped to the format above (`label`, `files`).
