# Isolated Paper wand regression probe

`WandProbe.java` is a test-only plugin, never a production dependency. Compile against Paper 26.3 and its compile dependencies with Java 25, package with a plugin descriptor whose main is `regression.probe.WandProbe`, dependency is `Spyglass`, and command is `wandprobe` (permission `spyglass.reload`).

On an isolated fixture with an inactive test player's wand and `tool.material = GLOWSTONE`, run `wandprobe <player>` from the console. It dispatches real Paper placement/interaction events for a PDC-tagged redstone-lamp wand in both hands as a non-op, then an ordinary lamp control. It restores the player's operator state. These are event-level checks, not a native-client placement test.

The Minecraft 26.3 bridged Mineflayer client failed the ordinary placement control even with Spyglass removed. `SG_BRIDGED_26_3=true` allows `regression/bot/verify-config-and-guis.mjs` to use this explicitly reported substitute; without it, the ordinary control must pass. The probe jar must be installed first. Snapshot and salvage GUI checks still use actual client clicks. Native 26.3 wand placement remains a manual acceptance check.

Never install this probe on a production server: it deliberately dispatches synthetic events and uses fixture coordinates 65,80,66.
