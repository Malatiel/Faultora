package dev.faultora.model.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    @Test
    void evidencePolicyEnforcesSensitiveHeadersAndCopiesInputs() {
        Set<String> denied = new HashSet<>(Set.of("x-secret"));
        EvidencePolicy policy = new EvidencePolicy(
                true, true, denied, 1024, 10,
                List.of("$.token"), Set.of("Application/JSON"), "session");
        denied.clear();

        assertThat(policy.headerDenylist())
                .contains("authorization", "cookie", "set-cookie",
                        "proxy-authorization", "x-secret");
        assertThat(policy.contentTypeAllowlist()).containsExactly("application/json");
    }
}
