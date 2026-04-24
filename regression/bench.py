#!/usr/bin/env python3
"""v1 vs v2 query timing benchmark.

Workflow:
  1. Drop and re-seed v1.DataEntry (v1) and Spyglass.EventRecords
     (v2) with a matched synthetic dataset, plus recreate the indexes the
     plugins create at startup (the bench can't rely on the running plugin
     to index a collection it dropped).
  2. Issue a set of representative search commands over RCON, timing each
     round trip end-to-end (client send -> server response).
  3. Report mean / p50 / p95 / p99 / min / max per query, side by side.

Every timed operation is wall-clock RCON round trip. The search command
reply fires after the sync part of the handler returns (parse, async
kick-off, "Querying..." message buffered into the RCON reply). The async
query + render runs after the reply, so raw RCON timings track the
sync-side cost. page-N timings, in contrast, are fully synchronous on
the main thread and reflect the actual cache-render cost.

Usage:
  python3 regression/bench.py # default: 50k records, 5 queries
  python3 regression/bench.py --records 250000 --trials 50 # larger
  python3 regression/bench.py --sizes 10000 50000 250000 # size sweep (no pageturn/seed options combine)
  python3 regression/bench.py --skip-seed # bench against current DB
  python3 regression/bench.py --skip-seed --only queries # only query bench
  python3 regression/bench.py --skip-seed --only pageturn # only page-turn bench
"""
from __future__ import annotations

import argparse
import json
import random
import statistics
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

from mcrcon import MCRcon
from pymongo import MongoClient

REPO_ROOT = Path(__file__).resolve().parent.parent
REPORT_PATH = REPO_ROOT / "spyglass" / "build" / "reports" / "regression" / "bench.json"

RCON_HOST = "127.0.0.1"
RCON_PORT = 25576
RCON_PASSWORD = "test123"

WORLD_UUID = uuid.UUID("77777777-7777-7777-7777-777777777777")
PLAYERS = [
    ("Alice", uuid.UUID("11111111-1111-1111-1111-111111111111")),
    ("Bob", uuid.UUID("22222222-2222-2222-2222-222222222222")),
    ("Carol", uuid.UUID("33333333-3333-3333-3333-333333333333")),
    ("Dave", uuid.UUID("44444444-4444-4444-4444-444444444444")),
    ("Eve", uuid.UUID("55555555-5555-5555-5555-555555555555")),
]
MATERIALS = ["STONE", "DIRT", "GRASS_BLOCK", "OAK_LOG", "IRON_ORE",
             "GLASS", "COBBLESTONE", "SAND", "GRAVEL", "DIAMOND_ORE"]
EVENTS = ["break", "place"] # v1 seed path only covers these

# v1-vs-v2 query pairs. The v1 field is None when a query uses an alias
# v1 doesn't match against this fixture (p:<name>, -g grouped, etc.).
# The `notes` field is rendered in the speedup summary for context.
QUERIES = [
    # Large match, wide time window — exercises sort+limit tail-decode path.
    {
        "id": "all-breaks-30d",
        "v2": "sg search a:break t:30d -g -ng",
        "v1": "sg search a:break t:30d -g -ng",
        "notes": "wide match, ungrouped",
    },
    {
        "id": "all-places-30d",
        "v2": "sg search a:place t:30d -g -ng",
        "v1": "sg search a:place t:30d -g -ng",
        "notes": "mirror of breaks, place variant",
    },
    # Player + event: tests (source.playerId, occurred) index on v2.
    {
        "id": "alice-breaks-30d",
        # v1 omits Player in the bench fixture (see make_v1 docstring);
        # kept as v2-only so the player-index path is measurable.
        "v2": "sg search p:Alice a:break t:30d -g -ng",
        "v1": None,
        "notes": "player+event, v2 index path",
    },
    # Material filter: forces sort-then-filter since we don't index target.
    {
        "id": "stone-breaks-30d",
        "v2": "sg search b:STONE a:break t:30d -g -ng",
        "v1": "sg search b:STONE a:break t:30d -g -ng",
        "notes": "material+event",
    },
    # Narrow time window: only recent docs, almost no sort work.
    {
        "id": "narrow-1h",
        "v2": "sg search a:break t:1h -g -ng",
        "v1": "sg search a:break t:1h -g -ng",
        "notes": "narrow time window",
    },
    {
        "id": "narrow-1d",
        "v2": "sg search a:break t:1d -g -ng",
        "v1": "sg search a:break t:1d -g -ng",
        "notes": "1-day window",
    },
    {
        "id": "narrow-7d",
        "v2": "sg search a:break t:7d -g -ng",
        "v1": "sg search a:break t:7d -g -ng",
        "notes": "7-day window",
    },
    # Composite with player + material + event.
    {
        "id": "composite-alice-stone",
        "v2": "sg search p:Alice b:STONE a:break t:30d -g -ng",
        "v1": None,
        "notes": "player+material+event",
    },
    # Grouped variant: v1 server-side $group, v2 client-side aggregate.
    # Both return at most 1000 aggregation rows; the cost shape is very
    # different between versions and is the bench most likely to highlight
    # architectural cost.
    {
        "id": "grouped-breaks-30d",
        "v2": "sg search a:break t:30d -g",
        "v1": "sg search a:break t:30d -g",
        "notes": "grouped (server-side v1 vs client-side v2 aggregation)",
    },
]

