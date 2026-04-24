package net.medievalrp.spyglass.plugin.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public interface V1ItemDecoder {

    String V1_CLASS_KEY = "ClassName";

    Optional<StoredItem> decode(int slot, Map<String, Object> itemDoc);

    static V1ItemDecoder bukkit() {
        return (slot, doc) -> {
            if (doc == null || doc.isEmpty()) {
                return Optional.empty();
            }
            try {
                Map<String, Object> translated = translateKeys(doc);
                ItemStack itemStack = (ItemStack) ConfigurationSerialization.deserializeObject(translated, ItemStack.class);
                if (itemStack == null) {
                    return Optional.empty();
                }
                StoredItem stored = ItemSerialization.storedItem(slot, itemStack);
                return Optional.ofNullable(stored);
            } catch (RuntimeException ex) {
                return Optional.empty();
            }
        };
    }

    static V1ItemDecoder noop() {
        return (slot, doc) -> Optional.empty();
    }

    @SuppressWarnings("unchecked")
    static Object translateValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return translateKeys((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(translateValue(item));
            }
            return out;
        }
        return value;
    }

    static Map<String, Object> translateKeys(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>(input.size());
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = V1_CLASS_KEY.equals(entry.getKey())
                    ? ConfigurationSerialization.SERIALIZED_TYPE_KEY
                    : entry.getKey();
            out.put(key, translateValue(entry.getValue()));
        }
        return out;
    }
}
