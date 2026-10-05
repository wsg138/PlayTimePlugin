# SPEAR: stable playtime displays

## Spec

- DISPLAY-01: When a loaded player cache is invalidated by minute accrual or a join, the last loaded lifetime and range totals shall remain available while asynchronous refresh runs.
- DISPLAY-02: When leaderboard caches are invalidated, loaded rows shall remain visible during refresh rather than changing to the configured fallback.
- DISPLAY-03: If an invalidation occurs while a read is running, the obsolete read shall not publish over that invalidation or resurrect an evicted entry.
- DISPLAY-04: If SQL fails, the refresh shall retain its known values and allow retry. A successful empty result shall remain distinguishable from failure.
- DISPLAY-05: Placeholder requests shall remain free of SQL, caches shall retain their configured bounds, and a normal asynchronous read shall not count as an audit repair.

## Prove

Before implementation, `PlaytimeDisplayRefreshTest` reproduced three failures:

1. A player with 5,000 minutes and one pending minute returned only one minute after invalidation.
2. Global join invalidation discarded the loaded player's lifetime value.
3. An invalidated in-flight read published an obsolete two-minute value.

Evidence: workspace `playtime-display-red.log`. Subsequent tests cover retained leaderboard rows, SQL failure and retry, successful empty results, shared-generation invalidation, and existing cache bounds.

## Engine

Invalidation now expires loaded entries instead of deleting their values. Per-entry revisions and shared generations guard asynchronous publication. Concurrent cache misses use `computeIfAbsent`, so requests share one refresh flag. Strict repository range and leaderboard reads propagate SQL failure; lifetime refresh uses the existing strict status. Existing repository APIs retain their fallback contracts for other callers.

## Arch

Holograms, scoreboard placeholders, and numeral placeholders all use the same read service. No renderer-specific changes or stored playtime migration is required. Numerals continue to use active minutes. Cache capacity and background SQL remain intact. The runtime audit no longer invalidates player caches merely because any display query is loading.

Cold caches still require their first asynchronous read. A refresh can serve a stale display temporarily; authoritative accrual, reward, and tier initialization paths are unchanged.

## Refine

The initial complete suite encountered the existing tier-initialization concurrency test timeout at `firstMainScheduled`; its focused rerun passed all four tests. The test now invokes the production retry path when concurrent flushes invalidate the initial SQL attempts, while still requiring 60 active minutes and exactly one announcement.

Final `mvn clean verify` passed 203 tests with zero failures, errors, or skips. Evidence: workspace `playtime-display-verify3.log`. The shaded JAR is `target/playtime-plugin-3.7.2.jar`; the test copy is `EnthusiaPlaytime-3.7.2-display-stability-test1.jar` in the workspace parent directory.

### Test server acceptance

1. Replace the JAR during a full server stop, then start normally.
2. Watch a known player's total/active formatted placeholders and roman prefix through minute accrual and a queue flush.
3. Join a second account while watching the first account's scoreboard and TAB prefix.
4. Watch spawn top-playtime holograms and GUI leaderboard while players join and accrue minutes.
5. Temporarily interrupt the test database connection after values have loaded, then restore it; loaded values should remain and refresh should recover.

Live hologram, scoreboard, TAB, and database-outage acceptance remains to be performed on the test server.
