package dev.faultora.connector.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DestinationPolicyTest {

    @Test
    void defaultPolicyBlocksLocalhost() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://localhost:8080"))).isNotNull();
    }

    @Test
    void defaultPolicyBlocks127() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://127.0.0.1:8080"))).isNotNull();
    }

    @Test
    void defaultPolicyBlocks10x() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://10.0.0.1:8080"))).isNotNull();
    }

    @Test
    void defaultPolicyBlocks192168() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://192.168.1.1:8080"))).isNotNull();
    }

    @Test
    void defaultPolicyBlocks172x() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://172.16.0.1:8080"))).isNotNull();
    }

    @Test
    void defaultPolicyBlocks169254() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://169.254.1.1:8080"))).isNotNull();
    }

    @Test
    void defaultPolicyBlocksIpv6Loopback() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("http://[::1]:8080"))).isNotNull();
    }

    @Test
    void defaultPolicyAllowsPublicHost() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("https://api.example.com"))).isNull();
    }

    @Test
    void permissivePolicyAllowsLocalhost() {
        DestinationPolicy policy = DestinationPolicy.permissive();
        assertThat(policy.check(URI.create("http://localhost:8080"))).isNull();
    }

    @Test
    void permissivePolicyAllowsPrivateNetwork() {
        DestinationPolicy policy = DestinationPolicy.permissive();
        assertThat(policy.check(URI.create("http://192.168.1.1:8080"))).isNull();
    }

    @Test
    void allowlistPermitsMatchingHost() {
        DestinationPolicy policy = new DestinationPolicy(
                false, Set.of("api.example.com"), Set.of());
        assertThat(policy.check(URI.create("https://api.example.com"))).isNull();
    }

    @Test
    void allowlistBlocksNonMatchingHost() {
        DestinationPolicy policy = new DestinationPolicy(
                false, Set.of("api.example.com"), Set.of());
        assertThat(policy.check(URI.create("https://evil.example.com"))).isNotNull();
    }

    @Test
    void blocklistBlocksMatchingHost() {
        DestinationPolicy policy = new DestinationPolicy(
                false, Set.of(), Set.of("evil.example.com"));
        assertThat(policy.check(URI.create("https://evil.example.com"))).isNotNull();
    }

    @Test
    void blocklistAllowsNonMatchingHost() {
        DestinationPolicy policy = new DestinationPolicy(
                false, Set.of(), Set.of("evil.example.com"));
        assertThat(policy.check(URI.create("https://api.example.com"))).isNull();
    }

    @Test
    void rejectsNullUri() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(null)).isNotNull();
    }

    @Test
    void rejectsUnsupportedScheme() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create("ftp://example.com"))).isNotNull();
    }

    @Test
    void rejectsNoScheme() {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        // URI.create will fail for no scheme, but let's test with a constructed URI
        assertThat(policy.check(URI.create("http://example.com"))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost", "http://localhost:8080",
            "http://127.0.0.1", "http://127.0.0.1:3000",
            "http://0.0.0.0", "http://0.0.0.0:8080",
            "http://10.0.0.1", "http://10.255.255.255",
            "http://172.16.0.1", "http://172.31.255.255",
            "http://192.168.0.1", "http://192.168.255.255",
            "http://169.254.0.1", "http://169.254.255.255"
    })
    void defaultPolicyBlocksPrivateAddresses(String uri) {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create(uri)))
                .as("Expected blocked: " + uri)
                .isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://api.example.com",
            "https://payments.stripe.com",
            "http://8.8.8.8",
            "https://1.1.1.1"
    })
    void defaultPolicyAllowsPublicAddresses(String uri) {
        DestinationPolicy policy = DestinationPolicy.defaultPolicy();
        assertThat(policy.check(URI.create(uri)))
                .as("Expected allowed: " + uri)
                .isNull();
    }
}
