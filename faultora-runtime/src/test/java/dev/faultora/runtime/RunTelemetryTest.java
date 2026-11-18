package dev.faultora.runtime;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RunTelemetryTest {
    @ParameterizedTest
    @ValueSource(strings = {"https://collector.example:4318", "http://127.0.0.1:4318", "http://[::1]:4318/"})
    void acceptsAnHttpsOrLiteralLoopbackOrigin(String configured) {
        assertThat(RunTelemetry.collector(configured)).isEqualTo(URI.create(configured));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:4318", "http://192.0.2.10", "ftp://127.0.0.1",
            "https://example.test/path", "https://example.test?credential=synthetic",
            "https://example.test#fragment", "https://fixture@example.test", "https://example.test:0",
            "https://example.test:65536", "http://127.0.0.1:", "example.test", "data:text/plain,example"})
    void rejectsUnexpectedSchemesAndOriginsWithMoreThanAnAuthority(String configured) {
        assertThatIllegalArgumentException().isThrownBy(() -> RunTelemetry.collector(configured));
    }
}
