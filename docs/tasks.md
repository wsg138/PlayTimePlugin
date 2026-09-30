# SPEAR work: numeral Discord roles

1. **Spec:** Keep authoritative active-playtime/highest-earned numeral policy in PlayTime and preserve the twelve configured numeral tiers. Requirements NR-01 through NR-08.
2. **Prove:** Keep focused tests for tier selection, link/unlink identity, failed reads, stale membership, duplicate reconciliation, restart persistence, and multi-linked-account behavior.
3. **Boundary:** Route numeral orchestration through a provider-neutral contract. DiscordSRV-specific account-link events/lookups and JDA mutations belong only in the temporary compatibility adapter.
4. **Orchestrate:** Keep startup/periodic/join/link/tier reconciliation, bounded retry/idempotency, and persisted unlink cleanup independent of provider transport.
5. **Platform cutover:** Once EnthusiaStaff #266 stabilizes, replace the compatibility provider with the shared managed-role client and publish complete desired membership snapshots for namespace `playtime-numerals`. Do not create a PlayTime-specific Discord transport.
6. **Verify:** Require `mvn clean verify`, packaging/Sentinel checks, hosted static analysis, and test-environment checks before any production cutover.

Current checkpoint: `integration/enthusia-discord-platform` preserves PR #27 behavior while isolating DiscordSRV behind `DiscordSrvNumeralRoleProvider`. One provider identity is reconciled from all currently linked Minecraft UUIDs, using the highest authoritative active-playtime tier. Staff #266 remains the dependency for the final shared transport/identity implementation; no production deployment or automatic merge is authorized by this checkpoint.