# Pagination bench: runs after the search below, then flips through cache.
# Only v2 has pagination commands in this plugin's CLI, so this is a
# v2-internal check (did the PageCache lazy-render refactor keep flips
# cheap, or did it add meaningful per-flip cost?).
PAGETURN_SEARCH = "sg search a:break t:30d -g -ng"
PAGETURN_PAGES = [1, 2, 5, 10, 50, 100]


def make_v2(event, material, player_name, player_id, x, y, z, occurred):
    return {
        "id": uuid.uuid4(),
        "schemaVersion": 1,
        "event": event,
        "occurred": occurred,
        "expiresAt": occurred + timedelta(weeks=4),
        "origin": {"kind": "player", "detail": None},
        "source": {
            "kind": "player",
            "playerId": player_id,
            "playerName": player_name,
            "entityId": None,
            "entityType": None,
            "pluginName": None,
            "commandBlockLocation": None,
            "description": None,
        },
        "location": {
            "worldId": WORLD_UUID,
            "worldName": "world",
            "x": x,
            "y": y,
            "z": z,
        },
        "target": material,
        "originalBlock": _snapshot(material if event == "break" else "AIR"),
        "newBlock": _snapshot("AIR" if event == "break" else material),
        "_benchTag": "sg-bench",
    }


def _snapshot(material):
    return {
        "material": material,
        "blockData": f"minecraft:{material.lower()}",
        "containerItems": [],
        "signFront": [],
        "signBack": [],
        "bannerPatterns": [],
        "jukeboxRecord": None,
    }


def make_v1(event, material, player_id, player_name, x, y, z, occurred):
    # v1's MongoRecordHandler.query triggers Bukkit.getOfflinePlayer when
    # a doc contains Player — and with fake UUIDs the returned stub has a
    # null name, which makes v1 NPE. Omit Player entirely and pin the
    # display name into Cause so v1 can still render results and we can
    # still search by b:<material> / a:<event> / t:<time>. p:<name>
    # searches are skipped from the v1 comparison for the same reason.
    return {
        "Event": event,
        "Cause": player_name,
        "Target": material,
        "MaterialType": material,
        "Created": occurred,
        "Expires": occurred + timedelta(weeks=4),
        "Location": {
            "X": x,
            "Y": y,
            "Z": z,
            "World": str(WORLD_UUID),
        },
        "_benchTag": "sg-bench",
    }


def generate(records, now, rng):
    v2_docs = []
    v1_docs = []
    window = timedelta(days=30)
    for _ in range(records):
        name, pid = rng.choice(PLAYERS)
        material = rng.choice(MATERIALS)
        event = rng.choice(EVENTS)
        x = rng.randint(-5000, 5000)
        y = rng.randint(-60, 200)
        z = rng.randint(-5000, 5000)
        offset = timedelta(seconds=rng.randint(0, int(window.total_seconds())))
        occurred = now - offset
        v2_docs.append(make_v2(event, material, name, pid, x, y, z, occurred))
        v1_docs.append(make_v1(event, material, pid, name, x, y, z, occurred))
    return v2_docs, v1_docs


