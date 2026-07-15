package net.medievalrp.spyglass.plugin.storage;

import java.nio.charset.StandardCharsets;

/**
 * Resolves the MongoDB connection string from config.
 *
 * <p>The discrete {@code host} / {@code port} / {@code user} / {@code password}
 * / {@code ssl} fields cover the common single-node case and read consistently
 * with the other backends. Anything they can't express - a replica set,
 * {@code mongodb+srv://} (Atlas), or advanced TLS - goes in the optional
 * {@code uri} override, which when set is used verbatim.
 */
public final class MongoConnectionString {

    private MongoConnectionString() {
    }

    /**
     * Returns {@code override} untouched when it is set; otherwise builds a
     * {@code mongodb://} string from the discrete fields, percent-encoding the
     * credentials (MongoDB requires it when they contain reserved characters).
     */
    public static String resolve(String override, String host, int port,
                                 String user, String password, boolean ssl) {
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        StringBuilder uri = new StringBuilder("mongodb://");
        if (user != null && !user.isBlank()) {
            uri.append(encode(user));
            if (password != null && !password.isEmpty()) {
                uri.append(':').append(encode(password));
            }
            uri.append('@');
        }
        uri.append(host).append(':').append(port);
        if (ssl) {
            uri.append("/?tls=true");
        }
        return uri.toString();
    }

    // Percent-encode per RFC 3986, escaping everything outside the unreserved
    // set so a credential containing ':' '@' '/' '?' etc. can't break the URI.
    private static String encode(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int c = raw & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }
}
