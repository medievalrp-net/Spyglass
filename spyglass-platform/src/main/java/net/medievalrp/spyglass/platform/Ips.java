package net.medievalrp.spyglass.platform;

import java.util.regex.Pattern;

/**
 * The IP rule (Crusalis D12): no {@code spyglass.*} event ever carries an IP address.
 *
 * <p>Spyglass stores one — {@link net.medievalrp.spyglass.api.event.JoinRecord} has an
 * {@code address} — and the mapper simply never reads that component. This class is the
 * belt to that braces: {@link #looksLikeIp(CharSequence)} is what the test over every
 * record fixture asserts against every published string, so a field added to a Spyglass
 * record later cannot quietly carry an address into the store.
 */
final class Ips {

    // Deliberately loose. The point is to catch anything that might be an address, not
    // to validate one: a false positive here costs a dropped field in a test fixture,
    // and a false negative costs a column of PII in a store that keeps it for 90 days.
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\w.])");
    private static final Pattern IPV6 = Pattern.compile(
            "(?<![\\w:])(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}(?![\\w:])");

    private Ips() {
    }

    static boolean looksLikeIp(CharSequence value) {
        if (value == null || value.length() < 3) {
            return false;
        }
        return IPV4.matcher(value).find() || IPV6.matcher(value).find();
    }
}
