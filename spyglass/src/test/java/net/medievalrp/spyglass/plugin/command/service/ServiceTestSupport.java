package net.medievalrp.spyglass.plugin.command.service;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Helpers shared across service tests for capturing and asserting against Adventure messages
 * delivered to mocked senders.
 */
final class ServiceTestSupport {

    private ServiceTestSupport() {
    }

    static List<Component> captureMessages(CommandSender sender) {
        List<Component> captured = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return null;
        }).when(sender).sendMessage(ArgumentMatchers.any(Component.class));
        return captured;
    }

    static List<String> plainTexts(List<Component> components) {
        List<String> out = new ArrayList<>(components.size());
        for (Component c : components) {
            out.add(PlainTextComponentSerializer.plainText().serialize(c));
        }
        return out;
    }

    /**
     * A {@link ServiceSupport} that queues onAsyncThread / onMainThread
     * runnables instead of running them, so a test can assert that a blocking
     * call has NOT executed inline (it's still queued) and then {@link #drain}
     * it to completion. async tasks run first (they may enqueue main tasks),
     * then main tasks.
     */
    static final class RecordingSupport implements ServiceSupport {
        final List<Runnable> async = new ArrayList<>();
        final List<Runnable> main = new ArrayList<>();

        @Override
        public void onMainThread(Runnable runnable) {
            main.add(runnable);
        }

        @Override
        public void onMainThreadLater(long delayTicks, Runnable runnable) {
            main.add(runnable);
        }

        @Override
        public void onAsyncThread(Runnable runnable) {
            async.add(runnable);
        }

        void drain() {
            while (!async.isEmpty()) {
                async.remove(0).run();
            }
            while (!main.isEmpty()) {
                main.remove(0).run();
            }
        }
    }
}
