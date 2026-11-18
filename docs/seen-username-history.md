# Seen username history

## Requirements and implementation

Record username observations by player UUID, including every accepted observation in queued and recovered batches. Search /seen by current name, observed previous name, or UUID. Report reused names as ambiguous and show observation dates for previous names. Preserve current profiles and migrate existing data without claiming a username was observed before its stored last-seen time.

The migration creates player_name_history and seeds one observation from each existing player profile. Writes update the history in the same transaction as queued profile writes. Recovery journal format 5 retains each observation while formats 2 through 4 remain readable. Name lookup and database reads run asynchronously.

## Verification

- Before implementation, the queue recovery test showed that WriteBatch lacked a collection for individual observations.
- The final TEST variant passed 261 tests with no failures, errors, or skips using Java 21. This includes history persistence, reused names, queued observations, recovery journal round trip, and a MockBukkit /seen lookup for an online player by an earlier name.
- TEST's SQLite database was backed up before migration. The SHA-256-verified JAR was installed on TEST with its existing custom sound changes. After a clean restart, the plugin enabled and the user observed /seen output for online and offline players.
- TEST's migrated database contains 12 profiles and 12 history entries, with no UUID having more than one recorded username. An in-game historical-name lookup therefore remains pending until a second observed name is available on TEST.
- Production has not been updated. The MySQL path was included in the implementation but was not exercised against a running MySQL server.
