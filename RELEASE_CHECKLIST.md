# Release checklist

For any release that touches storage, configuration migration, startup, reload, packaging, or database drivers:

1. Build and run the full automated test suite.
2. Test a first installation with no existing plugin data folder.
3. Test an in-place upgrade using a copy of a populated production-style data folder.
4. Verify the existing config values and database row counts remain unchanged after startup and reload.
5. Verify missing and empty established databases fail closed instead of creating a replacement.
6. Verify invalid or missing playtime schema fails before a datasource becomes active.
7. Verify `backups/playtime.db.last-good` is created from current data and replaced in place, leaving only one rolling database backup.
8. Download and inspect the exact deployable artifact produced by CI.

Do not describe a storage-affecting build as deployment-safe based only on a fresh-database test.
