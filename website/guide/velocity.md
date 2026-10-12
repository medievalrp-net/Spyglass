# Cross-server search (Velocity)

On a network where several Paper servers share one Spyglass database, the optional Velocity companion lets staff search all of them from the proxy.

It's **read-only**: it never logs anything and never rolls anything back. Each Paper server still needs Spyglass installed to record its own events and to run rollbacks.

## Setup

1. On each Paper server, give Spyglass a unique name and point it at the **same** database:

   ```hocon
   server {
     name = "survival"   # what srv: filters on
   }
   ```

2. Install the companion jar in the proxy's `plugins/` folder and start the proxy once. It creates `plugins/spyglass/config.conf`.
3. Copy the `database` block from a Paper server's `config.conf` into it and restart the proxy.

SQLite only works if the proxy can reach the same file (same machine or a shared folder). For a real network, use MariaDB/MySQL, MongoDB or ClickHouse.

::: info Getting the companion jar
Releases from 1.1.1 on only include the Paper plugin. Build the companion from source with `./gradlew :spyglass-velocity:build`; the jar is in `spyglass-velocity/build/mc26.1.2/libs/`.
:::

## Usage

The proxy uses its own command, `/sgv`, so it never hides the Paper `/sg` commands.

```
/sgv search srv:survival p:Steve t:1d
/sgv page 2
/sgv help
```

It takes the same [search keys](../reference/query) as `/sg search`, plus `srv:` to pick a server. Needs `spyglass.search` on the proxy; `spyglass.search.ip` reveals IP addresses.
