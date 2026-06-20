#!/usr/bin/env python3
"""Spyglass regression runner.

Seeds Mongo with deterministic test data, issues RCON queries against
`../RP_Server`, and verifies counts match the expectations in cases.json.
Writes a JSON report at `plugin/build/reports/regression/report.json` and
exits non-zero on any failure.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

REPO_ROOT = Path(__file__).resolve().parent.parent
REPORT_DIR = REPO_ROOT / "plugin" / "build" / "reports" / "regression"
REPORT_FILE = REPORT_DIR / "report.json"

RCON_HOST = "127.0.0.1"
RCON_PORT = 25576
RCON_PASSWORD = "test123"
SERVER_DIR = REPO_ROOT.parent / "RP_Server"

COLOR_RE = re.compile(r"§.")
PAGE_HEADER_RE = re.compile(r"Page\s+\d+/\d+\s+—\s+(\d+)\s+results")


def strip_colors(text: str) -> str:
    return COLOR_RE.sub("", text)


@dataclass
class Case:
    id: str
    description: str
    v2: Optional[str] = None
    v2_follow_up: Optional[str] = None
    v1: Optional[str] = None
    v1_follow_up: Optional[str] = None
    expected_count: Optional[int] = None
    expected_output_contains: Optional[str] = None
    compare: str = "exact"


@dataclass
class CaseResult:
    case: Case
    v2_count: Optional[int] = None
    v1_count: Optional[int] = None
    v2_output: str = ""
    v1_output: str = ""
    status: str = "pending"
    message: str = ""
    details: dict = field(default_factory=dict)


def load_cases(path: Path) -> list[Case]:
    with path.open() as fh:
        raw = json.load(fh)
    return [Case(**entry) for entry in raw]


def find_rcon_client():
    try:
        from mcrcon import MCRcon
        return MCRcon
    except ImportError as exc:
        print(f"ERROR: mcrcon not installed. `pip install --user mcrcon` (got {exc}).", file=sys.stderr)
        sys.exit(2)


def find_mongo_client():
    try:
        from pymongo import MongoClient
        return MongoClient
    except ImportError as exc:
        print(f"ERROR: pymongo not installed. `pip install --user pymongo` (got {exc}).", file=sys.stderr)
        sys.exit(2)


def ensure_seed(*, preserve_existing=False):
    """Seed regression fixtures.

    Defaults to a destructive drop of Spyglass.EventRecords and
    v1.DataEntry so exact-count cases aren't skewed by records
    from prior dev sessions or live gameplay. Pass preserve_existing=True
    to fall back to the legacy tag-scoped delete.
    """
    seed_script = REPO_ROOT / "regression" / "seed.py"
    argv = [sys.executable, str(seed_script)]
    if not preserve_existing:
        argv.append("--drop")
    result = subprocess.run(argv, capture_output=True, text=True)
    if result.returncode != 0:
        print("Seeding failed:", result.stderr, file=sys.stderr)
        sys.exit(2)
    print(result.stdout.strip())


def wait_for_rcon(mcrcon_cls, timeout_seconds: int = 30):
    deadline = time.time() + timeout_seconds
    last_error = None
    while time.time() < deadline:
        try:
            with mcrcon_cls(RCON_HOST, RCON_PASSWORD, port=RCON_PORT, timeout=5) as r:
                r.command("list")
                return
        except Exception as exc: # noqa: BLE001
            last_error = exc
            time.sleep(1.0)
    raise RuntimeError(f"Could not reach RCON on {RCON_HOST}:{RCON_PORT} within {timeout_seconds}s. last error: {last_error}")


def count_from_page(text: str) -> Optional[int]:
    clean = strip_colors(text)
    for line in clean.splitlines():
        match = PAGE_HEADER_RE.search(line)
        if match:
            return int(match.group(1))
    return None


def run_case(rcon, case: Case) -> CaseResult:
    result = CaseResult(case=case)
    if case.v2:
        initial = rcon.command(case.v2) or ""
        parts = [initial]
        if case.v2_follow_up:
            time.sleep(0.8)
            parts.append(rcon.command(case.v2_follow_up) or "")
        elif _needs_follow_up(case.v2):
            time.sleep(0.8)
            parts.append(rcon.command("sg page 1") or "")
        result.v2_output = "\n".join(parts)
        result.v2_count = count_from_page(result.v2_output)
    if case.v1:
        initial = rcon.command(case.v1) or ""
        parts = [initial]
        if case.v1_follow_up:
            time.sleep(0.8)
            parts.append(rcon.command(case.v1_follow_up) or "")
        elif _needs_v1_follow_up(case.v1):
            time.sleep(0.8)
            parts.append(rcon.command("sg page 1") or "")
        result.v1_output = "\n".join(parts)
        result.v1_count = extract_v1_count(result.v1_output)
    evaluate(result)
    return result


def _needs_follow_up(command: str) -> bool:
    return command.startswith("sg search") or command.startswith("sg rollback") or command.startswith("sg restore")


def _needs_v1_follow_up(command: str) -> bool:
    return command.startswith("sg search") or command.startswith("sg rollback") or command.startswith("sg restore")


def extract_v1_count(text: str) -> Optional[int]:
    clean = strip_colors(text)
    # v1 output for a search is a preamble followed by N lines starting with "= "
    matches = [line for line in clean.splitlines() if line.startswith("= ")]
    if matches:
        return len(matches)
    # Fallback: some v1 queries emit "Records: N" or similar headers; otherwise report None.
    for line in clean.splitlines():
        m = re.search(r"Records?:\s*(\d+)", line)
        if m:
            return int(m.group(1))
    return None


def evaluate(result: CaseResult) -> None:
    case = result.case
    if case.compare == "exact":
        if case.expected_count is None:
            result.status = "skip"
            result.message = "exact-compare without expected_count"
            return
        if result.v2_count is None:
            result.status = "fail"
            result.message = "could not parse v2 count from output"
            return
        if result.v2_count == case.expected_count:
            result.status = "pass"
            result.message = f"v2={result.v2_count}"
        else:
            result.status = "fail"
            result.message = f"expected {case.expected_count}, v2={result.v2_count}"
    elif case.compare == "ge_v1":
        if result.v2_count is None:
            result.status = "fail"
            result.message = "v2 count unavailable"
            return
        if case.expected_count is not None and result.v2_count != case.expected_count:
            result.status = "fail"
            result.message = f"expected v2={case.expected_count}, got {result.v2_count}"
            return
        if result.v1_count is not None and result.v2_count < result.v1_count:
            result.status = "fail"
            result.message = f"v2 ({result.v2_count}) regressed vs v1 ({result.v1_count})"
            return
        result.status = "pass"
        result.message = f"v2={result.v2_count}, v1={result.v1_count}"
    elif case.compare == "contains":
        if case.expected_output_contains is None:
            result.status = "skip"
            result.message = "contains-compare without expected_output_contains"
            return
        haystack = strip_colors(result.v2_output)
        if case.expected_output_contains in haystack:
            result.status = "pass"
            result.message = f"found '{case.expected_output_contains}'"
        else:
            result.status = "fail"
            result.message = f"output missing '{case.expected_output_contains}'"
    else:
        result.status = "skip"
        result.message = f"unknown compare mode '{case.compare}'"


def write_report(results: list[CaseResult]):
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    payload = {
        "summary": {
            "total": len(results),
            "pass": sum(1 for r in results if r.status == "pass"),
            "fail": sum(1 for r in results if r.status == "fail"),
            "skip": sum(1 for r in results if r.status == "skip"),
        },
        "cases": [
            {
                "id": r.case.id,
                "description": r.case.description,
                "status": r.status,
                "message": r.message,
                "v2_count": r.v2_count,
                "v1_count": r.v1_count,
            }
            for r in results
        ],
    }
    with REPORT_FILE.open("w") as fh:
        json.dump(payload, fh, indent=2)
    return payload


def print_summary(payload: dict):
    summary = payload["summary"]
    status = "PASS" if summary["fail"] == 0 else "FAIL"
    print()
    print(f"regression: {status} — pass={summary['pass']}/{summary['total']} fail={summary['fail']} skip={summary['skip']}")
    for case in payload["cases"]:
        marker = {"pass": "✓", "fail": "✗", "skip": "-"}[case["status"]]
        print(f" {marker} {case['id']:30} {case['message']}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-seed", action="store_true", help="Don't re-seed the Mongo databases.")
    parser.add_argument("--preserve-existing", action="store_true",
                        help="Keep untagged records during seeding (default drops the whole collection).")
    parser.add_argument("--cases", default=str(REPO_ROOT / "regression" / "cases.json"))
    args = parser.parse_args()

    mcrcon_cls = find_rcon_client()
    find_mongo_client()

    cases = load_cases(Path(args.cases))
    if not args.skip_seed:
        ensure_seed(preserve_existing=args.preserve_existing)

    wait_for_rcon(mcrcon_cls)
    results: list[CaseResult] = []
    with mcrcon_cls(RCON_HOST, RCON_PASSWORD, port=RCON_PORT, timeout=15) as r:
        for case in cases:
            results.append(run_case(r, case))

    payload = write_report(results)
    print_summary(payload)
    sys.exit(0 if payload["summary"]["fail"] == 0 else 1)


if __name__ == "__main__":
    main()
