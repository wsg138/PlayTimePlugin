# Numeral Discord roles: current evidence and test plan

Tracking: PlayTimePlugin #29. Umbrella architecture: EnthusiaStaff #264. Shared managed-role contract: EnthusiaStaff #266. Provider/shadow runtime: EnthusiaStaff #342.

## Verified repository evidence

- The tested PR #27 implementation remains the policy/configuration baseline: authoritative active playtime selects one highest-earned numeral tier and all twelve configured role IDs remain opt-in.
- Application orchestration now depends on the public provider-neutral `NumeralRoleProvider` boundary and opaque `NumeralRoleAccountRef` DTO. DiscordSRV account-link events/lookups and shaded JDA role mutations are isolated in `DiscordSrvNumeralRoleProvider`.
- `NumeralRoleSyncService` reconciles the effective highest tier across every Minecraft UUID currently linked to the same provider identity. Unlink cleanup keeps a numeral role when another linked UUID already establishes that effective tier.
- Failed authoritative playtime reads, unavailable provider state, and membership snapshots that change while effective playtime is calculated fail before role mutation so queued work can retry rather than infer a destructive state.
- Focused tests cover successful reconciliation, unavailable provider/read failure, unlink cleanup, serialized duplicate work, stale-link snapshots, zero-minute tiers, idempotent duplicate reconciliation, and multiple Minecraft UUIDs sharing one provider identity.
- Versioned pending-work state prevents an older success or retry completion from erasing a newer request for the same player/account.
- Pending unlink reconciliation survives restart and accepts the legacy persisted Discord-ID key during migration.
- The configuration remains disabled by default and rejects incomplete role ID mappings when enabled.
- The migration PR CI runs `mvn --batch-mode --no-transfer-progress -DreuseForks=false clean verify`, validates the packaged plugin JAR, and prepares the Sentinel regression artifact. The exact migration head must remain green before review handoff.

## Compatibility checkpoint

DiscordSRV 1.28.0 remains a provided soft dependency only for the legacy mutation provider during SHADOW. EnthusiaStaff #266 and #342 are merged, and CI compiles this branch against exact merged #342 commit `d978c32e665f72ab0a81ec0c035f8f9dfc3117e7`. PlayTime now dual-publishes provider-neutral complete desired membership snapshots while DiscordSRV remains authoritative for live mutations. No PlayTime-specific replacement Discord transport is introduced here. No production writer cutover or DiscordSRV removal is part of this checkpoint.

The compatibility adapter intentionally does not claim atomicity between DiscordSRV link state and an asynchronous Discord role read. The final Staff complete-snapshot contract is the architectural fix for that transport-time race.

## Remaining verification before final platform cutover

- Deploy this dual-publication build with SHADOW enabled alongside the merged Staff #342 runtime and collect repeated complete zero-unexplained-drift parity scans for namespace `playtime-numerals` before any writer cutover.
- Exercise Java/Floodgate link and unlink behavior against the shared canonical identity service, including one Discord account associated with multiple Minecraft UUIDs.
- Exercise restart, provider outage/recovery, stale identity data, role hierarchy failures, missing remote members, duplicate reconciliation, and rate limiting on a test environment before production readiness is claimed.
