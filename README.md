# EnthusiaPlaytime

[![Codacy Badge](https://app.codacy.com/project/badge/Grade/fe6ce9077a634202a86875a3ac3b39e4)](https://app.codacy.com/gh/wsg138/PlayTimePlugin/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)

EnthusiaPlaytime is Enthusia SMP's playtime, activity-state, leaderboard, and first-join tracking system. It separates time into **total**, **active**, and **AFK/non-active** time rather than treating every connected minute as active play.

## Player-facing behavior on Enthusia

### Playtime and activity

The server samples player activity once per second.

- A player is considered **idle** after 60 seconds without recognized activity.
- A player is considered **AFK** after 5 minutes without recognized activity.
- Idle and AFK minutes are both stored in the AFK/non-active playtime bucket rather than active time.
- Movement, interactions, combat-related actions, block actions, chat, and commands can count as activity.
- The current Enthusia configuration shows an `AFK` action-bar indicator after the full AFK threshold. Active and idle indicators are hidden.

`/playtime` (alias `/pt`) opens a GUI showing the player's lifetime total, active, and AFK time. The GUI has a separate Bedrock-friendly layout for players joining through Geyser/Floodgate.

### Automation-resistant active time

Active playtime is deliberately harder to farm with simple repetitive input. The activity system looks for patterns such as unusually regular clicking, repetitive rotation, repeating movement cycles, and repeated action sequences.

A suspicious pattern is **not a punishment or anti-cheat verdict**. It only controls whether the behavior continues to earn active playtime.

On the current Enthusia configuration:

- the first 10 consecutive suspicious minutes can still count as active time;
- additional continuous suspicious time stops earning active credit;
- legitimate varied activity can clear the suspicious state;
- disconnecting does not instantly reset an active suspicious streak; detector state can survive a short reconnect window.

This prevents trivial AFK/automation loops from indefinitely advancing active-playtime progression while giving normal repetitive gameplay some tolerance.

## Leaderboards

Players can browse playtime leaderboards through the GUI or with:

```text
/playtime top [active|afk|total] [today|7d|30d|all] [page]
```

Leaderboards can be switched between:

- **Total** playtime
- **Active** playtime
- **AFK/non-active** playtime

and between:

- **Today**
- **Last 7 days**
- **Last 30 days**
- **All time**

The GUI shows player heads, ranks, all three time totals, the viewer's own statistics, and next/previous page controls. The production configuration also exports cached public leaderboard data for the Enthusia website.

## Playtime numeral tiers

`/roman`, `/numerals`, or `/playtime numerals` shows Enthusia's playtime numeral progression.

**Numeral tiers are based on active playtime**, not total connected time.

| Tier | Active playtime required |
| --- | ---: |
| I | 1 hour |
| II | 8 hours |
| III | 20 hours |
| IV | 45 hours |
| V | 90 hours |
| VI | 170 hours |
| VII | 320 hours |
| VIII | 580 hours |
| IX | 1,090 hours |
| X | 2,000 hours |
| Y | 5,000 hours |
| Z | 15,000 hours |

The current server configuration announces tier-ups publicly when a player reaches a new numeral tier.

## First joins

The plugin owns Enthusia's first-join record and welcome broadcast.

When a player joins for the first time, the current SMP configuration broadcasts their username together with their unique join number.

Players can inspect first-join information with:

```text
/firstjoin
/firstjoin <player>
```

`/firstjoin` also has the alias `/fj`. The result includes the recorded date/time and a readable "time ago" value.

## Last seen

The plugin also records last-seen times. Where the `playtime.seen` permission is granted:

```text
/seen <player>
```

reports whether the player is online or, if offline, their last recorded time and how long ago it was. This permission is not granted to ordinary players by default.

## Commands

```text
/playtime
/pt
/playtime <player>
/playtime top [active|afk|total] [today|7d|30d|all] [page]
/playtime numerals
/roman
/numerals
/rn
/firstjoin [player]
/fj [player]
/seen [player]
```

Viewing another player's ordinary `/playtime <player>` data requires `playtime.others`; viewing your own data and leaderboards uses the normal base permission.

Administrative playtime tools exist under `/playtime admin`, but they are intentionally not part of the player feature set.

## PlaceholderAPI

When PlaceholderAPI is present, the expansion identifier is `playtime`.

Useful player placeholders include:

```text
%playtime_total%
%playtime_total_formatted%
%playtime_active%
%playtime_active_formatted%
%playtime_afk%
%playtime_afk_formatted%
%playtime_session%
%playtime_session_formatted%
%playtime_state%
%playtime_roman%
%playtime_roman_colored%
%playtime_roman_mm%
```

Range placeholders use the form:

```text
%playtime_<metric>_<range>%
%playtime_<metric>_<range>_formatted%
```

where metric is `total`, `active`, or `afk`, and range is `today`, `7d`, `30d`, or `all`.

Top-player placeholders are also available up to the configured public leaderboard limit:

```text
%playtime_top_<metric>_<range>_<rank>_<name|uuid|value|formatted>%
```

## Integrations

Optional integrations include:

- PlaceholderAPI for player and leaderboard placeholders
- Plan for server/player analytics
- Geyser/Floodgate detection for Bedrock-friendly GUI layouts
- public leaderboard export for website use

The plugin supports both SQLite and MySQL-backed storage. Enthusia's production deployment currently uses the networked database option; credentials and connection details are deliberately not documented here.

## Legacy rewards configuration

Some existing configuration files may still contain a `rewards:` / milestone section from an older version of the plugin. **The current code has removed the rewards system and does not use that section.** Do not treat those old milestone entries as active Enthusia rewards unless the feature is deliberately reintroduced in code.

## Build

- Java 21
- Maven
- Paper/Leaf API target for Minecraft 1.21.x

```powershell
mvn -q -DskipTests package
```
