package dev.faultora.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.faultora.model.identifier.RunId;
import dev.faultora.runtime.CatalogAssembly;
import dev.faultora.runtime.RunArtifacts;
import dev.faultora.runtime.RunPreparation;
import dev.faultora.runtime.PreparedRun;
import dev.faultora.runtime.RunEvidence;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spi.context.ConnectorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpExecutionTest {
    @TempDir Path directory;
    private static final String WAIT = """
            apiVersion: faultora.dev/v1
            kind: Scenario
            metadata: {name: synthetic}
            faults:
              - id: latency
                faultType: http-latency
                duration: 60s
                params: {delayMs: 1}
            execute:
              - id: pause
                type: wait
                timeout: 60s
            cleanup:
              - id: cleanup
                type: operation
                operationId: read
            """;

    private final class Session implements AutoCloseable {
        final KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final AtomicInteger requests = new AtomicInteger();
        final CountDownLatch responseEntered = new CountDownLatch(1);
        final CountDownLatch responseRelease = new CountDownLatch(1);
        volatile boolean blockResponse;
        final HttpServer target;
        final McpConfiguration config;
        final McpTools tools;

        Session() throws Exception {
            this(true);
        }

        Session(boolean allowPrivate) throws Exception {
            target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            target.createContext("/", exchange -> {
                requests.incrementAndGet();
                if (blockResponse) {
                    responseEntered.countDown();
                    try {
                        if (!responseRelease.await(15, TimeUnit.SECONDS)) {
                            exchange.close();
                            return;
                        }
                    } catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                        exchange.close();
                        return;
                    }
                }
                byte[] body = ("{\"value\":\"" + McpFixtures.MARKER + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Authorization", McpFixtures.MARKER);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            target.start();
            String source = McpFixtures.CATALOG.replace("http://api.example.com/SYNTHETIC_PRIVATE_VALUE",
                    "http://127.0.0.1:" + target.getAddress().getPort());
            config = new McpConfiguration(Map.of("demo", new CatalogAssembly.Document("openapi", source)),
                    McpFixtures.policy(), new McpConfiguration.Execution(directory, keys.getPublic(), allowPrivate));
            tools = new McpTools(config);
        }

        JsonNode prepare(String scenario) throws Exception {
            var arguments = McpJson.MAPPER.createObjectNode().put("scenario", scenario).put("seed", 42);
            arguments.putArray("catalogIds").add("demo");
            JsonNode result = tools.call("prepare_run", arguments);
            assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
            return result.path("structuredContent");
        }

        String approval(JsonNode prepared) throws Exception {
            return McpApproval.sign(keys.getPrivate(), prepared.path("preparedId").asText(),
                    prepared.path("preparationDigest").asText(), prepared.path("runId").asText(), Instant.now().plusSeconds(60));
        }

        JsonNode start(JsonNode prepared) throws Exception {
            return tools.call("start_run", McpJson.MAPPER.createObjectNode()
                    .put("preparedId", prepared.path("preparedId").asText()).put("approval", approval(prepared)));
        }

        Path artifacts(JsonNode prepared) {
            return RunArtifacts.dispatchedDirectory(directory, new RunId(prepared.path("runId").asText()));
        }

        JsonNode status(JsonNode prepared, boolean cancel) throws Exception {
            return tools.call(cancel ? "cancel_run" : "get_run", McpJson.MAPPER.createObjectNode()
                    .put("runId", prepared.path("runId").asText())).path("structuredContent");
        }

        JsonNode finished(JsonNode prepared) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            JsonNode status;
            do {
                status = status(prepared, false);
                if (status.path("terminal").asBoolean()) {
                    return status;
                }
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("Run did not complete: " + status);
        }

        void started(JsonNode prepared) throws Exception {
            Path journal = artifacts(prepared).resolve("events.ndjson");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                if (Files.exists(journal) && Files.readString(journal).contains("\"nodeId\":\"pause\"")) {
                    return;
                }
                Thread.sleep(10);
            }
            throw new AssertionError("Run did not reach its wait");
        }

        JsonNode artifact(JsonNode prepared, String id) throws Exception {
            return tools.call("get_artifact", McpJson.MAPPER.createObjectNode()
                    .put("runId", prepared.path("runId").asText()).put("artifactId", id));
        }

        @Override
        public void close() {
            responseRelease.countDown();
            tools.close();
            target.stop(0);
        }
    }

    @Test
    void signedApprovalExecutesExactlyOnceAndExportsOnlyTheStructuralReport() throws Exception {
        try (var session = new Session()) {
            JsonNode prepared = session.prepare(McpFixtures.SCENARIO + """
                    assertions:
                      - id: check
                        assertionType: jsonpath
                        targetStep: read
                        params: {path: value, equals: SYNTHETIC_PRIVATE_VALUE}
                    """);
            assertThat(session.requests).hasValue(0);
            assertThat(session.start(prepared).path("isError").asBoolean()).isFalse();
            JsonNode terminal = session.finished(prepared);
            assertThat(terminal.path("status").asText()).isEqualTo("PASSED");
            assertThat(terminal.path("assertionOutcomes").path("PASS").asInt()).isEqualTo(1);
            assertThat(terminal.path("cleanupConfirmed").asBoolean()).isTrue();
            assertThat(session.requests).hasValue(1);
            var report = session.artifact(prepared, "report");
            assertThat(report.path("isError").asBoolean()).isFalse();
            assertThat(McpJson.MAPPER.readTree(report.path("structuredContent").path("text").asText())).isEqualTo(terminal);
            assertThat(report.toString()).doesNotContain(McpFixtures.MARKER, directory.toString(), "127.0.0.1", "Authorization");
            assertThat(session.start(prepared).path("structuredContent").path("code").asText())
                    .isEqualTo("UNKNOWN_PREPARATION");
            assertThat(session.requests).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"forged", "other-key", "expired", "future", "other-digest", "other-run", "other-preparation"})
    void invalidApprovalNeverTouchesTargetsOrAllocatesArtifacts(String mode) throws Exception {
        try (var session = new Session()) {
            JsonNode prepared = session.prepare(McpFixtures.SCENARIO);
            String id = prepared.path("preparedId").asText();
            String digest = prepared.path("preparationDigest").asText();
            String run = prepared.path("runId").asText();
            var key = mode.equals("other-key") ? KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate()
                    : session.keys.getPrivate();
            Instant expiry = Instant.now().plusSeconds(mode.equals("expired") ? -1 : mode.equals("future") ? 600 : 60);
            String token = McpApproval.sign(key, mode.equals("other-preparation") ? java.util.UUID.randomUUID().toString() : id,
                    mode.equals("other-digest") ? "sha256:" + "0".repeat(64) : digest,
                    mode.equals("other-run") ? "run-" + java.util.UUID.randomUUID() : run, expiry);
            if (mode.equals("forged")) {
                token = token.substring(0, token.indexOf('.') + 1) + "AAAA";
            }
            var refused = session.tools.call("start_run", McpJson.MAPPER.createObjectNode()
                    .put("preparedId", id).put("approval", token));
            assertThat(refused.path("structuredContent").path("code").asText()).isEqualTo("APPROVAL_REFUSED");
            assertThat(session.requests).hasValue(0);
            assertThat(session.artifacts(prepared)).doesNotExist();
        }
    }

    @Test
    void confirmationScopeWideningAndSecretFieldsNeverGrantAuthority() throws Exception {
        try (var session = new Session()) {
            JsonNode prepared = session.prepare(McpFixtures.SCENARIO);
            for (String field : List.of("confirmed", "target", "allowPrivate", "secret", "output", "policy")) {
                var args = McpJson.MAPPER.createObjectNode().put("preparedId", prepared.path("preparedId").asText())
                        .put("approval", session.approval(prepared)).put(field, true);
                assertThat(session.tools.call("start_run", args).path("structuredContent").path("code").asText())
                        .isEqualTo("INVALID_ARGUMENT");
            }
            assertThat(session.requests).hasValue(0);
            assertThat(session.artifacts(prepared)).doesNotExist();
        }
    }

    @Test
    void cancellationPublishesTerminalOnlyAfterCleanupAndFaultRollback() throws Exception {
        try (var session = new Session()) {
            JsonNode prepared = session.prepare(WAIT);
            session.start(prepared);
            session.started(prepared);
            session.status(prepared, true);
            session.status(prepared, true);
            var terminal = session.finished(prepared);
            assertThat(terminal.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(terminal.path("cleanupConfirmed").asBoolean()).isTrue();
            assertThat(session.requests).hasValue(1);
            String journal = Files.readString(session.artifacts(prepared).resolve("events.ndjson"));
            assertThat(journal).contains("CLEANUP_COMPLETED", "FAULT_ROLLED_BACK");
            assertThat(session.artifact(prepared, "report").path("isError").asBoolean()).isFalse();
        }
    }

    @Test
    void closingSessionCancelsAndJoinsTheRunAndPreventsFurtherAccess() throws Exception {
        try (var session = new Session()) {
            JsonNode prepared = session.prepare(WAIT);
            session.start(prepared);
            session.started(prepared);
            session.tools.close();
            assertThat(session.requests).hasValue(1);
            String journal = Files.readString(session.artifacts(prepared).resolve("events.ndjson"));
            assertThat(journal).contains("CLEANUP_COMPLETED", "FAULT_ROLLED_BACK", "CANCELLED");
            assertThat(session.status(prepared, false).path("code").asText()).isEqualTo("SESSION_CLOSED");
        }
    }

    @Test
    void cancellationBeforeWorkerStartsStillFinalizesCleanupInsteadOfInterruptingArtifactIo() throws Exception {
        try (var session = new Session(); var runs = new McpRuns(session.config.execution())) {
            var request = new RunPreparation.Request(WAIT,
                    List.of(session.config.catalogs().get("demo")), "",
                    new ExtensionPolicy(Set.of(), false, 0,
                            Set.of(), Set.of()),
                    session.config.targets(), RunArtifacts.newRunId(), 42, null);
            var settings = new PreparedRun.Settings(
                    ExpressionContext.builder().build(),
                    new ConnectorContext(RunEvidence.defaultPolicy(),
                            ignored -> null, 5000, 30_000, 60_000, Map.of()), true,
                    session.config.targets().allowedFaultTypes());
            var prepared = PreparedRun.prepare(request, settings, Duration.ofMinutes(1));
            String id = request.runId().value();
            // The worker first acquires this monitor, so cancellation precedes every filesystem operation.
            synchronized (runs) {
                runs.start(prepared, request, settings);
                runs.status(id, true);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            JsonNode terminal;
            do {
                terminal = runs.status(id, false);
                if (terminal.path("terminal").asBoolean()) {
                    break;
                }
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            assertThat(terminal.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(terminal.path("cleanupConfirmed").asBoolean()).isTrue();
            assertThat(session.requests).hasValue(1);
        }
    }

    @Test
    void cancellationDuringCleanupDoesNotInterruptItsInFlightRequest() throws Exception {
        try (var session = new Session()) {
            session.blockResponse = true;
            JsonNode prepared = session.prepare(WAIT.replace("timeout: 60s", "timeout: 1ms"));
            session.start(prepared);
            try {
                assertThat(session.responseEntered.await(15, TimeUnit.SECONDS)).as("cleanup request entered").isTrue();
                session.status(prepared, true);
                long observation = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
                do {
                    assertThat(session.status(prepared, false).path("terminal").asBoolean())
                            .as("cleanup response has not been released").isFalse();
                    Thread.sleep(10);
                } while (System.nanoTime() < observation);
            } finally {
                session.responseRelease.countDown();
            }
            JsonNode terminal = session.finished(prepared);
            assertThat(terminal.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(terminal.path("cleanupConfirmed").asBoolean()).isTrue();
            assertThat(session.requests).hasValue(1);
            assertThat(Files.readString(session.artifacts(prepared).resolve("events.ndjson")))
                    .contains("FAULT_ROLLED_BACK", "CLEANUP_COMPLETED");
        }
    }

    @Test
    void anotherSessionCannotReadCancelOrExportAnOwnedRun() throws Exception {
        try (var session = new Session(); var stranger = new McpTools(session.config)) {
            JsonNode prepared = session.prepare(McpFixtures.SCENARIO);
            session.start(prepared);
            session.finished(prepared);
            for (String tool : List.of("get_run", "cancel_run", "get_artifact")) {
                var args = McpJson.MAPPER.createObjectNode().put("runId", prepared.path("runId").asText());
                if (tool.equals("get_artifact")) {
                    args.put("artifactId", "report");
                }
                assertThat(stranger.call(tool, args).path("structuredContent").path("code").asText()).isEqualTo("UNKNOWN_RUN");
            }
        }
    }

    @Test
    void admissionIsBoundedAndRejectedStartCanBeRetriedAfterCleanup() throws Exception {
        try (var session = new Session()) {
            JsonNode waiting = session.prepare(WAIT);
            JsonNode next = session.prepare(McpFixtures.SCENARIO);
            session.start(waiting);
            session.started(waiting);
            assertThat(session.start(next).path("structuredContent").path("code").asText()).isEqualTo("RUN_LIMIT");
            assertThat(session.artifacts(next)).doesNotExist();
            session.status(waiting, true);
            session.finished(waiting);
            assertThat(session.start(next).path("isError").asBoolean()).isFalse();
            assertThat(session.finished(next).path("status").asText()).isEqualTo("PASSED");
        }
    }

    @Test
    void retainedRunLimitRefusesAnotherRunWithoutAllocatingArtifacts() throws Exception {
        try (var session = new Session()) {
            for (int index = 0; index < 32; index++) {
                JsonNode prepared = session.prepare(McpFixtures.SCENARIO);
                assertThat(session.start(prepared).path("isError").asBoolean()).isFalse();
                assertThat(session.finished(prepared).path("status").asText()).isEqualTo("PASSED");
            }
            JsonNode prepared = session.prepare(McpFixtures.SCENARIO);
            assertThat(session.start(prepared).path("structuredContent").path("code").asText()).isEqualTo("RUN_LIMIT");
            assertThat(session.artifacts(prepared)).doesNotExist();
            assertThat(session.requests).hasValue(32);
        }
    }

    @Test
    void approvalCannotGrantPrivateNetworkPermissionExcludedByOperatorPolicy() throws Exception {
        try (var session = new Session(false)) {
            JsonNode prepared = session.prepare(McpFixtures.SCENARIO);
            assertThat(session.start(prepared).path("isError").asBoolean()).isFalse();
            assertThat(session.finished(prepared).path("status").asText()).isEqualTo("FAILED");
            assertThat(session.requests).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"traversal", "journal", "changed", "oversized", "owner", "directory"})
    void artifactExportRefusesUnknownIdsAndFilesystemTampering(String mode) throws Exception {
        try (var session = new Session()) {
            JsonNode prepared = session.prepare(McpFixtures.SCENARIO);
            session.start(prepared);
            session.finished(prepared);
            Path report = session.artifacts(prepared).resolve("report.json");
            String id = "report";
            if (mode.equals("traversal")) {
                id = "../report.json";
            } else if (mode.equals("journal")) {
                id = "events.ndjson";
            } else if (mode.equals("owner")) {
                Files.writeString(session.artifacts(prepared).resolve("run-owner"), "foreign-run");
            } else if (mode.equals("directory")) {
                Files.delete(report);
                Files.createDirectory(report);
            } else {
                Files.writeString(report, mode.equals("oversized") ? "x".repeat(65_537) : McpFixtures.MARKER);
            }
            var result = session.artifact(prepared, id);
            assertThat(result.path("isError").asBoolean()).isTrue();
            assertThat(result.toString()).doesNotContain(McpFixtures.MARKER, directory.toString());
        }
    }

    @Test
    void reparseAncestorIsRefusedEvenForAnOtherwiseAuthenticReport() throws Exception {
        try (var session = new Session()) {
            JsonNode prepared = session.prepare(McpFixtures.SCENARIO);
            session.start(prepared);
            session.finished(prepared);
            Path original = session.artifacts(prepared);
            Path moved = original.resolveSibling("moved");
            Files.move(original, moved);
            if (System.getProperty("os.name").startsWith("Windows")) {
                Process junction = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", original.toString(), moved.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                try {
                    assertThat(junction.waitFor(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(junction.exitValue()).isZero();
                } finally {
                    if (junction.isAlive()) {
                        junction.destroyForcibly();
                        junction.waitFor(5, TimeUnit.SECONDS);
                    }
                }
            } else {
                Files.createSymbolicLink(original, moved);
            }
            try {
                assertThat(session.artifact(prepared, "report").path("structuredContent").path("code").asText())
                        .isEqualTo("ARTIFACT_REFUSED");
            } finally {
                Files.delete(original);
            }
        }
    }

    @Test
    void preexistingRunDirectoryIsNeverReusedOrOverwritten() throws Exception {
        try (var session = new Session()) {
            JsonNode prepared = session.prepare(McpFixtures.SCENARIO);
            Path path = Files.createDirectory(session.artifacts(prepared));
            Files.writeString(path.resolve("sentinel"), "retain");
            session.start(prepared);
            assertThat(session.finished(prepared).path("status").asText()).isEqualTo("ERROR");
            assertThat(Files.readString(path.resolve("sentinel"))).isEqualTo("retain");
            assertThat(path.resolve("events.ndjson")).doesNotExist();
            assertThat(session.requests).hasValue(0);
        }
    }
}
