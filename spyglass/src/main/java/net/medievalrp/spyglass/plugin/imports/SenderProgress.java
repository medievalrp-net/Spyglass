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
            String text = toString(StandardCharsets.UTF_8);
            if (text.isEmpty()) {
                return;
            }
            reset();
            for (String line : text.split("\n", -1)) {
                if (!line.isBlank()) {
                    String msg = line.stripTrailing();
                    support.onMainThread(() -> sender.sendMessage("[import] " + msg));
                }
            }
        }
    }
}
