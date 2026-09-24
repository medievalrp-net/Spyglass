# Logging fixes #362–#367: local validation

The modern branch contains all six fixes. `maintenance/1.21.11` receives #364, the only one of these mechanics present in that version. No changes from this work have been pushed or published, and the personal 26.3 test server was not replaced.

## Build and runtime matrix

| Minecraft | Paper | Java | Build | Isolated runtime |
| --- | --- | --- | --- | --- |
| 1.21.11 | 132 | 21 | Passed; 991 tests passed, 93 skipped | Actual copper-golem AI and persisted slot history passed |
| 26.1.2 | 74 | 25 | Passed; 998 tests passed, 96 skipped | Age locking, copper transfers, persistence and inventory GUIs passed |
| 26.2 | 129 | 25 | Passed; 999 tests passed, 95 skipped | Age locking, sulfur interactions/spikes, copper transfers, persistence and inventory GUIs passed |
| 26.3 | 38 alpha | 25 | Passed; 1001 tests passed, 93 skipped | All applicable interaction and persistence checks passed; cushion and straw-bed rollback/undo passed |

Skipped tests include 92 Docker-backed storage tests (Docker unavailable) and one existing platform-specific test. Material-dependent tests also skip on targets that do not contain that material. BSON round-trip coverage includes the new cushion lifecycle record. Live persistence was verified against SQLite; the MongoDB and ClickHouse paths compiled but their container-backed integration tests did not run.

## What was exercised

- A real copper golem moved 16 diamonds from copper-chest slot 0 (32 to 16) into ordinary-chest slot 0 (1 to 17). Stored transfer records retained the golem UUID, exact slot, count and serialized before/after items.
- Golden dandelions locked and unlocked a cow, producing two records with the actual actor, entity identity and confirmed before/after state.
- Applicable sulfur cubes swallowed stone, ejected it when sheared, swallowed TNT, ignited, received a slimeball age boost, and were captured/released using a bucket. Stored history was checked separately from the interaction result.
- On 26.3, a red cushion was placed and destroyed through vanilla server methods. Both records retained identity and serialized state. `/sg rollback ... --entities` restored exactly one red cushion; `/sg undo` removed it.
- On 26.3, consumption recorded both straw-bed halves. Rollback restored both with `occupied=false`; undo removed both.
- Applicable floor and ceiling sulfur-spike columns lost their supports and disappeared. Four distinct break rows were checked, one per segment.

Existing snapshot and rollback-storage GUIs were also exercised on the isolated fixtures. For 26.3, the bridged 1.21.8 client cannot pass the ordinary placement control even without Spyglass, so wand placement uses the documented Paper-event probe; GUI interactions still use real client clicks. The logging probe invokes vanilla server methods and observes real Paper events. This is not a native 26.x client packet certification.

## Remaining coverage limits

Live permutations for weathered/waxed and double chests, concurrent-machine stress, sulfur dispenser/ground-pickup paths, sulfur WorldEdit/explosion cascades and cushion cancellation/support-removal were not all exercised. Their paths have implementation/source coverage, with unit tests for slot correlation, ambiguous reconstruction and spike orientation/deduplication. Do not interpret the matrix as exhaustive testing of every interaction or backend.

The release workflow was not changed or published by this work. Build artifacts are version-specific; only the lean variant was booted for this final logging pass.

## Reproduction and local evidence

See `regression/probe/README.md` and `regression/bot/verify-copper-golem.mjs`. Test plugins and generated worlds must stay outside production and releases.

The local worktree retains Gradle logs at `workspace/logging-fixes-build-<target>.log`, runtime summaries/hashes at `workspace/logging-fixes-runtime-modern-<target>.json` (legacy: `workspace/logging-fixes-runtime-legacy.json`), and server/bot logs at `.audit-server/final/<target>/logging-fixes-lean-*.log`. These generated fixtures are intentionally untracked.

Build commands (from the corresponding worktree):

```powershell
# Modern: repeat for 26.1.2, 26.2 and 26.3.
.\gradlew.bat build -PminecraftTarget=26.3 --console=plain
# maintenance/1.21.11:
.\gradlew.bat build --console=plain
```

Tested lean jar SHA-256 values:

| Minecraft | SHA-256 |
| --- | --- |
| 1.21.11 | `5c9243bb297defeafaa69fd65e2483dafca2552a90267967c3187a2019433a4a` |
| 26.1.2 | `3c3d94b67f883f7697fcd1431ac6bc1a4c127d2be05b01e0e4a9f806e6b1e730` |
| 26.2 | `0d649317e9fcc417c9b6d14500b0ad561b18d2885d8acee92d05baef2cba8f02` |
| 26.3 | `940f87ff859e51d30bdb5e8d9dac973d4f1a858b2bca61e5a312556843242c53` |
