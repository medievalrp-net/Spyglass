# Importing from CoreProtect

Bring your CoreProtect history into Spyglass and keep searching and rolling it back, including players who never join the new server. Both plugins can run side by side while you import, so nothing goes unlogged.

Needs the `spyglass.import` permission. Works with CoreProtect 20 and newer.

## Before you start

1. Install Spyglass and set up your [database](databases.md). ClickHouse is recommended, and imports into it take minutes instead of hours.
2. **Check `storage.retention`.** Imported records older than it are deleted after the import. To keep everything, set it first and restart:

   ```hocon
   storage {
     retention = "never"
   }
   ```

   The import warns you up front if part of the history is older than your retention.
3. Every world in the CoreProtect history must exist on this server. If one is missing, the import stops before writing anything.

## SQLite (CoreProtect's default)

Copy CoreProtect's `database.db` into `plugins/Spyglass/import/`, then run:

```
/sg import database.db
```

The file name tab-completes.

## MySQL

Add the CoreProtect database to `plugins/Spyglass/import.conf`. This file is only for imports and doesn't change where Spyglass stores its own data.

```hocon
sources {
  old-survival {
    host = "10.0.0.5"
    port = 3306
    database = "coreprotect"
    user = "spyglass_reader"   # only needs SELECT on the co_* tables
    password = "changeme"
    # server-name = "survival" # optional; defaults to this server's name
  }
}
```

Then run:

```
/sg import mysql old-survival
```

## While it runs

The import runs in the background without lagging the server, and reports progress in chat and the console. Big histories take a while. Into ClickHouse it's minutes even for tens of millions of records; into SQLite, plan for hours.

## Importing again

Importing the same source twice is refused. Add `--confirm` to import anyway; records already there are skipped, not duplicated. On MongoDB, re-importing is always refused.

## What's imported

Block changes, kills, logins and logouts, chat, commands, container deposits and withdrawals, and item drops and pickups. The full table-by-table coverage is in the [importer notes](https://github.com/medievalrp-net/Spyglass/blob/main/docs/importer.md).

When you're happy with the result, remove CoreProtect.

??? note "Importing without a running server"

    A standalone command-line importer can import offline and audit that every CoreProtect row arrived. See the [CoreProtect migration runbook](https://github.com/medievalrp-net/Spyglass/blob/main/docs/migration-from-coreprotect.md).
