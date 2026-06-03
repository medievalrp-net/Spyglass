---
name: taking-issues
description: Use when picking up one or more GitHub issues from the Spyglass backlog to implement. Captures the issue → branch off main → implement+test → self-review → verify → PR-and-merge-to-main loop, plus the rules for multi-issue sessions. Source of truth for Spyglass issue work.
---

# Taking issues — the Spyglass workflow

The end-to-end loop for moving a Spyglass GitHub issue from "open" to "merged on main".

## The loop

```
Read issue → Branch off main → Plan briefly → Implement with tests
  → Self-review → Verify it works → PR + merge to main → Close → Report
```

If verification comes back PARTIAL/FAIL: loop back to "Implement" — not to "Read issue".

## Single-issue path

### 1. Read the issue

```bash
gh issue view <N>
```

Parse:
- **User story** — the "As X, I want Y, so that Z" sentence
- **Acceptance criteria** — every Given/When/Then bullet
- **Implementation notes** — file paths, suggested approach, gotchas
- **Depends-on links** — read those issues too if they're not yet shipped

### 2. Confirm prerequisites

If the issue says "Depends on #N" and #N is still open, STOP. Either take #N first (insert it ahead in the queue) or report the blocker. Don't proceed past an unmet dependency.

Also pin down **which module(s)** the work lands in (`spyglass-api` / `spyglass-core` / `spyglass` / `spyglass-velocity`) — that sets the parity obligations in step 5.

### 3. Branch off main

```bash
git fetch origin
git checkout main
git pull --ff-only origin main
git checkout -b feat/<N>-<slug>
```

Slug = short kebab-case from the title. Example: `feat/42-clickhouse-ip-index`.

### 4. Plan briefly

Two to four sentences: approach, files to touch, test strategy. If the plan touches >5 files, crosses a module boundary, or the AC is genuinely unclear, surface it to the user before coding.

### 5. Implement with tests

Follow the conventions in `CLAUDE.md` and match the surrounding code. Non-trivial logic ships a JUnit 5 test (AssertJ + Mockito; Testcontainers for store integration tests) in the **same commit**.

Mind the **event-type parity rule** (`CLAUDE.md` → Hard rules) whenever you touch event types: the sealed interface, `EventCatalog`, the listener, **both** storage backends, and `config.conf` move together. A new event wired into only one backend is a bug, not a feature.

### 6. Run the tests

```bash
./gradlew :<module>:test     # fast — just the module you touched
./gradlew check              # full build + jacoco floors, before you merge
```

Output must show passes. "Looks correct to me" is not verification. Capture the output for the report. For rollback / world-write changes that need a live server, drive `/verify` or the regression harness (`./gradlew regression`, needs `../RP_Server` up).

### 7. Self-review

Run `/code-review` on your own diff:

```bash
git diff main..HEAD
```

Address every finding before committing. If the review surfaces a design problem, fix it now — don't defer. For changes to query parsing, SQL/BSON building, permissions, or anything taking player-typed input, also run `/security-review`.

### 8. Commit

Conventional commit style, referencing the issue:

```bash
git commit -m "feat: <subject> (#N)"
```

One logical change per commit. Multiple commits per issue is fine.

### 9. Verify it works

Map every AC bullet to concrete evidence — a passing test, captured output, or an observed run via `/verify`. Don't claim done on an AC you haven't demonstrated.

- All bullets demonstrably met → continue
- Any bullet PARTIAL/unmet → loop back to step 5 with the specific gap

### 10. PR + merge to main

```bash
git push -u origin feat/<N>-<slug>
gh pr create --fill --base main
# once green:
gh pr merge --squash --delete-branch
```

Never force-push. If `main` moved under you, `git fetch origin && git rebase origin/main` — never force-push a shared branch.

### 11. Close the issue

```bash
gh issue close <N> --comment "Merged to main as <SHA>."
```

### 12. Report

One paragraph to the user: what shipped, test-output reference, what's still open or blocked.

## Multi-issue path

When invoked with multiple numbers (e.g. `19 20 21`):

1. **Read all of them first** — `gh issue view` each before deciding order.
2. **Build the dependency graph** — parse each "Depends on" line, topologically sort.
3. **Confirm cohesion** — same epic or shared module/file area? If not, push back on the batching.
4. **Run the single-issue path for each, in dependency order.**
5. **Between issues, ensure `main` is clean** (no uncommitted changes, no stashes) before starting the next.
6. **Aggregate the final report.**

If one issue in the batch fails, STOP the batch. Report what completed and what blocked. Don't continue past a failure.

## Doing it yourself vs delegating

Default to doing the work **yourself, inline** — the user prefers firsthand implementation and review over fanning out to subagents. Reserve a subagent only for genuinely large, parallelizable sweeps (e.g. a mechanical change across all ~40 listeners) that one context can't hold. Never delegate the self-review or the AC verification away from your own read of the diff.

## Hard rules

- **Never force-push `main` or a shared branch.** Never rewrite published history.
- **Never skip verification.** Tests passing is necessary, not sufficient — the AC must actually be met.
- **Never invent AC.** If a bullet is unclear, comment on the issue and ask — don't guess.
- **Respect module boundaries and the event-type parity rule.** Cross-backend parity is not optional.
- **Never lower a jacoco floor to make `check` pass.** Add the missing test instead.
- **If credentials or services are missing** (a reachable Mongo/ClickHouse, `../RP_Server` for regression, `gh` repo access) and the issue needs them, STOP and report — don't fabricate a workaround that ships incorrect behavior.

## Failure modes to recognize

| Symptom | Likely cause | Action |
|---|---|---|
| Tests pass but AC isn't really met | Tests too shallow | Strengthen tests; re-map AC to evidence |
| New event records on one backend, missing on the other | Event-type parity rule missed | Wire `EventCatalog` + Mongo codec + ClickHouse schema/mapper + `config.conf` |
| `./gradlew check` fails on coverage | New code has no test | Add a test for the new branch — don't touch the floor |
| Rollback change is green in unit test but wrong in-world | Needs live verification | `/verify` or `./gradlew regression` against `../RP_Server` |
| Merge conflict on `main` | Stale feature branch | `git fetch origin && git rebase origin/main`; never force-push a shared branch |
| `gh`/`git` say "repository not found" | Wrong gh account active — the repo is private | `gh auth switch -u itdontmata` (the account with access), then retry |

## Done definition

An issue is done when ALL of these are true:

- Code changes merged on `main` (via PR)
- `./gradlew check` passes (output captured in the report)
- `/code-review` applied to the diff, findings addressed
- Every AC bullet demonstrably met
- Issue closed with the merge SHA in the comment

Anything less = not done. Report accurately.
