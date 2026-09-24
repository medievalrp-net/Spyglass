Spyglass 2.0 introduces separate Minecraft builds from one shared source release.

- Minecraft 26.1.2, 26.2 and 26.3 each have a matching lean and shaded JAR with their pinned InvUI dependency. Install the JAR labeled for your exact server version. All modern builds require Java 25.
- Rollback salvage (`/sg inventory`) and container/player snapshots (`/sg snapshot`) use inventory windows on every supported modern target. Text inventory listings and chat recovery commands have been removed. Console/RCON receives an in-game-only message, and an unavailable or failing snapshot GUI reports an error.
- Snapshot copying retains its permission checks, whole-stack inventory-fit requirement and audit records. Player inventory history still requires `snapshot.players.enabled=true`; container snapshots do not require that setting.
- Incorrect Minecraft builds are rejected at startup before InvUI initialization.
- One release contains all three targets, optional Velocity companions, developer API artifacts and SHA256 checksums. API Maven versions include the Minecraft target.

Minecraft 1.21.x remains on the separate `maintenance/1.21` release line with Java 21 and InvUI 1.49. Do not install a modern 2.0 JAR on a 1.21 server.
