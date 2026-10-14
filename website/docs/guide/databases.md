# Choosing a database

**We recommend ClickHouse.** It gives Spyglass the fastest rollbacks and keeps the most history in the least disk space. In a 2-million-block test, a rollback on ClickHouse took about 5 seconds and the history took about 11 MiB of disk. CoreProtect on MySQL took about 12 seconds and 180 MiB. [Set it up below](#setting-up-clickhouse).

Spyglass starts on **SQLite**, a file in the plugin folder, so it works the moment you install it. That's fine for trying Spyglass or for a small server, and you can move your history to ClickHouse later with one command.

| Backend | Setting | Good for |
| --- | --- | --- |
| **ClickHouse** (recommended) | `"clickhouse"` | Any server that can run it. The fastest rollbacks and the smallest storage. |
| **SQLite** | `"sqlite"` | The default. Nothing to set up; good for trying Spyglass or a small server. |
| **MariaDB / MySQL** | `"mariadb"` or `"mysql"` | Hosts that already run one. |
| **MongoDB** | `"mongo"` | Servers already running MongoDB. |

Spyglass creates its own database and tables on first start. You only need a database user that's allowed to create them.

## Setting up ClickHouse

1. **Run a ClickHouse server**, on the same machine as your Minecraft server or anywhere Spyglass can reach. Spyglass is tested with ClickHouse 24.8. With Docker:

    ```
    docker run -d --name clickhouse --restart unless-stopped \
      -p 127.0.0.1:8123:8123 \
      --ulimit nofile=262144:262144 \
      -e CLICKHOUSE_PASSWORD=change-me \
      -v clickhouse-data:/var/lib/clickhouse \
      clickhouse/clickhouse-server:24.8
    ```

    Without Docker, follow ClickHouse's [install guide](https://clickhouse.com/docs/install).

2. **Point Spyglass at it** in `config.conf`:

    ```hocon
    database {
      backend = "clickhouse"
      clickhouse {
        host = "localhost"
        port = 8123
        user = "default"
        password = "change-me"
      }
    }
    ```

3. **Restart the server.** Spyglass creates its database and table, and the console shows `backend = CLICKHOUSE`.

Already have history in SQLite? Fill in the `clickhouse` block but leave `backend` alone, run `/sg migrate clickhouse`, then switch `backend` and restart. See [Moving to another database](#moving-to-another-database).

## Settings

Only the block for the backend you chose is read. These are the defaults for each:

=== "ClickHouse"

    ```hocon
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

=== "SQLite"

    ```hocon
    database {
      backend = "sqlite"
      sqlite {
        path = "spyglass.db"   # inside plugins/Spyglass/
      }
    }
    ```

=== "MariaDB / MySQL"

    ```hocon
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

=== "MongoDB"

    ```hocon
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

## How long records are kept

Records are deleted after **26 weeks** by default. Change it with `storage.retention`, or per event type. See [Configuration](../reference/configuration.md#storage).

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
