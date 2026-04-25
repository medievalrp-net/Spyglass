# Spyglass — no-drop contract for ingest

Decision record + verification log for the AsyncRecorder no-drop fix landed 2026-04-24. Sibling document to [`ingest-bench-results.md`](ingest-bench-results.md) (which has the full performance writeup) and [`gap/decisions.md`](gap/decisions.md) (which has the rest of the v2 design decisions).

---

## The contract

> **Every event that fires a listener reaches the store.**

This was v1's behaviour, has been for as long as v1 has run on MedievalRP, and is the behaviour operators expect from an audit-logging plugin. v2 inherits the same contract verbatim. If `record()` returns, the event is durable — modulo two narrow remaining loss paths described below, both of which v1 has the same exposure to.

The contract is non-negotiable. An audit log that silently drops events under load can't be trusted: if `/sg rollback` returns "no events here" or `/sg inspect` shows a clean block, the operator has no way to tell whether nothing happened or the plugin lost the record. That ambiguity destroys the tool's value, and there is no useful UX that reintroduces it.

## What changed and why

v2's [`AsyncRecorder`](../../spyglass/src/main/java/net/medievalrp/spyglass/plugin/pipeline/AsyncRecorder.java) originally used a **bounded** `LinkedBlockingDeque`. When the queue filled, `record()` returned without enqueueing and incremented a `dropped` counter. The reasoning at the time was "bounded queues are good defensive programming; the drop counter exposes the problem to operators." That reasoning was wrong for this domain — silently shedding 90%+ of a burst (as the overload bench scenario demonstrated) violates the v1 contract regardless of whether the loss is counted.

The fix:

| Aspect | Before | After |
|---|---|---|
| Queue type | bounded `LinkedBlockingDeque` (cap = 100k) | unbounded `LinkedBlockingDeque` |
| `record()` behaviour | returns false + increments `dropped` when full | always succeeds; warns at threshold + at depth doublings |
| `queue-capacity` config key | drop ceiling | warn threshold (kept for back-compat) |
| Drain failure on transient Mongo error | retried — already correct | retried — unchanged |
| Shutdown flush failure | gave up after one attempt | retries with exponential backoff bounded by shutdown deadline |
| `dropped` counter increments | every queue-full intake + every shutdown failure | **only** in the catastrophic case where Mongo is unreachable for the entire shutdown flush window |

The full implementation rationale lives in the [`AsyncRecorder` Javadoc](../../spyglass/src/main/java/net/medievalrp/spyglass/plugin/pipeline/AsyncRecorder.java) — read the class-level comment for the no-drop invariant and the two remaining loss scenarios.

## Residual loss exposure (same as v1)

Two narrow paths can still lose data. Both require spill-to-disk to fully eliminate, and v1 has the same exposure today:

1. **Hard JVM death.** Server crash, SIGKILL, OOM, power loss. The queue is RAM-resident; anything queued but not yet `bulkWrite`-acked dies with the process. v1 has the same window.

2. **Shutdown flush deadline exhaustion.** [`flushRemaining`](../../spyglass/src/main/java/net/medievalrp/spyglass/plugin/pipeline/AsyncRecorder.java) retries with exponential backoff for the full configured `flush-timeout` (5 s by default). If Mongo is still unreachable when the deadline expires, the unflushed batch is counted into `ShutdownReport.dropped` and logged at `SEVERE`. Under normal Mongo availability this code path is unreachable. v1 has the same exposure but doesn't account for it: v1's `EntryQueueRunner.shutdown()` makes one synchronous drain attempt and silently loses the batch on any exception.

Aside from those, records are durable once `record()` returns.

### What would close those paths

