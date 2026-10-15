# DiscordSRV retirement: PlayTime numeral roles

Tracking: PlayTimePlugin #29. Umbrella: EnthusiaStaff #264. Shared managed-role contract: EnthusiaStaff #266. Managed-role provider/shadow runtime: EnthusiaStaff #342.

## Provider-neutral checkpoint

The tested PR #27 numeral policy remains authoritative: active playtime selects one highest-earned numeral tier, all twelve configured role IDs remain opt-in, and startup/periodic/join/tier/link reconciliation continues to retry until it converges.

Application orchestration now depends on `NumeralRoleProvider` and the opaque `NumeralRoleAccountRef` DTO. DiscordSRV link events, account-link lookups, shaded JDA types, guild/member access, role mutation and UNKNOWN_MEMBER handling are confined to `DiscordSrvNumeralRoleProvider`.

The legacy DiscordSRV adapter remains the active mutation implementation during SHADOW. EnthusiaStaff #266 (provider-neutral contract) and #342 (Paper provider + StaffBot shadow runtime) are merged. This branch publishes complete desired Minecraft membership snapshots to that merged runtime while DiscordSRV continues the live writes. No production writer cutover or DiscordSRV removal is authorized by this checkpoint.

## Multi-linked identities

A provider identity is reconciled from all Minecraft UUIDs currently linked to it. PlayTime reads authoritative active minutes for every linked UUID and applies the highest effective numeral tier. An unlink therefore does not remove a numeral role when another linked Minecraft UUID already establishes that tier. Failed authoritative reads and a membership snapshot that changes while effective playtime is being calculated fail before role mutation and are retried.

The compatibility adapter does not attempt to make DiscordSRV link state and an asynchronous Discord role read atomic. The final Staff contract removes that transport-time race structurally: PlayTime publishes complete desired Minecraft membership snapshots and the platform resolves canonical links before applying Discord state.

## Next contract step

Current SHADOW implementation publishes complete desired membership snapshots through the shared managed-role client for namespace `playtime-numerals`, compiled against the exact merged #342 runtime commit. The remaining step is production parity evidence before any writer cutover; do not introduce a separate PlayTime Discord transport.

## SHADOW failure isolation (October 15, 2026)

The opt-in numeral SHADOW observer is never a live Discord role writer. Periodic startup scheduling, provider discovery, authoritative membership snapshots, task submission, and asynchronous publication can fail without interrupting ordinary PlayTime behavior or permanently retaining its in-flight scan flag. A failed preparation/read sends **no** partial role snapshot. Failure warnings report a sanitized stage and exception class, not arbitrary downstream exception text, UUIDs, Discord role IDs, or secrets.

Failed, unstarted, unavailable-provider or skipped scans are **not** parity evidence. Confirm repeated complete twelve-tier snapshots (including empty tiers), exact saved DiscordSRV role IDs, canonical linked-account union, and zero unexplained drift in an authorized non-production environment while DiscordSRV remains the live writer. No SHADOW result authorizes a Discord role mutation, D13 cutover, or DiscordSRV removal.
