package dev.faultora.model.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Content digests used for reproducibility and evidence references.
 * <p>
 * Scenario and catalog digests pin what a run was compiled from; evidence
 * digests let a report reference a response body without storing it. Both use
 * the same algorithm and the same {@code sha256:} prefix, so they are defined
 * once here.
 */
public final class ContentDigest {

    /** Prefix identifying the digest algorithm in a digest reference. */
    public static final String SHA256_PREFIX = "sha256:";

    private ContentDigest() {
    }

    /** Hex-encoded SHA-256 of the given bytes. */
    public static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            // SHA-256 is mandated by the Java platform; reaching this means the
            // JVM cannot honour reproducibility guarantees at all.
            throw new IllegalStateException("SHA-256 is not available", unavailable);
        }
    }

    /** Hex-encoded SHA-256 of the UTF-8 encoding of the given text. */
    public static String sha256Hex(String content) {
        return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    /** Prefixed digest reference, as recorded in events and reports. */
    public static String sha256Uri(byte[] content) {
        return SHA256_PREFIX + sha256Hex(content);
    }

    /** Prefixed digest reference, as recorded in events and reports. */
    public static String sha256Uri(String content) {
        return SHA256_PREFIX + sha256Hex(content);
    }
}
