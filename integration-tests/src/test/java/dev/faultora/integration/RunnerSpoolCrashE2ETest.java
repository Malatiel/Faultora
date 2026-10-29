package dev.faultora.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.DispatchedRun;
import dev.faultora.runner.DispatchVerifier;
import dev.faultora.runner.DurableRunSpool;
import dev.faultora.runner.LeaseWatch;
import dev.faultora.runner.LocalLimits;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.DispatchedDocument;
import dev.faultora.runner.protocol.EffectivePolicy;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.Refusal;
import dev.faultora.runner.protocol.SignedPolicy;
import dev.faultora.runtime.RunArtifacts;
import dev.faultora.model.identifier.RunId;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerSpoolCrashE2ETest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExtensionPolicy EXTENSIONS = new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of());
    private static final LocalLimits LIMITS = new LocalLimits(Set.of(), Set.of(SafetyClassification.READ_ONLY),
            Set.of(), Set.of(), 2, 600_000, 20, 1_048_576, null);
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aKilledRunnerRetainsEvidenceWithoutRepeatingTheTargetOperation(boolean terminal) throws Exception {
        var calls = new AtomicInteger();
        var called = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/item", exchange -> {
            calls.incrementAndGet(); called.countDown();
            try {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally { exchange.close(); }
        });
        server.start();
        try {
            String scenario = """
                    apiVersion: faultora.dev/v1alpha1
                    kind: Scenario
                    metadata: {name: spool-crash}
                    execute:
                      - id: read
                        type: operation
                        operationId: read-item
                    """ + (terminal ? "" : """
                      - id: pause
                        type: wait
                        dependsOn: [read]
                        timeout: 60s
                    """);
            String api = """
                    openapi: 3.0.3
                    info: {title: Synthetic, version: '1'}
                    paths:
                      /item:
                        get:
                          operationId: read-item
                          responses:
                            '200': {description: An item}
                    """;
            var documents = List.of(new DispatchedDocument("openapi", api));
            var policy = new TargetPolicy(Set.of(new TargetId("default")), Set.of(SafetyClassification.READ_ONLY),
                    20, 2, 300_000, 1024, Set.of(), Set.of());
            var dispatch = new Dispatch("run-crash-probe", System.currentTimeMillis(), "synthetic-nonce",
                    scenario, documents, Map.of(), Map.of("", "http://127.0.0.1:" + server.getAddress().getPort()),
                    Dispatch.Credentials.none(), 42, new SignedPolicy(JSON.writeValueAsString(EffectivePolicy.of(policy)),
                    "fixture", "c2ln"), new Lease(System.currentTimeMillis(), 120_000, 30_000),
                    ContentDigest.sha256Uri(scenario), Dispatch.digestOfDocuments(documents));
            Path request = directory.resolve("dispatch.json"); JSON.writeValue(request.toFile(), dispatch);
            Path work = directory.resolve("work");
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), CrashRunner.class.getName(), request.toString(), work.toString());
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            builder.environment().remove("JDK_JAVA_OPTIONS");
            builder.environment().remove("_JAVA_OPTIONS");
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Path errors = directory.resolve("child.log"); builder.redirectError(errors.toFile());
            Process child = builder.start();
            try {
                assertThat(called.await(20, TimeUnit.SECONDS)).as(() -> read(errors)).isTrue();
                if (terminal) {
                    assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue();
                    assertThat(child.exitValue()).as(() -> read(errors)).isEqualTo(23);
                } else child.destroyForcibly();
            } finally {
                if (child.isAlive()) child.destroyForcibly();
                assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
            Path spool = RunArtifacts.dispatchedDirectory(work, new RunId(dispatch.runId())).resolve("spool.bin");
            var recovered = DurableRunSpool.recover(spool);
            assertThat(recovered.events()).isPositive();
            assertThat(recovered.recoveryRequired()).isEqualTo(!terminal);
            if (terminal) assertThat(recovered.terminal().status().name()).isEqualTo("PASSED");
            var refused = runner(work).execute(dispatch, Map.of(), EXTENSIONS);
            assertThat(refused.refusal().reason()).isEqualTo(Refusal.Reason.REPLAYED_DISPATCH);
            assertThat(calls).hasValue(1);
        } finally { server.stop(0); }
    }

    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (Exception unavailable) { return "Child diagnostics unavailable"; }
    }

    private static DispatchedRun runner(Path work) {
        return new DispatchedRun(new DispatchVerifier(LIMITS, policy -> true), work, handle -> null, true);
    }

    public static final class CrashRunner {
        public static void main(String[] args) throws Exception {
            var dispatch = JSON.readValue(Path.of(args[0]).toFile(), Dispatch.class);
            runner(Path.of(args[1])).execute(dispatch, Map.of(), EXTENSIONS, new DispatchedRun.WhileRunning() {
                @Override public void started(LeaseWatch lease, Path journal) { }
                @Override public void terminalCommitted() { Runtime.getRuntime().halt(23); }
            });
            System.exit(24);
        }
    }
}
