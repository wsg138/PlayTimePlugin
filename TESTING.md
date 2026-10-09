# EnthusiaPlaytime testing guide

This document explains the repository-local automated tests, the owner-directed test-hardening contracts, how to run/review the suite, and how repository tests differ from Sentinel or live Paper evidence.

## Test ownership

Handwritten PlayTime tests live in this repository under `src/test/java`. They should travel with the plugin and run whenever its code changes.

Sentinel Sim is an additional artifact/runtime compatibility layer. It is not the storage location for normal PlayTime unit, persistence, command, configuration, or regression tests.

## Test-hardening additions

### `SeenCommandTest`

Focused regression coverage for the `/seen` command boundary:

- permission denial fails before runtime lookup;
- unavailable runtime fails closed with a clear message;
- console use without a player argument returns usage;
- an online player using `/seen` without a target gets immediate online status.

The command's durable `last_seen` data and migration behavior remain covered by the repository's SQLite/storage suites. Live scheduler/provider behavior remains a Paper/runtime concern.

### `PluginSurfaceContractTest`

Freezes the reviewed plugin metadata surface:

- main class and API version;
- optional integration dependencies (Plan, PlaceholderAPI, ProtocolLib, Floodgate, Geyser);
- exact command set: `playtime`, `roman`, `firstjoin`, `seen`;
- command aliases;
- the intentionally internal/runtime-managed outer command permission boundary;
- exact permission set and reviewed `true`, `false`, and `op` defaults.

A failure here means the public/security surface changed. Do not simply edit expected values to make CI green; verify that the change is intentional and its behavior/authority boundaries are tested.

### `FullFeatureCoverageContractTest`

This is a maintenance inventory over the existing behavioral suite. It requires concrete regression-test sources for:

1. plugin runtime and safe reload;
2. configuration migration and recovery;
3. activity/AFK lifecycle;
4. playtime accrual accounting;
5. SQLite/storage safety and schema migration;
6. async write queue and shutdown recovery;
7. numeral/tier configuration and progression;
8. read-service bounds and leaderboard caching;
9. PlaceholderAPI continuity across reload;
10. SQLite packaging/runtime identity;
11. `/seen` command boundary behavior.

This guard does **not** prove those behaviors by filename. The owning behavioral tests do. Its purpose is to stop an entire test family from disappearing unnoticed.

Do not satisfy it with empty tests or unrelated filenames.

## Existing important suites

Examples of current repository evidence include:

- `PlayTimePluginRuntimeTest`;
- `ConfigRecoveryIntegrationTest` and `config/ConfigMigratorTest`;
- `activity/ActivityLifecycleTest`;
- `service/PlaytimeAccrualTrackerTest`;
- `data/SqliteStorageSafetyTest`;
- `data/SchemaMetadataMigrationTest` and legacy migration integration tests;
- `util/AsyncWriteQueueLedgerTest` and `AsyncWriteQueueLifecycleTest`;
- shutdown recovery journal tests;
- numeral/tier catalog, formatting, and progression tests;
- `service/PlaytimeReadServiceBoundsTest`;
- `PlaceholderReloadContinuityTest`;
- `SqlitePackagingConfigurationTest`.

When production behavior changes, extend the real owning test. Do not rely on the coverage inventory as a substitute for behavioral assertions.

## Running tests

Use Java 21 and Maven.

Run the whole unit/integration suite:

```bash
mvn -B test
```

Run the new hardening tests only:

```bash
mvn -B -Dtest=SeenCommandTest,PluginSurfaceContractTest,FullFeatureCoverageContractTest test
```

Run one existing focused class, for example:

```bash
mvn -B -Dtest=PlaytimeAccrualTrackerTest test
```

Run the canonical pre-merge verification:

```bash
mvn -B clean verify
```

Use the repository's GitHub Actions `Build` workflow as durable exact-head evidence. The active Sentinel artifact-producer package may change artifact/workflow retention behavior; it does not replace repository-local test results.

## Result locations

Surefire writes local test evidence to:

- `target/surefire-reports/*.txt`;
- `target/surefire-reports/*.xml`.

The repository's build workflow also validates the packaged JAR, including SQLite JDBC/runtime identity requirements and provided dependency behavior.

Final evidence must belong to the exact PR head. An older green run is stale after any new commit.

## Failure triage

### Plugin surface contract failure

A command, alias, soft dependency, permission, or permission default changed.

Review the change as a user-visible/security boundary before updating the contract. Verify runtime permission enforcement, Java/Bedrock usability, provider behavior, and documentation where relevant.

### Feature coverage contract failure

A major behavioral test family disappeared or moved.

Restore/add meaningful tests, or deliberately update the marker after verifying the replacement suite protects the same behavior. Never make the marker point at an unrelated file solely to obtain a pass.

### Behavioral test failure

Classify it as one of:

- product regression;
- intentional behavior change requiring a reviewed test update;
- fixture/API mismatch;
- environment/dependency failure.

Do not weaken an assertion just to make CI green.

### Database/storage failure

Treat storage failures as data-safety sensitive. Check schema metadata, migration compatibility, connection lifecycle, SQLite/MariaDB differences, restart recovery, duplicate/idempotent writes, and bounded queries before changing expectations.

### Packaging failure

A test pass does not override a packaging failure. Check SQLite JNI package identity, shaded dependencies, service resources, provider leakage, and exact artifact provenance separately.

## Adding tests for new features

For each meaningful product change:

1. identify the owning package/class and feature family;
2. add focused positive and negative tests close to that code;
3. cover permissions/console/player distinctions for commands;
4. cover Java/Bedrock/provider-present/provider-missing behavior where relevant;
5. cover reload, restart, duplicate, stale-state, partial-failure, and shutdown behavior for durable/asynchronous state;
6. add SQLite/MariaDB/migration tests when persistence changes;
7. update `PluginSurfaceContractTest` only when the reviewed public/security surface intentionally changes;
8. add a new coverage family if the feature does not fit the existing inventory;
9. run focused tests and then `mvn -B clean verify`;
10. inspect exact-head GitHub Actions evidence before merge.

## Known coverage boundaries

The current suite is substantial, but this guide does not claim every UI/command/provider path is exhaustively tested. In particular, when changing GUI interactions, the full `/playtime` and `/roman` command trees, Floodgate/Geyser-specific layouts, Plan integration, ProtocolLib behavior, R2/public leaderboard publishing, or live Bukkit scheduler behavior, add focused tests where deterministic and use runtime/staging evidence where a real server/provider is required.

## Sentinel and live Paper

Use repository-local tests for deterministic business logic, persistence, configuration, commands, queues, reload/recovery, and regressions.

Use Sentinel or real Paper/staging when validating:

- packaged artifact loading;
- optional dependency combinations;
- real scheduler/lifecycle behavior;
- cross-plugin compatibility;
- server restart/update behavior;
- real provider APIs;
- production-stack compatibility.

Repository tests, Sentinel, and live staging are separate evidence systems. Do not report one as another.

## Parallel-worker rule

Before changing tests, reconcile live open PRs and their changed paths. The current test-hardening work is intentionally test/documentation-only and must not absorb the active Sentinel artifact-producer workflow package or unrelated product changes.
