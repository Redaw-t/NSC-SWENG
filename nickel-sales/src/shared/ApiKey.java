package shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The developer's API key: typed in once on the first Installation Setup page, then sent with every
 * call one terminal makes to another (the X-API-Key header), which refuses calls without it.
 *
 * Only the key's SHA-256 hash is in the code, never the key itself, so the source code does not give
 * it away. To issue a new key, put its hash in {@link #DEFAULT_SHA256} (or set the environment variable
 * NSC_API_KEY_SHA256 on every terminal) and give the key to the store.
 */
public final class ApiKey {

    /** The HTTP header that carries the key. */
    public static final String HEADER = "X-API-Key";

    /** SHA-256 (hex) of the key issued by the developer. */
    private static final String DEFAULT_SHA256 = "ab04da0cd8ef5e77c5a928a6ca4f919103ea25653af1e0e7747c579ce19e3cec";

    private ApiKey() { }

    /** True when {@code key} is the developer's key (spaces around it and letter case are ignored). */
    public static boolean isValid(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        byte[] given = sha256(normalize(key));
        byte[] expected = HexFormat.of().parseHex(expectedHash());
        return MessageDigest.isEqual(given, expected);   // same time whatever matches, so it can't be guessed bit by bit
    }

    /** The key as it is compared and stored: trimmed, upper case (the keys are upper-case letters and digits). */
    public static String normalize(String key) {
        return key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
    }

    private static String expectedHash() {
        String override = Settings.get("NSC_API_KEY_SHA256");
        return override == null || override.isBlank() ? DEFAULT_SHA256 : override.trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
