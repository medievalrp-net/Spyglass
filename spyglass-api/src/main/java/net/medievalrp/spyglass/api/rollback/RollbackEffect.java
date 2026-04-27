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
     * Third-party rollback effect dispatched through a registered
     * {@link RollbackEffectHandler}. The {@code type} is the handler
     * key; {@code payload} is whatever the handler chose to encode
     * (treated as opaque bytes by Spyglass's storage and undo
     * layers).
     *
     * <p>Use this for restoring or reversing state your plugin owns
     * (faction territory, custom-block bridges, plugin-managed NPCs,
     * etc.) — anything Spyglass's built-in effects can't model.
     *
     * @param type handler key (e.g. {@code "faction-territory"}); must
     *     match a {@link RollbackEffectHandler#type()} registered at
     *     restore time, otherwise the effect is skipped with reason
     *     {@code NotSupported}
     * @param location best-effort location for display / wand
     *     attribution; may be {@code null}
     * @param payload handler-defined byte payload; never {@code null}
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
