#!/usr/bin/env python3
"""Analyze ingest-MSPT benchmark CSVs into an added-MSPT table.

Reads ticks-<config>-r<rate>-run<n>.csv files (columns: label,rate_evps,seq,
mspt_ns) written by the IngestBench plugin. For each (config, rate) it computes
per-run mean/p50/p95/p99/worst tick in ms, takes the median across runs, then
subtracts the vanilla baseline at the SAME rate to isolate the plugin's added
per-tick cost:

    added_MSPT(plugin, rate) = MSPT(plugin, rate) - MSPT(vanilla, rate)

Usage: analyze.py [csv_dir]   (default: ./out/csv relative to this script's run)
Writes a markdown report to stdout and a JSON summary next to the CSVs.
"""
import json
import math
import os
import re
import statistics
import sys
from collections import defaultdict

NAME_RE = re.compile(r"ticks-(?P<config>.+)-r(?P<rate>\d+)-run(?P<run>\d+)\.csv$")
NS_PER_MS = 1_000_000.0


def percentile(sorted_vals, p):
    if not sorted_vals:
        return float("nan")
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    k = (len(sorted_vals) - 1) * (p / 100.0)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return sorted_vals[int(k)]
    return sorted_vals[f] * (c - k) + sorted_vals[c] * (k - f)


def load_samples(path):
    vals = []
    with open(path) as fh:
        next(fh, None)  # header
        for line in fh:
            parts = line.rstrip("\n").split(",")
            if len(parts) != 4:
                continue
            try:
                vals.append(int(parts[3]) / NS_PER_MS)
            except ValueError:
                continue
    return vals


def file_stats(vals):
    s = sorted(vals)
    return {
        "count": len(s),
        "mean": statistics.fmean(s) if s else float("nan"),
        "p50": percentile(s, 50),
        "p95": percentile(s, 95),
        "p99": percentile(s, 99),
        "max": s[-1] if s else float("nan"),
    }


def median_of(dicts, key):
    xs = [d[key] for d in dicts if not math.isnan(d[key])]
    return statistics.median(xs) if xs else float("nan")


def fmt(x):
    return "n/a" if (x is None or (isinstance(x, float) and math.isnan(x))) else f"{x:.3f}"


def main():
    csv_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.getcwd(), "out", "csv")
    if not os.path.isdir(csv_dir):
        sys.exit(f"no such csv dir: {csv_dir}")

    # group[(config, rate)] = list of per-run file_stats
    group = defaultdict(list)
    for fn in sorted(os.listdir(csv_dir)):
        m = NAME_RE.search(fn)
        if not m:
            continue
        vals = load_samples(os.path.join(csv_dir, fn))
        if not vals:
            print(f"WARN: {fn} had no samples", file=sys.stderr)
            continue
        st = file_stats(vals)
        st["_run"] = int(m.group("run"))
        group[(m.group("config"), int(m.group("rate")))].append(st)

    if not group:
        sys.exit(f"no ticks-*.csv files matched in {csv_dir}")

    configs = sorted({c for (c, _) in group})
    rates = sorted({r for (_, r) in group})

    # agg[(config, rate)] = {stat: median across runs, plus spread for mean}
    agg = {}
    for key, runs in group.items():
        a = {k: median_of(runs, k) for k in ("mean", "p50", "p95", "p99", "max")}
        a["runs"] = len(runs)
        a["nsamples"] = median_of(runs, "count")
        means = sorted(r["mean"] for r in runs)
        a["mean_lo"], a["mean_hi"] = means[0], means[-1]
        agg[key] = a

    def vanilla(rate, stat):
        v = agg.get(("vanilla", rate))
        return v[stat] if v else float("nan")

    out = []
    out.append("## Ingest-MSPT benchmark results\n")
    out.append(f"Paper {os.environ.get('PAPER_VERSION', '1.21.8')}, "
               f"warmup {os.environ.get('WARMUP', '90')}s / measure {os.environ.get('MEASURE', '180')}s, "
               f"median of up to {max((a['runs'] for a in agg.values()), default=0)} runs. "
               "MSPT is the server's own per-tick work time from Server.getTickTimes(), in ms.\n")

    out.append("\n### Raw MSPT (absolute, ms)\n")
    out.append("| config | rate ev/s | mean | p95 | p99 | worst tick | mean run spread |")
    out.append("|---|---:|---:|---:|---:|---:|---:|")
    for config in configs:
        for rate in rates:
            a = agg.get((config, rate))
            if not a:
                continue
            spread = f"{fmt(a['mean_lo'])}-{fmt(a['mean_hi'])}"
            out.append(f"| {config} | {rate} | {fmt(a['mean'])} | {fmt(a['p95'])} | "
                       f"{fmt(a['p99'])} | {fmt(a['max'])} | {spread} |")

    out.append("\n### Added MSPT over the vanilla baseline at the same rate (ms)\n")
    out.append("This is the per-tick cost the logging plugin itself adds. Generator cost cancels.\n")
    out.append("| config | rate ev/s | added mean | added p95 | added p99 | added worst |")
    out.append("|---|---:|---:|---:|---:|---:|")
    for config in configs:
        if config == "vanilla":
            continue
        for rate in rates:
            a = agg.get((config, rate))
            if not a:
                continue
            row = [config, str(rate)]
            for stat in ("mean", "p95", "p99", "max"):
                row.append(fmt(a[stat] - vanilla(rate, stat)))
            out.append("| " + " | ".join(row) + " |")

    out.append("\n### Sanity: vanilla baseline (the generator's own cost, ms)\n")
    out.append("| rate ev/s | mean | p95 | p99 | worst |")
    out.append("|---:|---:|---:|---:|---:|")
    for rate in rates:
        a = agg.get(("vanilla", rate))
        if not a:
            continue
        out.append(f"| {rate} | {fmt(a['mean'])} | {fmt(a['p95'])} | {fmt(a['p99'])} | {fmt(a['max'])} |")

    report = "\n".join(out)
    print(report)

    summary = {
        "configs": configs,
        "rates": rates,
        "raw": {f"{c}|{r}": agg[(c, r)] for (c, r) in agg},
        "added": {
            f"{c}|{r}": {s: agg[(c, r)][s] - vanilla(r, s) for s in ("mean", "p95", "p99", "max")}
            for (c, r) in agg if c != "vanilla"
        },
    }
    jpath = os.path.join(csv_dir, "summary.json")
    with open(jpath, "w") as fh:
        json.dump(summary, fh, indent=2, default=lambda x: None if (isinstance(x, float) and math.isnan(x)) else x)
    mdpath = os.path.join(csv_dir, "report.md")
    with open(mdpath, "w") as fh:
        fh.write(report + "\n")
    print(f"\n[wrote {jpath} and {mdpath}]", file=sys.stderr)


if __name__ == "__main__":
    main()
