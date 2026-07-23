package dev.faultora.model.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SecretHandleTest {

    @Test
    void destroysSupplierValueAfterMakingDefensiveCopy() {
        AtomicReference<char[]> supplied = new AtomicReference<>();
        SecretHandle handle = new SecretHandle(
                "api-key", "***", "test", -1, () -> {
                    char[] value = "temporary-secret".toCharArray();
                    supplied.set(value);
                    return value;
                });

        char[] returned = handle.secretValue();

        assertThat(new String(returned)).isEqualTo("temporary-secret");
        assertThat(supplied.get()).containsOnly('\0');
    }
}