Spill-to-disk: write the queue contents to a local journal file (e.g. an MMAP'd append-only log) on every `record()` and replay on startup. Closes both paths at the cost of disk I/O on every event and a startup recovery phase. Not implemented in v2 because:

- The hard-JVM-death path is rare on MedievalRP's setup (containerised JVM, supervised restarts, Mongo-on-localhost).
- The flush-deadline-exhaustion path requires a sustained Mongo outage during shutdown, which has never been observed on the production server.
- Spill-to-disk is the kind of feature that's easy to add later when there's a concrete operator ask, hard to remove once added.

The signal for revisiting: an operator reports a missing-event incident traceable to one of those two paths, OR Mongo availability becomes unreliable enough that the flush-deadline path stops being theoretical.

## Verification

### Test coverage

The following tests cover the no-drop invariant and every loss-eligible code path. All eight pass on the current main:

| Test | What it proves |
|---|---|
| [`AsyncRecorderTest.drainsBatchesToStore`](../../spyglass/src/test/java/net/medievalrp/spyglass/plugin/pipeline/AsyncRecorderTest.java) | Basic happy-path: 50 records in, 50 records out. |
| [`AsyncRecorderTest.neverDropsAtIntakeEvenWhenStoreIsStalled`](../../spyglass/src/test/java/net/medievalrp/spyglass/plugin/pipeline/AsyncRecorderTest.java) | Latched store + 50 offers far above the warn threshold. Verifies `dropped == 0`, every record reaches the store after the latch opens. |
| [`AsyncRecorderTest.flushesRemainingOnShutdown`](../../spyglass/src/test/java/net/medievalrp/spyglass/plugin/pipeline/AsyncRecorderTest.java) | Shutdown flush drains a quiescent queue with no loss. |
| [`AsyncRecorderTest.drainRecoversAfterTransientStoreFailures`](../../spyglass/src/test/java/net/medievalrp/spyglass/plugin/pipeline/AsyncRecorderTest.java) | Drain thread survives transient `RuntimeException` from the store and continues processing subsequent records. Regression test for the silent-drain-death bug. |
| [`AsyncRecorderTest.shutdownFlushExhaustionCountsDroppedRecordsAndLogsSevere`](../../spyglass/src/test/java/net/medievalrp/spyglass/plugin/pipeline/AsyncRecorderTest.java) | The catastrophic path. `AlwaysFailingStore` + 1 s shutdown deadline. Verifies `dropped == 7`, `drained == 0`, `remaining == 0`, `attempts >= 1` — the only path where data loss is accounted for. |
| [`AsyncRecorderConcurrencyTest.manyProducersReachStoreWithoutLoss`](../../spyglass/src/test/java/net/medievalrp/spyglass/plugin/pipeline/AsyncRecorderConcurrencyTest.java) | 16 OS threads × 1 000 records: every record reaches the store, `dropped == 0`. |
| [`AsyncRecorderConcurrencyTest.noDropsUnderHeavyContentionEvenWithSlowStore`](../../spyglass/src/test/java/net/medievalrp/spyglass/plugin/pipeline/AsyncRecorderConcurrencyTest.java) | 8 producers × 2 000 records + 2 ms-per-save slow store. Verifies `drained + dropped + remaining == offered` (counter atomicity), `dropped == 0` (no-drop under contention), `drained == offered` (everything persisted). |
| [`AsyncRecorderConcurrencyTest.shutdownDuringActiveIngestIsBoundedAndConsistent`](../../spyglass/src/test/java/net/medievalrp/spyglass/plugin/pipeline/AsyncRecorderConcurrencyTest.java) | Yank the recorder while a producer is still pushing records. Shutdown completes within timeout + slack; no records are double-counted; `dropped == 0` under a healthy store. |

Plus the [`IngestThroughputBench`](../../spyglass/src/test/java/net/medievalrp/spyglass/plugin/pipeline/IngestThroughputBench.java) overload scenario (`overload_neither_pipeline_drops`) asserts `v2.dropped == 0` and `v2.saved == 200_000` against a real Mongo container — a realistic regression test if the no-drop invariant is ever weakened.

### Bench results

Headline numbers from the 2026-04-24 in-process bench against Mongo 7.0 (testcontainers). Full writeup in [`ingest-bench-results.md`](ingest-bench-results.md).

| Scenario | v1 saved | v2 saved | v1 dropped | v2 dropped |
|---|---|---|---|---|
| Sustained 10k ev/s × 15 s (150k ops) | 150 000 | 150 000 | 0 | 0 |
| Burst 8 × 25 000 (200k ops) | 200 000 | 200 000 | 0 | 0 |
| Overload 200k @ v2 warn=10k | 200 000 | 200 000 | 0 | 0 |
| **Total** | **550 000** | **550 000** | **0** | **0** |

The contract holds across all three bench scenarios, including a deliberate overload of 200 000 records pushed past v2's warn threshold in 400 ms (~500 k ev/s instantaneous offered rate, against a Mongo that saves at ~50 k/s).

Latency profile, for completeness: v2 wins on steady-state (p50 8.5 ms vs v1 40 ms @ 10k ev/s; p99 103 ms vs 179 ms). v1 wins on burst-drain wall-clock (one giant bulkWrite per tick is efficient at clearing a backlog), but at the cost of stalling the tick that drains. Detail in the bench doc.

## Operator-facing observability

Three signals surface backlog problems in the plugin log, designed to give operators progressively more urgent heads-up:

1. **First warn threshold crossing.** `WARNING: Spyglass recorder queue depth N (warn threshold T). No records dropped — queue is unbounded — but heap pressure grows with depth. Check Mongo reachability and drain latency.` Fires once when the queue first exceeds `queue-capacity`. Atomic-CAS on `lastWarnedDepth` so concurrent `record()` callers don't duplicate the line.

2. **Queue depth doublings.** Same warn line, fires at every depth doubling past the first crossing. Five warns ⇒ queue is at ~16× the threshold. Sustained Mongo outage produces a visible growth shape in the log without flooding it.

3. **Shutdown flush failure.** `SEVERE: Recorder shutdown flush gave up within deadline; N records could not be persisted and are lost. Mongo was unreachable through the full flush-timeout. Consider spill-to-disk if zero-loss across Mongo outages is required.` This is the catastrophic case — operators should investigate Mongo availability and consider whether `flush-timeout` needs to be longer or whether spill-to-disk is needed.

`ShutdownReport.dropped()` is the programmatic counterpart to signal #3. The plugin's `onDisable` logs the report; future versions could expose it via a `/sg stats` command.

## Configuration

`config.conf` carries the warn threshold under the legacy key name for back-compat:

```hocon
storage {
  retention = "4w"
  # WARN THRESHOLD — not a drop ceiling. The ingest queue is unbounded
  # (never drops events at intake, same contract as v1).
  queue-capacity = 100000
  flush-timeout = "5s"
}
```

Tuning guidance:

- **`queue-capacity`** — sized for ~10× steady-state peak. At MedievalRP's ~600 ev/s observed peak, 100 000 gives ~160 s of slack before the first warn line. Do not set this lower than ~10× expected peak ingest rate, or warn lines will fire on routine bursts (WorldEdit pastes, mob-farm destruction events) and operators will start ignoring them.
- **`flush-timeout`** — upper bound on `onDisable` wall time. Increase if Mongo recoveries take longer than 5 s on your deployment; the flush will retry the same batch with exponential backoff for the full window.

## Commit history

The fix landed across two commits on `main`:

- [`799ee5a`](https://github.com/medievalrp-net/Spyglass/commit/799ee5a) — `Make AsyncRecorder intake unbounded to match v1 no-drop contract`. The core change: unbounded queue, warn-threshold semantics, hardened flush-with-deadline retry, test rewrite, doc rewrite.
- [`1f0b490`](https://github.com/medievalrp-net/Spyglass/commit/1f0b490) — `Cover the catastrophic shutdown-flush-exhaustion path + drop CI workflow`. Added the missing test for the only path that increments `dropped`. Removed `.github/workflows/build.yml` (CI is run locally pre-push for this repo).

## Future work signals

The contract holds today. Re-evaluate these in priority order if the conditions shift:

1. **Spill-to-disk.** If a missing-event incident is ever traced to JVM crash or to flush-deadline exhaustion, this becomes the right answer. Existing API surface (`Recorder.record()`, `Recorder.shutdown()`) doesn't change; only the implementation under it.
2. **Backpressure signalling to listeners.** If queue depth ever sustainedly exceeds operator tolerance (the warn line is firing for hours rather than minutes), the listeners could read a `Recorder.depth()` signal and shed expensive optional captures (block-state extraction, container-NBT scrapes) ahead of recording. Lossy by design at the listener layer, but the lossy decision is observable and configurable rather than buried in a queue.
3. **`/sg stats` command.** Surface `drained` / `dropped` / `remaining` and queue high-water from the running recorder without needing to grep the log. Cheap to add when there's a concrete operator ask.
