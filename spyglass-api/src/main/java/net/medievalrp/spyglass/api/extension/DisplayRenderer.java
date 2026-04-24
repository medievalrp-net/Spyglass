package net.medievalrp.spyglass.api.extension;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.query.Flag;

public interface DisplayRenderer {

    default Component renderTarget(EventRecord record, Component defaultTarget, java.util.EnumSet<Flag> flags) {
        return defaultTarget;
    }

    default List<Component> hoverLines(EventRecord record) {
        return List.of();
    }
}
