# `/seen` username history verification

## Local SPEAR evidence

- Spec: `SEEN-01` through `SEEN-06` in `requirements.md`.
- Prove: focused test initially failed to compile because history query methods were absent.
- Engine: history schema, migration, atomic profile writes, recovery writes, async `/seen` reads, and visibility handling added.
- Arch: existing `player_profiles` current name and UUID identity retained; name history stores only server-observed names.
- Refine: focused integration tests cover legacy profile backfill, rename, name reuse, name ambiguity, and idempotent recovery replay. The existing tier initialization test was updated to exercise the documented retry path when a concurrent writer invalidates its SQL snapshot under full-suite load. Final `mvn clean verify` passed: 199 tests, zero failures.

## Test server checklist

1. Back up the test server's Playtime database and replace the plugin JAR during a full stop.
2. Run `/seen` for an online player, offline player, unknown name, and a player with no rename history.
3. Join with a renamed Java account, restart, then query both current and previous names. Confirm both resolve to one UUID and prior names are shown without inventing pre-migration names.
4. Repeat with a Floodgate player. Check that a Java and Bedrock player with similar names retain separate UUID records.
5. Check a vanished player from a viewer who cannot see them, and check `/seen` from console.
6. Test with a temporary database outage; `/seen` should show temporary unavailability, and a later retry should succeed.

Local tests use SQLite and MockBukkit. MySQL schema and live Paper, Floodgate, and vanish integrations still require test server validation.
