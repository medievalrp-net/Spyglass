#!/usr/bin/env python3
"""Correlate elevated ingest ticks against G1 GC pauses to root-cause tail latency.

Reads a ticks CSV (with the wall_ms column) and a JVM -Xlog:gc log, and reports
what fraction of the slow ticks (top percentile, and an absolute >5ms cut) line
up in wall-clock time with a GC pause. If most do, the tail is GC/allocation
driven; if few do, it is something else (e.g. queue-lock contention).

Usage: gc-correlate.py <ticks.csv> <gc.log>
"""
import re
import sys
from datetime import datetime, timezone

def load_ticks(path):
    rows = []
    with open(path) as fh:
        next(fh, None)
        for ln in fh:
            p = ln.rstrip().split(",")
            if len(p) >= 5:
                try:
                    rows.append((int(p[4]), int(p[3]) / 1e6))  # (wall_ms, mspt_ms)
                except ValueError:
                    pass
    return rows

# -Xlog:gc:...:time,uptime lines look like:
#  [2026-06-21T15:30:00.123+0000][123.456s] GC(42) Pause Young (Normal) ... 5.678ms
TIME_RE = re.compile(r"\[(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+\-]\d{4})\]")
PAUSE_RE = re.compile(r"\bPause\b.*?([\d.]+)ms\s*$")

def load_gc(path):
    pauses = []  # (wall_ms, dur_ms)
    with open(path) as fh:
        for ln in fh:
            if "Pause" not in ln:
                continue
            mt = TIME_RE.search(ln)
            mp = PAUSE_RE.search(ln.strip())
            if not (mt and mp):
                continue
            ts = mt.group(1)
            # normalise +0000 -> +00:00 for fromisoformat
            iso = ts[:-5] + ts[-5:-2] + ":" + ts[-2:]
            dt = datetime.fromisoformat(iso).astimezone(timezone.utc)
            pauses.append((int(dt.timestamp() * 1000), float(mp.group(1))))
    return pauses

def main():
    ticks = load_ticks(sys.argv[1])
    pauses = load_gc(sys.argv[2])
    if not ticks:
        sys.exit("no ticks with wall_ms")
    mspts = sorted(t[1] for t in ticks)
    p99 = mspts[int(len(mspts) * 0.99)]
    p95 = mspts[int(len(mspts) * 0.95)]
    print(f"ticks={len(ticks)}  p95={p95:.2f}ms  p99={p99:.2f}ms  worst={mspts[-1]:.2f}ms")
    print(f"GC pauses in window: {len(pauses)}  total={sum(d for _,d in pauses):.1f}ms"
          + (f"  max={max(d for _,d in pauses):.1f}ms" if pauses else ""))

    # A tick's sample wall_ms is stamped just AFTER the completed tick, so a GC
    # that caused it fired within the preceding ~110ms (a tick is ~50ms).
    def explained(threshold):
        slow = [t for t in ticks if t[1] >= threshold]
        hit = 0
        for wall, _ in slow:
            if any(wall - 115 <= gw <= wall + 15 for gw, _ in pauses):
                hit += 1
        return len(slow), hit

    for label, thr in [("p99", p99), (">5ms", 5.0), (">10ms", 10.0)]:
        n, hit = explained(thr)
        pct = (100.0 * hit / n) if n else 0.0
        print(f"  ticks {label:>6} ({thr:5.2f}ms): {n:4d} slow, {hit:4d} coincide with a GC pause ({pct:.0f}%)")

if __name__ == "__main__":
    main()
