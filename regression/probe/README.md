# Isolated Paper wand regression probe

`WandProbe.java` is a test-only plugin, never a production dependency. Compile against Paper 26.3 and its compile dependencies with Java 25, package with a plugin descriptor whose main is `regression.probe.WandProbe`, dependency is `Spyglass`, and command is `wandprobe` (permission `spyglass.reload`).

On an isolated fixture with an inactive test player's wand and `tool.material = GLOWSTONE`, run `wandprobe <player>` from the console. It dispatches real Paper placement/interaction events for a PDC-tagged redstone-lamp wand in both hands as a non-op, then an ordinary lamp control. It restores the player's operator state. These are event-level checks, not a native-client placement test.

The Minecraft 26.3 bridged Mineflayer client failed the ordinary placement control even with Spyglass removed. `SG_BRIDGED_26_3=true` allows `regression/bot/verify-config-and-guis.mjs` to use this explicitly reported substitute; without it, the ordinary control must pass. The probe jar must be installed first. Snapshot and salvage GUI checks still use actual client clicks. Native 26.3 wand placement remains a manual acceptance check.

Never install this probe on a production server: it deliberately dispatches synthetic events and uses fixture coordinates 65,80,66.

## Logging interaction probe

`LoggingProbe.java` is also test-only. Compile with Java 25 against Paper 26.3 and Adventure 5.2.0, with main `regression.probe.LoggingProbe`, dependency `Spyglass`, and console command `loggingprobe` (permission `spyglass.reload`). The probe uses reflection for version-specific vanilla methods and runs on all three modern fixtures. Do not load it on 1.21.11.

`loggingprobe <player>` changes fixture coordinates 111–120 around Y=80 and invokes vanilla server interaction methods for age locking, applicable sulfur-cube mechanics, cushions and straw beds, then breaks sulfur-spike supports. It writes `logging-probe-result.txt`. These exercise server behavior and real Paper events; they do not certify native 26.x client packet handling.

`regression/bot/verify-copper-golem.mjs` runs actual copper-golem AI on all four versions. Configure `SG_HOST`, `SG_PORT`, `SG_RCON_PORT`, and `SG_RCON_PASS` for an isolated offline fixture. With `SG_LOGGING_PROBE=true`, `SG_SERVER_DIR` and `SG_MINECRAFT_TARGET`, it also invokes the modern probe and verifies applicable rollback/undo. An independent database check must verify the resulting persisted events and slot payloads; successful world interactions alone do not establish logging correctness.

Never install either probe on a production server. Both intentionally modify blocks, entities and player state.
