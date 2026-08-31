# Enthusia Sentinel staging

EnthusiaPlaytime uses the existing private `wsg138/EnthusiaStaff-Staging` / Enthusia Sentinel service for disposable Paper acceptance tests. Do not create another staging controller, GitHub App, or database-testing service for this plugin.

## Target artifact

The Java 21 Maven build publishes a stable exact-head Actions artifact:

- artifact: `enthusiaplaytime-plugin`
- plugin JAR inside artifact: `target/EnthusiaPlaytime.jar`
- legacy config fixture: `fixtures/EnthusiaPlaytime-config.yml`
- legacy SQLite fixture: `fixtures/EnthusiaPlaytime-playtime.db`

The SQLite file is generated during CI from `scripts/create-sentinel-playtime-fixture.py`. It is disposable test data only. Sentinel copies it into an isolated Paper sandbox and never receives a production PlayTime database.

## Regression purpose

The database fixture contains established PlayTime canary rows while `lifetime_agg` intentionally predates the `last_seen` column. If the private Sentinel policy is later expanded to authorize the `database` profile, the existing fixture can be used to prove that the real plugin can open an established SQLite installation, preserve the original database rather than create a replacement, create its rolling pre-migration backup, add/backfill `lifetime_agg.last_seen`, keep declared tables and canaries intact, shut down cleanly, and start again against the same migrated database.

The config fixture is intentionally sparse and uses `config-version: 3` with `sampling.afk-seconds: 777`. It is retained for future config-profile acceptance if those profiles are explicitly authorized in the private control-plane policy.

## Authorized profile surface

The committed production Sentinel policy currently authorizes PlayTime for the manual `restart` profile only, so `.enthusia-test.yml` intentionally declares only `restart`. Sentinel's strict manifest validator requires every profile declared by an untrusted repository manifest to also be authorized by the trusted control-plane policy. Repository-side fixtures for broader config/database coverage do not grant execution permission and remain inert until both the trusted policy and manifest are deliberately expanded together.

For the current automation-detection change, require a successful `restart` acceptance on the exact unchanged PR head after ordinary CI passes. MockBukkit/unit tests are useful but do not replace this disposable Paper acceptance test.

Record the exact target SHA, successful build workflow/artifact, Sentinel command/profile, job/result, and cleanup evidence. Do not treat queued, stale, cancelled, rejected, or wrong-SHA runs as passes.

Canonical controls and onboarding procedure live in `wsg138/EnthusiaStaff-Staging/docs/repository-onboarding.md` and the current Sentinel manifest/command documentation.
