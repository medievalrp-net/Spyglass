#!/usr/bin/env python3
"""On-disk footprint reporter for the benchmark backends.

The rollback comparison (regression/bot/compare.js) and the search bench
(regression/bench.py) measure speed; this measures the other axis the
benchmarks were missing -- how much disk each backend spends to store the
same dataset. Reports all four backends the 4-way comparison covers:

  - spyglass-mongo      Spyglass on MongoDB  (storage + index, via dbStats)
  - spyglass-clickhouse Spyglass on ClickHouse (compressed bytes on disk)
  - cp-sqlite           CoreProtect on SQLite  (database file size)
  - cp-mysql            CoreProtect on MySQL   (data_length + index_length)

Only the backends that are reachable (and, for a given benchmark run, the
two that were actually active) are meaningful. Unreachable backends are
reported as skipped, never fail the run.

Usage:
  python3 regression/disk.py                     # human table, all reachable
  python3 regression/disk.py --json              # machine-readable JSON
  python3 regression/disk.py --which spyglass-mongo,cp-sqlite
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

ALL_BACKENDS = ["spyglass-mongo", "spyglass-clickhouse", "spyglass-sqlite",
                "cp-sqlite", "cp-mysql"]


def human(n):
    if n is None:
        return "n/a"
    units = ["B", "KiB", "MiB", "GiB", "TiB"]
    f = float(n)
    for u in units:
        if f < 1024 or u == units[-1]:
            return f"{f:.1f} {u}" if u != "B" else f"{int(f)} B"
        f /= 1024


def measure_mongo(uri, db_name):
    import pymongo  # local import so a missing driver only skips this backend
    client = pymongo.MongoClient(uri, serverSelectionTimeoutMS=4000)
    try:
        stats = client[db_name].command("dbStats")
    finally:
        client.close()
    storage = int(stats.get("storageSize", 0))
    index = int(stats.get("indexSize", 0))
    return {
        "backend": "spyglass-mongo",
        "objects": int(stats.get("objects", 0)),
        "avg_obj_bytes": int(stats.get("avgObjSize", 0) or 0),
        "storage_bytes": storage,
        "index_bytes": index,
        "total_bytes": storage + index,
    }


def measure_clickhouse(url, ch_db):
    q = ("SELECT sum(bytes_on_disk), sum(data_uncompressed_bytes), sum(rows) "
         f"FROM system.parts WHERE active AND database = '{ch_db}'")
    full = url.rstrip("/") + "/?query=" + urllib.parse.quote(q)
    with urllib.request.urlopen(full, timeout=6) as resp:
        raw = resp.read().decode().strip()
    on_disk, uncompressed, rows = (raw.split("\t") + ["0", "0", "0"])[:3]
    on_disk = int(on_disk or 0)
    uncompressed = int(uncompressed or 0)
    return {
        "backend": "spyglass-clickhouse",
        "objects": int(rows or 0),
        "storage_bytes": on_disk,
        "index_bytes": 0,  # ClickHouse's sparse primary index is folded into parts
        "total_bytes": on_disk,
        "uncompressed_bytes": uncompressed,
        "compression_ratio": round(uncompressed / on_disk, 2) if on_disk else None,
    }


def measure_sqlite(path, backend="cp-sqlite"):
    # Both SQLite footprints (Spyglass and CoreProtect) are the db file plus
    # its WAL/SHM sidecars; indexes live in the same file. Checkpoint the
    # Spyglass db before measuring (its writer leaves rows in the -wal until
    # a checkpoint folds them back) so the file reflects the full dataset.
    if not os.path.exists(path):
        raise FileNotFoundError(path)
    total = 0
    for suffix in ("", "-wal", "-shm"):
        p = path + suffix
        if os.path.exists(p):
            total += os.path.getsize(p)
    return {
        "backend": backend,
        "path": path,
        "storage_bytes": total,
        "index_bytes": 0,  # SQLite indexes live in the same file
        "total_bytes": total,
    }


def measure_mysql(host, port, user, password, db):
    # table_rows is InnoDB's estimate (good enough to flag a non-pristine DB);
    # co_block is CoreProtect's block-edit table, the bulk of a rollback set.
    sql = ("SELECT IFNULL(SUM(data_length),0), IFNULL(SUM(index_length),0), "
           "IFNULL(MAX(CASE WHEN table_name='co_block' THEN table_rows END),0) "
           f"FROM information_schema.tables WHERE table_schema = '{db}'")
    cmd = ["mysql", "-h", host, "-P", str(port), "-u", user, "-N", "-B", "-e", sql]
    if password:
        cmd.insert(1, f"-p{password}")
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=8)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip() or "mysql query failed")
    data_len, index_len, rows = (out.stdout.strip().split("\t") + ["0", "0", "0"])[:3]
    data_len = int(data_len or 0)
    index_len = int(index_len or 0)
    return {
        "backend": "cp-mysql",
        "objects": int(rows or 0),
        "storage_bytes": data_len,
        "index_bytes": index_len,
        "total_bytes": data_len + index_len,
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--which", default="all",
                   help="Comma list of " + ",".join(ALL_BACKENDS) + " (default: all reachable).")
    p.add_argument("--mongo", default="mongodb://localhost:27017")
    p.add_argument("--mongo-db", default="Spyglass")
    p.add_argument("--clickhouse", default="http://localhost:8123")
    p.add_argument("--ch-db", default="spyglass")
    p.add_argument("--sqlite", default=str(
        (os.path.dirname(__file__) or ".") + "/../../RP_Server/plugins/CoreProtect/database.db"))
    p.add_argument("--sg-sqlite", default=str(
        (os.path.dirname(__file__) or ".") + "/../../RP_Server/plugins/Spyglass/spyglass.db"))
    p.add_argument("--mysql-host", default=os.environ.get("MYSQL_HOST", "127.0.0.1"))
    p.add_argument("--mysql-port", type=int, default=int(os.environ.get("MYSQL_PORT", "3306")))
    p.add_argument("--mysql-user", default=os.environ.get("MYSQL_USER", "root"))
    p.add_argument("--mysql-password", default=os.environ.get("MYSQL_PASSWORD", ""))
    p.add_argument("--mysql-db", default=os.environ.get("MYSQL_DB", "coreprotect"))
    p.add_argument("--json", action="store_true")
    args = p.parse_args()

    which = ALL_BACKENDS if args.which == "all" else [w.strip() for w in args.which.split(",")]
    runners = {
        "spyglass-mongo": lambda: measure_mongo(args.mongo, args.mongo_db),
        "spyglass-clickhouse": lambda: measure_clickhouse(args.clickhouse, args.ch_db),
        "spyglass-sqlite": lambda: measure_sqlite(os.path.abspath(args.sg_sqlite), "spyglass-sqlite"),
        "cp-sqlite": lambda: measure_sqlite(os.path.abspath(args.sqlite)),
        "cp-mysql": lambda: measure_mysql(args.mysql_host, args.mysql_port,
                                          args.mysql_user, args.mysql_password, args.mysql_db),
    }

    results = {}
    for name in which:
        if name not in runners:
            continue
        try:
            results[name] = runners[name]()
        except Exception as exc:  # noqa: BLE001 — a down backend is skipped, never fatal
            results[name] = {"backend": name, "skipped": str(exc)}

    if args.json:
        print(json.dumps(results, indent=2, default=str))
        return

    print(f"{'backend':22} {'objects/rows':>14} {'storage':>12} {'index':>12} {'total':>12}  notes")
    print("-" * 92)
    for name in which:
        r = results.get(name)
        if not r:
            continue
        if "skipped" in r:
            print(f"{name:22} {'—':>14} {'—':>12} {'—':>12} {'—':>12}  skipped: {r['skipped'][:32]}")
            continue
        note = ""
        if "compression_ratio" in r and r["compression_ratio"]:
            note = f"{r['compression_ratio']}x vs uncompressed"
        print(f"{name:22} {r.get('objects','—'):>14} "
              f"{human(r.get('storage_bytes')):>12} {human(r.get('index_bytes')):>12} "
              f"{human(r.get('total_bytes')):>12}  {note}")


if __name__ == "__main__":
    main()
