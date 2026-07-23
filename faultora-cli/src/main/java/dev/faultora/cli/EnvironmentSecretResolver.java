package dev.faultora.cli;

import dev.faultora.model.security.SecretHandle;
import dev.faultora.spi.contract.SecretResolutionException;
import dev.faultora.spi.contract.SecretResolver;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves secrets from environment variables.
 * <p>
 * Handle IDs are mapped to environment variable names using a configurable prefix.
 * For example, with prefix {@code FAULTORA_SECRET_}, handle ID {@code api-key}
 * maps to environment variable {@code FAULTORA_SECRET_API_KEY}.
 * <p>
 * The raw secret value is accessible via {@link SecretHandle#secretValue()} which
 * returns a defensive copy. The redacted form (last 4 characters, masked) is stored
 * directly in the handle for safe display.
 * <p>
 * Thread-safe: handles are cached after first resolution.
 */
public class EnvironmentSecretResolver implements SecretResolver {

    private static final String DEFAULT_PREFIX = "FAULTORA_SECRET_";
    private static final int MIN_SECRET_LENGTH = 4;

    private final String prefix;
    private final Map<String, Object> resolvedCache = new ConcurrentHashMap<>();

    /**
     * Create a resolver with the default prefix ({@code FAULTORA_SECRET_}).
     */
    public EnvironmentSecretResolver() {
        this(DEFAULT_PREFIX);
    }

    /**
     * Create a resolver with a custom environment variable prefix.
     *
     * @param prefix the prefix for environment variable lookup
     */
    public EnvironmentSecretResolver(String prefix) {
        this.prefix = prefix != null ? prefix : DEFAULT_PREFIX;
    }

    @Override
    public SecretHandle resolve(String handleId) {
        if (handleId == null || handleId.isBlank()) {
            throw new SecretResolutionException(handleId, "Handle ID must not be null or blank");
        }

        // Check cache first (may contain null sentinel for "resolved as absent")
        Object cached = resolvedCache.get(handleId);
        if (cached instanceof SecretHandle handle) {
            if (handle.isExpired()) {
                resolvedCache.remove(handleId);
                throw new SecretResolutionException(handleId,
                        "Secret handle has expired: " + handleId);
            }
            return handle;
        }
        if (cached == NULL_SENTINEL) {
            throw new SecretResolutionException(handleId,
                    "Secret not found in environment: " + envVarName(handleId));
        }

        // Resolve from environment
        String envVar = envVarName(handleId);
        String value = System.getenv(envVar);

        if (value == null) {
            resolvedCache.put(handleId, NULL_SENTINEL);
            throw new SecretResolutionException(handleId,
                    "Secret not found in environment: " + envVar);
        }

        SecretHandle handle = createHandle(handleId, value);
        resolvedCache.put(handleId, handle);
        return handle;
    }

    @Override
    public boolean isAvailable(String handleId) {
        if (handleId == null || handleId.isBlank()) return false;

        Object cached = resolvedCache.get(handleId);
        if (cached instanceof SecretHandle handle) {
            return !handle.isExpired();
        }
        if (cached == NULL_SENTINEL) return false;

        return System.getenv(envVarName(handleId)) != null;
    }

    /**
     * Create a SecretHandle from a raw secret value.
     * The raw value is captured via a supplier so it can be retrieved
     * when needed (e.g., for Authorization header injection).
     * The redacted form shows only the last 4 characters (or fewer if shorter).
     */
    static SecretHandle createHandle(String handleId, String value) {
        String redacted = redact(value);
        // Capture the value as a char[] supplier. The char[] is created on first call
        // and zeroed immediately after the defensive copy is made, so the original
        // secret material does not persist in memory beyond the first access.
        // The caller must still zero-fill the returned copy after use.
        char[] valueChars = value.toCharArray();
        return new SecretHandle(handleId, redacted, "env", -1, () -> {
            if (valueChars == null) return null;
            char[] copy = Arrays.copyOf(valueChars, valueChars.length);
            Arrays.fill(valueChars, '\0');
            return copy;
        });
    }

    /**
     * Redact a secret value: show last 4 characters, mask the rest.
     * Returns "***" for values shorter than 4 characters.
     */
    static String redact(String value) {
        if (value == null) return "***";
        if (value.length() <= MIN_SECRET_LENGTH) return "***";
        int visibleStart = value.length() - MIN_SECRET_LENGTH;
        return "*".repeat(visibleStart) + value.substring(visibleStart);
    }

    private String envVarName(String handleId) {
        // Convert handle ID to env var: lowercase, replace hyphens/periods with underscores, uppercase
        return prefix + handleId.toLowerCase()
                .replace("-", "_")
                .replace(".", "_")
                .toUpperCase();
    }

    /**
     * Sentinel object to distinguish "resolved as absent" from null (not yet checked).
     */
    private static final Object NULL_SENTINEL = new Object();
}
