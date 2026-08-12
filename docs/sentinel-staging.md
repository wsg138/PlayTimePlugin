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

The database fixture contains established PlayTime canary rows while `lifetime_agg` intentionally predates the `last_seen` column. A successful Sentinel `database` profile therefore proves that the real plugin can open an established SQLite installation, preserve the original database rather than create a replacement, create its rolling pre-migration backup, add/backfill `lifetime_agg.last_seen`, keep all declared tables and canaries intact, shut down cleanly, and start again against the same migrated database.

Sentinel independently rejects unexpected database replacement, unsafe SQLite objects, malformed databases, leftover journal/WAL state, row-count changes outside the manifest contract, and canary changes.

The config fixture is intentionally sparse and uses `config-version: 3` with `sampling.afk-seconds: 777`. The `restart-config` profile proves migration to the current config version preserves that valid custom value and creates the last-good backup. The `reload-config` profile changes the value to `778`, runs `playtime admin reload`, waits for the plugin's successful reload marker, and verifies the edited value remains on disk after reload.

## Declared profiles

`.enthusia-test.yml` declares `startup`, `restart`, `restart-config`, `reload-config`, `database`, `full`, and `post-merge`. Private Sentinel policy decides which profiles may run automatically or manually; the repository manifest cannot grant itself execution permission.

For changes touching config migration, SQLite schema/storage, startup, reload, shutdown, recovery, or persistence, require the relevant Sentinel profile on the exact unchanged PR head after ordinary CI passes. MockBukkit/unit tests are useful but do not replace this disposable Paper acceptance test.

Record the exact target SHA, successful build workflow/artifact, Sentinel command/profile, job/result, and cleanup evidence. Do not treat queued, stale, cancelled, rejected, or wrong-SHA runs as passes.

Canonical controls and onboarding procedure live in `wsg138/EnthusiaStaff-Staging/docs/repository-onboarding.md` and the current Sentinel manifest/command documentation.