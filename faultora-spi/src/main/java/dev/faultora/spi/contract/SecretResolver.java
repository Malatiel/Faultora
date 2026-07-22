package dev.faultora.spi.contract;

import dev.faultora.model.security.SecretHandle;

/**
 * Resolves secret handle IDs to opaque SecretHandle values.
 * Implementations may read from environment variables, files, or vault services.
 * The returned handle never exposes the raw secret value.
 */
public interface SecretResolver {

    /**
     * Resolve a secret by its handle ID.
     *
     * @param handleId the secret handle identifier
     * @return the resolved secret handle
     * @throws SecretResolutionException if the secret cannot be resolved
     */
    SecretHandle resolve(String handleId);

    /**
     * Check if a secret is available without actually resolving it.
     *
     * @param handleId the secret handle identifier
     * @return true if the secret can be resolved
     */
    boolean isAvailable(String handleId);
}
