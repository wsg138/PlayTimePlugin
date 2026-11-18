# Playtime display and command stability

## Requirement and implementation

A single `/playtime <player>` request should return a result after a cold asynchronous read. Display consumers should retain last successfully loaded values while refreshes run, without publishing an obsolete read after invalidation. Failed SQL reads must remain distinguishable from successful empty results.

The command waits for its target's refresh on the server scheduler, with a bounded timeout and runtime/player validity checks. Display caches retain loaded values during invalidation. Revisions and generations reject obsolete reads. Player and server totals cache pending minutes together with the corresponding SQL results and defer publication when a write can overlap the SQL snapshot. Failed display queries retain the prior value and wait five seconds before retrying the same cache key.

## Verification and review

- Before implementation, the command integration test reproduced the cold-cache retry message, and the display regression showed an obsolete read replacing 5,000 minutes with 2.
- `mvn clean verify` on the parent main baseline passed 253 tests, with zero failures, errors, or skips, using Java 21 and the pinned EnthusiaStaff API.
- The command fix was installed on TEST and the user confirmed it works.
- Before the audit fixes, regressions reproduced a one-minute drop on a warm read after flushing and an immediate SQL retry on the next display poll. The fixed Java 21 build passed 256 tests, including a flush overlapping SQL and an in-flight write awaiting ledger finalization. The deployment build passed 258 tests with TEST's existing custom sound changes.

## TEST status

| Surface | Status | Verification |
| --- | --- | --- |
| Playtime holograms | Fixed on TEST | The live all-time leaderboard placeholder returned the same populated value on two warm reads. |
| TAB playtime prefixes and ordering | Fixed on TEST | Automated tests covered stable cached player totals during flushes and failed refreshes. Paper confirmed the new Playtime JAR and Display 0.1.10 were enabled on TEST. |
| Nametags | Visual check pending | |

The status records the fixes deployed to TEST. A player-client visual check of hologram flicker and TAB ordering was outside the server-side smoke test. TEST used its existing SQLite playtime data. Display 0.1.9 was removed from the plugin directory and archived in the TEST backups folder. The uploaded Playtime JAR matched the verified build's SHA-256. Production deployment remains pending.
