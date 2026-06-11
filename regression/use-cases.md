# Spyglass vs CoreProtect — use-case catalog

106 scenarios for head-to-head testing against CoreProtect-ClickHouse. Each case is run on both plugins where both claim the capability; cases one side cannot attempt are scored **N/A-capability** (and that asymmetry is itself a result).

**Scoring per case:** `SG: pass|fail|n/a` · `CP: pass|fail|n/a` · notes (timings, worst tick, row counts where tagged). A case *passes* when the verify column holds exactly — "mostly restored" is a fail with a note.

**Comparison dimensions:** `C` correctness (world/data state) · `K` capability (can it at all) · `P` performance (wall-clock + worst tick + GC log) · `S` storage (rows/bytes written) · `U` UX (what the operator sees).

**Automation:** `bot` = scriptable today with the mineflayer harness (`regression/bot/`) · `rcon` = console commands suffice · `man` = needs a human or a purpose-built rig.

Measurement discipline (from the perf campaign): same-run comparisons only; judge lag by WORST SINGLE TICK + the GC log, never MSPT averages; world-state verification by server-truth sampling (`verify-rollback.js`), not plugin summaries.

---

## A. Block basics — A1–A10

| # | Scenario | Verify | Dim | Auto |
|---|---|---|---|---|
| A1 | Player breaks one stone block | Search shows the break with actor/time/location; rollback restores it | C,U | bot |
| A2 | Player places dirt | Rollback removes it; restore re-places it | C | bot |
| A3 | 100 mixed blocks broken (logs, ores, glass, stairs) | Single rollback restores every block with exact BlockData (orientation, half, shape) | C | bot |
| A4 | Same player places then breaks the same block | Rollback to T0 nets to the original state — no ghost block, no double-restore | C | bot |
| A5 | Players A and B alternately edit one block; rollback only A | Per-block history semantics: B's surviving contribution handled identically on both plugins (document each side's semantic) | C,U | bot |
| A6 | Rollback `r:5` around operator | Boundary blocks at exactly distance 5 in/out match CP's `r:5` interpretation; no off-by-one | C | bot |
| A7 | Grief at T-2h and T-10m; rollback `t:1h` | Only the younger grief reverts | C | bot |
| A8 | Mixed break+place grief; rollback `a:break` only | Placements remain, breaks restored | C | bot |
| A9 | Mixed-material grief; rollback filtered to diamond ore only | Ore restored, the rest untouched | C | bot |
| A10 | Grief spans chunks that are unloaded at rollback time | Chunks load on demand; restoration complete; no console errors | C,P | bot |

## B. Stateful / multi-block — B1–B12

| # | Scenario | Verify | Dim | Auto |
|---|---|---|---|---|
| B1 | Sign placed, text written both sides, dyed + glowing, then broken | Rollback restores text, dye, glow on both sides | C | man |
| B2 | Existing sign's text edited (1.20+ sign editing) | Search shows the edit (`useSign`); rollback restores the prior text | C,K | man |
| B3 | Chest with 27 named+enchanted items broken | Rollback restores chest and full contents — slots, names, lore, enchants | C | bot |
| B4 | Double chest broken (both halves, contents split) | Both halves restored, contents exact, nothing duplicated | C | bot |
| B5 | Shulker box with items broken and picked up | Events recorded; rollback restores placed shulker with contents | C | man |
| B6 | Banner with 5 patterns broken | Patterns restored | C | man |
| B7 | Jukebox with disc broken | Disc restored inside | C | man |
| B8 | Decorated pot: sherds inserted/removed (`pot-insert/remove`), then broken | History shows sherd ops; rollback restores pot with sherds | C,K | man |
| B9 | Chiseled bookshelf: books inserted/removed (`bookshelf-insert/remove`) | Occupancy history correct; rollback restores slots | C,K | man |
| B10 | Bed broken (either half) | Both halves restored, color + facing correct | C | bot |
| B11 | Door / tall flower broken at top vs bottom half | Pair restored consistently from either trigger | C | bot |
| B12 | Waterlogged stairs broken | Waterlogged state survives rollback | C | bot |

## C. Containers & item flow — C1–C14

