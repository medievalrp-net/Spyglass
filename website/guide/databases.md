# Choosing a database

Spyglass works with four kinds of storage. Pick one with `database.backend` in `config.conf` and restart.

| Backend | Setting | Good for |
| --- | --- | --- |
| **SQLite** | `"sqlite"` | **The default.** A file in the plugin folder, nothing to set up. Fine for most servers. |
| **MariaDB / MySQL** | `"mariadb"` or `"mysql"` | Hosts that already run one. |
| **MongoDB** | `"mongo"` | Servers already running MongoDB. |
| **ClickHouse** | `"clickhouse"` | Large servers. The fastest rollbacks and the smallest storage at scale. |

Spyglass creates its own database and tables on first start. You only need a database user that's allowed to create them.

## Settings

Only the block for the backend you chose is read. These are the defaults:

::: code-group

```hocon [SQLite]
database {
  backend = "sqlite"
  sqlite {
    path = "spyglass.db"   # inside plugins/Spyglass/
  }
}
```

```hocon [MariaDB / MySQL]
database {
  backend = "mariadb"      # or "mysql"
  mariadb {
    host = "localhost"
    port = 3306
    database = "spyglass"
    user = "root"
    password = ""
    ssl = false
  }
}
```

```hocon [MongoDB]
database {
  backend = "mongo"
  mongo {
    host = "localhost"
    port = 27017
    database = "Spyglass"
    user = ""
    password = ""
    ssl = false
    collection = "EventRecords"
    # A full connection string (replica set, Atlas, advanced TLS).
    # When set, it replaces the fields above.
    uri = ""
  }
}
```

```hocon [ClickHouse]
database {
  backend = "clickhouse"
  clickhouse {
    host = "localhost"
    port = 8123            # the HTTP port
    database = "spyglass"
    table = "event_records"
    user = "default"
    password = ""
    ssl = false
  }
}
```

:::

## How long records are kept

Records are deleted after **26 weeks** by default. Change it with `storage.retention`, or per event type. See [Configuration](../reference/configuration#storage).

## Moving to another database

`/sg migrate <backend>` copies every record from the database you're using into another one. History is never lost when you switch.

1. Fill in the new backend's block in `config.conf`. Leave `backend` as it is for now.
2. Run `/sg migrate <sqlite|mariadb|mongo|clickhouse>` and wait for "Migration complete".
3. Change `database.backend` to the new one and restart.

Good to know:

- Needs the `spyglass.migrate` permission.
- If the target already has records, add `--confirm`. Re-running a migration skips records that are already there, except on MongoDB, where it's refused.
- Records written while the copy runs might not be copied. Migrate during a quiet time and switch right after.
- Only one migration runs at a time, and not while an import is running.
- Undo history, wand state, salvage and saved player inventories aren't copied. They start fresh on the new database.
