package dev.faultora.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.engine.LocalEngine;
import dev.faultora.engine.exec.JournalWriter;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.run.RunResult;
import dev.faultora.faults.local.LocalFaultProvider;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.NodeId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.runtime.PreparedRun;
import dev.faultora.runtime.RunArtifacts;
import dev.faultora.runtime.RunPreparation;
import dev.faultora.spi.contract.FaultProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Session ownership lasts until execution, connector close and artifact flush have finished. */
final class McpRuns implements AutoCloseable {
    // Leave room for the summary and for quoting the report in both MCP result representations.
    private static final int MAX_ASSERTION_BYTES = 40 * 1024;
    private static final int MAX_REPORT_BYTES = 64 * 1024;
    private final McpConfiguration.Execution configuration;
    private final Map<String, OwnedRun> runs = new LinkedHashMap<>();
    private final RunExecutor executor;
    private final long cleanupMs;
    private final long graceMs;
    private boolean closed;

    @FunctionalInterface
    interface RunExecutor {
        RunResult execute(PreparedRun prepared, RunPreparation.Request request, PreparedRun.Settings settings,
                          RunJournal journal, AtomicBoolean cancellation);
    }

    static final class Refused extends RuntimeException {
        Refused(String code) {
            super(code);
        }
    }

    private static final class OwnedRun {
        final String id;
        final AtomicBoolean cancellation = new AtomicBoolean();
        final CountDownLatch finished = new CountDownLatch(1);
        final Thread thread;
        final McpRunShutdown shutdown;
        volatile JsonNode terminal;
        Path directory;
        String reportDigest;

        OwnedRun(String id, Runnable work, Path directory, long runMs, long cleanupMs, long graceMs) {
            this.id = id;
            thread = new Thread(work, "faultora-mcp-run");
            shutdown = new McpRunShutdown(id, directory, cancellation, runMs, cleanupMs, graceMs);
        }

        void cancel() {
            if (finished.getCount() != 0) {
                // The same worker may already be in cleanup; interrupting it would abort that obligation.
                cancellation.set(true);
            }
        }

        JsonNode status() {
            if (shutdown.unconfirmed()) {
                return failure(id, McpRunShutdown.REASON);
            }
            if (finished.getCount() == 0) {
                return terminal.deepCopy();
            }
            return McpJson.MAPPER.createObjectNode().put("runId", id).put("terminal", false)
                    .put("status", cancellation.get() ? "CANCELLING" : "RUNNING");
        }
    }

    McpRuns(McpConfiguration.Execution configuration) {
        this(configuration, (prepared, request, settings, journal, cancellation) ->
                        prepared.execute(request, settings, faultProviders(),
                                journal, cancellation),
                LocalEngine.DEFAULT_CLEANUP_TIMEOUT_MS, McpRunShutdown.GRACE_MS);
    }

    /** The shutdown budget assumes faults cannot remain active outside this JVM. */
    static Map<String, FaultProvider> faultProviders() {
        return Map.of("local", new LocalFaultProvider());
    }

    /** Package-private subprocess seam: a callback and short budgets, never tool/configuration input. */
    McpRuns(McpConfiguration.Execution configuration, RunExecutor executor, long cleanupMs, long graceMs) {
        this.configuration = configuration;
        this.executor = executor;
        this.cleanupMs = cleanupMs;
        this.graceMs = graceMs;
    }

    static long effectiveRunMs(dev.faultora.runtime.PlanPreview.Budgets budgets) {
        return budgets.scenarioTimeoutMs() > 0
                ? Math.min(budgets.scenarioTimeoutMs(), budgets.maxDurationMs()) : budgets.maxDurationMs();
    }

    synchronized JsonNode start(PreparedRun prepared, RunPreparation.Request request, PreparedRun.Settings settings) {
        if (closed || runs.values().stream().anyMatch(run -> run.shutdown.unconfirmed())) {
            throw new Refused("SESSION_CLOSED");
        }
        if (runs.values().stream().anyMatch(run -> run.finished.getCount() != 0)) {
            throw new Refused("RUN_LIMIT");
        }
        prepared.requireCurrent(request, settings);
        String id = request.runId().value();
        if (runs.containsKey(id)) {
            throw new Refused("PREPARATION_REFUSED");
        }
        if (runs.size() >= 32) {
            // Admission is already validated. Forget only the oldest completed record, never its files.
            runs.remove(runs.keySet().iterator().next());
        }
        var preview = prepared.preview();
        var knownTypes = Set.copyOf(preview.capabilities().assertionTypes());
        OwnedRun owned = new OwnedRun(id, () -> execute(id, prepared, request, settings, knownTypes),
                RunArtifacts.dispatchedDirectory(configuration.workingDirectory(), request.runId()),
                effectiveRunMs(preview.budgets()), cleanupMs, graceMs);
        runs.put(id, owned);
        try {
            owned.shutdown.start();
            owned.thread.start();
        } catch (RuntimeException | Error startFailed) {
            owned.shutdown.complete();
            runs.remove(id);
            throw startFailed;
        }
        return owned.status();
    }