| # | Scenario | Verify | Dim | Auto |
|---|---|---|---|---|
| C1 | Deposit 64 iron into a chest | `deposit` recorded (amount, slot); container rollback returns the items to inventory-truth on both sides | C | bot |
| C2 | Withdraw a stack from a chest | Rollback puts items back in the chest | C | bot |
| C3 | Session: open → deposit ×3 → withdraw ×1 → close | Event ordering and amounts exact; open/close pairing recorded (SG) vs CP equivalent | C,K | bot |
| C4 | Hopper drains a player's chest | Attribution of automated movement; compare each side's hopper story | K,C | rcon |
| C5 | Hopper minecart under a chest | Same as C4 for the minecart path | K | man |
| C6 | Dropper fires items into a chest | Attribution chain | K | rcon |
| C7 | Player A drops item, player B picks it up | `drop`+`pickup` chain reconstructs custody A→B with timestamps | K,C | bot |
| C8 | Item frame: place item, rotate, remove | Each step attributed (`use`, `entity-deposit/withdraw`); CP parity | K,C | man |
| C9 | Armor stand equipped then stripped by another player | Entity deposit/withdraw attribution | K | man |
| C10 | Bundle: insert 3 item types, extract 1 (`bundle-insert/extract`) | SG records both; **CP expected N/A** — confirm | K | man |
| C11 | Crafter (1.21) crafts an item | `crafter` event with result; CP parity check | K | man |
| C12 | Trial vault unlocked by player (`vault`) | Loot + player recorded; CP parity check | K | man |
| C13 | Suspicious sand brushed (`brush`) | Extracted item + player recorded; CP parity check | K | man |
| C14 | Lectern: book placed, page turned, book taken | Which side records what; theft of the book attributable | K,C | man |

## D. Entities — D1–D10

| # | Scenario | Verify | Dim | Auto |
|---|---|---|---|---|
| D1 | Player kills a cow | `death` recorded with killer; search by killer works | C | bot |
| D2 | Player kills a **named** pet (wolf "Rex") | Name in the record; entity rollback resurrects the mob (SG experimental NBT vs CP mob restore) — compare fidelity: name, tame owner, collar | C,K | man |
| D3 | Mob renamed via nametag (`named`) | Rename attributed | K | man |
| D4 | Player mounts/dismounts horse and boat | `mount`/`dismount` trail; CP expected N/A | K | man |
| D5 | Arrow shot hits another player (`shot`/`hit`) | PvP forensics: shooter, victim, damage | K | man |
| D6 | Painting and item frame broken by player | Hanging-entity attribution both sides | C,K | man |
| D7 | Leashed mob dies to lava | Death source = environment, not the leasher; no false attribution | C | man |
| D8 | Spawn egg used (`use`) | "Who spawned the warden" answerable | K | man |
| D9 | Villager killed by zombie | Non-player kill attributed to mob; noise level acceptable | C,S | man |
| D10 | Wither destroys blocks while fighting | Entity grief rollbackable; attribution to wither | C | rcon |

## E. Player meta — E1–E8

| # | Scenario | Verify | Dim | Auto |
|---|---|---|---|---|
| E1 | Chat messages (`say`) searched by player + keyword | Hits correct; SG's 1 MB-message hard cap truncates gracefully | K,C | bot |
| E2 | Commands logged (`command`) | Full command line recorded; compare what each side captures (CP logs commands too) | K | bot |
| E3 | Join/quit cycle with address extra | Session reconstruction (online window) from records | K | bot |
| E4 | Player teleports (`teleport`) | Movement forensics; CP expected N/A | K | bot |
| E5 | Player dies, drops scatter, another player loots | Death + drop + pickup chain identifies the looter | C,K | bot |
| E6 | Quit during combat (logger) | Quit timestamp + last events tell the story | U | bot |
| E7 | 50 players' worth of bot chatter for an hour | Chat search latency + storage growth comparison | P,S | bot |
| E8 | Operator without permission tries search/rollback/wand | Clean denial, no stack traces, no partial leakage | U | bot |

## F. Natural & environment — F1–F10

