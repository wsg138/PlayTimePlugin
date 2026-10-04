# SPEAR work: numeral Discord roles

## NR-08/NR-09 retry follow-up (2026-10-04)

Spec: production test3 logged unavailable authoritative snapshots during three joins; the queue returns -1 after concurrent commits invalidate its three read attempts. This is deferred data, not a failed database query.
Prove: reproduce the missing transient classification against PR #27 before applying the existing local patch; record the actual result.
Engine: use a typed pending-snapshot outcome and a one-second quiet retry; keep failures at thirty seconds, preserve unrelated roles and newer pending requests.
Arch: retain DiscordSRV/JDA in the adapter, asynchronous reconciliation and eight-dispatch-per-second cap. Inspect runtime 1.30.5 against the provided 1.28.0 API; the separate provider-neutral migration is unmerged and is not silently pulled into this compatibility PR.
Refine: clean canonical-path Maven verification, exact-head CI/review inspection, and PR #27 delivery. Canonical main is a5c8619; local branches and production state are separate evidence.
Tooling: this PlayTime branch contains requirements/tasks/verification but no project EARS validator or state helper. No tooling pass is claimed; phases and evidence are recorded here.
Status: local spec/prove/engine/arch/refine checks complete; PR #27 delivery and hosted checks pending. User's current Git/SPEAR agreement supersedes older local-only delivery instructions. See verification.md for observed results.

1. **Spec:** Verify the active-playtime tier source, DiscordSRV account-link API, role ownership, and the confirmed highest-earned-only policy. Requirements NR-01 through NR-07.
2. **Prove:** Write focused tests for pure tier-to-role selection, link/unlink identity, stale-role cleanup, failed reads, and retry behavior before runtime wiring.
3. **Engine:** Add an opt-in DiscordSRV gateway and role reconciler; use current configured numeral thresholds and UUID-based links.
4. **Arch:** Wire tier advancement, account-link events, startup/reload reconciliation, and a bounded retry queue without running Discord API calls on the server thread.
5. **Refine:** Run focused tests, full clean build, packaging, and test-server checks with actual DiscordSRV, JDA permissions, Java and Bedrock links, restart, unlink, and outage recovery.

Current state: The server owner supplied 12 existing role IDs, mapped in order to I through z, and confirmed that linked players keep only their highest earned numeral role. The opt-in integration listens for DiscordSRV link/unlink events, requests sync on tier gain, sweeps linked accounts at startup and every five minutes, bounds dispatch to eight requests per second, retries failures, and persists unlink cleanup IDs across restart. Local build and test packaging pass; live DiscordSRV/JDA, Bedrock, role hierarchy, outage, and restart checks remain for the test server.
