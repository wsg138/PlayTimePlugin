# PlayTimePlugin Testing Checklist

## Status Summary
- Current workspace build status: `mvn -q -DskipTests package` passes.
- Plugin metadata version is aligned at `3.0.0` in both `pom.xml` and `plugin.yml`.
- The plugin is suitable to move into manual testing before wider release.
- The highest-risk areas to verify are suspicious streak behavior, reload/shutdown persistence, SQLite correctness, offline lookup behavior, and leaderboard/admin freshness under live updates.

## Known Acceptable Limitations
- Leaderboards do not merge buffered pending minute deltas directly into rank ordering. Ranking can lag behind live minute accrual by up to one flush interval.
- Suspicious detection is heuristic-based. It is designed to resist simple macro patterns, not to perfectly classify every borderline input pattern.
- Offline player lookup prefers local server/plugin-known identity data. It does not do remote profile lookups.
- `last_seen` accuracy for old existing databases should be validated on first startup after schema update.

## Recommended Testing / Release Approach
1. Test first on a fresh local SQLite database.
2. Test with at least:
   - one normal Java account
   - one Bedrock/Floodgate account if available
   - one operator/admin account
3. Run the checklist once on a clean startup.
4. Run the reload/restart/shutdown section separately after data exists.
5. Run the performance section with several players or simulated concurrent sessions before wider deployment.
6. Only move to server rollout after the suspicious rule, join/seen behavior, and persistence checks pass.

## Ordered Manual Testing Checklist
1. Startup and bootstrap
   - Start the server with a clean plugin data folder.
   - Confirm the plugin enables without stack traces.
   - Confirm `playtime.db` and `skins.yml` are created as expected.
   - Confirm commands register: `/playtime`, `/roman`, `/firstjoin`, `/seen`.
   - Confirm PlaceholderAPI registration only happens when PlaceholderAPI is installed and enabled in config.

2. Basic self commands
   - Join with a normal player and run `/playtime`.
   - If GUIs are enabled, confirm the main GUI opens.
   - Confirm self stats are shown correctly after at least 1-2 minutes.
   - Run `/roman` and `/playtime numerals`.
   - Confirm tier output reflects active minutes, not total minutes.

3. Permission behavior
   - Test a player with only `playtime.base`.
   - Confirm self `/playtime` works.
   - Confirm `/playtime <other>` is denied.
   - Grant `playtime.others` and confirm `/playtime <other>` works.
   - Confirm `/playtime admin` is denied without `playtime.admin.base`.
   - Confirm `/playtime admin reload` requires `playtime.admin.reload`.
   - Confirm `/playtime admin debug` requires `playtime.admin.debug`.
   - Confirm `/seen` requires `playtime.seen`.
   - Confirm `/firstjoin` requires `playtime.firstjoined`.

4. Main GUI behavior
   - Open `/playtime`.
   - Confirm the player head renders correctly.
   - Confirm the leaderboard button opens the leaderboard GUI.
   - Confirm close button works.
   - Confirm inventory item movement is blocked while the GUI is open.

5. Leaderboard GUI behavior
   - Open leaderboard from the main GUI.
   - Switch metrics: `TOTAL`, `ACTIVE`, `AFK`.
   - Switch ranges: `TODAY`, `7D`, `30D`, `ALL`.
   - Turn pages forward/back.
   - Confirm self card updates with the chosen range.
   - Confirm heads and names render for online and known offline players.
   - Confirm console `/playtime top ...` output matches expected ordering.

6. Admin GUI behavior
   - Open `/playtime admin` as an authorized player.
   - Confirm the players screen opens and filters work for `ALL`, `ACTIVE`, `IDLE`, `AFK`, `SUSPICIOUS`.
   - Confirm session length values update plausibly.
   - Confirm the server activity screen loads without SQL errors.
   - Confirm range switching in the server activity view works.
   - Confirm back/close navigation works throughout.

7. First join / seen / offline lookup
   - On a clean database, join with a never-seen player.
   - Confirm first-join broadcast/player message/ping fire exactly once.
   - Run `/firstjoin <player>` and confirm the timestamp matches the plugin’s stored first join.
   - Disconnect the player and run `/seen <player>`.
   - Confirm `/seen` shows the quit time, not the join time.
   - Rejoin and disconnect again; confirm `/seen` updates.
   - Test lookup by exact online name.
   - Test lookup after logout using Bukkit cached offline player data.
   - Test lookup after logout using only plugin-known cached head/name data if possible.