def seed(client, records, seed_value, batch_size=5000):
    rng = random.Random(seed_value)
    now = datetime.now(timezone.utc)

    v2_db = client["Spyglass"]
    v1_db = client["v1"]
    v2_db["EventRecords"].drop()
    v1_db["DataEntry"].drop()

    # Batch-insert to keep peak Python memory sane at large --records.
    v2_total = 0
    v1_total = 0
    remaining = records
    while remaining > 0:
        chunk = min(batch_size, remaining)
        v2_chunk, v1_chunk = generate(chunk, now, rng)
        if v2_chunk:
            v2_db["EventRecords"].insert_many(v2_chunk, ordered=False)
            v2_total += len(v2_chunk)
        if v1_chunk:
            v1_db["DataEntry"].insert_many(v1_chunk, ordered=False)
            v1_total += len(v1_chunk)
        remaining -= chunk

    # The plugin creates these indexes at boot; dropping the collection
    # for a fresh seed wipes them, so the bench would measure unindexed
    # COLLSCAN behaviour that never happens in production. Recreate the
    # exact index set on both sides so the numbers reflect real workloads.
    create_indexes(v2_db["EventRecords"], v1_db["DataEntry"])
    return v2_total, v1_total


def create_indexes(v2_coll, v1_coll):
    # v2: mirrors IndexManager.ensureRecordIndexes.
    v2_coll.create_index([("source.playerId", 1), ("occurred", -1)])
    v2_coll.create_index([("event", 1), ("occurred", -1)])
    v2_coll.create_index([
        ("location.worldId", 1),
        ("location.x", 1),
        ("location.z", 1),
        ("location.y", 1),
        ("occurred", -1),
    ])
    v2_coll.create_index("expiresAt", expireAfterSeconds=0)
    # v1: mirrors MongoStorageHandler.ensureIndexes.
    v1_coll.create_index([("Location.X", 1), ("Location.Z", 1),
                          ("Location.Y", 1), ("Created", -1)])
    v1_coll.create_index([("Created", -1), ("EventName", 1)])
    v1_coll.create_index("Expires", expireAfterSeconds=0)


def collection_stats(client):
    """Return per-collection doc counts + avg doc size for context."""
    v2 = client["Spyglass"]["EventRecords"]
    v1 = client["v1"]["DataEntry"]
    try:
        v2_stats = client["Spyglass"].command("collStats", "EventRecords")
        v1_stats = client["v1"].command("collStats", "DataEntry")
    except Exception:
        return {}
    return {
        "v2": {
            "count": v2_stats.get("count", 0),
            "avg_obj_size": v2_stats.get("avgObjSize", 0),
            "storage_size": v2_stats.get("storageSize", 0),
        },
        "v1": {
            "count": v1_stats.get("count", 0),
            "avg_obj_size": v1_stats.get("avgObjSize", 0),
            "storage_size": v1_stats.get("storageSize", 0),
        },
    }


def wait_for_rcon(timeout_seconds=30):
    deadline = time.time() + timeout_seconds
    last_error = None
    while time.time() < deadline:
        try:
            with MCRcon(RCON_HOST, RCON_PASSWORD, port=RCON_PORT, timeout=5) as r:
                r.command("list")
                return
        except Exception as exc: # noqa: BLE001
            last_error = exc
            time.sleep(1.0)
    raise RuntimeError(f"RCON not reachable on {RCON_HOST}:{RCON_PORT}: {last_error}")


def time_command(rcon, command):
    start = time.perf_counter()
    rcon.command(command)
    return (time.perf_counter() - start) * 1000.0 # ms


def summarize(samples):
    if not samples:
        return {"n": 0}
    sorted_samples = sorted(samples)
    n = len(sorted_samples)

    def pct(p):
        idx = min(n - 1, max(0, int(round(p * (n - 1)))))
        return sorted_samples[idx]

    return {
        "n": n,
        "mean_ms": round(statistics.fmean(sorted_samples), 2),
        "min_ms": round(sorted_samples[0], 2),
        "p50_ms": round(pct(0.50), 2),
        "p95_ms": round(pct(0.95), 2),
        "p99_ms": round(pct(0.99), 2),
        "max_ms": round(sorted_samples[-1], 2),
    }


