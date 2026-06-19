package net.medievalrp.spyglass.plugin.worldedit;

import com.fastasyncworldedit.core.extent.processor.ProcessorScope;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.Tag;
import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.item.ItemType;
import com.sk89q.worldedit.world.item.ItemTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@SuppressWarnings({"removal", "deprecation"})
@ApiStatus.Internal
final class FaweBatchLogger implements IBatchProcessor {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final Source source;
    private final UUID worldId;
    private final String worldName;
    private final FaweEditDedupe loggedCells = new FaweEditDedupe();

    FaweBatchLogger(Recorder recorder, RecordingSupport support,
                    Source source,
                    UUID worldId, String worldName) {
        this.recorder = recorder;
        this.support = support;
        this.source = source;
        this.worldId = worldId;
        this.worldName = worldName;
    }

    @Override
    public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) {
        return set;
    }

    @Override
    public Future<?> postProcessSet(IChunk chunk, IChunkGet get, IChunkSet set) {
        logSet(chunk, get, set);
        return CompletableFuture.completedFuture(null);
    }

    private void logSet(IChunk chunk, IChunkGet get, IChunkSet set) {
        if (set.isEmpty()) {
            return;
        }
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        int minSection = set.getMinSectionPosition();
        int maxSection = set.getMaxSectionPosition();
        Instant occurred = support.now();
        Origin origin = Origin.fawe();
        Source source = this.source;
        String serverName = support.serverName();
        List<EventRecord> out = new ArrayList<>();

        for (int sy = minSection; sy <= maxSection; sy++) {
            if (!set.hasSection(sy)) {
                continue;
            }
            for (int ly = 0; ly < 16; ly++) {
                int wy = sy * 16 + ly;
                for (int lz = 0; lz < 16; lz++) {
                    int wz = chunkZ * 16 + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        int wx = chunkX * 16 + lx;
                        BlockState weBefore;
                        BlockState weAfter;
                        try {
                            weBefore = get.getBlock(lx, wy, lz);
                            weAfter = set.getBlock(lx, wy, lz);
                        } catch (RuntimeException ex) {
                            continue;
                        }
                        if (weAfter == null) {
                            continue;
                        }
                        if (weBefore != null && weBefore.equalsFuzzy(weAfter)) {
                            continue;
                        }
                        if (!loggedCells.mark(wx, wy, wz)) {
                            continue;
                        }
                        BlockSnapshot before = snapshot(weBefore, safeTile(get, lx, wy, lz));
                        BlockSnapshot after = snapshot(weAfter, null);
                        BlockLocation location = new BlockLocation(worldId, worldName, wx, wy, wz);
                        WorldEditRecords.appendCell(out, support, origin, source, serverName,
                                location, occurred, before, after);
                    }
                }
            }
        }
        if (!out.isEmpty()) {
            recorder.recordAll(out);
        }
    }

    @Override
    public Extent construct(Extent child) {
        return child;
    }

    @Override
    public ProcessorScope getScope() {
        return ProcessorScope.READING_BLOCKS;
    }

    private BlockSnapshot snapshot(BlockState weState, CompoundTag tile) {
        if (weState == null) {
            return BlockSnapshots.air();
        }
        try {
            BlockData data = BukkitAdapter.adapt(weState);
            Material material = data.getMaterial();
            String blockDataStr = data.getAsString();
            List<StoredItem> items = tile == null ? List.of() : parseContainerItems(tile);
            return new BlockSnapshot(material, blockDataStr,
                    items, List.of(), List.of(), List.of(), null);
        } catch (RuntimeException ex) {
            return BlockSnapshots.air();
        }
    }

    private List<StoredItem> parseContainerItems(CompoundTag tile) {
        List<? extends Tag<?, ?>> list;
        try {
            list = tile.getList("Items");
        } catch (RuntimeException ex) {
            return List.of();
        }
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<StoredItem> out = new ArrayList<>();
        for (Tag<?, ?> tag : list) {
            if (!(tag instanceof CompoundTag itemTag)) {
                continue;
            }
            StoredItem item = asStoredItem(itemTag);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    private StoredItem asStoredItem(CompoundTag itemTag) {
        try {
            int slot = itemTag.getByte("Slot") & 0xFF;
            String id = itemTag.getString("id");
            if (id == null || id.isEmpty()) {
                return null;
            }
            ItemType itemType = ItemTypes.get(id);
            if (itemType == null) {
                return null;
            }
            int count;
            try {
                count = itemTag.getByte("Count");
            } catch (RuntimeException ex) {
                count = 1;
            }
            if (count <= 0) {
                count = 1;
            }
            BaseItemStack baseItemStack = new BaseItemStack(itemType, itemTag, count);
            ItemStack bukkitStack = BukkitAdapter.adapt(baseItemStack);
            if (bukkitStack == null || bukkitStack.getType() == Material.AIR) {
                return null;
            }
            return ItemSerialization.storedItem(slot, bukkitStack);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private CompoundTag safeTile(IChunkGet get, int lx, int wy, int lz) {
        try {
            return get.getTile(lx, wy, lz);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
