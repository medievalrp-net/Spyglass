# Can JVM flags eliminate the rollback GC freeze? (A/B, 2026-06-10)

Three same-day `compare.js` runs of the **2M-block rollback** (126³, ClickHouse, 6 GB heap, same deployed jar — main `c2903f7`), varying only the collector flags. Question under test: can a server owner tune away the 0.3–1.0 s freeze that large rollbacks provoke at `rollback-page-size = 1000000`, without plugin changes?

**Answer: no.** Every variant relocates the freeze instead of removing it. The fix has to be in the plugin's allocation/retention behavior — #17 (streaming undo capture + paged replay, shipped with this report) and #19 (streaming the page read into effect collection).

| 2M rollback, 6 GB heap | wall-clock | worst in-window stall | mechanism |
|---|---|---|---|
| **Stock Aikar G1** (baseline) | **8.7 s · 229 k bps** | 354 ms stop-the-world | Mixed-GC evacuations of prematurely promoted page data (`MaxTenuringThreshold=1` promotes anything surviving one young GC; pages live ~2–3 s) + 22–40 humongous regions from 1M-row arrays |
| **G1 retuned** (`MaxTenuringThreshold=4`, `SurvivorRatio=8`, `G1HeapRegionSize=32M`) | 8.8 s | **818 ms** stop-the-world | Humongous solved (40 → 3 regions) but ×4 survivor copying makes each evacuation bigger — fewer, *larger* pauses. CoreProtect's on-main phases also degraded (worst tick 484 → 626 ms) |
| **Generational ZGC** (Temurin 25, `-XX:+UseZGC`) | 14.5 s (+67 %) | **1031 ms** worst tick | GC pauses genuinely eliminated (276 pauses, max **0.49 ms**) — but at ~1 GB/s allocation the collector can't keep up on 6 GB: heap hit 100 % and **allocation stalls blocked the Server thread directly** (64 stall events; `Allocation Stall (Server thread) 56.9ms` repeatedly) |

Reading the table: the MSPT-derived "worst tick" and the GC log must be read together — stock G1's 354 ms pause produced only a 191 ms worst tick because off-main work lets pauses land between measured ticks (see the [June 3 report](rollback-bench-results.md), *The TPS metric is lying*).

## Why each lever fails

- **Aikar's flags are correct for Minecraft and wrong for batch ops.** `MaxTenuringThreshold=1` + `SurvivorRatio=32` assume anything surviving two young GCs is long-lived — true for tick-transient data, false for a rollback page that lives seconds and then dies. Every page is force-promoted; G1 answers with Mixed evacuations mid-operation. Old gen grew 239 → 380 regions (+~1.1 GB) in 5 s during the baseline rollback.
- **Raising tenuring just moves the copying.** Pages then bounce through survivor space up to 4×, and the eventual evacuation is bigger (818 ms observed in-window). Steady-state tick latency also degrades — the exact trade Aikar's values were chosen to avoid.
- **ZGC needs headroom this workload doesn't leave.** Sub-millisecond pauses are real, but on a 6 GB heap the rollback's allocation rate outruns concurrent collection and ZGC's backpressure is to stall the *allocating thread* — which is the main thread. With ~10+ GB it would likely hold (untested); that's a per-host luxury, not a shippable default.

## Operative conclusion

Customers run stock flags. The plugin must be smooth there, which means not allocating/retaining gigabytes per rollback in the first place:

- **#17 (this branch):** undo capture streams to the ledger per page and `/undo` replays per chunk — removes the whole-operation inverse list, the dominant *retained* promotion source, and unblocks `/undo` past ~250 K effects.
- **#19 (next):** stream the ClickHouse/Mongo page read straight into effect collection — removes the two-pages-of-records *transient* and its humongous arrays at any page size.

Repro: `RP_Server/start-bench-zgc.sh` and `RP_Server/start-bench-g1tuned.sh` (flag deltas inline above); GC logs in `RP_Server/logs/gc-bench-{zgc,g1tuned}.log`; bench driver `regression/bot/compare.js`.
