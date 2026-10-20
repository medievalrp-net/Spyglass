package net.medievalrp.spyglass.plugin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

/**
 * The acceptance criterion that matters to every network that is not Crusalis: with the
 * integration off, or without the SDK, <b>no integration class is loaded</b> — not
 * disabled, not loaded.
 *
 * <p>Asserted by watching a class loader rather than by reading the code, because the
 * failure mode is subtle: naming a type in a field, a parameter or a catch clause is
 * enough to make the JVM resolve it, and the result on a server without the SDK is a
 * {@code NoClassDefFoundError} during enable — a plugin that fails to start because of
 * an integration nobody asked for.
 */
class CrusalisPlatformLoaderTest {

    private JavaPlugin pluginLogging(List<String> lines) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Logger logger = new Logger("test", null) {
            @Override
            public void info(String message) {
                lines.add(message);
            }
        };
        when(plugin.getLogger()).thenReturn(logger);
        return plugin;
    }

    @Test
    void disabledLoadsNothingAndSaysNothing() {
        List<String> lines = new ArrayList<>();
        JavaPlugin plugin = pluginLogging(lines);

        AutoCloseable handle = CrusalisPlatformLoader.start(plugin,
                Map.of("enabled", false, "include", List.of("*")));

        assertThat(handle).isNull();
        // Silence is the point: a server that never asked about Crusalis should not
        // learn from its console that Crusalis exists.
        assertThat(lines).isEmpty();
    }

    @Test
    void anAbsentSettingsBlockIsTreatedAsOff() {
        assertThat(CrusalisPlatformLoader.start(pluginLogging(new ArrayList<>()), null))
                .isNull();
        assertThat(CrusalisPlatformLoader.start(pluginLogging(new ArrayList<>()), Map.of()))
                .isNull();
    }

    /**
     * The real guarantee, read off the class file: {@link CrusalisPlatformLoader} holds
     * no symbolic reference to any Crusalis class or to the integration module, so
     * loading it cannot resolve one. Both names appear in its constant pool as strings,
     * which is the whole technique — a string is not a class reference.
     *
     * <p>Checked by walking the constant pool rather than by watching a class loader,
     * because a class loader only sees what something asked for; this sees what the
     * verifier would have to resolve.
     */
    @Test
    void theLoaderHoldsNoSymbolicReferenceToAnyCrusalisClass() throws Exception {
        List<String> referenced = classReferencesOf(CrusalisPlatformLoader.class);

        assertThat(referenced).isNotEmpty();
        assertThat(referenced)
                .as("a class reference that a server without the SDK would have to resolve")
                .noneMatch(name -> name.startsWith("net/crusalis/"))
                .noneMatch(name -> name.startsWith("net/medievalrp/spyglass/platform/"));
    }

    /** CONSTANT_Class entries of a class file: everything the verifier may resolve. */
    private static List<String> classReferencesOf(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (java.io.InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            bytes = in.readAllBytes();
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        buffer.getInt();                       // magic
        buffer.getShort();                     // minor
        buffer.getShort();                     // major
        int count = buffer.getShort() & 0xFFFF;
        Map<Integer, String> utf8 = new java.util.HashMap<>();
        Map<Integer, Integer> classes = new java.util.HashMap<>();
        for (int index = 1; index < count; index++) {
            int tag = buffer.get() & 0xFF;
            switch (tag) {
                case 1 -> {                    // Utf8
                    int length = buffer.getShort() & 0xFFFF;
                    byte[] text = new byte[length];
                    buffer.get(text);
                    utf8.put(index, new String(text, java.nio.charset.StandardCharsets.UTF_8));
                }
                case 7 -> classes.put(index, buffer.getShort() & 0xFFFF);  // Class
                case 8, 16, 19, 20 -> buffer.getShort();                   // String, MethodType, Module, Package
                case 15 -> {                                               // MethodHandle
                    buffer.get();
                    buffer.getShort();
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> buffer.getInt();       // int/float/refs/NameAndType/Dynamic
                case 5, 6 -> {                                             // long, double
                    buffer.getLong();
                    index++;                   // they take two pool slots
                }
                default -> throw new IllegalStateException("unknown constant pool tag " + tag);
            }
        }
        List<String> names = new ArrayList<>();
        classes.values().forEach(nameIndex -> names.add(utf8.get(nameIndex)));
        return names;
    }

    @Test
    void enabledWithoutTheSdkLogsOneLineAndStartsNothing() {
        List<String> lines = new ArrayList<>();

        AutoCloseable handle = CrusalisPlatformLoader.start(pluginLogging(lines),
                Map.of("enabled", true));

        // The SDK's API is genuinely absent from this module's classpath: the plugin
        // module does not depend on it, which is why this test can assert the branch at
        // all rather than mocking it.
        assertThat(handle).isNull();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("CrusalisPlatformSDK")
                .contains("not installed")
                .contains("Spyglass's own logging is unaffected");
    }

}
