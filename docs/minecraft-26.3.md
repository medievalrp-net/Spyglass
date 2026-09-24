# Minecraft 26.3 port

This records the initial Paper 26.3 / Java 25 port. The 2.0 line now produces [separate builds for each supported Minecraft version](versioned-builds.md). Minecraft 1.21.x is maintained separately on `maintenance/1.21`, starting at the v1.0.12 release (`4da291b`). No published history was rewritten.

## Dependencies and compatibility

- Paper API: `26.3.build.38-alpha`.
- Cloud Minecraft: `2.0.1`. The old beta.16 item parser searched signatures removed from CraftItemStack and disabled the entire plugin at startup; 2.0.1 supports `asBukkitMirror`.
- InvUI: `2.5.0`, bundled and relocated in both distributions. Its click, item-provider, window and pagination APIs replace the InvUI 1.x APIs.
- The GUI gate reads `Server.getMinecraftVersion()`, not the changing Bukkit artifact-version format. Each artifact only loads on its embedded Minecraft target; mismatched versions are rejected before configuration or InvUI initialization.
- WorldEdit compile API: 7.4.5. Live WorldEdit testing used the 26.3-compatible 7.4.6-beta-01 plugin. FAWE remains an optional compile-only API; no 26.3 FAWE runtime was validated.
- Java 25 toolchains, JaCoCo 0.8.14 and Adventure 5-compatible test assertions.

InvUI's upstream compatibility table currently assigns 1.49 to Minecraft 1.14–1.21.11; 2.0–2.1 to 26.1.2; 2.2–2.3 to 26.2; and 2.4–2.5 to 26.3. See https://github.com/NichtStudioCode/InvUI#version-compatibility.

## Verification (2026-09-23)

`gradlew.bat build` passes on Windows / Java 25.0.1: 969 passed, 93 skipped, zero failures. Of the skips, 92 were already skipped in the baseline run without Docker; the remaining skip is the existing read-only-directory test, whose setup is unsupported by this Windows filesystem. The test now uses an assumption for that setup instead of failing before reaching the behavior under test. Database-container integration coverage is not claimed. The Verify workflow runs the Java 25 build on PRs, including snapshot development versions that the release workflow does not publish.

Live Paper 26.3 build 38 (alpha), default SQLite, isolated loopback server:

| Check | Result |
| --- | --- |
| Lean jar startup and commands, without extra plugins | Pass |
| Shaded jar startup and commands, without extra plugins | Pass |
| WorldEdit deletion recorded, rollback restores stone, restore deletes it again | Pass |
| New 26.3 poplar logs: place, rollback, restore with world-state assertions | Pass |
| Container snapshot GUI renders and copies exactly seven diamonds | Pass, both jars |
| Snapshot take permission removed while window is open | Refused, shaded jar |
| Snapshot whole-stack extraction with a full inventory | Refused, shaded jar |
| Salvage rollback -> container -> item navigation | Pass, both jars |
| Salvage withdraw, reopen without duplicating items, undo restores chest | Pass, both jars |
| SQLite history remains queryable after restart and jar replacement | Pass |

GUI checks use a real bot connection through ViaVersion/ViaBackwards 5.12.0, with a 1.21.8 client protocol. Mineflayer 4.39.0 lacks a native 26.3 protocol implementation. The bridge rejects bot movement, so the GUI probe suppresses movement packets and uses server teleports; inventory clicks still travel over the connection. Inventory quantities are asserted through server-side RCON entity data, not the bot's stale inventory cache. WorldEdit creates the logged changes. This is not a native 26.3 client walkthrough or proof of vanilla player place/break interactions, every event type, all GUI click modes, pagination, or full player-snapshot coverage.

No production service was changed. The isolated test server was stopped after verification. Before release, complete the native client and wider integration checks above; `2.0.0-SNAPSHOT` is deliberately not automatically published.

## Repeating the GUI probe

Use a disposable Paper 26.3 server with SQLite, the candidate Spyglass jar, WorldEdit 7.4.6-beta-01, ViaVersion and ViaBackwards 5.12.0, offline mode, whitelist disabled, and loopback-only game/RCON ports. Enable `snapshot.players.enabled=true` and set its interval to `1s` in this disposable server's config; the probe now checks player snapshots too. The probe changes blocks near 64,80,64, creates an operator bot, and exercises item recovery. Do not run it against production.

Install the regression bot dependencies with `npm install --prefix regression/bot`. Set `SG_PORT`, `SG_RCON_PORT`, `SG_RCON_PASS`, and optionally `SG_HOST` (defaults to loopback), then run:

```
node regression/bot/_26.3-gui.mjs
```

The existing `scripts/boot-smoke.sh` can separately test either jar against 26.3 on a Bash host. Main's release workflow uses Java 25 and skips `-SNAPSHOT` versions. Legacy patches should be released from the maintenance branch with explicit versioned links and without promoting them over the modern latest release.
