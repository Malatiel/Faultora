package dev.faultora.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.faultora.engine.exec.JournalWriter;
import dev.faultora.engine.journal.RunJournal;
import dev.faultora.engine.run.RunResult;
import dev.faultora.faults.local.LocalFaultProvider;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.runtime.PreparedRun;
import dev.faultora.runtime.RunArtifacts;
import dev.faultora.runtime.RunPreparation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Session ownership lasts until execution, connector close and artifact flush have finished. */
final class McpRuns implements AutoCloseable {
    private final McpConfiguration.Execution configuration;
    private final Map<String, OwnedRun> runs = new LinkedHashMap<>();
    private boolean closed;

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
        volatile JsonNode terminal;
        Path directory;
        String reportDigest;

        OwnedRun(String id, Runnable work) {
            this.id = id;
            thread = new Thread(work, "faultora-mcp-run");
        }

        void cancel() {
            if (finished.getCount() != 0) {
                // The same worker may already be in cleanup; interrupting it would abort that obligation.
                cancellation.set(true);
            }
        }

        JsonNode status() {
            if (finished.getCount() == 0) {
                return terminal.deepCopy();
            }
            return McpJson.MAPPER.createObjectNode().put("runId", id).put("terminal", false)
                    .put("status", cancellation.get() ? "CANCELLING" : "RUNNING");
        }
    }

    McpRuns(McpConfiguration.Execution configuration) {
        this.configuration = configuration;
    }

    synchronized JsonNode start(PreparedRun prepared, RunPreparation.Request request, PreparedRun.Settings settings) {
        if (closed) {
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
        OwnedRun owned = new OwnedRun(id, () -> execute(id, prepared, request, settings));
        runs.put(id, owned);
        owned.thread.start();
        return owned.status();
    }

    private void execute(String id, PreparedRun prepared, RunPreparation.Request request, PreparedRun.Settings settings) {
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
                RunResult result = prepared.execute(request, settings,
                        Map.of("local", new LocalFaultProvider()), journal, owned.cancellation);
                result = JournalWriter.closeJournal(journal, result);
                terminal = summary(result, journal);
            }
            // Engine cancellation preserves interrupt; publishing a report is part of completion.
            Thread.interrupted();
            String report = McpJson.MAPPER.writeValueAsString(terminal);
            Files.writeString(directory.resolve("report.json"), report, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            owned.reportDigest = ContentDigest.sha256Uri(report);
        } catch (IOException | RuntimeException failed) {
            terminal = failure(id, "EXECUTION_FAILED");
        } finally {
            owned.terminal = terminal;
            owned.finished.countDown();
        }
    }

    private static JsonNode summary(RunResult result, RunJournal journal) {
        var response = McpJson.MAPPER.createObjectNode().put("runId", result.runId().value())
                .put("terminal", true).put("status", result.status().name()).put("totalNodes", result.totalNodes())
                .put("passedAssertions", result.passedAssertions()).put("failedAssertions", result.failedAssertions());
        var outcomes = response.putObject("assertionOutcomes").put("PASS", 0).put("FAIL", 0).put("INDETERMINATE", 0);
        result.nodeResults().values().forEach(node -> node.assertions().forEach(assertion -> {
            String outcome = assertion.outcome().name();
            outcomes.put(outcome, outcomes.path(outcome).asInt() + 1);
        }));
        boolean cleanupFailed = journal.events().stream().anyMatch(event ->
                event instanceof RunEvent.CleanupCompleted cleanup && cleanup.failed() != 0);
        // An ERROR may mean rollback, journal or connector close was not confirmed.
        response.put("cleanupConfirmed", !cleanupFailed && result.status() != RunResult.Status.ERROR);
        response.putArray("artifactIds").add("report");
        return response;
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
        if (run.finished.getCount() != 0 || run.reportDigest == null) {
            throw new Refused("ARTIFACT_UNAVAILABLE");
        }
        try {
            McpConfiguration.requireDirectory(configuration.workingDirectory());
            McpConfiguration.requireDirectory(run.directory);
            String owner = McpJson.utf8(McpConfiguration.readFile(run.directory.resolve("run-owner"), 128));
            if (!owner.equals(id)) {
                throw new IOException("Artifact owner changed");
            }
            byte[] bytes = McpConfiguration.readFile(run.directory.resolve("report.json"), 65_536);
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
                    run.thread.join();
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
