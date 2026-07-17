# Attribution & third-party licenses

Chess for Light Phone III bundles and depends on third-party work. Credits and licenses
are listed below. Full license texts referenced here live in [`LICENSES/`](./LICENSES).

## Chess piece artwork — the "pixel" set (GNU AGPL v3 or later)

The piece graphics in `tool/src/main/res/drawable/piece_*.xml` (including the `piece_*_bank.xml`
variants) are derived from the **"pixel"** chess piece set:

- **Author:** therealqtpi — <https://twitter.com/therealqtpi>
- **Original project:** Lichess (lila), `public/piece/pixel` —
  <https://github.com/lichess-org/lila/tree/master/public/piece/pixel>
- **Obtained via:** ShareChess — <https://github.com/sharechess/sharechess>
  (see its `PIECES_CREDITS.json`)
- **License:** GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later) —
  [`LICENSES/AGPL-3.0.txt`](./LICENSES/AGPL-3.0.txt) · <https://www.gnu.org/licenses/agpl-3.0.txt>

The original SVGs were converted to Android vector drawables and recolored (the black-piece
shadow layer was removed; outline shades were adjusted; a lighter-outline `_bank` variant was
added for the material/pocket bars). These modifications are likewise licensed
**AGPL-3.0-or-later**. The corresponding source for these assets is this repository.

> Note: because these bundled assets are AGPL-3.0-or-later, distributing the app carries that
> set's AGPL obligations for the artwork — the full corresponding source must remain publicly
> available (this repo) and this notice must be retained. See "Open decision" below.

## Lichess

Games, challenges, seeks, and live play are provided by **Lichess** (<https://lichess.org>) via
its public API. "Powered by Lichess."

- Lichess software (lila) and its API are licensed AGPL-3.0-or-later.
- Consuming the Lichess API **as a client does not place AGPL obligations on this app's own
  code** (the AGPL applies to running a modified lila service, not to being an API consumer).
- API terms: <https://lichess.org/api> · Terms of Service: <https://lichess.org/terms-of-service>

## Libraries

All Apache License 2.0 unless noted ([`LICENSES/Apache-2.0.txt`](./LICENSES/Apache-2.0.txt)):

- Kotlin, kotlinx.coroutines, kotlinx.serialization — JetBrains — Apache-2.0
- Ktor (HTTP client) — JetBrains — Apache-2.0
- OkHttp (Ktor engine) — Square — Apache-2.0
- Jetpack Compose and AndroidX (Lifecycle, DataStore, Annotation, core-splashscreen, Room,
  WorkManager, CameraX) — Google — Apache-2.0
- UnifiedPush connector (`org.unifiedpush.android:connector`) — Apache-2.0
- Google ML Kit barcode scanning — Google — governed by the Google ML Kit terms. *(Only pulled
  in if the camera/QR feature is used; the v1 type-only login does not use it.)*
- Light Phone SDK (this repository) — The Light Phone — MIT (see [`LICENSE`](./LICENSE)). The LP3
  keyboard component (`com.thelightphone.lp3keyboard:ui`) is provided by The Light Phone.

## This app

App code © 2026 Andy Weaver. The repository scaffolding is under the MIT License
([`LICENSE`](./LICENSE)).

## Open decision (piece set license)

The "pixel" set is **AGPL-3.0-or-later**. Two viable paths for going public:

1. **Keep it and comply.** The tool source is already public (a Light requirement), so the
   "corresponding source available" obligation is met; retain this attribution + the AGPL text,
   and treat the piece assets (and their modifications) as AGPL-licensed. Simple and legitimate.
2. **Swap to a permissive set** (CC0 / public-domain, or CC-BY, or MIT) if you'd rather avoid any
   copyleft on the artwork. Keeps the same pixel look with fewer obligations, at the cost of
   re-importing a different set.

TODO before public release: surface these credits to end users too — an in-app "About / Credits"
screen in Settings (and/or ship this text as a bundled asset), since the installed APK's users
should see the attribution, not just readers of the repo.
