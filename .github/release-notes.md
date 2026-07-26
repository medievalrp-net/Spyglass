Point-in-time inventory and container views, a public block-capture API, and a fix for Maven Central publishes that were silently never going public.

- New /sg snapshot: reconstruct a player inventory or a container as it stood at a past instant, browse it in a GUI, and take items back out of it. Every take is logged and attributed, so pulling an item out of a reconstructed inventory is itself searchable history.
- spyglass-api can now build a BlockSnapshot. BlockSnapshots.capture(BlockState) and the captureRaw/finishCapture split live in net.medievalrp.spyglass.api.capture, along with ItemSerialization. Plugins recording their own block events previously had no supported way to produce the snapshot that BlockBreakRecord and BlockPlaceRecord require, and the workaround was compiling against the GPL-3.0 plugin jar. See API.md section 3.
- Maven Central publishing actually publishes now. Releases 1.0.8, 1.0.9 and 1.0.10 uploaded to the Central Portal, passed validation, then stopped and waited for a manual click nobody made, while the release job reported success. Central served 1.0.7 throughout. The release now publishes without the manual step and waits for Central to confirm, so a green job means the artifact is public.
- spyglass-api.jar ships as a GitHub release asset from this release on, with sources and javadoc, so Central is no longer the only way to get the API.
- API.md said the API jar ships under the plugin's terms and linked the GPL-3.0 licence. It is Apache-2.0. Corrected, along with a local-jar section that pointed at a file nothing published.

Nothing here changes stored data or config, and no migration runs. Upgrading from 1.0.10 is a jar swap.
