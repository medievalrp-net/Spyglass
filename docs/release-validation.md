# Build, release and Minecraft validation

Validated locally on 2026-09-23. Nothing was pushed, uploaded or published.

## Sources and builds

Modern runtime source: `a851fd273f0e0360b328486d348826729e45c563`.
An isolated checkout changed only the version to `2.0.0` for the stable-version
build exercise; the real development branch remains `2.0.0-SNAPSHOT`. Release
notes were refreshed after finding a stale claim that chat fallback still existed.

Legacy source: `maintenance/1.21`, plugin version `1.0.12`. Commit `e4de1f5`
adds the already-used filesystem assumption to the read-only-directory test.
The original Windows run failed while arranging that test because Windows would
not make the directory read-only. The corrected suite skips it on unsupported
filesystems and still executes it where permissions can be changed. No legacy
runtime code or JAR behavior changed.

| Target | Gradle build | Tests | Passed | Skipped | Failed |
| --- | --- | ---: | ---: | ---: | ---: |
| 1.21 legacy | Pass | 1066 | 973 | 93 | 0 |
| 26.1.2 | Pass | 1068 | 975 | 93 | 0 |
| 26.2 | Pass | 1068 | 975 | 93 | 0 |
| 26.3 | Pass | 1068 | 975 | 93 | 0 |

Each suite skips 92 Docker-dependent tests and one unsupported Windows directory
permission test. Docker was unavailable; these are not full backend integration runs.

## Live server checks

| Paper server | Java | Plugin | Lean | Shaded |
| --- | --- | --- | --- | --- |
| 1.21.8 build 60 | 21 | 1.0.12 | Pass | Pass |
| 1.21.11 build 132 | 21 | 1.0.12 | Pass | Pass |
| 26.1.2 build 74 | 25 | 2.0.0-mc26.1.2 | Pass | Pass |
| 26.2 build 129 | 25 | 2.0.0-mc26.2 | Pass | Pass |
| 26.3 build 38 (alpha) | 25 | 2.0.0-mc26.3 | Pass | Pass |

Each of the ten runs opened real inventory windows for container snapshots,
player snapshots and the rollback/container/item salvage browsers. Assertions
verified snapshot copying, permission revocation, refusal with a full inventory,
exact salvage withdrawal counts, no duplicate recovery after reopening, and
rollback/undo block state. Item quantities were checked on the server through RCON.
The modern builds also rejected console inventory/snapshot browsing as in-game only.
No tested run logged a Spyglass enable failure, snapshot GUI fallback/error or
NoSuchMethodError. All isolated servers were stopped after the checks.

Tests used SQLite, WorldEdit 7.3.19 for legacy and 7.4.6-beta-01 for modern servers,
and ViaVersion/ViaBackwards 5.12.0. The test client uses Minecraft 1.21.8 protocol;
newer servers were exercised through the protocol bridge, with movement suppressed
and server teleports. This verifies live GUI interactions, not a native 26.x client
walkthrough. Pagination, every click mode, FAWE, every event type and every 1.21
patch release were not exhaustively tested. Player snapshot capture was enabled
with a one-second interval only in the disposable test fixtures.

## Release workflow checks

`python -m unittest discover -s scripts/tests` passes 16 tests. The tests execute
the actual workflow Bash with local fake `gh` and Gradle commands where external
publication would occur. They cover snapshots, already-published releases, new
combined releases, same-commit draft resumption, wrong-commit drafts, existing
standalone tags, invalid versions, all-target build/publication loops, failure
short-circuiting and absent Central credentials. Structural checks require build
and staging before publication and retain publication conditions.

Real Gradle builds produced the stable candidate artifacts for all three modern
targets. The real staging script collected all 18 jars and verified embedded plugin
versions/targets; all SHA256 checksums matched. Packaging regression cases reject
missing assets, wrong targets and nonempty output directories. Release and Verify
workflows pass actionlint 1.7.12.

GitHub-hosted Actions execution, real release upload, signing and Sonatype publishing
were not exercised. Those require a pushed workflow and real external publication.

## Tested plugin checksums

- `Spyglass-1.0.12.jar`: `710251254e769fa1d86a67763a09b9c1eb0b40d3630743fde616861bd8df180f`
- `Spyglass-1.0.12-shaded.jar`: `ee2646d1f8f22981bcf80a6c8d8a6785f796fef252fd4da182a9cf7f16e37a6d`
- `Spyglass-2.0.0-mc26.1.2.jar`: `7efddbec0aa335f5745aa519a2eaba0e48c1526e8aae10638a364a23dbe06154`
- `Spyglass-2.0.0-mc26.1.2-shaded.jar`: `b492f77b2b54ebbdc42d2268e3b1c8963b1dcbd3286ff178d4149c7e5d5bf66b`
- `Spyglass-2.0.0-mc26.2.jar`: `f77e754de2602546cd800f1b2862453a33a8bffdddff0cdd57c7c47eed4b9780`
- `Spyglass-2.0.0-mc26.2-shaded.jar`: `053429374c75467ad8d083fbf6e023002256959c6dd810912efa548e1d457e5b`
- `Spyglass-2.0.0-mc26.3.jar`: `00fdc92278f9bca59ddc3951d5d6bbd9e6300da43cd47fdfab35074cd462020c`
- `Spyglass-2.0.0-mc26.3-shaded.jar`: `17d3ccf02caf985aca874d174e241458fb0cba7ca7a3a1181633544044523137`

The task commits use the configured account `itdontmatta` as author and committer, with no AI co-author trailers.
