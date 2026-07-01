# Spyglass importer + ClickHouse test rig

Self-contained Docker environment for exercising the CoreProtect
importer against a real ClickHouse instance, without polluting the host.

## Layout

| Service             | Profile  | Purpose |
|---------------------|----------|---------|
| `clickhouse`        | (always) | ClickHouse 24.8 server. HTTP on 8123, native TCP on 9000. Data persisted in named volume `clickhouse-data`. |
| `importer`          | `import` | One-shot run of the importer fat jar against `coreprotect-test/`. Mounts the jar from the host so no rebuild is needed between code changes. |
| `clickhouse-client` | `shell`  | Disposable interactive `clickhouse-client` connected to the server, defaulting to the `spyglass` database. |

## Prerequisites

- Docker Engine 24+ with Compose v2.
- A CoreProtect SQLite DB at `../coreprotect-test/<name>.db` and a
  worlds directory layout `../coreprotect-test/<worldname>/uid.dat`.
  The default compose command targets `crusalis-aod-3-database.db`;
  edit `docker-compose.yml` `command:` block to point at a different
  fixture.
- For the importer service, build the fat jar first:
  ```
  ./gradlew :spyglass-importer:shadowJar
  ```
  Re-run this whenever you change Java code in `spyglass-importer/`.
  No `docker compose build` required — the jar is bind-mounted, not
  baked into an image.

## Common workflows

All commands assume you're at the repository root.

### Bring up ClickHouse

```sh
docker compose -f docker/docker-compose.yml up -d clickhouse
```

The healthcheck pings `http://localhost:8123/ping`; the server is
ready when `docker compose ps clickhouse` shows `(healthy)`.

### Dry-run an import

Validates source schema, world UUIDs, and mapper output. Touches
ClickHouse only enough to satisfy the `depends_on: healthy` gate —
nothing is written to the DB.

```sh
./gradlew :spyglass-importer:shadowJar
docker compose -f docker/docker-compose.yml --profile import run --rm importer --dry-run
```

### Run a real import

```sh
./gradlew :spyglass-importer:shadowJar
docker compose -f docker/docker-compose.yml --profile import run --rm importer
```

The default `command:` block in compose handles connection settings;
add ad-hoc flags by appending them:

```sh
docker compose -f docker/docker-compose.yml --profile import \
    run --rm importer --batch-size 25000 --progress-interval 250000
```

### Inspect what landed

```sh
docker compose -f docker/docker-compose.yml --profile shell run --rm clickhouse-client
```

Sample queries inside the prompt:

```sql
SELECT count() FROM event_records;
SELECT event, count() FROM event_records GROUP BY event ORDER BY count() DESC;
SELECT origin_kind, source_kind, count()
  FROM event_records
  GROUP BY origin_kind, source_kind
  ORDER BY count() DESC;
SELECT * FROM event_records ORDER BY occurred DESC LIMIT 5 FORMAT Vertical;
```

### Clean slate

```sh
# stop, keep data
docker compose -f docker/docker-compose.yml down

# stop AND wipe ClickHouse data
docker compose -f docker/docker-compose.yml down -v
```

## Editing the default import target

The compose `command:` block hard-codes the SQLite filename, server
name, and worlds dir. To target a different fixture, edit
`docker-compose.yml` directly — overriding `command:` on the CLI
replaces it entirely, so partial overrides aren't useful for the
fixture path.

## Notes

- The importer image is stock `eclipse-temurin:21-jre` with no custom
  layers. Iteration is `edit Java → gradle shadowJar → compose run` —
  no `docker build` step.
- ClickHouse data lives in the named volume `clickhouse-data`, not
  bind-mounted. Survives `docker compose down`, dies on `down -v`.
- The CoreProtect SQLite is mounted read-only (`:ro`); the importer
  cannot mutate the source DB even if a future bug tried.
