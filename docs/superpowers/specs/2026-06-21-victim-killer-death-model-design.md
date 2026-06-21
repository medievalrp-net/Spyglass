# Victim/Killer-Aware Death Model

**Date:** 2026-06-21
**Status:** Approved design — pending implementation plan
**Backend in use:** ClickHouse (design is backend-agnostic; no schema change required)

## Problem

Spyglass records a single `death` event whose `source` is the **killer**, with
the victim captured only as an entity-type string (`target`/`entityType`) plus a
raw, non-searchable UUID (`entityId`). The player query param `p:` matches
**only** `source.playerId` / `source.playerName`.

Consequences:

- `p:the60th a:death` returns deaths the60th **caused**, not deaths he
  **suffered** — because when he dies, `source` is whoever/whatever killed him,
  and as a victim he is not in any `p:`-searchable field.
- There is no way to search a player's kills: `kill` is not an event; kills are
  folded into `death` with `source = killer`.

Desired:

- `spyglass search p:the60th a:death` → every time the60th died.
- `spyglass search p:the60th a:kill` → every kill the60th made.
- Mob kills captured separately so they filter distinctly: `a:mob-kill`.

## Design Overview

Model each lethal event from **two perspectives** so the relevant actor is the
`source` (and therefore `p:`-searchable) in each:

| Event       | `source` (the `p:`-searchable subject) | `target`                              | Record type         | Rollbackable |
|-------------|----------------------------------------|---------------------------------------|---------------------|--------------|
| `death`     | the **victim** who died                | killer name / mob type / damage cause | `EntityDeathRecord` | yes (unchanged) |
| `kill`      | the **player** killer                  | victim (player name or mob type)      | `EntityHitRecord`   | no           |
| `mob-kill`  | the **mob** killer                     | victim (player name or mob type)      | `EntityHitRecord`   | no           |

`kill` and `mob-kill` reuse the existing `EntityHitRecord`, whose shape
(`source` + `victimType` + `victimId` + `damage`/`projectile`/`projectileType`)
is already exactly "killer → victim". Both Mongo and ClickHouse already persist
`EntityHitRecord` (used by the `hit` and `shot` events), so **no new record type,
no Mongo codec change, no ClickHouse schema/column/mapper change** is needed.

This is the minimal change that satisfies the queries, because `p:` resolves to
`source` only: the victim must be the `source` of `death`, and the killer must be
the `source` of `kill`/`mob-kill`.

### Why `death` is *flipped* rather than a new `died` event

The user types `a:death` to mean "times X died" — i.e. they expect `death` to be
victim-centric. Flipping the existing event matches that mental model and avoids
a redundant fourth event. The `hit`/`shot` events keep `source = attacker`
(they read naturally as "X hit Y" actions); only `death` is special-cased to be
victim-centric, with the killer perspective expressed by `kill`/`mob-kill`.

## Field Semantics After the Change

### `death` (`EntityDeathRecord`)
- `source` = the victim:
  - victim is a Player → `support.playerSource(victimPlayer)`
  - victim is a mob → `support.entitySource(victim.getUniqueId(), entityType)`
- `target` = the killer/cause, for self-describing display and at-a-glance
  forensics:
  - player killer → killer's name
  - mob killer → mob entity type
  - environment/unknown → the damage cause (e.g. `FALL`, `LAVA`)
- `entityType` = victim entity type (unchanged — used by rollback respawn)
- `entityId` = victim UUID (unchanged — used by `restoreEffect`)
- `killerType` = `"player"`, the mob type, or the damage cause
- `damageCause` = `lastDamageCause` cause name, or `"UNKNOWN"`
- `entityNbt` = victim NBT (unchanged)
- `origin`:
  - player killer → `Origin.player()`
  - mob killer → `Origin.environment("death:" + cause)`
  - environment → `Origin.environment("death:" + cause)` / `"death"`

Rollback is unaffected: `rollbackEffect()` still spawns `entityType` at
`location` from `entityNbt`; `restoreEffect()` still removes by `entityId`.

### `kill` and `mob-kill` (`EntityHitRecord`)
- `event` = `"kill"` or `"mob-kill"`
- `source` = the killer:
  - `kill` → `support.playerSource(killerPlayer)`
  - `mob-kill` → `support.entitySource(killerMob.getUniqueId(), killerType)`
- `target` = the victim identity: victim player name, else victim mob type
- `victimType` = victim entity type
- `victimId` = victim UUID
- `damage` = killing-blow damage from `lastDamageCause.getDamage()` when
  available, else `0.0`
- `projectile` = true when the damage cause is `PROJECTILE`
- `projectileType` = projectile entity type when resolvable, else `null`

## Listener Logic (`EntityDeathListener.onEntityDeath`)

