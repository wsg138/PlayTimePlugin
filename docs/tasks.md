# SPEAR work: numeral Discord roles

1. **Spec:** Keep authoritative active-playtime/highest-earned numeral policy in PlayTime and preserve the twelve configured numeral tiers. Requirements NR-01 through NR-08.
2. **Prove:** Keep focused tests for tier selection, link/unlink identity, failed reads, stale membership, duplicate reconciliation, restart persistence, and multi-linked-account behavior.
3. **Boundary:** Route numeral orchestration through a provider-neutral contract. DiscordSRV-specific account-link events/lookups and JDA mutations belong only in the temporary compatibility adapter.
4. **Orchestrate:** Keep startup/periodic/join/link/tier reconciliation, bounded retry/idempotency, and persisted unlink cleanup independent of provider transport.
5. **SHADOW parity:** Publish complete desired membership snapshots through the merged Enthusia managed-role client for namespace `playtime-numerals` while DiscordSRV remains the live writer. Require repeated complete zero-unexplained-drift StaffBot scans before any writer cutover. Do not create a PlayTime-specific Discord transport.
6. **Verify:** Require `mvn clean verify`, packaging/Sentinel checks, hosted static analysis, and test-environment checks before any production cutover.

Current checkpoint: `integration/enthusia-discord-platform` preserves PR #27 behavior while isolating DiscordSRV behind `DiscordSrvNumeralRoleProvider` and dual-publishing complete desired state to the merged Enthusia managed-role runtime (#266 + #342). One provider identity is reconciled from all currently linked Minecraft UUIDs, using the highest authoritative active-playtime tier. No production writer cutover or DiscordSRV removal is authorized by this checkpoint.