| # | Scenario | Verify | Dim | Auto |
|---|---|---|---|---|
| F1 | Flint-and-steel fire spreads and burns a build | Ignition attributed to the player; burn damage rollbackable as one incident | C,K | man |
| F2 | Player-placed TNT explodes | Crater rollback attributes to the placer | C | bot |
| F3 | Creeper blows up a wall | Environment-source rollback restores the wall | C | rcon |
| F4 | Enderman picks up and places blocks | Grief recorded and rollbackable | C | man |
| F5 | Water flows into a build (`form`/flow) | Flow recorded; rollback dries it out without breaking the source ledger | C | bot |
| F6 | Ice melts / snow forms over a region | Natural churn volume: rows/bytes per 1000 events compared (noise cost) | S | rcon |
| F7 | Tree chopped, leaves decay (`decay`) | Rollback restores trunk AND canopy | C | bot |
| F8 | Sculk spreads from a mob death (`sculk`) | Spread recorded; rollbackable; CP parity check | C,K | man |
| F9 | Wither spawn breaks containment blocks | Entity grief rollback | C | rcon |
| F10 | Lightning starts a fire | Attribution chain (natural); rollback scope correct | C | rcon |

## G. Rollback semantics — G1–G14

| # | Scenario | Verify | Dim | Auto |
|---|---|---|---|---|
| G1 | Standard grief → rollback | Server-truth sampling (`verify-rollback.js`): 100% of sampled cells match pre-grief | C | bot |
| G2 | Rollback → undo | World returns to exact post-grief state (undo = inverse) | C | bot |
| G3 | Rollback, manual edits inside region, identical rollback re-run | Changed blocks skipped with reasons; SG store grows +1 op row (synthesis); CP growth measured | C,S | bot |
| G4 | Two overlapping ops by one operator; undo twice | LIFO per-operator undo ordering correct both times | C | bot |
| G5 | Player standing inside region during rollback | No suffocation in restored blocks / sane player handling both sides | U | man |
| G6 | Preview before applying | **CP `#preview` exists; SG has none — expected CP-ahead capability cell** | K,U | man |
| G7 | Cancel mid-rollback | Partial application reported accurately; world consistent; re-runnable | C,U | bot |
| G8 | `kill -9` the server mid-rollback, restart | SG resume marker offers continuation and completes; CP behavior documented | K,C | man |
| G9 | Region half loaded, half unloaded | Both halves restored equally | C | bot |
| G10 | Search `a:rolled-place` after a rollback, then attempt to roll back those entries | Audit entries visible in search; not themselves re-rollbackable into a feedback loop | C,U | bot |
| G11 | Crater containing chests with loot | Tile entities restored with contents post-rollback | C | bot |
| G12 | Over-broad rollback fixed by `restore` of the same query | World returns to pre-rollback state exactly | C | bot |
| G13 | Rollback everything except chests (negation filter) | Exclusion honored both sides | C,K | bot |
| G14 | Rollback while 600 ev/s of live traffic ingests | Read-your-writes: just-recorded grief included (flush gate); no lost or phantom restores | C,P | bot |

## H. Query & search capability — H1–H12

| # | Scenario | Verify | Dim | Auto |
|---|---|---|---|---|
| H1 | `p:<player> t:1h r:10` | Result sets equivalent (same incidents surfaced) | C,U | bot |
| H2 | Page through 10,000 results while new events arrive | Stable pagination, no skips/dupes | C,P | bot |
| H3 | Search by item name `iname:Excaliblur` | **SG capability; CP expected N/A** | K | bot |
| H4 | Search by lore line `ilore:` | SG-only expected | K | bot |
| H5 | Search by enchantment `ench:sharpness=5` | SG-only expected | K | bot |
| H6 | Multiple players `p:a,b` + multiple actions | Filter combination parity | K | bot |
| H7 | Negations (exclude a player, exclude an action) | Parity of negative filters | K | bot |
| H8 | Cross-server search via Velocity proxy (`srv:`) | SG-only expected (CP has no proxy story) | K | man |
| H9 | Wand/inspector click on grief block | Full block history incl. synthesized rolled entries; CP inspector parity; click-to-teleport UX notes | U,C | man |
| H10 | Time syntax: `t:30s`, `t:4w`, `t:1d2h` | Window math exact; no DST/timezone drift across a boundary | C | bot |
| H11 | Global search without radius (`-g`) | Permission-gated; default radius enforced when omitted | U | bot |
| H12 | Search latency at 150M-row store during 600 ev/s ingest | P50/P99 of common lookups: SG-Mongo vs SG-CH vs CP-CH | P | bot |

