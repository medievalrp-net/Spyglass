# Regression harness

Automated regression for Spyglass, for Spyglass.

## What it does

1. Confirms the dev server at `../RP_Server` is running (or starts it).
2. Seeds deterministic test records into both Mongo databases:
   - `v1.DataEntry` (v1's shape)
   - `Spyglass.EventRecords` (v2's shape)
3. Runs a query matrix (defined in `cases.json`) through RCON:
   - Each case has a `v2` command (`/sg search ...`) and, where applicable, a `v1` command (`/spyglass search ...`).
   - Expected result counts per case.
4. Asserts the observed counts match the expectations; if `v1` is present, also asserts v2's count meets-or-exceeds v1's count (v2 must not regress relative to v1).
5. Writes a report at `plugin/build/reports/regression/report.json`.

## Run it

```
./gradlew regression
```

Which expands to: `./gradlew deployToRpServer` (builds + drops `Spyglass.jar` into `../RP_Server/plugins/`) → `python3 regression/run.py`.

## Requirements

- Python 3.9+
- `pip install --user mcrcon pymongo`
- Mongo running on `localhost:27017` (the dev server uses it).
- `../RP_Server` with `paper.jar` and both `v1.jar` + `Spyglass.jar` in `plugins/`.
- RCON enabled on port 25576 with password `test123` (matches `server.properties`).

## Adding a case

Edit `cases.json`. Each case is:

```json
{
  "id": "break-global",
  "description": "Seeded 3 break records; searching with -g should return 3",
  "v2": "sg search a:break t:1d -g -ng",
  "v2_follow_up": "sg page 1",
  "v1": "sg search a:break t:1d -g -ng",
  "v1_follow_up": "sg page 1",
  "expected_count": 3,
  "compare": "ge_v1"
}
```

- `v2`, `v1` — the command to execute. `v1` is optional.
- `*_follow_up` — for v2 (and v1 when supported), paging is needed to surface async results.
- `expected_count` — asserted count after normalizing output.
- `compare` — `"exact"` (v2 count must equal `expected_count`) or `"ge_v1"` (v2 count must be >= v1 count).

## Known limitations

- No live-event reproduction yet — we seed Mongo directly. That means event-extractor logic (what fires on each Bukkit event) is covered by unit tests, not by regression. Mineflayer bot integration is the planned follow-up; stubbed at `regression/bot/`.
- v1's paging doesn't survive RCON disconnects, so v1 comparisons that use `page` retrieve nothing. For those cases the runner falls back to counting matches from the `search` command's response, if v1 emits anything there.
