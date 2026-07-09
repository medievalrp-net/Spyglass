package net.medievalrp.spyglass.plugin.imports;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import net.medievalrp.spyglass.plugin.command.service.ServiceSupport;
import org.bukkit.command.CommandSender;

/** Bridges the engine's PrintStream progress to a Bukkit sender, line by line. */
final class SenderProgress extends PrintStream {
    SenderProgress(CommandSender sender, ServiceSupport support) {
        super(new LineForwarder(sender, support), true, StandardCharsets.UTF_8);
    }

    private static final class LineForwarder extends ByteArrayOutputStream {
        private final CommandSender sender;
        private final ServiceSupport support;

        LineForwarder(CommandSender sender, ServiceSupport support) {
            this.sender = sender;
            this.support = support;
        }

        @Override
        public synchronized void flush() {
            // The autoflushing PrintStream flushes per printf SEGMENT, not per
            // line - forwarding the raw buffer sent fragments ("co_block",
            // " done - 17,103", " rows") as separate chat messages (#252).
            // Forward complete lines only; keep any trailing partial buffered.
            String text = toString(StandardCharsets.UTF_8);
            int lastNewline = text.lastIndexOf('\n');
            if (lastNewline < 0) {
                return;
            }
            String remainder = text.substring(lastNewline + 1);
            String complete = text.substring(0, lastNewline);
            reset();
            if (!remainder.isEmpty()) {
                byte[] keep = remainder.getBytes(StandardCharsets.UTF_8);
                write(keep, 0, keep.length);
            }
            for (String line : complete.split("\n", -1)) {
                if (!line.isBlank()) {
                    String msg = line.stripTrailing();
                    support.onMainThread(() -> sender.sendMessage(
                            net.medievalrp.spyglass.plugin.command.render.Feedback.info(msg)));
                }
            }
        }
    }
}