def bench_queries(rcon_factory, queries, trials, warmup, inter_delay=0.05):
    """Run the query benchmark suite.

    rcon_factory is a callable returning a fresh MCRcon context manager — used
    so we can reconnect between query blocks if the server closes the pipe after
    a slow trial.
    """
    results = []
    for query in queries:
        entry = {
            "id": query["id"],
            "notes": query.get("notes", ""),
            "v2": {},
            "v1": {},
        }
        for version in ("v2", "v1"):
            cmd = query.get(version)
            if cmd is None:
                entry[version] = {"skipped": "not applicable to this version"}
                continue
            # Fresh connection per (query, version) block. Long v1 scans have
            # been observed to leave the pipe in a wedged state by the time
            # we try the next batch.
            samples = []
            err = None
            try:
                with rcon_factory() as rcon:
                    for _ in range(warmup):
                        rcon.command(cmd)
                        time.sleep(inter_delay)
                    for _ in range(trials):
                        samples.append(time_command(rcon, cmd))
                        time.sleep(inter_delay)
            except Exception as exc: # noqa: BLE001
                err = exc
            if err is not None and not samples:
                entry[version] = {"error": f"{err}"}
            elif err is not None:
                # Partial — report what we got, note the error.
                entry[version] = summarize(samples)
                entry[version]["command"] = cmd
                entry[version]["partial_error"] = str(err)
            else:
                entry[version] = summarize(samples)
                entry[version]["command"] = cmd
        results.append(entry)
    return results


def bench_pageturn(rcon_factory, trials, warmup, inter_delay=0.05):
    """Time page-turn commands against a cached 1000-result search.

    Priming: run the search once so the PageCache has 1000 records
    stored for the RCON sender. Then for each target page, time
    `sg page N` round trips. The PageCache.show path is fully
    synchronous on the Bukkit main thread, so this is a direct probe
    of the lazy-render cost.

    v2-only — v1's paging command doesn't exist in this plugin's CLI.
    """
    results = []
    try:
        with rcon_factory() as rcon:
            rcon.command(PAGETURN_SEARCH)
            time.sleep(0.5) # let the async query finish and store the page cache
            for page in PAGETURN_PAGES:
                cmd = f"sg page {page}"
                samples = []
                for _ in range(warmup):
                    rcon.command(cmd)
                    time.sleep(inter_delay)
                for _ in range(trials):
                    samples.append(time_command(rcon, cmd))
                    time.sleep(inter_delay)
                entry = summarize(samples)
                entry["page"] = page
                entry["command"] = cmd
                results.append(entry)
    except Exception as exc: # noqa: BLE001
        results.append({"error": f"{exc}"})
    return results


def render_query_table(results):
    header = (f"{'query':28} {'ver':>3} {'n':>4} "
              f"{'mean':>8} {'p50':>8} {'p95':>8} {'p99':>8} "
              f"{'min':>8} {'max':>8}")
    print(header)
    print("-" * len(header))
    for row in results:
        for version in ("v2", "v1"):
            metrics = row[version]
            if "error" in metrics:
                print(f"{row['id']:28} {version:>3} ERR {metrics['error']}")
                continue
            if "skipped" in metrics:
                print(f"{row['id']:28} {version:>3} --- (skipped: {metrics['skipped']})")
                continue
            print(f"{row['id']:28} {version:>3} {metrics['n']:>4} "
                  f"{metrics['mean_ms']:>8.2f} {metrics['p50_ms']:>8.2f} "
                  f"{metrics['p95_ms']:>8.2f} {metrics['p99_ms']:>8.2f} "
                  f"{metrics['min_ms']:>8.2f} {metrics['max_ms']:>8.2f}")
        v2m = row["v2"].get("mean_ms")
        v1m = row["v1"].get("mean_ms")
        if v2m and v1m:
            ratio = v1m / v2m if v2m > 0 else float("inf")
            note = row.get("notes", "")
            note_suffix = f" ({note})" if note else ""
            print(f"{' ':28} {'':>3} {'→':>4} v2 is {ratio:.2f}x v1 by mean{note_suffix}")
    print()


def render_pageturn_table(results):
    if not results:
        return
    if "error" in results[0]:
        print(f"pageturn: ERROR {results[0]['error']}\n")
        return
    header = (f"{'page':>5} {'n':>4} {'mean':>8} {'p50':>8} {'p95':>8} "
              f"{'p99':>8} {'min':>8} {'max':>8}")
    print("page-turn bench (v2 only, cached 1000-result search):")
    print(header)
    print("-" * len(header))
    for entry in results:
        if "error" in entry:
            continue
        print(f"{entry['page']:>5} {entry['n']:>4} "
              f"{entry['mean_ms']:>8.2f} {entry['p50_ms']:>8.2f} "
              f"{entry['p95_ms']:>8.2f} {entry['p99_ms']:>8.2f} "
              f"{entry['min_ms']:>8.2f} {entry['max_ms']:>8.2f}")
    print()


