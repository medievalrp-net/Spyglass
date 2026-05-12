package net.medievalrp.spyglass.api.rollback;

import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;

public sealed interface RollbackEffect permits
        RollbackEffect.BlockReplace,
        RollbackEffect.ContainerSlotWrite,
        RollbackEffect.EntitySpawn,
        RollbackEffect.EntityRemove,
        RollbackEffect.Custom {

    record BlockReplace(BlockLocation location, BlockSnapshot expectedCurrent, BlockSnapshot replacement) implements RollbackEffect {
    }

    record ContainerSlotWrite(BlockLocation location, int slot, StoredItem expectedCurrent, StoredItem replacement) implements RollbackEffect {
    }

    record EntitySpawn(BlockLocation location, String entityType, String serializedEntity) implements RollbackEffect {
    }

    record EntityRemove(BlockLocation location, String entityType, String entityId) implements RollbackEffect {
    }

    /**
     * Third-party rollback effect routed to a registered
     * {@link RollbackEffectHandler}. Use this for state your plugin
     * owns that Spyglass's built-in effects can't model (faction
     * territory, custom-block bridges, plugin-managed NPCs).
     *
     * @param type handler key; must match a registered
     *     {@link RollbackEffectHandler#type()} at restore time or the
     *     effect is skipped with {@code NotSupported}
     * @param location best-effort location for display and wand
     *     attribution; may be {@code null}
     * @param payload handler-defined bytes; never {@code null}
     */
    record Custom(String type, BlockLocation location, byte[] payload) implements RollbackEffect {
        public Custom {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("RollbackEffect.Custom requires a non-blank type");
            }
            payload = payload == null ? new byte[0] : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
