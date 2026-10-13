# FFL Live — Yahoo Fantasy Football for Android

Live Yahoo Fantasy Football scoring on your phone: your matchup, every matchup in the league, and the
NFL slate, with the play that last moved each score. A native Android port of
[ha-ffl-yahoo](https://github.com/johnbr/ha-ffl-yahoo), with no Home Assistant needed.

**All it needs is your league id.** It needs no Yahoo account, sign-in, API key or approval. Private
leagues work too.

## Features

- **Three tabs.**
  - **My Matchup** shows your team against your opponent. Under it are the NFL games in progress that
    a starter on either side is playing in.
  - **League** shows every matchup.
  - **NFL** shows the week's games: live ones first, today's next, and the rest folded under "later this
    week" and "final".
- **Live points and projections.** Each team shows its current points and its live projection. The
  projection is coloured against the morning's number.
- **Per-matchup scoring plays.** Under each matchup is the play that last moved *that* score,
  aligned to the side that scored it. Bench points never count. Tap the line for the matchup's full
  scoring history, or tap the matchup for both lineups and a win probability.
- **The NFL games.**
  - Each game shows its clock, down and distance, possession, a red-zone badge and a field bar.
  - Every game has its play list.
  - A TOUCHDOWN / FIELD GOAL / SAFETY banner stays up until the next play lands.
  - A provisional result (`Gain of 7, 1st down`) appears while Yahoo's sentence is still on its way.
- **Live mode (opt-in).** A foreground service keeps scores current with the app closed.
  - It starts itself shortly before kickoff and stops when the games are over.
  - It sends alerts for *my player scored*, *opponent scored* and *lead changed*.
  - A notification is updated in place when the play's text lands.
- **Home-screen widget.** Your matchup: both scores and live projections (green above the morning's
  projection, red below), with the decimal points lined up, and the last play. The winner turns gold
  once the matchup is over. Two rows tall, it adds the league and the number of live games.
- **Several leagues.** Switch from the title bar.

## How it gets the data

It reads Yahoo's **GameChannel** tier, the same anonymous endpoints `sports.yahoo.com/nfl/gamechannel/`
runs on:

- `pub-api.fantasysports.yahoo.com/fantasy/v3/redzone/nfl?league_id=…` provides teams, rosters,
  matchups, projections and your league's own scoring settings. It is cached for 3 minutes.
- `relay-stream.sports.yahoo.com/nfl/{stats,games}.txt` is the live tier. It is polled every 10 s
  while a rostered player's game is on, every 5 minutes near games and every 30 minutes otherwise.
  Leagues share these fetches.
- `relay-stream.sports.yahoo.com/nfl/plays-<id>.txt` gives play-by-play for live games. It is fetched
  with `If-Modified-Since`, so an unchanged feed costs a `304`.

**The points are computed, not read.** Yahoo publishes no live fantasy totals on this tier. The app
multiplies each player's live stat line by your league's scoring modifiers, as Yahoo's own page does.
Scoring plays come from diffing consecutive polls. "Real time" means 30–60 seconds, because that is how
far Yahoo's own live scoring trails the play. See the
[integration's README](https://github.com/johnbr/ha-ffl-yahoo#worth-knowing) for the details:
stat corrections, plays that arrive in pieces, the relay's overnight restart, and held feeds.

## Install

Download the APK from the [latest release](https://github.com/johnbr/ffl-yahoo-android/releases/latest)
and open it on your phone. You will need to allow installing from your browser or file manager.
Android 8.0 or newer is required.

To get updates automatically, add `https://github.com/johnbr/ffl-yahoo-android` to
[Obtainium](https://github.com/ImranR98/Obtainium).

On first launch, paste your league id or the league's URL. The id is `802904` in
`https://football.fantasysports.yahoo.com/f1/802904`. Then pick your team.

## Development

```
core/   pure Kotlin/JVM: the engine (parsers, scoring, play synthesis, pending plays, banners,
        state shaping, the polling engine), ported from the integration's Python
app/    Android: Compose UI, live-mode service, notifications, Glance widget
tools/  parity_dump.py, which writes the golden files the engine is tested against
```

```bash
./gradlew :core:test                    # engine tests, no Android SDK needed (add -PcoreOnly without one)
./gradlew :app:lintDebug :app:assembleDebug
```

The engine is held to the integration's own behaviour. `tools/parity_dump.py` runs the integration's
Python over the shared fixtures, and through 60 scripted polls of a live game driven through its real
coordinator. It writes `core/src/test/resources/golden/`, and `StaticParityTest` and
`ScenarioParityTest` require identical output. After a deliberate change to the integration,
regenerate the golden files:

```bash
python3 tools/parity_dump.py --ha-repo ../ha-ffl-yahoo
```

Versions are managed by [release-please](https://github.com/googleapis/release-please) from
conventional commits. A release builds a signed APK and attaches it.

## Attribution

Fantasy data provided by Yahoo Fantasy. This project is not affiliated with, endorsed by, or
sponsored by Yahoo or the NFL.

## License

[MIT](LICENSE)
