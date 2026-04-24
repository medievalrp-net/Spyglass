package net.medievalrp.omniscience2.api.extension;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.medievalrp.omniscience2.api.event.EventRecord;
import net.medievalrp.omniscience2.api.query.Flag;

public interface DisplayRenderer {

    default Component renderTarget(EventRecord record, Component defaultTarget, java.util.EnumSet<Flag> flags) {
        return defaultTarget;
    }

    default List<Component> hoverLines(EventRecord record) {
        return List.of();
    }
}