## I. Scale & performance — I1–I8

| # | Scenario | Verify | Dim | Auto |
|---|---|---|---|---|
| I1 | The 2M cube standard bench (`compare.js`) | Full scorecard: wall-clock, worst tick, GC log, TPS — the campaign baseline rerun per release | P | bot |
| I2 | 10M-block rollback | SG unlimited cap + streaming holds (bounded heap); CP at 10M measured or fails | P,K | bot |
| I3 | Same 2M rollback at 10M vs 150M total store rows | SG flat (keyset+projection); CP degradation curve with history depth | P | bot |
| I4 | 100 sequential 20K rollbacks | Per-op overhead amortization; queue behavior | P | bot |
| I5 | 2M rollback while 10 bots walk/build | Worst tick with real player load | P | bot |
| I6 | `//set` 5M blocks ingest burst | Queue-depth warning fires (no drops, unbounded queue); drain time; CP ingest behavior under same burst | P,S | bot |
| I7 | Identical grief+rollback cycle ×10 re-runs | SG store grows +1 row per re-run; CP growth per re-run measured | S | bot |
| I8 | First rollback after cold boot vs warmed | First-op penalty quantified both sides | P | bot |

## J. Operations & resilience — J1–J8

| # | Scenario | Verify | Dim | Auto |
|---|---|---|---|---|
| J1 | DB down during play with `durability=wal-batched` | Events fsynced to WAL, replayed on DB return — zero loss; CP behavior with DB down documented | K,C | man |
| J2 | DB dies mid-rollback | Clean operator-facing failure; resumable once DB returns | U,C | man |
| J3 | Retention expiry (`4w`) + undo TTL (24h) | Expired events leave search AND disk (after merges); expired undo fails gracefully with a clear message | C,S,U | rcon |
| J4 | Same suite on Mongo backend | Every C-dimension case above passes identically on Mongo (backend parity) | C | bot |
| J5 | `kill -9` mid-ingest-burst with WAL, restart | Replay produces no duplicate rows post-merge (ReplacingMergeTree dedup) | C | man |
| J6 | Two servers share one store with distinct `server.name` | `srv:` partitions results correctly; rollback scoped to own server | C,K | man |
| J7 | Store written by a pre-synthesis build (persisted receipts) read by current build | Legacy rolled-* rows still searchable alongside synthesized ones; no double-render | C | rcon |
| J8 | Full permission matrix (search/rollback/restore/undo/wand/global) | Each node gates exactly its commands; failure messages clean | U | bot |

---

## Known asymmetries the suite encodes

Going in, these are the *expected* capability splits to confirm or refute — each is a scored case above, not an assumption:

- **Expected SG-only:** item name/lore/enchant search (H3–H5), cross-server proxy search (H8), bundle ops (C10), mount/teleport trails (D4, E4), crash-resume of an interrupted rollback (G8), WAL durability (J1), unlimited rollback size + flat lag profile at scale (I2–I3), +1-row re-run storage (I7).
- **Expected CP-ahead:** `#preview` (G6 — SG has no preview mode today). Anything else CP-ahead that the suite surfaces gets filed as an issue.
- **Known SG loss by design:** bytes/row (~3× CP) — richer forensic payload; tracked under S-dimension cases (F6, I6–I7) so the trade stays measured, not assumed.

## Suggested execution order

1. **Automate the `bot` rows** as scripted scenarios in `regression/bot/` (one `cases/` module per category; `compare.js` stays the perf harness). ~60 cases automatable.
2. Run A–C + G + H first (correctness core), both backends (J4), same server boot.
3. The `man` rows become a checklist run on the staging server once per release.
4. Every case failure or CP-ahead discovery → GitHub issue with the case ID in the title.
