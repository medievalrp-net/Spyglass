package net.medievalrp.ingestbench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Sustained synthetic block-event load generator plus per-tick MSPT capture,
 * built for the ingest benchmark (Spyglass vs CoreProtect vs a vanilla
 * baseline).
 *
 * <p>The plugin runs in ALL three benchmark configurations so its own cost
 * cancels in the delta: added MSPT(plugin) = MSPT(generator + plugin) -
 * MSPT(generator alone). Every tick it fires R = round(eventsPerSecond / 20)
 * real {@link BlockBreakEvent} / {@link BlockPlaceEvent}s through the plugin
 * manager as an online actor player; the logging plugins' MONITOR listeners
 * record them. The world is never mutated, so per-tick cost stays constant and
 * identical across configs.
 *
 * <p>MSPT is taken from the server's own per-tick work durations via
 * {@link org.bukkit.Server#getTickTimes()} (nanoseconds). We do NOT trust a
 * fixed buffer-index convention: each tick we diff the array against the
 * previous tick's snapshot and the single changed slot is the newest completed
 * tick. This is robust to whatever index ordering the running Paper build uses.
 */
public final class IngestBench extends JavaPlugin {

    // ── run state ──────────────────────────────────────────────────────
    private volatile boolean running = false;
    private String label = "run";
    private double evps = 0.0;
    private int warmupTicks = 0;
    private int measureTicks = 0;
    private int elapsedTicks = 0;
    private double rateAccumulator = 0.0;
    private int cellCursor = 0;

    // ── platform (set from config.yml on each start) ───────────────────
    private String worldName = "world";
    private int px = 0, py = 64, pz = 0, psize = 8;
    // Workload. mutate=true: realistic break + place that actually toggles the
    // work cells air<->stone, so CoreProtect (which logs by reading the world
    // block) and Spyglass both log every event 1:1. mutate=false: non-mutating
    // break-only (cheapest, also fair, since both log breaks identically).
    private boolean mutate = false;

    // ── tick-time capture ──────────────────────────────────────────────
    private long[] prevTimes = null;
    private int headIndex = -1;            // slot written for the most recent completed tick
    private final List<Long> samples = new ArrayList<>();
    // Wall-clock (epoch ms) for each sample, so elevated ticks can be correlated
    // against an external GC log to root-cause tail latency.
    private final List<Long> sampleWall = new ArrayList<>();
    private int anomalies = 0;             // ticks where the changed-slot count was not 1

    private BukkitRunnable task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        task = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        };
        // Period 1 -> runs once per server tick, on the main thread.
        task.runTaskTimer(this, 1L, 1L);
        getLogger().info("IngestBench enabled; per-tick driver scheduled.");
    }

    @Override
    public void onDisable() {
        if (running) {
            finishRun("disable");
        }
        if (task != null) {
            task.cancel();
        }
    }

    // ── the per-tick driver ─────────────────────────────────────────────
    private void tick() {
        // Capture first, every tick (cheap), so the baseline tracker stays warm
        // even between runs and so the sample is the tick BEFORE we add load.
        long newest = captureNewestTickTime();

        if (!running) {
            return;
        }

        fireEvents();

        elapsedTicks++;
        if (elapsedTicks > warmupTicks && newest >= 0) {
            samples.add(newest);
            sampleWall.add(System.currentTimeMillis());
        }
        if (elapsedTicks >= warmupTicks + measureTicks) {
            finishRun("complete");
        }
    }

    /**
     * Returns the duration (ns) of the most recently completed tick, detected by
     * diffing the {@link org.bukkit.Server#getTickTimes()} ring against last
     * tick's snapshot. Returns -1 while still bootstrapping (first observed tick)
     * or if the value cannot be disambiguated.
     */
    private long captureNewestTickTime() {
        long[] cur = Bukkit.getServer().getTickTimes();
        if (cur == null || cur.length == 0) {
            return -1;
        }
        if (prevTimes == null || prevTimes.length != cur.length) {
            prevTimes = cur.clone();
            return -1; // need one baseline snapshot before we can diff
        }

        int changed = 0, changedIdx = -1;
        for (int i = 0; i < cur.length; i++) {
            if (cur[i] != prevTimes[i]) {
                changed++;
                changedIdx = i;
            }
        }

        long result;
        if (changed == 1) {
            // Normal case: exactly one new tick completed since last sample.
            headIndex = changedIdx;
            result = cur[changedIdx];
        } else if (changed == 0) {
            // Two consecutive ticks landed the identical nanosecond value in the
            // same slot (vanishingly rare). Advance the head by one and read it.
            if (headIndex >= 0) {
                headIndex = (headIndex + 1) % cur.length;
                result = cur[headIndex];
            } else {
                result = -1;
            }
        } else {
            // The scheduler skipped one or more ticks (server hiccup) so several
            // slots changed at once and we cannot order them. Advance by one from
            // the last known head and flag it.
            anomalies++;
            if (headIndex >= 0) {
                headIndex = (headIndex + 1) % cur.length;
                result = cur[headIndex];
            } else {
                result = -1;
            }
        }

        prevTimes = cur.clone();
        return result;
    }

    private void fireEvents() {
        rateAccumulator += evps / 20.0;
        int n = (int) Math.floor(rateAccumulator);
        if (n <= 0) {
            return;
        }
        rateAccumulator -= n;

        Player actor = firstPlayer();
        if (actor == null) {
            return; // no online actor this tick; skip (re-checked next tick)
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            world = actor.getWorld();
        }
        if (world == null) {
            return;
        }

        PluginManager pm = Bukkit.getPluginManager();
        ItemStack inHand = new ItemStack(Material.STONE);
        int span = Math.max(1, psize);

        for (int i = 0; i < n; i++) {
            int dx = cellCursor % span;
            int dz = (cellCursor / span) % span;
            cellCursor++;
            try {
                if (mutate) {
                    // Realistic break + place with REAL world mutation, so both
                    // CoreProtect (which logs a block change by reading the world)
                    // and Spyglass log every event identically (1 row each). Each
                    // work cell toggles air<->stone in the layer above the platform,
                    // in true Minecraft event order: a break fires while the block
                    // is still solid (MONITOR listeners read it) then clears it; a
                    // place sets the block first then fires (listeners read it).
                    Block cell = world.getBlockAt(px + dx, py + 1, pz + dz);
                    if (cell.getType() == Material.STONE) {
                        pm.callEvent(new BlockBreakEvent(cell, actor));
                        cell.setType(Material.AIR, false);
                    } else {
                        BlockState replaced = cell.getState();
                        cell.setType(Material.STONE, false);
                        Block against = world.getBlockAt(px + dx, py, pz + dz);
                        pm.callEvent(new BlockPlaceEvent(
                                cell, replaced, against, inHand, actor, true, EquipmentSlot.HAND));
                    }
                } else {
                    // Non-mutating break-only: both plugins log breaks 1:1 cheaply,
                    // with no setBlock physics/lighting cost in the baseline.
                    Block solid = world.getBlockAt(px + dx, py, pz + dz);
                    pm.callEvent(new BlockBreakEvent(solid, actor));
                }
            } catch (Throwable t) {
                // One malformed event must never abort the run.
                getLogger().log(Level.WARNING, "IngestBench event fire failed", t);
            }
        }
    }

    private Player firstPlayer() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            return p;
        }
        return null;
    }

    private void finishRun(String why) {
        running = false;
        writeCsv();
        getLogger().info(String.format(
                "IngestBench run '%s' %s: rate=%.0f ev/s, warmup=%dt, measured %d ticks, anomalies=%d",
                label, why, evps, warmupTicks, samples.size(), anomalies));
        samples.clear();
        sampleWall.clear();
        elapsedTicks = 0;
        anomalies = 0;
        rateAccumulator = 0.0;
    }

    private void writeCsv() {
        Path dir = outputDir();
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("ticks-" + label + ".csv");
            try (BufferedWriter w = Files.newBufferedWriter(file)) {
                w.write("label,rate_evps,seq,mspt_ns,wall_ms\n");
                long seq = 0;
                long rate = Math.round(evps);
                for (int i = 0; i < samples.size(); i++) {
                    w.write(label);
                    w.write(',');
                    w.write(Long.toString(rate));
                    w.write(',');
                    w.write(Long.toString(seq++));
                    w.write(',');
                    w.write(Long.toString(samples.get(i)));
                    w.write(',');
                    w.write(Long.toString(i < sampleWall.size() ? sampleWall.get(i) : 0L));
                    w.write('\n');
                }
            }
            getLogger().info("IngestBench wrote " + file + " (" + samples.size() + " samples)");
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "IngestBench CSV write failed", ex);
        }
    }

    private Path outputDir() {
        String d = getConfig().getString("output-dir", "");
        if (d == null || d.isEmpty()) {
            return getDataFolder().toPath().resolve("out");
        }
        return Paths.get(d);
    }

    private void loadPlatform() {
        reloadConfig();
        worldName = getConfig().getString("platform.world", "world");
        px = getConfig().getInt("platform.x", 0);
        py = getConfig().getInt("platform.y", 64);
        pz = getConfig().getInt("platform.z", 0);
        psize = Math.max(1, getConfig().getInt("platform.size", 8));
        mutate = getConfig().getBoolean("mutate", false);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String lbl, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("usage: /ingestbench start <evps> <warmupSec> <measureSec> <label> | stop | status");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "start": {
                if (running) {
                    sender.sendMessage("IngestBench: already running");
                    return true;
                }
                if (args.length < 5) {
                    sender.sendMessage("usage: /ingestbench start <evps> <warmupSec> <measureSec> <label>");
                    return true;
                }
                try {
                    evps = Double.parseDouble(args[1]);
                    warmupTicks = (int) Math.round(Double.parseDouble(args[2]) * 20.0);
                    measureTicks = (int) Math.round(Double.parseDouble(args[3]) * 20.0);
                    label = args[4].replaceAll("[^A-Za-z0-9_.-]", "_");
                } catch (NumberFormatException ex) {
                    sender.sendMessage("bad number: " + ex.getMessage());
                    return true;
                }
                loadPlatform();
                samples.clear();
        sampleWall.clear();
                elapsedTicks = 0;
                anomalies = 0;
                rateAccumulator = 0.0;
                cellCursor = 0;
                running = true;
                sender.sendMessage(String.format(
                        "IngestBench: started '%s' %.0f ev/s warmup=%ss measure=%ss platform=%s@%d,%d,%d size=%d",
                        label, evps, args[2], args[3], worldName, px, py, pz, psize));
                return true;
            }
            case "stop": {
                if (!running) {
                    sender.sendMessage("IngestBench: not running");
                    return true;
                }
                finishRun("stopped");
                sender.sendMessage("IngestBench: stopped, CSV written");
                return true;
            }
            case "status": {
                sender.sendMessage(String.format(
                        "IngestBench: running=%s label=%s rate=%.0f elapsed=%d/%d samples=%d players=%d anomalies=%d",
                        running, label, evps, elapsedTicks, warmupTicks + measureTicks,
                        samples.size(), Bukkit.getOnlinePlayers().size(), anomalies));
                return true;
            }
            default:
                sender.sendMessage("unknown subcommand: " + args[0]);
                return true;
        }
    }
}
