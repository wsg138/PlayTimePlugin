# Numeral Discord roles: current evidence and test plan

The Google task-list entry is under the Playtime section of the Enthusia SMP master task list.

## Local evidence

### Retry follow-up, 2026-10-04

- Isolated branch from canonical main a5c8619 plus existing PR #27 head 6cffe2b; both remote refs rechecked before delivery. Original dirty test checkout preserved.
- Actual red: the service regression ran six tests against PR #27, one assertion failure because the unavailable snapshot was a generic IllegalStateException instead of SnapshotPendingException. This is behavioral proof, not a compilation failure.
- Green: canonical `mvn clean verify` passed 218 tests, zero failures/errors/skips. Deterministic coordinator tests cover repeated one-second quiet deferral, eventual success, thirty-second logged real failures, and a newer request surviving an older failed attempt. Service tests cover no role mutation on deferral and correct recovery.
- Runtime contract: downloaded official DiscordSRV 1.30.5 via the existing scarsz Maven repository; a temporary POM changing only the provided DiscordSRV version passed another clean verify with the same 218 tests. The tracked POM remains at its established 1.28.0 compile profile. No new Discord API calls are introduced.
- Architecture: typed transient outcome stays in the reconciliation service; DiscordSRV/JDA remain adapters. Eight dispatches per second, UUID account identity, unlink persistence and configured role ownership are preserved.
- Existing PR review's seven actionable findings are already addressed in its prior commits; inspected all seven resolved threads against current code. The separate unmerged provider-neutral branch 39c30c8 changes the coordinator and service contracts and still has the generic failure; its future integration must port these retry requirements and tests rather than overwrite the fix.
- This branch has no EARS/state helper. Requirements and phase evidence are maintained in docs; no helper execution is claimed.
- Exact-head Codacy review identified a missing exception serialVersionUID and five repeated-literal findings in existing PR tests. Added the serial ID and shared fixture constants; canonical clean verify still passes 218 tests. Hosted build requires maintainer workflow approval; the final Codacy result is checked after pushing this refinement.
- Network audit: canonical enthusia-network pins playtime-plugin to 2a5b57d and enthusia-tags to 36bd6c5. After component merges, a separate pin PR and combined build are required before network deployment.
- Local builds are unmerged test artifacts. No production/staging upload or activation in this follow-up. Hosted exact-head checks and live Discord outage/recovery remain distinct gates.

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
