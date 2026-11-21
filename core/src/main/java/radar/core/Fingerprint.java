package radar.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A stable fingerprint for an incident signal, so the same failure coming back is recognised as a recurrence and
 * linked to the post-incident actions that were meant to prevent it.
 *
 * Volatile parts are stripped: UUIDs, hex ids, numbers, quoted values, pod suffixes.
 */
public final class Fingerprint {
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HEX = Pattern.compile("\\b0x[0-9a-f]+\\b|\\b[0-9a-f]{12,}\\b");
    private static final Pattern POD = Pattern.compile("-[a-z0-9]{8,10}-[a-z0-9]{5}\\b");
    private static final Pattern QUOTED = Pattern.compile("'[^']*'|\"[^\"]*\"");
    private static final Pattern NUM = Pattern.compile("\\d+");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private Fingerprint() {}

    public static String normalise(String signal) {
        String s = signal.toLowerCase(Locale.ROOT);
        s = UUID.matcher(s).replaceAll("<id>");
        s = POD.matcher(s).replaceAll("-<pod>");
        s = HEX.matcher(s).replaceAll("<hex>");
        s = QUOTED.matcher(s).replaceAll("<v>");
        s = NUM.matcher(s).replaceAll("<n>");
        return SPACE.matcher(s).replaceAll(" ").trim();
    }

    public static String of(String service, Mode mode, String signal) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((service + "|" + mode + "|" + normalise(signal)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
