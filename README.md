# Chess for Light Phone

Correspondence chess for the Light Phone III. It pulls your ongoing Lichess correspondence games into a clean, minimal screen that fits how the phone is meant to be used: you glance at it a few times a day, make your move, and put it down.

No blitz, no bullet, no timer. Just your correspondence games, waiting until you have a minute.

## Installing

Community tools aren't fully wired into LightOS yet, so for now this is a sideload.

**Just want to try it?** Get the latest APK from the [Releases](https://github.com/anweaver23/light-sdk/releases) page and install it over [adb](https://developer.android.com/tools/adb):

```
adb install chess-<version>.apk
```

## You need a Lichess account for online play

This is a client for [Lichess](https://lichess.org). It has no accounts of its own, no email, no password to manage here. You sign in with a Lichess account you already have. If you don't have one, make one at lichess.org first (it's free), then come back. **You are able to play in-person or over-the-board without logging in!**

## Signing in

The Light Phone can't open a web browser, so there's no "Log in with Lichess" button. Instead you make a personal access token on another device and type it into the app once.

1. On your computer or phone, open this link (you'll need to be logged in to Lichess):

   **[Create your token](https://lichess.org/account/oauth/token/create?scopes[]=board:play&scopes[]=challenge:read&scopes[]=challenge:write&scopes[]=follow:read&description=Light+Phone+Chess)**

   It comes pre-filled with the exact permissions the app needs, nothing more:
   - Play games with the board API
   - Read incoming challenges
   - Create, accept, decline challenges
   - Read followed players

2. Scroll down and hit **Create**. Lichess shows you the token once. Copy it.
3. In the app, tap **Enter token**, type it in, and log in.

The token lives only on your phone. Logging out deletes it, and you can revoke it any time from your Lichess account settings under Security.

## What you can do

- See all your correspondence games at once, the ones waiting on you first.
- Make a move by tapping the piece and its destination, with a confirm step so you don't send the wrong one by accident.
- Start a game: challenge a friend, challenge someone by username, or seek a random opponent. Set the days per move, rated or casual, your color, and the **variant**.
- Accept or decline challenges people send you.
- Step through any finished game move by move to review it.
- Variants: Standard, Crazyhouse, Chess960, King of the Hill, Three-check, Antichess, Atomic, Horde, and Racing Kings.

## How it's different from lichess.org

This is a focused client, not the whole site. A few things worth knowing up front:

- **Correspondence games only.** You can only start and play correspondence games (or daily games), the ones measured in days per move. But if your opponent is online and you both stay on the board, their moves come through live as they play them, so a correspondence game can turn into something close to real time when you both happen to be around.
- **You can review any game, including timed ones.** Playing is correspondence-only, but reviewing isn't. Any finished game in your Lichess history opens for review, including your blitz and rapid games.
- **Your friends list is read-only.** The app shows the players you follow on Lichess so you can challenge them in a tap, but you can't follow or unfollow anyone from here. Do that on lichess.org.
- **No move notifications yet.** The app won't buzz you when it's your turn, so check in when you feel like it. Push notifications are on the list for later.

## Privacy

There's nothing here that watches you. No analytics, no telemetry, no ads, no accounts of its own. The app talks only to [Lichess](https://lichess.org), using the personal token you provide, and stores that token only on your phone. Logging out deletes it.

## Credits

Powered by [Lichess](https://lichess.org).

Piece art is the "pixel" set by therealqtpi, from Lichess, used under the AGPL. Full credits and third-party licenses are in [ATTRIBUTION.md](./ATTRIBUTION.md).

Built on the [Light Phone SDK](https://github.com/lightphone/light-sdk).

## License

The Chess app (everything in [`tool/`](./tool)) is © 2026 Andy Weaver, licensed **GNU AGPL-3.0-or-later** ([`tool/LICENSE`](./tool/LICENSE)) — the copyleft comes from the bundled AGPL piece art. See [ATTRIBUTION.md](./ATTRIBUTION.md) for the full breakdown.

This repository is a fork of the Light Phone SDK; the SDK modules (`sdk/`, `plugin/`, `builder/`, `examples/`, `lint-rules/`) are The Light Phone's own work under their MIT license (root [`LICENSE`](./LICENSE)).

## Not affiliated

This is an independent, unofficial project. It is not affiliated with, authorized by, or endorsed by The Light Phone, Inc. or Lichess. "Light Phone" and "Lichess" belong to their respective owners; they're used here only to describe what the app works with.
