# Numeral Discord roles: current evidence and test plan

The Google task-list entry is under the Playtime section of the Enthusia SMP master task list.

## Local evidence

- Requirements NR-01 through NR-06 recorded before implementation.
- `NumeralRolePolicyTest`, `NumeralRoleSyncServiceTest`, and `NumeralDiscordConfigTest` each failed to compile before their respective implementation was added, then passed.
- `DiscordSrvNumeralGateway` compiles against DiscordSRV 1.28.0 as a provided soft dependency.
- The config is disabled by default and rejects incomplete role ID mappings when enabled.
- The twelve supplied IDs are mapped in order from I through z and checked by a resource configuration test.
- A clean `mvn verify` completed with 210 tests, 0 failures, 0 errors, and 0 skipped after addressing CodeRabbit's seven review findings. Added checks for per-account unlink ordering, zero-hour tier cleanup, unknown-member responses, and dotted tier labels. The existing tier-initialization race test was made to exercise its retry path under suite load; the same fix is already present in the separate `/seen` work.

## Remaining verification

- The server owner confirmed highest-earned-only mode and supplied the configured role IDs. The feature remains disabled until enabled on the test server.
- Exercise the live checks below before claiming production readiness.

## Test server checks

- Link and unlink Java and Floodgate accounts; confirm role ownership follows UUID rather than username.
- Advance active minutes across numeral thresholds; confirm the highest earned role replaces lower managed numeral roles.
- Restart during queued role changes and Discord outage; confirm eventual convergence without removing unrelated roles.
- Check missing role IDs, bot role hierarchy, Discord member absence, and rate limits.
