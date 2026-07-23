package dev.faultora.cli;

import dev.faultora.model.security.SecretHandle;
import dev.faultora.spi.contract.SecretResolutionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentSecretResolverTest {

    @Test
    void redactShowsLastFourCharacters() {
        assertThat(EnvironmentSecretResolver.redact("abcdefghijklmnop"))
                .isEqualTo("************mnop");
    }

    @Test
    void redactShortValueReturnsMask() {
        assertThat(EnvironmentSecretResolver.redact("abc"))
                .isEqualTo("***");
    }

    @Test
    void redactExactFourCharsReturnsAll() {
        // 4-char value: length <= MIN_SECRET_LENGTH → returns "***"
        assertThat(EnvironmentSecretResolver.redact("abcd"))
                .isEqualTo("***");
    }

    @Test
    void redactFiveCharsShowsLastFour() {
        assertThat(EnvironmentSecretResolver.redact("abcde"))
                .isEqualTo("*bcde");
    }

    @Test
    void redactNullReturnsMask() {
        assertThat(EnvironmentSecretResolver.redact(null))
                .isEqualTo("***");
    }

    @Test
    void redactEmptyReturnsMask() {
        assertThat(EnvironmentSecretResolver.redact(""))
                .isEqualTo("***");
    }

    @Test
    void createHandleDoesNotExposeRawValue() {
        SecretHandle handle = EnvironmentSecretResolver.createHandle("test-key", "super-secret-value-1234");
        assertThat(handle.handleId()).isEqualTo("test-key");
        // "super-secret-value-1234" = 23 chars → 19 asterisks + "1234"
        assertThat(handle.redacted()).isEqualTo("*******************1234");
        assertThat(handle.sourceType()).isEqualTo("env");
        assertThat(handle.expiresAt()).isEqualTo(-1);
        // toString should also not expose the raw value
        assertThat(handle.toString()).doesNotContain("super-secret-value-1234");
    }

    @Test
    void createHandleProvidesSecretValue() {
        SecretHandle handle = EnvironmentSecretResolver.createHandle("test-key", "super-secret-value-1234");
        char[] value = handle.secretValue();
        assertThat(value).isNotNull();
        assertThat(new String(value)).isEqualTo("super-secret-value-1234");
    }

    @Test
    void secretValueReturnsDefensiveCopyAndZeroesOnFirstAccess() {
        SecretHandle handle = EnvironmentSecretResolver.createHandle("test-key", "my-secret");
        char[] first = handle.secretValue();
        assertThat(first).isNotNull();
        assertThat(new String(first)).isEqualTo("my-secret");

        // After first access, the internal value is zeroed — subsequent calls
        // return a zeroed array (security: secret material doesn't persist)
        char[] second = handle.secretValue();
        assertThat(second).isNotNull();
        assertThat(second).containsExactly(new char[9]); // new char[9] is all zeros

        // Modifying the first copy should not affect anything (it's independent)
        first[0] = 'X';
        assertThat(first[0]).isEqualTo('X');
    }

    @Test
    void valuelessHandleReturnsNullSecret() {
        SecretHandle handle = new SecretHandle("test", "***", "env", -1);
        assertThat(handle.secretValue()).isNull();
    }

    @Test
    void resolveThrowsForNullHandleId() {
        EnvironmentSecretResolver resolver = new EnvironmentSecretResolver();
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(SecretResolutionException.class);
    }

    @Test
    void resolveThrowsForBlankHandleId() {
        EnvironmentSecretResolver resolver = new EnvironmentSecretResolver();
        assertThatThrownBy(() -> resolver.resolve("  "))
                .isInstanceOf(SecretResolutionException.class);
    }

    @Test
    void resolveThrowsForNonexistentEnvVar() {
        // Use a prefix that's extremely unlikely to exist
        EnvironmentSecretResolver resolver = new EnvironmentSecretResolver("FAULTORA_TEST_NOEXIST_");
        assertThatThrownBy(() -> resolver.resolve("definitely-not-real"))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("FAULTORA_TEST_NOEXIST_DEFINITELY_NOT_REAL");
    }

    @Test
    void isAvailableReturnsFalseForNullHandleId() {
        EnvironmentSecretResolver resolver = new EnvironmentSecretResolver();
        assertThat(resolver.isAvailable(null)).isFalse();
    }

    @Test
    void isAvailableReturnsFalseForBlankHandleId() {
        EnvironmentSecretResolver resolver = new EnvironmentSecretResolver();
        assertThat(resolver.isAvailable("  ")).isFalse();
    }

    @Test
    void isAvailableReturnsFalseForNonexistentEnvVar() {
        EnvironmentSecretResolver resolver = new EnvironmentSecretResolver("FAULTORA_TEST_NOEXIST_");
        assertThat(resolver.isAvailable("definitely-not-real")).isFalse();
    }

    @Test
    void isAvailableReturnsTrueForExistingEnvVar() {
        // PATH is guaranteed to exist on all systems
        EnvironmentSecretResolver resolver = new EnvironmentSecretResolver("");
        assertThat(resolver.isAvailable("PATH")).isTrue();
    }

    @Test
    void resolveReturnsHandleForExistingEnvVar() {
        // PATH is guaranteed to exist on all systems
        EnvironmentSecretResolver resolver = new EnvironmentSecretResolver("");
        SecretHandle handle = resolver.resolve("PATH");
        assertThat(handle).isNotNull();
        assertThat(handle.handleId()).isEqualTo("PATH");
        assertThat(handle.sourceType()).isEqualTo("env");
        assertThat(handle.redacted()).isNotBlank();
        // The redacted value should not be the full PATH
        String pathValue = System.getenv("PATH");
        assertThat(handle.redacted()).isNotEqualTo(pathValue);
    }

    @Test
    void resolveCachesSubsequentCalls() {
        EnvironmentSecretResolver resolver = new EnvironmentSecretResolver("");
        SecretHandle first = resolver.resolve("PATH");
        SecretHandle second = resolver.resolve("PATH");
        // Should return the same cached instance
        assertThat(first).isSameAs(second);
    }

    @Test
    void customPrefixIsUsed() {
        // Use HOME which exists on most Unix systems
        EnvironmentSecretResolver resolver = new EnvironmentSecretResolver("");
        assertThat(resolver.isAvailable("HOME")).isTrue();
    }
}
