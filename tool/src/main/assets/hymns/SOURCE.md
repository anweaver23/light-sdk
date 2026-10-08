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

## Regenerating

Run the build script. It writes both JSON files and prints any audio filename that does not equal `slug(title)`:

`python3 -I build.py <musicapi>/sacredmusic/api/hymns.json <musicapi>/sacredmusic/api/hymns-for-home-and-church.json <hfhc-2026-07-25.json> <outdir>`

The script lives in the agent scratchpad and is not committed. It reads `songNumber` and `title` from the `data[]` array of each file.
