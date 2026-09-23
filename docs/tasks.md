# SPEAR work: numeral Discord roles

1. **Spec:** Verify the active-playtime tier source, DiscordSRV account-link API, role ownership, and the confirmed highest-earned-only policy. Requirements NR-01 through NR-07.
2. **Prove:** Write focused tests for pure tier-to-role selection, link/unlink identity, stale-role cleanup, failed reads, and retry behavior before runtime wiring.
3. **Engine:** Add an opt-in DiscordSRV gateway and role reconciler; use current configured numeral thresholds and UUID-based links.
4. **Arch:** Wire tier advancement, account-link events, startup/reload reconciliation, and a bounded retry queue without running Discord API calls on the server thread.
5. **Refine:** Run focused tests, full clean build, packaging, and test-server checks with actual DiscordSRV, JDA permissions, Java and Bedrock links, restart, unlink, and outage recovery.

Current state: The server owner supplied 12 existing role IDs, mapped in order to I through z, and confirmed that linked players keep only their highest earned numeral role. The opt-in integration listens for DiscordSRV link/unlink events, requests sync on tier gain, sweeps linked accounts at startup and every five minutes, bounds dispatch to eight requests per second, retries failures, and persists unlink cleanup IDs across restart. Local build and test packaging pass; live DiscordSRV/JDA, Bedrock, role hierarchy, outage, and restart checks remain for the test server.
