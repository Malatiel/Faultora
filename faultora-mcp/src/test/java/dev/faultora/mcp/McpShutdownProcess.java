package dev.faultora.mcp;

import dev.faultora.engine.run.RunResult;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.runtime.PreparedRun;
import dev.faultora.runtime.RunArtifacts;
import dev.faultora.runtime.RunEvidence;
import dev.faultora.runtime.RunPreparation;
import dev.faultora.spec.expression.ExpressionContext;
import dev.faultora.spi.context.ConnectorContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** A separate JVM is necessary: the production deadline deliberately calls Runtime.halt. */
public final class McpShutdownProcess {
    private McpShutdownProcess() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        String mode = args[1];
        var request = new RunPreparation.Request(McpFixtures.SCENARIO + "timeout: 300ms\n",
                List.of(McpFixtures.configuration().catalogs().get("demo")), "",
                new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of()), McpFixtures.policy(),
                RunArtifacts.newRunId(), 42, null);
        var settings = new PreparedRun.Settings(ExpressionContext.builder().build(),
                new ConnectorContext(RunEvidence.defaultPolicy(), ignored -> null, 5000, 30_000, 60_000, Map.of()),
                false, Set.of());
        var prepared = PreparedRun.prepare(request, settings, Duration.ofMinutes(1));
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        var configuration = new McpConfiguration.Execution(directory, key, false);
        var entered = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        McpRuns.RunExecutor callback = (plan, captured, execution, journal, cancellation) -> {
            worker.set(Thread.currentThread());
            try {
                Files.writeString(directory.resolve("ready"), captured.runId().value());
                entered.countDown();
                if (mode.equals("complete")) {
                    return new RunResult(captured.runId(), RunResult.Status.PASSED, 0, 0, 0, Map.of(), 0, null);
                }
                boolean observed = false;
                while (true) {
                    Thread.interrupted();
                    if (cancellation.get() && !observed) {
                        Files.writeString(directory.resolve("cancel-observed"), "true");
                        observed = true;
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
            } catch (java.io.IOException unavailable) {
                throw new java.io.UncheckedIOException(unavailable);
            }
        };
        var runs = new McpRuns(configuration, callback, 200, 500);
        runs.start(prepared, request, settings);
        if (!entered.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Callback was not entered");
        }
        if (mode.equals("complete")) {
            runs.close();
            Thread.sleep(1200);
            return;
        }
        worker.get().interrupt();
        if (mode.equals("marker-unavailable")) {
            Path run = RunArtifacts.dispatchedDirectory(directory, request.runId());
            Files.writeString(run.resolve("run-owner"), "foreign-owner");
        }
        if (mode.equals("interrupted-close")) {
            Thread.currentThread().interrupt();
        }
        switch (mode) {
            case "close", "interrupted-close", "marker-unavailable" -> runs.close();
            case "shutdown-hook" -> {
                Runtime.getRuntime().addShutdownHook(new Thread(runs::close));
                System.exit(0);
            }
            case "deadline" -> Thread.currentThread().join();
            default -> throw new IllegalArgumentException("Unknown synthetic mode");
        }
    }
}
