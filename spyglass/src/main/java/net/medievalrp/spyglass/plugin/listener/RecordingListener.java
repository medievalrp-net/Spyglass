package net.medievalrp.spyglass.plugin.listener;

import java.util.Set;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.ApiStatus;

/**
 * A plain Bukkit {@link Listener} that emits records to the
 * {@link net.medievalrp.spyglass.plugin.pipeline.Recorder} via
 * {@code @EventHandler}-annotated methods. Supersedes the earlier
 * {@code EventExtractor} indirection — there's no separate registry and no
 * generic {@code extract() -> Stream<R>} pipeline; handlers call
 * {@code recorder.record(...)} directly.
 *
 * <p>The single contract {@link #events()} lets the plugin gate registration
 * on config — a listener is only registered with Bukkit when at least one of
 * its declared events is enabled. Listeners that don't care about the config
 * gate can return an empty set and will always be registered.
 */
@ApiStatus.Internal
public interface RecordingListener extends Listener {

    Set<String> events();
}
