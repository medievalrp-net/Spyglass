# Spyglass — User Stories

Forensic logging and rollback for Paper 1.21.x. Stories are grouped by
audience: server staff (operator/moderator), plugin developers building
against the API, and end-user-visible behavior.

Search examples use the actual built-in aliases:
`a:` (action/event), `m:` (message), `p:` (player), `r:` (radius),
`time:`, `w:` (world), `t:` (target coordinates), `rcpt:` (recipient),
`i:` (IP), `b:` (block), `ent:` (entity), `c:` (cause), `e:` (enchant),
`n:` (item name), `lore:` (item lore).

---

## Forensic search

### Feature: Investigate a griefed area

**Given:** A moderator stands at the location of suspected griefing.
**When:** They run `/spyglass search a:break,place r:30 time:6h`.
**Then:** They get a paged list of every block break/place within 30 blocks in the last 6 hours, with player, block type, and timestamp.

**Details:** The radius is clamped to the configured `limits.max-radius`. With no `r:`, the configured `defaultRadius` is injected unless a spatial-suppressing param is present.

---

### Feature: Track a single player's recent activity

**Given:** A staff member suspects a specific player of misbehavior.
**When:** They run `/spyglass search p:Steve time:1d`.
**Then:** Every event tied to that player in the last day is returned, sorted newest first.

**Details:** Adding `-nod` (no-defaults) flag suppresses the implicit time and radius windows when needed for cross-server-day investigations.

---

### Feature: Page through a large result set

**Given:** A search returns more rows than fit on screen.
**When:** The same staff member runs `/spyglass page 2`.
**Then:** The next page of the most recent search is shown.

**Details:** Page state is per-sender and is replaced on every new search.

---

### Feature: Time-bounded incident review

**Given:** An incident is reported as "yesterday around 4 PM."
**When:** Staff narrow down with `time:1d` or an absolute window.
**Then:** Only events inside that window are returned.

**Details:** `time:` accepts compact units (`30m`, `2h`, `1d`, `1w`). The default time window is configurable on the operator side.

---

## Rollback

### Feature: Roll back a griefer's damage

**Given:** A player has broken several builds across an area.
**When:** Staff run `/spyglass rollback p:Griefer r:50 time:2h`.
**Then:** Every block break and place attributed to that player in that scope is reverted, and synthetic `rolled-place` / `rolled-break` records are written so the audit trail shows the rollback itself.

**Details:** Container contents and entity-NBT (experimental) restore alongside blocks. WorldEdit-attributed changes reverse via the WE/FAWE-aware path.

---

### Feature: Undo a bad rollback

**Given:** A rollback was run with too wide a scope and removed legitimate builds.
**When:** Staff run `/spyglass undo`.
**Then:** Their last rollback or restore is reversed and the world returns to the post-grief state.

**Details:** `/spyglass undo` only reverses the caller's own most recent rollback/restore in this session.

---

### Feature: Restore previously rolled-back changes

**Given:** Investigation determined a roll-back actually undid legitimate work.
**When:** Staff run `/spyglass restore` with the same predicate they used for rollback.
**Then:** The reverted changes are reapplied.

**Details:** Restore is symmetric to rollback and consults the same record set.

---

### Feature: Roll back inside a WorldEdit selection

**Given:** Staff have a WorldEdit region defining the affected area.
**When:** They run `/spyglass rollback p:Griefer we:1 time:1h`.
**Then:** Only changes inside the selection are reverted.

**Details:** `we:1` swaps spatial scope from radius to the sender's current WE selection; `we:0` clears it.

---

## Inspection wand

### Feature: Identify who broke a block

**Given:** A staff member is holding the inspection wand.
**When:** They left-click the air at the spot where a block used to be.
**Then:** The most recent attribution for that exact coordinate is shown — who broke it, what it was, when.

**Details:** Default lookback window for the wand is 7 days. Wand item is configurable; the default is glowstone.

---

### Feature: Probe what a placed block used to be

**Given:** Staff want to know the history of the block they're looking at.
**When:** They right-click a block with the wand.
**Then:** Recent place/break records for that coordinate are shown.

**Details:** Right-click also acts as a "place test" against denied actions, so the click itself does not modify the world.

---

## Chat & moderation

### Feature: Audit chat by channel

