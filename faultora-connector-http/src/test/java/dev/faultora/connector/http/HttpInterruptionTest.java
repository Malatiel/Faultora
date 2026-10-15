package dev.faultora.connector.http;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.result.OperationResult;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.RequestFailedException;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.BasicHttpEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class HttpInterruptionTest {
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    static Stream<IOException> interruptedFailures() {
        return Stream.of(new InterruptedIOException("SYNTHETIC_PRIVATE_FAILURE"),
                new RequestFailedException("SYNTHETIC_PRIVATE_FAILURE"));
    }

    @ParameterizedTest
    @MethodSource("interruptedFailures")
    void cancellationRestoresTheFlagClearedByTheTransport(IOException failure) {
        OperationResult result = execute(failure, true, true, 60_000);

        assertThat(result.error().category()).isEqualTo(NormalizedError.ErrorCategory.CANCELLED);
        assertThat(result.error().code()).isEqualTo("CANCELLED");
        assertThat(result.error().retryable()).isFalse();
        assertThat(result.error().message()).isEqualTo("HTTP operation cancelled");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void anInterruptedSocketFailureIsCancellation() {
        OperationResult result = execute(new SocketException("synthetic"), true, false, 60_000);

        assertThat(result.error().category()).isEqualTo(NormalizedError.ErrorCategory.CANCELLED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    static Stream<IOException> timeouts() {
        return Stream.of(new SocketTimeoutException("synthetic"),
                new ConnectionRequestTimeoutException("synthetic"));
    }

    @ParameterizedTest
    @MethodSource("timeouts")
    void aTransportTimeoutDoesNotBecomeCancellation(IOException failure) {
        OperationResult result = execute(failure, false, false, 60_000);

        assertThat(result.error().category()).isEqualTo(NormalizedError.ErrorCategory.TIMEOUT);
        assertThat(result.error().code()).isEqualTo("REQUEST_TIMEOUT");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void anExplicitInterruptTakesPrecedenceOverATransportTimeout() {
        OperationResult result = execute(new SocketTimeoutException("synthetic"), true, false, 60_000);

        assertThat(result.error().category()).isEqualTo(NormalizedError.ErrorCategory.CANCELLED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void anAlreadyInterruptedCallerDoesNotSendARequest() {
        Thread.currentThread().interrupt();
        OperationResult result = execute(new IOException("must not execute"), false, false, 60_000);

        assertThat(result.error().category()).isEqualTo(NormalizedError.ErrorCategory.CANCELLED);
        assertThat(calls.get()).isZero();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void anExhaustedTotalBudgetDoesNotInterruptTheCaller() {
        OperationResult result = execute(new RequestFailedException("must not execute"), false, false, 0);

        assertThat(result.error().category()).isEqualTo(NormalizedError.ErrorCategory.TIMEOUT);
        assertThat(result.error().code()).isEqualTo("TOTAL_TIMEOUT");
        assertThat(calls.get()).isZero();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void connectionRefusalRetainsItsNetworkClassification() {
        OperationResult result = execute(new ConnectException("synthetic"), false, false, 60_000);

        assertThat(result.error().category()).isEqualTo(NormalizedError.ErrorCategory.NETWORK);
        assertThat(result.error().code()).isEqualTo("CONNECTION_REFUSED");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void closingAnInterruptedBodyCannotClearTheRestoredFlag() {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream body = new InputStream() {
            @Override
            public int read() throws IOException {
                Thread.currentThread().interrupt();
                Thread.interrupted();
                throw new InterruptedIOException("synthetic body cancellation");
            }

            @Override
            public void close() {
                closed.set(true);
                Thread.interrupted();
            }
        };
        CloseableHttpClient client = new StubClient() {
            @Override
            protected CloseableHttpResponse doExecute(HttpHost host, ClassicHttpRequest request, HttpContext context) {
                BasicClassicHttpResponse response = new BasicClassicHttpResponse(200);
                response.setEntity(new BasicHttpEntity(body, ContentType.APPLICATION_OCTET_STREAM));
                return CloseableHttpResponse.adapt(response);
            }
        };

        OperationResult result = execute(client, 60_000);

        assertThat(result.error().category()).isEqualTo(NormalizedError.ErrorCategory.CANCELLED);
        assertThat(result.body()).isNull();
        assertThat(closed.get()).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private OperationResult execute(IOException failure, boolean interrupt, boolean clear, long timeout) {
        CloseableHttpClient client = new StubClient() {
            @Override
            protected CloseableHttpResponse doExecute(HttpHost host, ClassicHttpRequest request,
                                                       HttpContext context) throws IOException {
                calls.incrementAndGet();
                if (interrupt) {
                    Thread.currentThread().interrupt();
                }
                if (clear) {
                    assertThat(Thread.interrupted()).isTrue();
                }
                throw failure;
            }
        };
        return execute(client, timeout);
    }

    private OperationResult execute(CloseableHttpClient client, long timeout) {
        try (HttpConnector connector = new HttpConnector(DestinationPolicy.permissive(), client)) {
            TargetDefinition target = new TargetDefinition(new TargetId("test"), "Test", "http://127.0.0.1:1",
                    List.of(new ProtocolId("http")), List.of(), Map.of());
            ConnectorContext context = new ConnectorContext(EvidencePolicy.MINIMAL, handle -> null,
                    5000, 30_000, timeout, Map.of());
            OperationDefinition operation = new OperationDefinition(new OperationId("read"), new ProtocolId("http"),
                    target.id(), SafetyClassification.READ_ONLY, Map.of(), null, Map.of(),
                    Map.of("method", "GET", "path", "/"));
            return connector.execute(connector.prepare(target, context), operation, Map.of(), context);
        }
    }

    private abstract static class StubClient extends CloseableHttpClient {
        @Override
        public void close() {
        }

        @Override
        public void close(CloseMode mode) {
        }
    }
}
