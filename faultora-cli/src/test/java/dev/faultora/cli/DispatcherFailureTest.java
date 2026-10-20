package dev.faultora.cli;

import com.fasterxml.jackson.core.JsonParseException;
import dev.faultora.runner.TlsMaterial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an operator reads when a runner cannot reach its dispatcher.
 * <p>
 * The JDK raises most of these with no message, and the runner printed
 * "Could not register: null". Each kind now has words, and none of them repeat
 * what the exception carried.
 */
class DispatcherFailureTest {
    private static final String MARKER = "SYNTHETIC_PRIVATE_FAILURE_VALUE";

    static Stream<Arguments> failuresWithoutAMessage() {
        return Stream.of(
                Arguments.of(new ConnectException(), "connection was refused"),
                Arguments.of(new UnknownHostException(), "does not resolve"),
                Arguments.of(new HttpConnectTimeoutException(null), "connection timed out"),
                Arguments.of(new HttpTimeoutException(null), "did not answer in time"),
                Arguments.of(new SocketTimeoutException(), "did not answer in time"),
                Arguments.of(new SSLHandshakeException(null), "TLS handshake failed"),
                Arguments.of(new JsonParseException(null, (String) null), "not a runner protocol response"));
    }

    @ParameterizedTest
    @MethodSource("failuresWithoutAMessage")
    void aFailureWithoutAMessageIsStillNamed(Exception failure, String expected) {
        assertThat(DispatcherFailure.describe(failure)).contains(expected).doesNotContain("null");
    }

    @Test
    void theCauseIsFoundUnderWhateverWrappedIt() {
        // HttpClient wraps the socket's refusal in its own ConnectException,
        // and a caller may wrap that again.
        var wrapped = new IOException(MARKER, new IllegalStateException(new ConnectException()));

        assertThat(DispatcherFailure.describe(wrapped)).contains("connection was refused").doesNotContain(MARKER);
    }

    @Test
    void anUnrecognisedFailureIsNamedByItsKindAndNotByWhatItSaid() {
        assertThat(DispatcherFailure.describe(new IOException(MARKER)))
                .isEqualTo("the connection failed (IOException)");
        assertThat(DispatcherFailure.describe(new IllegalStateException(MARKER)))
                .isEqualTo("an unexpected error (IllegalStateException)");
    }

    @Test
    void unusableTlsMaterialKeepsTheExplanationWrittenForTheOperator() {
        var unusable = new TlsMaterial.TlsUnavailable("No readable TLS material at runner.p12", null);

        assertThat(DispatcherFailure.describe(new IOException(unusable)))
                .isEqualTo("No readable TLS material at runner.p12");
    }
}