**Given:** A heated argument is reported in the RP channel.
**When:** A moderator runs `/spyglass search a:say channel:RP time:2h`.
**Then:** Every chat message sent in the RP channel in the last two hours is returned, with sender, channel tag, and message body.

**Details:** `channel:` is a custom search param registered by WhisperNet via Spyglass's `QueryParamHandler` extension point. Each ChatRecord carries the channel name in its `target` field at write time.

---

### Feature: Find every message a player heard

**Given:** Staff suspect a player overheard sensitive RP information.
**When:** They run `/spyglass search a:say rcpt:<player-uuid> time:1h`.
**Then:** Every chat message that landed in that player's audience list is returned, regardless of who sent it.

**Details:** Recipients are populated by WhisperNet at send time — proximity members for ranged channels, full membership for global channels.

---

### Feature: Search across all chat by phrase

**Given:** A keyword is at the center of an investigation.
**When:** Staff run `/spyglass search a:say m:treasure time:7d`.
**Then:** Every chat message containing "treasure" in the last week is returned, across all channels and senders.

**Details:** Message search is substring; case sensitivity follows the storage backend's default collation.

---

### Feature: Audit commands run by a player

**Given:** A privilege-misuse report is filed against an operator.
**When:** Staff run `/spyglass search a:command p:OpSteve time:1d`.
**Then:** Every command that player ran in the last day is returned, including console-driven commands attributed to them.

**Details:** Both player- and console-source commands are stored; sender kind is preserved on the record.

---

## Containers

### Feature: Trace items missing from a chest

**Given:** A chest is reported emptied.
**When:** Staff stand at the chest and run `/spyglass search a:withdraw,deposit r:3 time:1d`.
**Then:** The withdraw/deposit transactions on that container are listed, with player and item delta.

**Details:** Bookshelves, decorated pots, bundles, item frames (entity-deposit / entity-withdraw), shulker boxes, vaults, and crafters all participate.

---

### Feature: Reconstruct a chest's contents

**Given:** A chest was destroyed by an explosion or break.
**When:** Staff run `/spyglass rollback t:<x>,<y>,<z> time:30m`.
**Then:** The chest is rebuilt and its contents at the time of break are restored from the deposit/withdraw history.

**Details:** Container-snapshot rollback restores the slot inventory captured at break; transient since-then changes are not retroactively applied.

---

## Combat & entities

### Feature: Resolve a PvP dispute

**Given:** Two players file conflicting reports about a fight.
**When:** Staff run `/spyglass search a:hit,shot,death p:Alice,Bob time:30m`.
**Then:** A timeline of strikes, ranged hits, and deaths involving either player is returned.

**Details:** Hit records carry attacker and victim; shot records cover projectiles; death records include death cause.

---

### Feature: Locate a missing tamed animal

**Given:** A player reports their horse missing.
**When:** Staff run `/spyglass search a:mount,dismount p:HorseOwner time:1d`.
**Then:** Mount/dismount events tied to the player are returned, narrowing where the horse last was.

**Details:** Mount/dismount events record the entity UUID and type, allowing follow-up searches on the specific entity.

---

### Feature: Catch a sculk-trap exploiter

**Given:** Suspicions that a player is deliberately triggering wardens.
**When:** Staff run `/spyglass search a:sculk p:Suspect time:1h`.
**Then:** Every sculk sensor or shrieker the suspect activated is returned with location.

**Details:** Sculk events are player-attributed; ambient noise from non-player triggers is not recorded as `sculk`.

---

## Login & IP

### Feature: Detect ban-evasion via shared IP

**Given:** A banned player is suspected of returning under a new account.
**When:** Staff run `/spyglass search a:join i:<ip> time:30d`.
**Then:** Every join from that IP is returned, listing each account that connected from it.

**Details:** Login IPs are captured at join time and indexed for query. Disable via the `events.join.enabled` config switch if not desired.

---

## WorldEdit / FAWE

### Feature: Audit a WorldEdit-driven mass change

**Given:** A large area was modified all at once and staff want to know how.
**When:** They run `/spyglass search r:200 time:10m` over the area.
**Then:** Block changes attributed to `worldedit` or `fawe` origin are shown alongside player attribution.

**Details:** Spyglass wraps the WorldEdit/FAWE extent chain at edit time so every set-block produces a paired break+place record tagged with the appropriate origin.

---

### Feature: Reverse a //set or //paste