    private void execute(String id, PreparedRun prepared, RunPreparation.Request request,
                         PreparedRun.Settings settings, Set<String> knownTypes) {
        OwnedRun owned;
        synchronized (this) {
            owned = runs.get(id);
        }
        JsonNode terminal = failure(id, "EXECUTION_FAILED");
        try {
            McpConfiguration.requireDirectory(configuration.workingDirectory());
            Path directory = RunArtifacts.dispatchedDirectory(configuration.workingDirectory(), request.runId());
            // Claim only a new child: an existing directory could be a link or another session's run.
            Files.createDirectory(directory);
            McpConfiguration.requireDirectory(directory);
            RunArtifacts artifacts = RunArtifacts.claim(directory, request.runId());
            owned.directory = directory;
            try (RunJournal journal = new RunJournal(artifacts.journalPath(), true)) {
                RunResult result = executor.execute(prepared, request, settings, journal, owned.cancellation);
                result = JournalWriter.closeJournal(journal, result);
                terminal = summary(result, journal, prepared.assertionChecks(), knownTypes);
            }
            // Engine cancellation preserves interrupt; publishing a report is part of completion.
            Thread.interrupted();
            try {
                String report = McpJson.MAPPER.writeValueAsString(terminal);
                Files.writeString(directory.resolve("report.json"), report, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                owned.reportDigest = ContentDigest.sha256Uri(report);
            } catch (IOException | RuntimeException unavailable) {
                var completed = (com.fasterxml.jackson.databind.node.ObjectNode) terminal;
                completed.put("reportStatus", "UNAVAILABLE").put("reportErrorCode", "REPORT_WRITE_FAILED");
                completed.putArray("artifactIds");
            }
        } catch (IOException | RuntimeException failed) {
            terminal = failure(id, "EXECUTION_FAILED");
        } finally {
            owned.terminal = terminal;
            owned.shutdown.complete();
            owned.finished.countDown();
        }
    }

    private static JsonNode summary(RunResult result, RunJournal journal,
                                    List<PreparedRun.AssertionCheck> planned, Set<String> knownTypes)
            throws IOException {
        var response = McpJson.MAPPER.createObjectNode().put("runId", result.runId().value())
                .put("terminal", true).put("status", result.status().name()).put("totalNodes", result.totalNodes())
                .put("passedAssertions", result.passedAssertions()).put("failedAssertions", result.failedAssertions());
        var outcomes = response.putObject("assertionOutcomes").put("PASS", 0).put("FAIL", 0).put("INDETERMINATE", 0);
        result.nodeResults().values().forEach(node -> node.assertions().forEach(assertion -> {
            String outcome = assertion.outcome().name();
            outcomes.put(outcome, outcomes.path(outcome).asInt() + 1);
        }));
        var events = journal.events();
        addAssertionDetails(response, result, events, planned, knownTypes);
        boolean cleanupFailed = events.stream().anyMatch(event ->
                event instanceof RunEvent.CleanupCompleted cleanup && cleanup.failed() != 0);
        // An ERROR may mean rollback, journal or connector close was not confirmed.
        response.put("cleanupConfirmed", !cleanupFailed && result.status() != RunResult.Status.ERROR);
        response.put("reportStatus", "AVAILABLE");
        response.putArray("artifactIds").add("report");
        return response;
    }

    private static void addAssertionDetails(ObjectNode response, RunResult result, List<RunEvent> events,
                                            List<PreparedRun.AssertionCheck> planned, Set<String> knownTypes)
            throws IOException {
        var entries = new AssertionEntries(response.putArray("assertions"));
        Map<NodeId, Integer> evaluated = new HashMap<>();
        for (RunEvent event : events) {
            if (event instanceof RunEvent.AssertionEvaluated assertion) {
                evaluated.merge(assertion.nodeId(), 1, Integer::sum);
                entries.add(McpJson.MAPPER.createObjectNode().put("nodeId", assertion.nodeId().value())
                        .put("assertionType", assertion.assertionType()).put("outcome", assertion.outcome()));
            }
        }
        // Conditions of one eventually node are emitted together in declaration order.
        for (var check : planned) {
            Integer remaining = evaluated.computeIfPresent(check.nodeId(), (id, count) -> count - 1);
            if (remaining != null && remaining >= 0) {
                continue;
            }
            RunResult.NodeResult node = result.nodeResults().get(check.nodeId());
            boolean skipped = node != null && node.status() == RunResult.Status.SKIPPED;
            var detail = McpJson.MAPPER.createObjectNode().put("nodeId", check.nodeId().value())
                    .put("outcome", skipped ? "SKIPPED" : "NOT_EVALUATED")
                    .put("reasonCode", skipped ? "DEPENDENCY_NOT_PASSED" : notEvaluatedReason(node));
            if (check.assertionType() != null && knownTypes.contains(check.assertionType())) {
                detail.put("assertionType", check.assertionType());
            }
            entries.add(detail);
        }
        response.put("omittedAssertionCount", entries.omitted);
    }

    private static String notEvaluatedReason(RunResult.NodeResult node) {
        if (node == null) {
            return "RUN_STOPPED";
        }
        String code = node.error() == null ? null : node.error().code();
        return switch (code) {
            case "VALIDATION" -> "ASSERTION_REFUSED";
            case "INPUTS_UNRESOLVABLE", "REQUEST_BUDGET_EXCEEDED", "EVENTUALLY_TIMEOUT",
                    "DEADLINE_EXCEEDED", "CANCELLED" -> code;
            case null, default -> "EVALUATION_UNAVAILABLE";
        };
    }

    private static final class AssertionEntries {
        private final ArrayNode array;
        private int remaining = MAX_ASSERTION_BYTES - 2; // Array brackets.
        private int omitted;

        private AssertionEntries(ArrayNode array) {
            this.array = array;
        }

        private void add(ObjectNode entry) throws IOException {
            if (omitted == 0) {
                int size = McpJson.MAPPER.writeValueAsBytes(entry).length + (array.isEmpty() ? 0 : 1);
                if (size <= remaining) {
                    array.add(entry);
                    remaining -= size;
                    return;
                }
            }
            omitted++;
        }
    }

    private static JsonNode failure(String id, String code) {
        return McpJson.MAPPER.createObjectNode().put("runId", id).put("terminal", true)
                .put("status", "ERROR").put("code", code).put("cleanupConfirmed", false);
    }

    private OwnedRun owned(String id) {
        OwnedRun run = runs.get(id);
        if (run == null || closed) {
            throw new Refused("UNKNOWN_RUN");
        }
        return run;
    }

    synchronized JsonNode status(String id, boolean cancel) {
        OwnedRun run = owned(id);
        if (cancel) {
            run.cancel();
        }
        return run.status();
    }

    synchronized JsonNode artifact(String id, String artifact) {
        OwnedRun run = owned(id);
        if (!artifact.equals("report")) {
            throw new Refused("UNKNOWN_ARTIFACT");
        }
        if (run.finished.getCount() != 0 || run.reportDigest == null || run.shutdown.unconfirmed()) {
            throw new Refused("ARTIFACT_UNAVAILABLE");
        }
        try {
            McpConfiguration.requireDirectory(configuration.workingDirectory());
            McpConfiguration.requireDirectory(run.directory);
            String owner = McpJson.utf8(McpConfiguration.readFile(run.directory.resolve("run-owner"), 128));
            if (!owner.equals(id)) {
                throw new IOException("Artifact owner changed");
            }
            byte[] bytes = McpConfiguration.readFile(run.directory.resolve("report.json"), MAX_REPORT_BYTES);
            String text = McpJson.utf8(bytes);
            if (!run.reportDigest.equals(ContentDigest.sha256Uri(text))) {
                throw new IOException("Artifact changed");
            }
            return McpJson.MAPPER.createObjectNode().put("runId", id).put("artifactId", artifact)
                    .put("mediaType", "application/json").put("bytes", bytes.length).put("text", text);
        } catch (IOException invalid) {
            throw new Refused("ARTIFACT_REFUSED");
        }
    }

    @Override
    public void close() {
        java.util.List<OwnedRun> owned;
        synchronized (this) {
            closed = true;
            owned = java.util.List.copyOf(runs.values());
            owned.forEach(OwnedRun::cancel);
        }
        boolean interrupted = Thread.interrupted();
        for (OwnedRun run : owned) {
            while (true) {
                try {
                    run.shutdown.join(run.thread);
                    break;
                } catch (InterruptedException cancelled) {
                    interrupted = true;
                }
            }
        }
        synchronized (this) {
            runs.clear();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
