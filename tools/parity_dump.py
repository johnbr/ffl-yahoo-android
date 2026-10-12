#!/usr/bin/env python3
"""Run the Home Assistant integration's own modules and write golden JSON for the Kotlin port.

    python3 tools/parity_dump.py --ha-repo ../ha-ffl-yahoo

The Kotlin engine in ``core/`` is a port of ``ha-ffl-yahoo``. Instead of trusting a hand port of every
test, this script feeds the real Python code the same inputs the Kotlin tests use and records what it
produces; ``ParityTest`` asserts the Kotlin output is identical. Re-run it whenever the integration's
behaviour changes on purpose, and commit the result.

Two goldens:

* ``static.json`` — pure functions over the captured fixtures: the league assembled from the seed and
  the relay feeds, every roster, the NFL slate, stat lines, play text, names.
* ``scenario.json`` — the integration's real ``YahooFantasyCoordinator``, Home Assistant stubbed out,
  driven through 60 scripted polls of a live game built from the fixtures (stat rows and plays revealed
  a few at a time, the games feed running a play ahead of the play feed). Every poll's bodies are
  written out (each distinct body once, under ``bodies``), so the Kotlin side replays the same requests
  rather than regenerating them.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import sys
from pathlib import Path
from types import ModuleType, SimpleNamespace

LEAGUE_ID = "999999"
W1_NOW = 1789005218.0  # 2026-09-09T18:53:38-0700, when the w1 relay fixtures were captured
W2_RESET_NOW = 1789657491.0  # 2026-09-18T08:04:51-0700, the morning the relay forgot Det at Buf
POLLS = 60
FIRST_PLAY = 30
POLL_SECONDS = 10.0


# --------------------------------------------------------------------------------------------------
# Home Assistant, stubbed: enough to import the package and run the coordinator's update method.
# --------------------------------------------------------------------------------------------------


class _Clock:
    now = 0.0


def _module(name: str, **attrs: object) -> ModuleType:
    mod = ModuleType(name)
    for key, value in attrs.items():
        setattr(mod, key, value)
    sys.modules[name] = mod
    if "." in name:
        parent_name, _, child = name.rpartition(".")
        parent = sys.modules.get(parent_name)
        if parent is not None:
            setattr(parent, child, mod)
    return mod


class _DataUpdateCoordinator:
    def __class_getitem__(cls, item):
        return cls

    def __init__(self, hass, logger, name, update_interval=None):
        self.hass = hass
        self.update_interval = update_interval
        self.data = None


class _UpdateFailed(Exception):
    pass


class _Store:
    def __init__(self, *args, **kwargs):
        pass

    async def async_load(self):
        return None

    async def async_save(self, data):
        return None


class _UtcNow:
    def timestamp(self):
        return _Clock.now


def _install_stubs() -> None:
    _module("homeassistant")
    _module("homeassistant.components")
    _module("homeassistant.components.http", StaticPathConfig=type("StaticPathConfig", (), {}))
    _module("homeassistant.config_entries", ConfigEntry=type("ConfigEntry", (), {}))
    _module(
        "homeassistant.const",
        EVENT_HOMEASSISTANT_STARTED="homeassistant_started",
        MATCH_ALL="*",
        Platform=SimpleNamespace(SENSOR="sensor"),
    )
    _module("homeassistant.core", HomeAssistant=type("HomeAssistant", (), {}), callback=lambda f: f)
    _module("homeassistant.helpers")
    _module("homeassistant.helpers.config_validation", config_entry_only_config_schema=lambda domain: {})
    _module("homeassistant.helpers.aiohttp_client", async_get_clientsession=lambda hass: None)
    _module("homeassistant.helpers.storage", Store=_Store)
    _module(
        "homeassistant.helpers.update_coordinator",
        DataUpdateCoordinator=_DataUpdateCoordinator,
        UpdateFailed=_UpdateFailed,
    )
    _module("homeassistant.loader", async_get_integration=None)
    _module("homeassistant.util")
    _module("homeassistant.util.dt", utcnow=lambda: _UtcNow())
    _module("aiohttp", ClientTimeout=lambda total=None: None)


# --------------------------------------------------------------------------------------------------
# JSON helpers
# --------------------------------------------------------------------------------------------------


def _clean(value):
    """Drop None-valued keys (the Kotlin side omits them) and turn sets/tuples into lists."""
    if isinstance(value, dict):
        return {k: _clean(v) for k, v in value.items() if v is not None}
    if isinstance(value, (list, tuple)):
        return [_clean(v) for v in value]
    if isinstance(value, (set, frozenset)):
        return sorted(_clean(v) for v in value)
    if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
        return str(value)
    return value


def _write(path: Path, payload) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(_clean(payload), indent=1, sort_keys=True) + "\n")


# --------------------------------------------------------------------------------------------------
# Static goldens
# --------------------------------------------------------------------------------------------------


def static_golden(fixtures: Path):
    from yahoo_fantasy_football import league_state, plays, yahoo_redzone as rz
    from yahoo_fantasy_football.yahoo_web import extract_league_id

    seed = json.loads((fixtures / "yahoo_redzone_2026_w1.json").read_text())
    stats_text = (fixtures / "yahoo_relay_stats_2026_w1.txt").read_text()
    name = rz.league_name(seed, LEAGUE_ID)

    leagues = {}
    for label, games_file, now in (
        ("w1", "yahoo_relay_games_2026_w1.txt", W1_NOW),
        ("w2_reset", "yahoo_relay_games_2026_w2_reset.txt", W2_RESET_NOW),
        ("no_games", None, W1_NOW),
    ):
        games_text = (fixtures / games_file).read_text() if games_file else ""
        data = rz.league_from_payloads(seed, stats_text, games_text, LEAGUE_ID, now)
        feed = plays.PlayFeed()
        scoreboard = league_state.scoreboard_attributes(data, feed, LEAGUE_ID, name)
        scoreboard.pop("source", None)
        leagues[label] = {
            "now": now,
            "games_file": games_file,
            "scoreboard": scoreboard,
            "nfl": league_state.nfl_games_attributes(data, LEAGUE_ID),
            "details": [
                {**league_state.player_rows(data, i), "week": data.week} for i in range(len(data.matchups))
            ],
            "poll_interval": league_state.poll_interval(data),
            "poll_interval_after_10": league_state.poll_interval(data, 10),
            "poll_interval_after_1800": league_state.poll_interval(data, 1800),
            "my_team": {
                team_id: league_state.find_team(data, team_id) for team_id in rz.team_choices(seed, LEAGUE_ID)
            },
            "live_clubs": data.live_clubs,
            "plays_feeds": data.plays_feeds,
            "live_tick": data.live_tick,
        }

    modifiers = rz.scoring_modifiers(seed["service"]["leagues"][LEAGUE_ID])
    stat_lines = []
    lines = list(rz.parse_relay_stats(stats_text).items()) + [
        (f"def:{k}", v) for k, v in rz.parse_relay_defense(stats_text).items()
    ]
    previous = {}
    for key, stats in lines:
        half = {k: math.floor(v / 2) for k, v in stats.items()}
        delta = rz.describe_delta(half, stats)
        back = rz.describe_delta(stats, half)
        stat_lines.append(
            {
                "key": key,
                "stats": stats,
                "score": rz.score(stats, modifiers),
                "stat_line": rz.stat_line(stats),
                "delta": delta,
                "delta_back": back,
                "short": rz.shorten_stat_delta(delta),
                "short_back": rz.shorten_stat_delta(back),
                "merged": rz.merge_stat_deltas(previous.get("delta", ""), delta),
                "merged_back": rz.merge_stat_deltas(delta, back),
            }
        )
        previous = {"delta": delta}

    play_sets = []
    for plays_file, players_file in (
        ("yahoo_relay_plays_26_2026_w1.txt", "yahoo_relay_players_2026_w1.txt"),
        ("yahoo_relay_plays_9_2026_w3.txt", "yahoo_relay_players_2026_w3.txt"),
    ):
        relay_plays = rz.parse_relay_plays((fixtures / plays_file).read_text())
        names = rz.parse_relay_players((fixtures / players_file).read_text())
        needed = frozenset(pid for p in relay_plays for pid in rz.play_ids_needed(p.text))
        short = plays.abbreviate_names(names, needed)
        play_sets.append(
            {
                "plays_file": plays_file,
                "players_file": players_file,
                "names": names,
                "short_names": short,
                "plays": [
                    {
                        "play_id": f"{p.game_key}.{p.sequence}",
                        "player_ids": list(p.player_ids),
                        "needed": list(rz.play_ids_needed(p.text)),
                        "text": rz.humanize_play(p.text, names),
                        "short_text": rz.humanize_play(p.text, short),
                        "situation": league_state.down_and_distance(p.down, p.distance, p.yards_to_goal),
                        "play_type": p.play_type,
                    }
                    for p in relay_plays
                ],
            }
        )

    names = [
        "Puka Nacua", "D.J. Reed", "C.J. Stroud", "Amon-Ra St. Brown", "Travis Etienne Jr.", "Ravens",
        "", "  Spaced   Out  ", "Kenny Moore II", "Ja'Marr Chase",
    ]
    extract = [
        "802904", " 802904 ", "https://football.fantasysports.yahoo.com/f1/802904",
        "https://football.fantasysports.yahoo.com/2025/f1/476807/matchup?week=13", "league 12345 please",
        "12", "", "abc",
    ]
    g_values = [0.0, 26.0, 26.000000000000004, 0.5, -3.0, 0.1 + 0.2, 1e-5, 1234567.0, 123456.0, 2.675, -0.0]
    return {
        "league_id": LEAGUE_ID,
        "league_name": name,
        "team_choices": rz.team_choices(seed, LEAGUE_ID),
        "modifiers": modifiers,
        "leagues": leagues,
        "stat_lines": stat_lines,
        "play_sets": play_sets,
        "abbreviate": {n: plays.abbreviate_name(n) for n in names},
        "extract": {e: extract_league_id(e) for e in extract},
        "g": [[v, f"{v:g}", f"{v:+g}", f"{v:.2f}", f"{v:+.2f}", round(v, 2)] for v in g_values],
        "clock_text": [
            [p, c, rz.clock_text(p, c)]
            for p, c in (("1", "15:00"), ("2", "0:00"), ("2", "00:00"), ("4", "0:12"), ("5", "8:11"), ("", "x"))
        ],
        "down_and_distance": [
            [d, ds, tg, league_state.down_and_distance(d, ds, tg)]
            for d, ds, tg in (("1", "10", "75"), ("2", "7", "19"), ("4", "6", "1"), ("3", "0", "0"), ("0", "10", "50"),
                              ("", "", ""), ("x", "1", "1"), ("5", "3", "40"))
        ],
        "erf": [[x, math.erf(x)] for x in (-3.0, -1.0, -0.25, 0.0, 0.1, 0.5, 1.0, 1.5, 1.99, 2.0, 2.5, 3.0, 4.5, 7.0)],
    }


# --------------------------------------------------------------------------------------------------
# The scenario: a scripted live game through the real coordinator
# --------------------------------------------------------------------------------------------------


def _relay_rows(text: str):
    header, rows = [], []
    for line in text.splitlines():
        (header if line.startswith("#") else rows).append(line)
    return header, rows


def build_polls(fixtures: Path):
    """Every poll's response bodies, by URL. Deterministic, so both engines see the same thing."""
    from yahoo_fantasy_football import yahoo_redzone as rz

    seed_body = (fixtures / "yahoo_redzone_2026_w1.json").read_text()
    players_body = (fixtures / "yahoo_relay_players_2026_w1.txt").read_text()
    stats_header, stats_rows = _relay_rows((fixtures / "yahoo_relay_stats_2026_w1.txt").read_text())
    plays_header, plays_rows = _relay_rows((fixtures / "yahoo_relay_plays_26_2026_w1.txt").read_text())
    games_header, games_rows = _relay_rows((fixtures / "yahoo_relay_games_2026_w1.txt").read_text())
    play_rows = [r for r in plays_rows if r.startswith("p|")]
    parsed = rz.parse_relay_plays("\n".join(play_rows))

    def game_row(after: int) -> str:
        """Game 26's `g` row as it stands once the first ``after`` plays have been run."""
        cells = next(r for r in games_rows if r.startswith("g|20260909026")).split("|")
        away = home = 0
        for p in parsed[:after]:
            if "touchdown" in p.text:
                if p.team_with_ball == cells[2]:
                    away += 7
                else:
                    home += 7
        # The situation after play N is the one play N+1 ran from.
        nxt = parsed[after] if after < len(parsed) else parsed[-1]
        cells[4] = "P"
        cells[6], cells[7] = nxt.period or cells[6], nxt.clock or cells[7]
        cells[8], cells[9] = str(away), str(home)
        cells[11], cells[12], cells[13], cells[14] = nxt.down, nxt.distance, nxt.yards_to_goal, nxt.team_with_ball
        return "|".join(cells)

    polls = []
    for k in range(POLLS):
        # One play every two polls (20 s, past pending_plays.REFINE_SECONDS), starting part-way into the
        # game so the touchdown falls inside the run, and in sync. On odd polls the games feed moves to
        # the result of the next play while its text is still out; on even polls the text lands.
        shown = min(len(play_rows), FIRST_PLAY + k // 2)
        ahead = min(len(parsed), FIRST_PLAY + (k + 1) // 2)
        revealed = min(len(stats_rows), (k + 1) * len(stats_rows) // POLLS)
        stats = list(stats_rows[:revealed])
        if k == 25 and len(stats) > 3:
            del stats[2]  # one stat row walks back for a poll: a correction, then it returns
        games = [game_row(ahead) if r.startswith("g|20260909026") else r for r in games_rows]
        bodies = {
            rz.redzone_url(LEAGUE_ID): seed_body,
            rz.relay_url("stats"): "\n".join(stats_header + stats) + "\n",
            rz.relay_url("games"): "\n".join(games_header + games) + "\n",
            rz.relay_url("players"): players_body,
            rz.relay_url("plays-26"): "\n".join(plays_header + play_rows[:shown]) + "\n",
        }
        polls.append({"now": W1_NOW - (POLLS - 1 - k) * POLL_SECONDS, "bodies": bodies})
    return polls


class _ScenarioServer:
    """Serves one poll's bodies; plays feeds answer If-Modified-Since by when the body first appeared."""

    def __init__(self):
        self.bodies = {}
        self.first_seen = {}
        self.poll = 0
        self.log = []

    def set_poll(self, index, bodies):
        self.poll = index
        self.bodies = bodies
        for url, body in bodies.items():
            if self.first_seen.get(url, (None, None))[1] != body:
                self.first_seen[url] = (f"poll-{index}", body)

    async def fetch(self, url):
        self.log.append(url)
        return self.bodies[url]

    async def fetch_if_modified(self, url, since):
        self.log.append(f"{url} since={since}")
        modified = self.first_seen[url][0]
        if since is not None and since == modified:
            return None, since
        return self.bodies[url], modified


def scenario_golden(fixtures: Path):
    from yahoo_fantasy_football import coordinator as coord_mod, league_state
    from yahoo_fantasy_football.plays import PlayFeed
    from yahoo_fantasy_football.redzone_client import RedzoneClient

    polls = build_polls(fixtures)
    server = _ScenarioServer()
    fired = []

    class _Bus:
        def async_fire(self, event_type, data):
            fired.append(data)

    class _Hass:
        bus = _Bus()

        def async_create_task(self, coro):
            coro.close()

    class _Entry:
        entry_id = "parity"
        data = {"league_id": LEAGUE_ID}

        def async_create_background_task(self, hass, coro, name):
            coro.close()  # the chase between polls is not part of the scenario
            return SimpleNamespace(done=lambda: True)

    coordinator = coord_mod.YahooFantasyCoordinator(_Hass(), _Entry())
    coordinator.client = RedzoneClient(server.fetch, LEAGUE_ID, fetch_if_modified=server.fetch_if_modified)
    coordinator.feed = PlayFeed()
    name = "Kush test league"

    out = []
    for index, poll in enumerate(polls):
        server.set_poll(index, poll["bodies"])
        _Clock.now = poll["now"]
        fired.clear()
        data = asyncio.run(coordinator._async_update_data())
        coordinator.data = data
        nfl_plays = asyncio.run(coordinator.async_game_plays("26", 12, now=poll["now"]))
        pending = coordinator.nfl_pending_row("26")
        if pending is not None:
            nfl_plays = [pending, *nfl_plays][:12]
        scoreboard = league_state.scoreboard_attributes(data, coordinator.feed, LEAGUE_ID, name)
        scoreboard.pop("source", None)
        out.append(
            {
                "now": poll["now"],
                "interval": int(coordinator.update_interval.total_seconds()),
                "fired": list(fired),
                "scoreboard": scoreboard,
                "nfl": league_state.nfl_games_attributes(
                    data, LEAGUE_ID, coordinator.nfl_last_plays, coordinator.nfl_pending_plays,
                    coordinator.nfl_score_banners,
                ),
                "history": [
                    league_state.play_dict(e) for e in coordinator.feed.recent(200, include_corrections=True)
                ],
                "detail_1": {**league_state.player_rows(data, 0), "week": data.week},
                "nfl_plays_26": nfl_plays,
            }
        )
    # Bodies repeat from poll to poll (the 180 KB seed every time); store each once.
    table, ids = {}, {}
    for poll in polls:
        for url, body in list(poll["bodies"].items()):
            if body not in ids:
                ids[body] = str(len(ids))
                table[ids[body]] = body
            poll["bodies"][url] = ids[body]
    return {"league_id": LEAGUE_ID, "league_name": name, "bodies": table, "polls": polls, "results": out}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--ha-repo", default="../ha-ffl-yahoo", type=Path)
    parser.add_argument("--out", default=Path(__file__).resolve().parent.parent / "core/src/test/resources/golden", type=Path)
    args = parser.parse_args()

    _install_stubs()
    sys.path.insert(0, str((args.ha_repo / "custom_components").resolve()))
    fixtures = (args.ha_repo / "tests/fixtures").resolve()

    _write(args.out / "static.json", static_golden(fixtures))
    _write(args.out / "scenario.json", scenario_golden(fixtures))
    print(f"wrote {args.out}/static.json and scenario.json")


if __name__ == "__main__":
    main()
