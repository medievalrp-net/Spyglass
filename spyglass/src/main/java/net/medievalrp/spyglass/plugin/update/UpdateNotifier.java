package net.medievalrp.spyglass.plugin.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bson.Document;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** Six-hour anonymous GitHub checks off the server thread; never downloads plugin jars. */
public final class UpdateNotifier implements Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, String> notified = new HashMap<>();
    private volatile String status = "Update check pending.";
    private volatile ReleaseChecker.Release available;
    private BukkitTask task;

    public UpdateNotifier(JavaPlugin plugin) { this.plugin = plugin; }

    public void start() throws java.io.IOException {
        var root = HoconConfigurationLoader.builder().path(plugin.getDataFolder().toPath().resolve("config.conf")).build().load();
        if (!root.node("updates", "enabled").getBoolean(true)) {
            status = "Update checks disabled.";
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::check, 20L, 6L * 60 * 60 * 20);
    }

    public String status() { return status; }

    private void check() {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            List<Document> releases = new ArrayList<>();
            boolean complete = false;
            for (int page = 1; page <= 10; page++) {
                var request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/medievalrp-net/Spyglass/releases?per_page=100&page=" + page))
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("User-Agent", "Spyglass-update-checker")
                        .GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    if (response.statusCode() != 200) throw new java.io.IOException("GitHub HTTP " + response.statusCode());
                    byte[] bytes = body.readNBytes(8 * 1024 * 1024 + 1);
                    if (bytes.length > 8 * 1024 * 1024) throw new java.io.IOException("Release response too large");
                    List<Document> batch = Document.parse("{\"releases\":" + new String(bytes, java.nio.charset.StandardCharsets.UTF_8) + "}")
                            .getList("releases", Document.class);
                    releases.addAll(batch);
                    if (batch.size() < 100) { complete = true; break; }
                }
            }
            if (!complete) throw new java.io.IOException("Release history exceeds check limit");
            String minecraft = plugin.getServer().getMinecraftVersion();
            var latest = ReleaseChecker.newest(releases, minecraft);
            available = latest.filter(r -> ReleaseChecker.newer(r.version(), plugin.getPluginMeta().getVersion())).orElse(null);
            status = available != null ? "Spyglass " + available.version() + " is available for Minecraft " + minecraft + ": " + available.url()
                    : latest.isEmpty() ? "No compatible published release found for Minecraft " + minecraft + "."
                    : "No newer compatible release found for Minecraft " + minecraft + ".";
            if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (available != null) {
                    plugin.getLogger().info(status);
                    plugin.getServer().getOnlinePlayers().forEach(this::notifyPlayer);
                }
            });
        } catch (Exception failure) {
            available = null;
            status = "Update check unavailable; see GitHub releases or try again later.";
            plugin.getLogger().fine("Spyglass update check: " + failure.getMessage());
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> notifyPlayer(event.getPlayer()), 60L);
    }

    private void notifyPlayer(Player player) {
        var release = available;
        if (release == null || !player.isOnline() || !player.hasPermission("spyglass.update")
                || release.version().equals(notified.get(player.getUniqueId()))) return;
        notified.put(player.getUniqueId(), release.version());
        player.sendMessage(Component.text("Spyglass " + release.version() + " is available for this Minecraft version. Click to view the release.", NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.openUrl(release.url())));
    }
}