**Given:** A staff member ran a destructive WE operation.
**When:** They run `/spyglass rollback p:<self> we:1 time:5m`.
**Then:** Just the WE-driven change is reverted, scoped to their selection.

**Details:** WE-origin changes can be reverted en masse without affecting concurrent natural breaks/places by other players.

---

## Operations & durability

### Feature: Survive a server crash without losing forensic data

**Given:** The server crashes during a high-event window.
**When:** It boots back up.
**Then:** Pending records are replayed from the write-ahead log and persisted to ClickHouse before normal traffic resumes.

**Details:** WAL files are fsynced per drain batch. Recovery counts are logged at startup so operators can confirm replay.

---

### Feature: Disable noisy events

**Given:** An operator does not want chat or join events stored.
**When:** They set `events.say.enabled=false` (or similar) in `config.conf` and restart.
**Then:** Those events are no longer recorded; existing data is unaffected.

**Details:** Each event in the catalog has its own toggle. `/spyglass events` lists the current enabled set.

---

### Feature: Auto-purge stale records

**Given:** The operator only wants 90 days of history retained.
**When:** They configure `retention=90d` in `config.conf`.
**Then:** Records past that age are TTL-evicted by the storage backend without manual intervention.

**Details:** New records carry `expiresAt = occurred + retention`. Operator extensions that record via the API can fetch the same retention via `SpyglassApi.limits()`.

---

## Plugin developer API

### Feature: Record a custom event from a third-party plugin

**Given:** I am building a plugin and want my domain events to flow into Spyglass forensics.
**When:** I build an `EventRecord` and call `spyglassApi.record(record)`.
**Then:** The record lands in `event_records` with the same query, render, and rollback infrastructure as built-in events.

**Details:** `record()` is non-blocking and safe from any thread. Records are batched on the async drain thread before persistence.

---

### Feature: Add a custom search parameter

**Given:** My plugin owns a domain concept (faction, region, channel) that staff want to filter on.
**When:** I implement `QueryParamHandler` and register it on plugin enable.
**Then:** `/spyglass search myparam=foo` parses my alias and applies my predicate against the event log.

**Details:** Aliases are lowercased on registration. Re-registering the same alias replaces the prior handler. `parse()` runs on the main thread during command parsing.

---

### Feature: Add a custom result flag

**Given:** My plugin needs an extra mode toggle on `/spyglass search`.
**When:** I implement `FlagHandler` and register it.
**Then:** `/spyglass search ... -myflag` and `-myflag=value` parse and apply.

**Details:** Built-in flag aliases (`ng`, `g`, `nc`, `ex`, `we`, `ord`, `nod`) cannot be shadowed.

---

### Feature: Render custom events with my own format

**Given:** My custom event has fields the default renderer doesn't know.
**When:** I implement `DisplayRenderer` for my event name and register it.
**Then:** Search results and the inspection wand show my custom layout for that event.

**Details:** The most-recent registration for a given event name wins. Falls back to a generic renderer if my plugin disables.

---

### Feature: Roll back domain state outside the world

**Given:** My plugin keeps state in its own database that should be reverted alongside block rollbacks.
**When:** I emit `RollbackEffect.Custom` payloads on relevant events and register a `RollbackEffectHandler`.
**Then:** `/spyglass rollback` and `/spyglass undo` route those payloads to my handler, which reverses the side-effect.

**Details:** Handlers see the original payload, the rollback reason, and an op context. Returning a result tells the engine whether the effect was applied successfully.

---

### Feature: Align my plugin's bounds with operator config

**Given:** My plugin exposes a similar radius/retention concept and should not exceed Spyglass's limits.
**When:** I call `spyglassApi.limits()` on enable.
**Then:** I get a snapshot of `maxRadius`, `defaultRadius`, `defaultTimeWindow`, `retention` to clamp my own values to.

**Details:** Limits are a snapshot at the time of the call; re-fetch after operator config reloads if you need fresh numbers.

---

## Permissions

| Permission | What it gates |
|---|---|
| `spyglass.use` | Help, events, page, basic commands |
| `spyglass.search` | All `/spyglass search` queries |
| `spyglass.rollback` | `/spyglass rollback`, `/spyglass restore`, `/spyglass undo` |
| `spyglass.tool` | The inspection wand |
| `spyglass.tele` | `/spyglass tele` to a result coordinate |
| `spyglass.worldedit` | Use a WE selection in spatial scope |

All permissions default to op.
