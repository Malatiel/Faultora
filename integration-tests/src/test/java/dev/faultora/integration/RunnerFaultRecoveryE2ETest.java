package dev.faultora.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runner.DispatchVerifier;
import dev.faultora.runner.DispatchedRun;
import dev.faultora.runner.DurableRunSpool;
import dev.faultora.runner.LocalLimits;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.DispatchedDocument;
import dev.faultora.runner.protocol.EffectivePolicy;
import dev.faultora.runner.protocol.Lease;
import dev.faultora.runner.protocol.Refusal;
import dev.faultora.runner.protocol.SignedPolicy;
import dev.faultora.runtime.RunArtifacts;
import dev.faultora.spi.context.FaultContext;
import dev.faultora.spi.contract.ExpiringFaultProvider;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.result.ActiveFault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunnerFaultRecoveryE2ETest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RunId RUN = new RunId("run-fault-recovery-probe");
    private static final String FAULT_TYPE = "synthetic-outage";
    private static final ExtensionPolicy EXTENSIONS = new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of());
    private static final LocalLimits LIMITS = new LocalLimits(Set.of(), Set.of(SafetyClassification.MUTATING),
            Set.of(), Set.of(FAULT_TYPE), 1, 300_000, 10, 1024, null);

    @TempDir Path directory;

    @Test
    void restartConfirmsExternalExpiryAfterLosingTheActivationResponse() throws Exception {
        Path work = directory.resolve("work");
        Path run = RunArtifacts.dispatchedDirectory(work, RUN);
        try (var dependency = new ExternalDependency(run.resolve("fault-obligations"));
             var client = HttpClient.newHttpClient()) {
            URI endpoint = dependency.endpoint();
            Dispatch dispatch = dispatch(endpoint);
            Path request = directory.resolve("dispatch.json");
            JSON.writeValue(request.toFile(), dispatch);
            Path spool = run.resolve("spool.bin");
            Path crashLog = directory.resolve("crash.log");
            Process child = child(work, endpoint, "execute", request, crashLog);
            try {
                assertThat(dependency.applied.await(20, TimeUnit.SECONDS))
                        .as(() -> diagnostics(crashLog)).isTrue();
                assertThat(dependency.activation.activationId()).isNotBlank();
                assertThat(dependency.intentPresentAtActivation).isTrue();
                assertThat(dependency.businessCalls).hasValue(1);
                assertThat(child.isAlive()).isTrue();
            } finally {
                try {
                    stop(child);
                } finally {
                    // The activation response remains blocked until the original JVM is dead.
                    dependency.allowResponse.countDown();
                }
            }

            var before = DurableRunSpool.recover(spool);
            assertThat(before.recoveryRequired()).isTrue();
            assertThat(status(client, endpoint.resolve("/dependency"))).isEqualTo(503);
            var pending = inspectInNewProcess(work, endpoint, "pending");
            assertThat(pending).isEqualTo(new ObservedRecovery(dependency.activation.activationId(),
                    dependency.activation.hardExpiryMs(), false));

            // Only the external dependency's clock advances. The runner must ask the provider
            // for evidence instead of deciding that expiry happened from its own wall clock.
            dependency.nowMs = dependency.activation.hardExpiryMs();
            assertThat(status(client, endpoint.resolve("/dependency"))).isEqualTo(200);
            try (var provider = new SyntheticProvider(endpoint)) {
                var mismatched = runner(work).inspectFaultRecovery(RUN, Map.of("other-dependency", provider));
                assertThat(mismatched).containsExactly(
                        new DispatchedRun.FaultRecovery(pending.activationId(), pending.hardExpiryMs(), false));
            }
            var confirmed = inspectInNewProcess(work, endpoint, "confirmed");
            assertThat(confirmed).isEqualTo(new ObservedRecovery(pending.activationId(), pending.hardExpiryMs(), true));

            var recovered = DurableRunSpool.recover(spool);
            assertThat(recovered.identity()).isEqualTo(before.identity());
            assertThat(recovered.events()).isEqualTo(before.events());
            assertThat(recovered.recoveryRequired()).isTrue();
            assertThat(recovered.terminal()).isNull();
            try (var provider = new SyntheticProvider(endpoint)) {
                Path foreignWork = directory.resolve("foreign-runner");
                Path foreignRun = RunArtifacts.dispatchedDirectory(foreignWork, RUN);
                Files.createDirectories(foreignRun.resolve("fault-obligations"));
                Files.writeString(foreignWork.resolve("runner-identity"), UUID.randomUUID().toString());
                Files.copy(run.resolve("run-owner"), foreignRun.resolve("run-owner"));
                Files.copy(spool, foreignRun.resolve("spool.bin"));
                Files.copy(run.resolve("fault-obligations/state.bin"), foreignRun.resolve("fault-obligations/state.bin"));
                assertThatThrownBy(() -> runner(foreignWork).inspectFaultRecovery(RUN, providers(provider)))
                        .isInstanceOf(IOException.class).hasMessage("FAULT_OBLIGATION_OWNER_MISMATCH");
                var replay = runner(work).execute(dispatch, providers(provider), EXTENSIONS);
                assertThat(replay.refusal().reason()).isEqualTo(Refusal.Reason.REPLAYED_DISPATCH);
            }
            assertThat(dependency.businessCalls).hasValue(1);
            assertThat(dependency.activations).hasValue(1);
            assertThat(dependency.expiryQueries).hasValue(2);
            assertThat(dependency.rollbacks).hasValue(0);
        }
    }

    private ObservedRecovery inspectInNewProcess(Path work, URI endpoint, String phase) throws Exception {
        Path result = directory.resolve(phase + ".json");
        Path errors = directory.resolve(phase + ".log");
        Process child = child(work, endpoint, "inspect", result, errors);
        try {
            assertThat(child.waitFor(20, TimeUnit.SECONDS)).as(() -> diagnostics(errors)).isTrue();
            assertThat(child.exitValue()).as(() -> diagnostics(errors)).isZero();
            return JSON.readValue(result.toFile(), ObservedRecovery.class);
        } finally {
            stop(child);
        }
    }

    private static Process child(Path work, URI endpoint, String action, Path ioFile, Path errors) throws IOException {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), CrashRunner.class.getName(),
                action, work.toString(), endpoint.toString(), ioFile.toString());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        return builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(errors.toFile()).start();
    }

    private static void stop(Process child) throws InterruptedException {
        if (child.isAlive()) child.destroyForcibly();
        assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
    }

    private static String diagnostics(Path errors) {
        try {
            return Files.readString(errors);
        } catch (IOException unavailable) {
            return "Child diagnostics unavailable";
        }
    }

    private static int status(HttpClient client, URI uri) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static Dispatch dispatch(URI endpoint) throws IOException {
        String scenario = """
                apiVersion: faultora.dev/v1alpha1
                kind: Scenario
                metadata: {name: fault-recovery}
                execute:
                  - id: mutate
                    type: operation
                    operationId: mutate
                faults:
                  - id: impair
                    faultType: synthetic-outage
                    targetScope: dependency
                    duration: 60s
                    dependsOn: [mutate]
                """;
        String api = """
                openapi: 3.0.3
                info: {title: Synthetic, version: '1'}
                paths:
                  /business:
                    post:
                      operationId: mutate
                      responses:
                        '204': {description: Mutation applied}
                """;
        var documents = List.of(new DispatchedDocument("openapi", api));
        var policy = new TargetPolicy(Set.of(), Set.of(SafetyClassification.MUTATING),
                10, 1, 300_000, 1024, Set.of(FAULT_TYPE), Set.of());
        long now = System.currentTimeMillis();
        return new Dispatch(RUN.value(), now, "synthetic-recovery-nonce", scenario, documents,
                Map.of(), Map.of("", endpoint.toString()), Dispatch.Credentials.none(), 42,
                new SignedPolicy(JSON.writeValueAsString(EffectivePolicy.of(policy)), "fixture", "c2ln"),
                new Lease(now, 120_000, 60_000), ContentDigest.sha256Uri(scenario), Dispatch.digestOfDocuments(documents));
    }

    private static DispatchedRun runner(Path work) {
        return new DispatchedRun(new DispatchVerifier(LIMITS, signed -> "fixture".equals(signed.keyId())),
                work, handle -> null, true);
    }

    private static Map<String, FaultProvider> providers(SyntheticProvider provider) {
        return Map.of("synthetic-dependency", provider);
    }

    private record Activation(String activationId, long hardExpiryMs) { }

    private record ObservedRecovery(String activationId, long hardExpiryMs, boolean expiryConfirmed) { }

    public static final class CrashRunner {
        public static void main(String[] args) throws Exception {
            DispatchedRun runner = runner(Path.of(args[1]));
            Path ioFile = Path.of(args[3]);
            try (var provider = new SyntheticProvider(URI.create(args[2]))) {
                if ("execute".equals(args[0])) {
                    var dispatch = JSON.readValue(ioFile.toFile(), Dispatch.class);
                    var outcome = runner.execute(dispatch, providers(provider), EXTENSIONS);
                    throw new AssertionError("Runner returned before being killed: " + outcome);
                }
                var recovery = runner.inspectFaultRecovery(RUN, providers(provider));
                assertThat(recovery).hasSize(1);
                var obligation = recovery.getFirst();
                JSON.writeValue(ioFile.toFile(), new ObservedRecovery(obligation.activationId(),
                        obligation.hardExpiryMs(), obligation.expiryConfirmed()));
            }
        }
    }

    private static final class SyntheticProvider implements ExpiringFaultProvider, AutoCloseable {
        private final URI endpoint;
        private final HttpClient client = HttpClient.newHttpClient();

        SyntheticProvider(URI endpoint) {
            this.endpoint = endpoint;
        }

        @Override public Set<String> capabilities() { return Set.of(FAULT_TYPE); }

        @Override
        public ActiveFault inject(String faultType, Map<String, Object> params, FaultContext context) {
            var activationId = (String) context.config().get(FaultContext.ACTIVATION_ID);
            var activation = new Activation(activationId, context.hardExpiryMs());
            if (post("/activate", activation) != 204) throw new IllegalStateException("Fixture activation failed");
            return new ActiveFault(activationId, faultType, context.targetScope(),
                    Math.min(System.currentTimeMillis(), context.hardExpiryMs() - 1),
                    context.hardExpiryMs(), "Restore synthetic dependency");
        }

        @Override
        public boolean confirmsExpiry(String activationId, long hardExpiryMs) {
            return switch (post("/expiry", new Activation(activationId, hardExpiryMs))) {
                case 204 -> true;
                case 202 -> false;
                default -> throw new IllegalStateException("Fixture did not recognize the activation");
            };
        }

        @Override
        public void rollback(ActiveFault fault, FaultContext context) {
            if (post("/rollback", new Activation(fault.handle(), fault.hardExpiryMs())) != 204) {
                throw new IllegalStateException("Fixture rollback failed");
            }
        }

        private int post(String path, Activation activation) {
            try {
                var request = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(activation))).build();
                return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Fixture request interrupted", interrupted);
            } catch (IOException failure) {
                throw new IllegalStateException("Fixture request failed", failure);
            }
        }

        @Override public void close() { client.close(); }
    }

    private static final class ExternalDependency implements AutoCloseable {
        private final HttpServer server;
        private final Path obligations;
        final CountDownLatch applied = new CountDownLatch(1);
        final CountDownLatch allowResponse = new CountDownLatch(1);
        final AtomicInteger businessCalls = new AtomicInteger();
        final AtomicInteger activations = new AtomicInteger();
        final AtomicInteger expiryQueries = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();
        volatile Activation activation;
        volatile long nowMs;
        boolean intentPresentAtActivation;

        ExternalDependency(Path obligations) throws IOException {
            this.obligations = obligations;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/business", exchange -> {
                businessCalls.incrementAndGet();
                respond(exchange, 204);
            });
            server.createContext("/activate", this::activate);
            server.createContext("/dependency", exchange -> respond(exchange, expired() ? 200 : 503));
            server.createContext("/expiry", exchange -> {
                expiryQueries.incrementAndGet();
                var requested = JSON.readValue(exchange.getRequestBody(), Activation.class);
                respond(exchange, !requested.equals(activation) ? 404 : expired() ? 204 : 202);
            });
            server.createContext("/rollback", exchange -> {
                rollbacks.incrementAndGet();
                respond(exchange, 204);
            });
            server.start();
        }

        URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }

        private void activate(HttpExchange exchange) throws IOException {
            activations.incrementAndGet();
            if (Files.isDirectory(obligations)) {
                try (var entries = Files.list(obligations)) {
                    intentPresentAtActivation = entries.anyMatch(Files::isRegularFile);
                }
            }
            activation = JSON.readValue(exchange.getRequestBody(), Activation.class);
            nowMs = activation.hardExpiryMs() - 1;
            applied.countDown();
            try {
                respond(exchange, allowResponse.await(25, TimeUnit.SECONDS) ? 204 : 504);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        }

        private boolean expired() {
            return activation != null && nowMs >= activation.hardExpiryMs();
        }

        private static void respond(HttpExchange exchange, int status) throws IOException {
            try (exchange) {
                exchange.sendResponseHeaders(status, -1);
            }
        }

        @Override public void close() { server.stop(0); }
    }
}