8. Suspicious streak rule
   - Trigger normal activity and confirm minutes count as expected.
   - Trigger a suspicious-only pattern for at least 12 consecutive minutes if possible.
   - Confirm suspicious minutes count for minutes 1 through 10.
   - Confirm suspicious minutes stop counting after the 10th consecutive suspicious minute.
   - Confirm the debug log message appears only when the threshold is reached if debug logging is enabled.
   - Perform definite non-suspicious activity after the threshold:
     - movement/aim change
     - chat or command if enabled
     - block placement
     - teleport/world change
   - Confirm the suspicious streak resets and suspicious counting can start again from minute 1.

9. Storage correctness
   - Verify lifetime totals, daily totals, and joins continue to accumulate correctly across relogs.
   - Confirm `first_join`, `last_join`, and `last_seen` behave distinctly:
     - `first_join` should never move after first record
     - `last_join` should move only on join
     - `last_seen` should move on quit/shutdown
   - Confirm join retention purge behavior if `joins.retention-days` is set to a positive value.

## Edge Cases To Verify
- Player joins during a flush interval and immediately runs `/firstjoin` or `/seen`.
- Player disconnects immediately after accumulating a minute.
- Player disconnects while suspicious-count blocking is active.
- Player changes worlds while in a suspicious streak.
- Plugin reload while players are online and in different states.
- Server stop while players are online.
- Empty database behavior for leaderboards/admin screens.
- Invalid configured first-join sound name.
- Missing optional integrations: PlaceholderAPI absent, Floodgate absent.

## Reload / Restart / Shutdown Checks
1. Safe reload
   - With players online, run `/playtime admin reload`.
   - Confirm no duplicate action bars, listeners, or GUI oddities appear after reload.
   - Confirm commands still work after reload.
   - Confirm playtime keeps accruing after reload.
   - Confirm suspicious streaks do not incorrectly persist through reload.
   - Confirm buffered writes before reload are not lost.

2. Restart
   - Stop and restart the server after players have recorded data.
   - Confirm stats persist.
   - Confirm offline heads still render after restart.
   - Confirm Bedrock player heads still render if applicable.

3. Shutdown
   - Stop the server while players are online.
   - Confirm no flush errors appear during shutdown.
   - Restart and verify:
     - prior playtime is intact
     - `/seen` reflects shutdown-time quit/last-seen behavior reasonably
     - no suspicious streak state carried over

## Integration Checks
- PlaceholderAPI
  - Verify placeholders:
    - `%playtime_total%`
    - `%playtime_total_formatted%`
    - `%playtime_active%`
    - `%playtime_afk%`
    - `%playtime_state%`
    - `%playtime_roman%`
    - range variants such as `%playtime_total_today_formatted%`
- Floodgate / Geyser
  - Verify Bedrock player detection does not break GUIs.
  - Verify Bedrock player heads/names display correctly in playtime and admin GUIs.
- ProtocolLib
  - Confirm presence/absence does not affect normal function.

## Performance / Hot-Path Checks
- With several concurrent players online, confirm no obvious lag spikes every minute.
- Watch TPS/MSPT around:
  - minute tick crediting
  - leaderboard GUI opens
  - admin activity screen opens
  - `/playtime admin reload`
- Confirm SQLite write batching prevents visible lag during normal accumulation.
- Confirm action bar updates do not spam errors or duplicate scheduling after reloads.
- Confirm GUI opens do not trigger slow offline profile lookups for already-known players.

## Final Release Decision
- Release to testing if all of the following are true:
  - no enable/reload/shutdown exceptions
  - suspicious 10-minute rule behaves exactly as intended
  - `/playtime <other>` permission behavior is correct
  - `/firstjoin` and `/seen` use plugin-owned data correctly
  - buffered writes survive reload/shutdown cleanly
  - leaderboards/admin views remain stable under live use
- Hold release if any of the following fail:
  - `last_join` or `last_seen` metadata is wrong
  - suspicious streak fails to reset when legitimate activity resumes
  - reload loses buffered data or duplicates runtime behavior
  - admin or leaderboard screens produce SQL or concurrency errors
