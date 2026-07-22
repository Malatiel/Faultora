package dev.faultora.testkit;

import dev.faultora.model.security.SecretHandle;
import dev.faultora.spi.contract.SecretResolutionException;
import dev.faultora.spi.contract.SecretResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Technology compatibility kit for SecretResolver implementations.
 */
public abstract class SecretResolverCompatibilityKit {

    /**
     * Provide the resolver under test.
     */
    protected abstract SecretResolver createResolver();

    /**
     * Provide a valid handle ID that the resolver can resolve.
     */
    protected abstract String validHandleId();

    @Test
    void resolveValidHandleReturnsSecretHandle() {
        SecretResolver resolver = createResolver();
        SecretHandle handle = resolver.resolve(validHandleId());

        assertThat(handle).isNotNull();
        assertThat(handle.handleId()).isNotBlank();
        assertThat(handle.redacted()).isNotBlank();
        assertThat(handle.sourceType()).isNotBlank();
    }

    @Test
    void resolveInvalidHandleThrowsException() {
        SecretResolver resolver = createResolver();
        assertThatThrownBy(() -> resolver.resolve("nonexistent-handle-id-12345"))
                .isInstanceOf(SecretResolutionException.class);
    }

    @Test
    void isAvailableReturnsCorrectly() {
        SecretResolver resolver = createResolver();
        assertThat(resolver.isAvailable(validHandleId())).isTrue();
        assertThat(resolver.isAvailable("nonexistent-handle-id-12345")).isFalse();
    }

    @Test
    void resolvedHandleDoesNotExposeRawValue() {
        SecretResolver resolver = createResolver();
        SecretHandle handle = resolver.resolve(validHandleId());
        String toString = handle.toString();

        assertThat(toString).doesNotContain("raw-secret");
        assertThat(toString).contains(handle.handleId());
    }
}
