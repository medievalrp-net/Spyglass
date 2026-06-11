# Use-case suite results — 2026-06-11

First full execution of the [comparison catalog](../../regression/use-cases.md) against Spyglass (main `c2e00a4` + #19) and CoreProtect-ClickHouse, same server, same run (Paper 1.21.4, ClickHouse backend, runner: `regression/bot/cases/`). 47 of 106 cases automated this round; 59 remain a manual/staging checklist (choreography-heavy, env-blocked, or perf-campaign-covered).

| | pass | fail | n/a-capability | manual |
|---|---|---|---|---|
| **Spyglass** | 35 | 12 | 0 | 59 |
| **CoreProtect** | 36 | 4 | 7 | 59 |

Every Spyglass failure is a filed issue; every CoreProtect failure is reproduced evidence, not an assumption.

## Spyglass findings (filed)

| Issue | Cases | Finding |
|---|---|---|
| [#27](https://github.com/medievalrp-net/Spyglass/issues/27) | A5, G3 | Parallel fast-path palette writes skip the expected-state check: rolling back player A **overwrites player B's newer edit** (CP preserves it), and identical re-runs re-apply instead of skipping. |
| [#28](https://github.com/medievalrp-net/Spyglass/issues/28) | C1 | Chest **deposit** rollback fails (`0 reversals, 1 skipped, 1 error`) — items stay in the chest. Withdraw rollback (C2) works. |
| [#29](https://github.com/medievalrp-net/Spyglass/issues/29) | D1 | Entity-kill rollback skips instead of resurrecting (CP resurrects the mob on the same run). |
| [#30](https://github.com/medievalrp-net/Spyglass/issues/30) | G13, H7 | Param negation: **`b:!chest` silently inverts to `b:chest`** (rolls back exactly the protected material — dangerous); `a:!place` errors loudly. CauseParam has the `!` pattern; Event/Block/Player params don't. |
| [#31](https://github.com/medievalrp-net/Spyglass/issues/31) | G4 | `/undo` of an undo ping-pongs (replay pushes its own reference) — older ops unreachable by repeated `/undo`. |
| [#32](https://github.com/medievalrp-net/Spyglass/issues/32) | H3–H5 | `iname:`/`ilore:`/`ench:` **error on the ClickHouse backend** (nested-snapshot fields live in opaque BSON) — flagship filters are Mongo-only today. |
| [#33](https://github.com/medievalrp-net/Spyglass/issues/33) | H11 | Global search (`-g`, no action filter) renders nothing when the window is rollback-op dense — suspected synthesis-expansion stall. |
| [#34](https://github.com/medievalrp-net/Spyglass/issues/34) | F2 | TNT crater rollback by placer is partial (4/6 sampled blocks); CP attributes explosions to `#tnt`, not the placer, so closing this is a differentiator. |

## CoreProtect findings (reproduced, for the comparison story)

- **A4** — place-then-break of the same block: CP rollback leaves a **ghost block** (Spyglass nets to air correctly).
- **B3** — chest restore content anomaly (3× reproduced): CP restored a duplicate sword where the golden apple belonged. Caveat: the scenario interleaves SG world-writes CP can't see; needs an isolated CP-only confirmation before quoting externally.
- **B12** — CP **loses `waterlogged=true`** restoring broken stairs; Spyglass restores the exact state.
- **H6** — one unknown name in `u:a,b` rejects the whole CP lookup; Spyglass filters normally.
- **Capability n/a** (by design, suite-confirmed): no teleport logging (E4), no undo (G2/G4 — restore is a manual inverse), no item name/lore/enchant filters (H3–H5), no synthesized audit (I7: SG grows +6 op rows and 0 per-block receipts for 3 rollback+undo cycles of the same grief).
- **CP-ahead cell**, unchanged: `#preview` (G6) — Spyglass has no preview mode.

## Where both passed (the parity core)

Block basics incl. exact BlockData/stair states (A1–A3, A6–A10), double chests (B4), beds/doors as pairs (B10/B11), waterlogged states (B12 SG), chest-with-loot craters (G11), unloaded-chunk rollbacks (A10, G9), rollback→undo→restore inversions (G1/G2/G12), rolled-audit visibility without re-rollback loops (G10), permission gating (E8, J8), chat/command/session/teleport forensics (E1–E4), custody chains (C7), pagination at 2K events (H2), time-syntax parity (H10), per-actor scoping (A5 CP-side, A7–A9).

## Caveats

- One execution environment (the bench server), one run each — fail verdicts were re-run and are deterministic; pass verdicts are single-run.
- The bench server carries extra plugins (a graylist gated E8's non-op path — noted in the case) and blocks creeper explosions near players (F3 → manual).
- CP comparisons used `u:`/`a:`/`e:`/`b:` equivalents; where CP groups output (C3) assertions accept grouping.

## Full matrix

| case | SG | CP | notes |
|---|---|---|---|
| A1 single block break: record + rollback on both sides | pass | pass |  |
| A2 single block place: record + rollback on both sides | pass | pass |  |
| A3 mixed-material strip incl. stair states restores exact Block | pass | pass |  |
| A4 place-then-break same block nets to air (no ghost block) | pass | fail | CP left a ghost block |
| A5 two actors, one block: per-actor rollback semantics | fail | pass | state=air, line=«Spyglass» 1 reversals across 1 chunk in 82ms — expected cobble + skip; CP per-actor semantics: block=air after rolling back only A (SG: air) |
| A6 radius boundary r:5: outside untouched on both sides | pass | pass | boundary at exactly 5: SG included; boundary at exactly 5: CP included |
| A7 time window: t:8s only reverts the younger grief | pass | pass |  |
| A8 action filter: roll back only breaks, placements stay | pass | pass |  |
| A9 block filter: roll back only diamond ore | pass | pass |  |
| A10 rollback into unloaded chunks loads and restores | pass | pass |  |
| B1 sign with styled text broken + restored | manual-deferred | manual-deferred | sign text entry needs the sign-editor packet flow |
| B2 sign text edited, edit rolls back | manual-deferred | manual-deferred | sign-editor packet flow |
| B3 chest with named+enchanted loot restores exactly | pass | fail | CP restored: sword=he following block data: {"minecraft:enchantments": {"minecraft:sharpness": 5}, "minecraft:custom_name": '"Excaliblur"'} apple=diamond_sword", count: 1, components: {"minecraft:enchantments": {"minecra |
| B4 double chest: both halves + contents, no duplication | pass | pass |  |
| B5 shulker box with items | manual-deferred | manual-deferred | shulker pickup/contents flow needs inventory choreography |
| B6 banner with patterns | manual-deferred | manual-deferred | pattern NBT round-trip needs visual/NBT-deep verify |
| B7 jukebox with disc | manual-deferred | manual-deferred | disc insert via right-click choreography |
| B8 decorated pot sherds | manual-deferred | manual-deferred | pot insert/remove choreography |
| B9 chiseled bookshelf slots | manual-deferred | manual-deferred | slot-targeted clicks needed |
| B10 bed: breaking one half restores both halves | pass | pass |  |
| B11 door: breaking lower half restores the pair | pass | pass |  |
| B12 waterlogged stairs keep waterlogged=true through rollback | pass | fail | CP lost waterlogged state |
| C1 chest deposit: record + container rollback both sides | fail | pass | chest still holds: 16969, 80, 16008 has the following block data: [{Slot: 0b, id: "minecraft:iron_ingot", count: 64}]; C1 SG rollback line: «Spyglass» 0 reversals in 149ms. 1 skipped, 1 error |
| C2 chest withdraw: rollback puts the loot back | pass | pass |  |
| C3 open→deposit→withdraw→close session ordering | pass | pass | CP records container transactions only — no open/close events (SG-extra forensics) |
| C4 hopper drains a chest: automation attribution | manual-deferred | manual-deferred | hopper timing flaky in suite; verify on staging with a:withdraw + cause filters |
| C5 hopper minecart attribution | manual-deferred | manual-deferred | needs cart choreography |
| C6 dropper fires into chest | manual-deferred | manual-deferred | needs redstone choreography |
| C7 drop → pickup custody chain across two players | pass | pass |  |
| C8 item frame place/rotate/remove | manual-deferred | manual-deferred | entity right-click choreography |
| C9 armor stand equip/strip | manual-deferred | manual-deferred | entity interaction choreography |
| C10 bundle insert/extract | manual-deferred | manual-deferred | inventory-GUI bundle clicks not bot-scriptable; SG events exist, CP expected N/A |
| C11 crafter crafts | manual-deferred | manual-deferred | needs redstone pulse rig |
| C12 trial vault unlock | manual-deferred | manual-deferred | needs trial chamber + key |
| C13 suspicious sand brush | manual-deferred | manual-deferred | brush choreography |
| C14 lectern book place/take | manual-deferred | manual-deferred | lectern GUI choreography |
| D1 player kills a mob: record + entity rollback both sides | fail | pass | no cow after entity rollback; D1 SG rollback line: «Spyglass» 0 reversals in 129ms. 1 skipped; SG undo of resurrection: cow count 0→0 |
| D2 named pet kill + resurrection fidelity | manual-deferred | manual-deferred | tame/name choreography; fidelity diff (name, owner, collar) needs NBT-deep compare |
| D3 nametag rename | manual-deferred | manual-deferred | entity right-click choreography |
| D4 mount/dismount trail | manual-deferred | manual-deferred | mount choreography; CP expected N/A |
| D5 PvP shot/hit forensics | manual-deferred | manual-deferred | two-bot ranged combat choreography |
| D6 painting/item frame broken | manual-deferred | manual-deferred | hanging entity placement choreography |
| D7 leashed mob dies to lava: no false attribution | manual-deferred | manual-deferred | leash + lava rig |
| D8 spawn egg attribution | manual-deferred | manual-deferred | egg use is bot-able but warden cleanup is not suite-safe; staging checklist |
| D9 villager killed by zombie | manual-deferred | manual-deferred | mob-vs-mob rig |
| D10 wither block grief rollback | manual-deferred | manual-deferred | boss fight not suite-safe |
| E1 chat recorded and searchable by player | pass | pass |  |
| E2 command recorded with full line | pass | pass |  |
| E3 session join searchable | pass | pass |  |
| E4 teleport trail | pass | n/a-capability | CP has no teleport logging |
| E5 death-drop loot theft chain | manual-deferred | manual-deferred | survival death choreography; custody core covered by C7 |
| E6 combat-log quit story | manual-deferred | manual-deferred | needs PvP rig |
| E7 hour-scale chat volume cost | manual-deferred | manual-deferred | long-horizon storage measurement; see perf campaign methodology |
| E8 permission denial is clean for non-ops | pass | pass | a Vesta graylist also gates non-op commands on this server — denial observed through it |
| F1 flint-and-steel fire spread attribution | manual-deferred | manual-deferred | fire spread is slow/random; staging checklist |
| F2 player TNT: crater attributed and rolled back | fail | pass | floor 4/6 after p:-rollback — TNT attribution gap?; CP needed u:#tnt (u:player → "CoreProtect - Rollback completed for "ucr0bk"."; u:#tnt → "CoreProtect - Rollback completed for "#tnt".") |
| F3 creeper explosion: environment-source rollback | manual-deferred | manual-deferred | reclassified manual: not reliably bot-scriptable on this server (see module note) |
| F4 enderman block grief | manual-deferred | manual-deferred | enderman pickup is rare/random; staging checklist |
| F5 water flow into a build: recorded and dried out | manual-deferred | manual-deferred | reclassified manual: not reliably bot-scriptable on this server (see module note) |
| F6 ice/snow churn storage cost | manual-deferred | manual-deferred | needs biome rig + long horizon; storage method in perf docs |
| F7 leaf decay rollback | manual-deferred | manual-deferred | decay timing is minutes-scale random; staging checklist |
| F8 sculk spread from mob death | manual-deferred | manual-deferred | sculk catalyst rig; staging checklist |
| F9 wither containment grief | manual-deferred | manual-deferred | boss fight not suite-safe |
| F10 lightning fire attribution | manual-deferred | manual-deferred | lightning rig; staging checklist |
| G1 grief→rollback: server-truth sampling, both sides | pass | pass |  |
| G2 undo is the exact inverse of the rollback | pass | n/a-capability | CP has no undo stack; /co restore is a manual inverse (see G12) |
| G3 identical rollback re-run: skips, no double-apply | fail | pass | re-run applied 64, expected 0 — fast-path force-overwrite, issue #27; CP rollback after SG already restored: CoreProtect - Rollback completed for "ucr0bk". (world already correct) |
| G4 undo stack is LIFO per operator | fail | n/a-capability | second undo did not reach the older op; CP has no undo stack to compare |
| G5 player inside region during rollback | manual-deferred | manual-deferred | needs a human to judge suffocation/clip UX |
| G6 preview before applying | manual-deferred | manual-deferred | CP #preview exists; SG has no preview — known CP-ahead capability cell |
| G7 cancel mid-rollback | manual-deferred | manual-deferred | needs a multi-second op; exercised ad-hoc during the perf campaign |
| G8 crash mid-rollback, resume on restart | manual-deferred | manual-deferred | kill -9 orchestration unsafe in the shared suite run; resume path unit/IT-covered |
| G9 grief spanning loaded + unloaded chunks restores both | pass | pass |  |
| G10 rolled-* audit entries visible but not re-rollbackable | pass | pass | CP lookup after CP-unrelated SG rollback shows raw events (4 lines) |
| G11 chest with loot inside the crater restores contents | pass | pass |  |
| G12 restore inverts an over-broad rollback | pass | pass |  |
| G13 exclusion filter: everything except chests | fail | pass | stone=air chest=chest; G13 SG b:!chest line: «Spyglass» 1 reversals across 1 chunk in 133ms |
| G14 rollback during live ingest (read-your-writes) | manual-deferred | manual-deferred | flush-gate behavior; covered by rollback-flush-timeout design + perf campaign, noisy in a shared suite |
| H1 p:+t:+r: triple returns the incident on both sides | pass | pass |  |
| H2 pagination across a ~2000-event result set | pass | pass |  |
| H3 search by item name (iname:) | fail | n/a-capability | no iname hit: Querying records... \| «Spyglass» (Error) Query failed: net.medievalrp.spyglass.plugin.storage.PredicateToSql$UnsupportedPredicateException: ClickHouse backend cannot filter on field 'item.name': nested-sna |
| H4 search by item lore (ilore:) | fail | n/a-capability | no ilore hit: Querying records... \| «Spyglass» (Error) Query failed: net.medievalrp.spyglass.plugin.storage.PredicateToSql$UnsupportedPredicateException: ClickHouse backend cannot filter on field 'item.lore': nested-sna |
| H5 search by enchantment (ench:) | fail | n/a-capability | no ench hit: Querying records... \| «Spyglass» (Error) Query failed: net.medievalrp.spyglass.plugin.storage.PredicateToSql$UnsupportedPredicateException: ClickHouse backend cannot filter on field 'item.enchants': nested- |
| H6 multi-player filter p:a,b | pass | fail | multi-u failed: CoreProtect - Lookup searching. Please wait... \| CoreProtect - User "notch" not found. |
| H7 negation filter excludes an action | fail | pass | negation leak: «Spyglass» (Error) Unknown or disabled event: !place |
| H8 cross-server search via Velocity proxy | manual-deferred | manual-deferred | needs the proxy rig; spyglass-velocity covered by its own tests |
| H9 wand/inspector parity on a grief block | manual-deferred | manual-deferred | wand interaction needs a human (or dedicated packet work) |
| H10 time syntax: t:30s and compound t:1d2h parse and bound | pass | pass |  |
| H11 default radius reminder + -g global flag | fail | pass | -g failed: Querying records... |
| H12 search latency on the ~160M-row store (informational) | pass | pass | lookup latency (incl. ~2s output-quiet window): SG 2884ms, CP 2568ms |
| I1 2M-cube standard bench | manual-deferred | manual-deferred | run regression/bot/compare.js — 2026-06-10: SG 7.7s/107ms worst tick vs CP 9.4s/288ms, both flat-20 TPS for SG |
| I2 10M-block rollback | manual-deferred | manual-deferred | heavy bench; run compare.js SIZES=10M on a dedicated session |
| I3 speed vs history depth | manual-deferred | manual-deferred | tracked across the perf campaign: SG flat via keyset+projection at 77M→160M rows; CP variance 9.2–16.7s |
| I4 100 sequential small rollbacks | manual-deferred | manual-deferred | heavy; dedicated session |
| I5 rollback under bot player load | manual-deferred | manual-deferred | multi-bot rig; dedicated session |
| I6 5M-block ingest burst behavior | manual-deferred | manual-deferred | queue-warning + drain measured in perf campaign; re-run per release via compare.js ingest phase |
| I7 storage growth per re-run: SG +1 op row, no per-block rows | pass | n/a-capability | CP growth-per-rerun measured at the 2M scale in the perf campaign (CP re-logs rolled blocks) |
| I8 cold-boot first-op penalty | manual-deferred | manual-deferred | needs controlled reboot; perf campaign measured warm/cold pairs |
| J1 DB down during play (WAL durability) | manual-deferred | manual-deferred | container kill orchestration; WAL replay is IT-covered (DurabilityIT) — staging drill per release |
| J2 DB dies mid-rollback | manual-deferred | manual-deferred | container kill orchestration; staging drill |
| J3 retention + undo TTL expiry | manual-deferred | manual-deferred | needs >24h horizon or clock control |
| J4 full suite on Mongo backend | manual-deferred | manual-deferred | backend swap + reboot + rerun: config database.backend=mongo, then runner.js --cat A,B,C,G,H |
| J5 kill -9 mid-burst, WAL replay dedup | manual-deferred | manual-deferred | crashtest.js exists for this; not suite-safe inline |
| J6 two servers, one store, srv: partitioning | manual-deferred | manual-deferred | needs second server instance |
| J7 pre-#22 persisted receipts still searchable | manual-deferred | manual-deferred | needs legacy-store fixture; decode-compat unit-tested (UndoReferenceBson v1, receipts mode IT) |
| J8 permission matrix: search/rollback/tool gated for non-ops | pass | pass |  |
