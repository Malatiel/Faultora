package dev.faultora.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.faultora.cli.FaultoraCli;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.DispatchVerifier;
import dev.faultora.runner.DispatchedRun;
import dev.faultora.runner.LocalLimits;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.DispatchedDocument;
import dev.faultora.runner.protocol.EffectivePolicy;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.SignedPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Effective payload policy reaches the actual HTTP transport through both entry points. */
@Timeout(20)
class HttpPayloadPolicyE2ETest {
    @TempDir
    Path directory;
    private HttpServer server;
    private ExecutorService handlers;
    private String baseUrl;
    private final List<byte[]> bodies = new CopyOnWriteArrayList<>();

    private static final String OPENAPI = """
            openapi: 3.0.3
            info:
              title: Synthetic Payload API
              version: 1.0.0
            paths:
              /payload:
                post:
                  operationId: send-payload
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: string
                  responses:
                    '204':
                      description: accepted
            """;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/payload", exchange -> {
            try (exchange) {
                bodies.add(exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        handlers.shutdownNow();
        assertThat(handlers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void cliRefusesARequestAboveItsOneMiBPolicyBeforeTheServerReceivesAnything() throws Exception {
        int exit = cli("x".repeat(1_048_577));
        assertThat(exit).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
        assertThat(bodies).isEmpty();
        assertThat(Files.readString(directory.resolve("results/events.ndjson"))).contains("REQUEST_TOO_LARGE");
    }

    @Test
    void cliAcceptsExactlyOneMiBOfUtf8Bytes() throws Exception {
        String body = "é".repeat(524_288);
        assertThat(cli(body)).isEqualTo(FaultoraCli.EXIT_PASS);
        assertThat(bodies).hasSize(1);
        assertThat(bodies.getFirst()).containsExactly(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void runnerRefusesAnEncodedBodyAboveTheDispatchedPolicy() throws Exception {
        DispatchedRun.Outcome outcome = runner("é😺", 5);
        assertThat(outcome.didRun()).as(() -> String.valueOf(outcome.refusal())).isTrue();
        assertThat(outcome.result().status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(bodies).isEmpty();
        assertThat(Files.readString(outcome.journalPath())).contains("REQUEST_TOO_LARGE");
    }

    @Test
    void runnerAcceptsExactlyTheDispatchedUtf8BodyLimit() throws Exception {
        DispatchedRun.Outcome outcome = runner("é😺", 6);
        assertThat(outcome.didRun()).as(() -> String.valueOf(outcome.refusal())).isTrue();
        assertThat(outcome.result().status()).isEqualTo(RunResult.Status.PASSED);
        assertThat(bodies).hasSize(1);
        assertThat(bodies.getFirst()).containsExactly("é😺".getBytes(StandardCharsets.UTF_8));
    }

    private int cli(String body) throws Exception {
        Path scenario = directory.resolve("scenario.yaml");
        Path openapi = directory.resolve("openapi.yaml");
        Files.writeString(scenario, scenario(body));
        Files.writeString(openapi, OPENAPI);
        StringWriter output = new StringWriter();
        return new FaultoraCli(new PrintWriter(output, true), new PrintWriter(output, true))
                .run(new String[]{"test", "--scenario", scenario.toString(), "--openapi", openapi.toString(),
                        "--target", baseUrl, "--allow-private", "--seed", "42", "--format", "console,json",
                        "--output", directory.resolve("results").toString()});
    }

    private DispatchedRun.Outcome runner(String body, long maxBytes) throws Exception {
        String scenario = scenario(body);
        List<DispatchedDocument> documents = List.of(new DispatchedDocument("openapi", OPENAPI));
        TargetPolicy policy = new TargetPolicy(Set.of(), Set.of(SafetyClassification.MUTATING),
                10, 1, 5000, maxBytes, Set.of(), Set.of());
        SignedPolicy signed = new SignedPolicy(new ObjectMapper().writeValueAsString(EffectivePolicy.of(policy)),
                "synthetic-trusted-key", "c2ln");
        long now = System.currentTimeMillis();
        Dispatch dispatch = new Dispatch("synthetic-payload", now, "synthetic-nonce", scenario, documents,
                Map.of(), Map.of("", baseUrl), Dispatch.Credentials.none(), 42, signed,
                new Lease(now, 10_000, 5000), ContentDigest.sha256Uri(scenario), Dispatch.digestOfDocuments(documents));
        LocalLimits limits = new LocalLimits(Set.of(), Set.of(SafetyClassification.MUTATING), Set.of(), Set.of(),
                1, 5000, 10, 1_048_576, null);
        DispatchedRun runner = new DispatchedRun(new DispatchVerifier(limits,
                candidate -> "synthetic-trusted-key".equals(candidate.keyId())),
                directory.resolve("runner"), key -> null, true);
        return runner.execute(dispatch, Map.of(), new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of()));
    }

    private static String scenario(String body) {
        return """
                apiVersion: faultora.dev/v1
                kind: Scenario
                metadata:
                  name: synthetic-payload
                execute:
                  - id: send
                    type: operation
                    operationId: send-payload
                    inputs:
                      body: '%s'
                """.formatted(body);
    }
}
