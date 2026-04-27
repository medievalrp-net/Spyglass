package net.medievalrp.spyglass.api.event;

public record Origin(String kind, String detail) {

    public static final String PLAYER = "player";
    public static final String WORLDEDIT = "worldedit";
    public static final String FAWE = "fawe";
    public static final String PLUGIN = "plugin";
    public static final String ENVIRONMENT = "environment";
    public static final String ROLLBACK = "rollback";

    public static Origin player() {
        return new Origin(PLAYER, null);
    }

    public static Origin worldEdit() {
        return new Origin(WORLDEDIT, null);
    }

    public static Origin fawe() {
        return new Origin(FAWE, null);
    }

    public static Origin plugin(String pluginName) {
        return new Origin(PLUGIN, pluginName);
    }

    public static Origin environment(String description) {
        return new Origin(ENVIRONMENT, description);
    }

    /**
     * Origin for synthesized records created by {@code /sg rollback},
     * {@code /sg restore}, or {@code /sg undo}. The detail string is
     * the operator's name so a wand-hover on a rolled-back block reads
     * "ROLLBACK (by Joe)" rather than just "ROLLBACK".
     */
    public static Origin rollback(String operatorName) {
        return new Origin(ROLLBACK, operatorName);
    }
}
