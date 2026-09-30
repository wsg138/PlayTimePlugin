# DiscordSRV retirement: PlayTime numeral roles

Tracking: PlayTimePlugin #29. Umbrella: EnthusiaStaff #264. Shared managed-role contract: EnthusiaStaff #266.

## Provider-neutral checkpoint

The tested PR #27 numeral policy remains authoritative: active playtime selects one highest-earned numeral tier, all twelve configured role IDs remain opt-in, and startup/periodic/join/tier/link reconciliation continues to retry until it converges.

Application orchestration now depends on `NumeralRoleProvider` and the opaque `NumeralRoleAccountRef` DTO. DiscordSRV link events, account-link lookups, shaded JDA types, guild/member access, role mutation and UNKNOWN_MEMBER handling are confined to `DiscordSrvNumeralRoleProvider`.

The legacy adapter remains the active compatibility implementation while the shared Enthusia managed-role contract is still a draft. No production cutover or DiscordSRV removal is authorized by this checkpoint.

## Multi-linked identities

A provider identity is reconciled from all Minecraft UUIDs currently linked to it. PlayTime reads authoritative active minutes for every linked UUID and applies the highest effective numeral tier. An unlink therefore does not remove a numeral role when another linked Minecraft UUID still establishes that tier. If any authoritative read or linked-membership snapshot is unavailable/stale, reconciliation fails before role mutation and is retried.

## Next contract step

Once EnthusiaStaff #266 stabilizes, replace the compatibility adapter with the shared managed-role client and move from opaque per-identity reconciliation to complete desired membership snapshots for namespace `playtime-numerals`. Do not introduce a separate PlayTime Discord transport.