def run_once(args, records_for_size, client):
    """One iteration of the bench at a specified dataset size."""
    if records_for_size is not None and not args.skip_seed:
        print(f"Seeding {records_for_size} records per side...")
        t0 = time.perf_counter()
        v2_count, v1_count = seed(client, records_for_size, args.seed_value)
        print(f" v2 EventRecords: {v2_count}")
        print(f" v1 DataEntry : {v1_count}")
        print(f" seed time : {time.perf_counter() - t0:.2f}s")

    stats = collection_stats(client)
    if stats:
        print(f" collection sizes: v2 avgObj={stats['v2']['avg_obj_size']}B "
              f"storage={stats['v2']['storage_size'] // 1024}KiB | "
              f"v1 avgObj={stats['v1']['avg_obj_size']}B "
              f"storage={stats['v1']['storage_size'] // 1024}KiB")

    wait_for_rcon()

    def rcon_factory():
        return MCRcon(RCON_HOST, RCON_PASSWORD, port=RCON_PORT, timeout=60)

    payload = {
        "records_per_side": records_for_size or stats.get("v2", {}).get("count", 0),
        "trials": args.trials,
        "warmup": args.warmup,
        "seed_value": args.seed_value,
        "collection_stats": stats,
    }

    if args.only in ("all", "queries"):
        queries = QUERIES if not args.filter_ids else [
            q for q in QUERIES if q["id"] in args.filter_ids
        ]
        print(f"Query bench: {len(queries)} queries x {args.trials} trials x 2 versions "
              f"(warmup {args.warmup})...")
        payload["query_results"] = bench_queries(
            rcon_factory, queries, args.trials, args.warmup)

    if args.only in ("all", "pageturn"):
        print(f"Page-turn bench: {len(PAGETURN_PAGES)} pages x {args.trials} trials "
              f"(warmup {args.warmup})...")
        payload["pageturn_results"] = bench_pageturn(
            rcon_factory, args.trials, args.warmup)

    return payload


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--records", type=int, default=50000,
                        help="Records to seed into each of v1 and v2 (default 50000).")
    parser.add_argument("--sizes", type=int, nargs="+",
                        help="Dataset size sweep. If set, re-seeds and re-runs for each. "
                             "Overrides --records and forces re-seed per size.")
    parser.add_argument("--trials", type=int, default=30,
                        help="Timed trials per query per version (default 30).")
    parser.add_argument("--warmup", type=int, default=5,
                        help="Warmup runs per query per version, not counted (default 5).")
    parser.add_argument("--seed-value", type=int, default=42,
                        help="RNG seed for dataset reproducibility.")
    parser.add_argument("--mongo", default="mongodb://localhost:27017")
    parser.add_argument("--skip-seed", action="store_true",
                        help="Skip re-seeding; bench the current DB state.")
    parser.add_argument("--only", choices=("all", "queries", "pageturn"),
                        default="all",
                        help="Which bench section(s) to run (default: all).")
    parser.add_argument("--filter-ids", nargs="+",
                        help="Restrict to the named query IDs (from QUERIES).")
    parser.add_argument("--report", default=str(REPORT_PATH))
    args = parser.parse_args()

    client = MongoClient(args.mongo, uuidRepresentation="standard")

    all_runs = []
    if args.sizes:
        for size in args.sizes:
            print()
            print(f"=== Dataset size: {size} records ===")
            payload = run_once(args, size, client)
            payload["dataset_size"] = size
            all_runs.append(payload)
            print()
            if payload.get("query_results"):
                render_query_table(payload["query_results"])
            if payload.get("pageturn_results"):
                render_pageturn_table(payload["pageturn_results"])
    else:
        payload = run_once(args, None if args.skip_seed else args.records, client)
        all_runs.append(payload)
        print()
        if payload.get("query_results"):
            render_query_table(payload["query_results"])
        if payload.get("pageturn_results"):
            render_pageturn_table(payload["pageturn_results"])

    client.close()

    report_path = Path(args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    with report_path.open("w") as fh:
        json.dump({"runs": all_runs}, fh, indent=2, default=str)
    print(f"Full report: {report_path}")


if __name__ == "__main__":
    main()