On each `EntityDeathEvent` (priority `MONITOR`, `ignoreCancelled = true`):

1. Build victim identity (`entityType`, `entityId`, `location`, NBT as today).
2. Resolve the killer:
   - **Player killer** — `victim.getKiller()` is a `Player`:
     emit `death` (source = victim, target = killer name) **and**
     `kill` (source = killer, target = victim).
   - **Mob killer** — `getKiller()` is null but `getLastDamageCause()` is an
     `EntityDamageByEntityEvent`: take `getDamager()`; if it is a `Projectile`,
     resolve its `getShooter()`; if the resulting entity is a non-player
     `LivingEntity`, emit `death` (source = victim, target = mob type) **and**
     `mob-kill` (source = mob, target = victim). If the damager resolves to a
     player (edge cases `getKiller()` missed), treat as the player-killer branch.
   - **Environment / unknown** — neither of the above: emit `death` only
     (source = victim, target = damage cause).
3. The per-mob loot `drop` records (non-player victims) are unchanged.

The `kill`/`mob-kill` records are gated by their config toggles (below); when an
event is disabled, only the `death` record (if `death` is enabled) is emitted.

`events()` returns `{"death", "drop", "kill", "mob-kill"}` so registration and
the enabled-event gate cover the new names.

## Catalog, Config, Display

- **`EventCatalog`**: add `m.put("kill", EntityHitRecord.class)` and
  `m.put("mob-kill", EntityHitRecord.class)`.
- **`config.conf`**: under the events section add `kill = true` and
  `mob-kill = true`; add display verbs so `death → "died"` and
  `kill` / `mob-kill → "killed"`. (Verb lookup follows the existing
  per-event past-tense config mechanism.)
- **`ResultRenderer`** and **`ProxyResultRenderer`**:
  - `death` inline → `"<victim> died <target>"` where `<target>` is the
    killer/cause (the hover already shows `Cause`).
  - `kill` / `mob-kill` inline → render via the existing `EntityHitRecord`
    path with verb "killed": `"<killer> killed <victim>"`. The existing hover
    detail (Damage, Weapon for projectiles) applies unchanged.

## Historical Data

Existing `death` rows were written with the **old** semantics (`source` =
killer). After the flip, `p:X a:death` is correct only for rows written **after**
deployment; pre-existing rows still have killer-as-source and will not match a
victim search (and will match the killer's `p:` search instead).

**Recommendation: accept going-forward correctness and do not migrate.**
Re-keying historical `death` rows on ClickHouse is a heavy MergeTree mutation
(rewrites parts) for a forensic log that is already append-only and ages out via
retention. If a backfill is ever wanted, it is a separate one-off task. No new
`kill`/`mob-kill` rows are synthesized for past deaths.

## Testing

- **Listener logic** (the core change): three branches —
  1. player kill → emits a `death` (source = victim) and a `kill`
     (source = killer);
  2. mob kill → emits a `death` (source = victim) and a `mob-kill`
     (source = mob);
  3. environment death → emits `death` only, with `source` = victim.
  Assert source/target/event on each emitted record. Projectile-shooter
  resolution covered by a mob-projectile case.
- **Config gating**: `kill` disabled → player kill emits `death` only.
- **Storage round-trip**: a `kill` and a `mob-kill` record persist and decode
  back as `EntityHitRecord` with the right `event`, on the in-use backend
  (ClickHouse IT; SQLite unit test mirrors it).
- **Rendering**: `ResultRenderer`/`ProxyResultRenderer` produce the new
  "died" / "killed" inline forms for the three events.
- **Catalog/params**: `EventParamTest` and suggestion tests pick up `kill` /
  `mob-kill` automatically from `EventCatalog`; add assertions that the names
  are known and stored as `EntityHitRecord`.

## Out of Scope

- Migrating/backfilling historical `death` rows.
- Changing `hit` / `shot` semantics (they remain attacker-as-source).
- Capturing the killer's weapon item on `kill` (possible future enhancement via
  `target`/extensions; not required for the requested queries).

## Event-Type Parity Checklist (per CLAUDE.md)

- [x] Sealed `EventRecord` — no new type (reuses `EntityDeathRecord` +
      `EntityHitRecord`); no change.
- [x] `EventCatalog` — add `kill`, `mob-kill` → `EntityHitRecord`.
- [x] Emitting listener — `EntityDeathListener` flips `death`, emits
      `kill`/`mob-kill`, updates `events()`.
- [x] Mongo codec — `EntityHitRecord` already registered; no change.
- [x] ClickHouse schema/mapper — `EntityHitRecord` columns already exist; no
      change.
- [x] Config default — add `kill = true`, `mob-kill = true` + verbs.
