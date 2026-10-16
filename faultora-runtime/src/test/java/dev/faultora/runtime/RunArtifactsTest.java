package dev.faultora.runtime;

import dev.faultora.model.identifier.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunArtifactsTest {
    @TempDir Path directory;

    @Test
    void identitiesDoNotDependOnTheReplaySeed() throws Exception {
        var first = RunArtifacts.claimDispatched(directory, RunArtifacts.newRunId());
        var second = RunArtifacts.claimDispatched(directory, RunArtifacts.newRunId());
        assertThat(first.runId()).isNotEqualTo(second.runId());
        assertThat(first.directory()).isNotEqualTo(second.directory());
        Files.writeString(first.journalPath(), "first");
        Files.writeString(second.journalPath(), "second");
        assertThat(Files.readString(first.journalPath())).isEqualTo("first");
    }

    @Test
    void ownershipSurvivesRestartAndNeverTruncatesExistingArtifacts() throws Exception {
        RunId runId = new RunId("synthetic-run");
        var artifacts = RunArtifacts.claimDispatched(directory, runId);
        Files.writeString(artifacts.journalPath(), "original");
        assertThatThrownBy(() -> RunArtifacts.claimDispatched(directory, runId)).isInstanceOf(IOException.class);
        assertThat(Files.readString(artifacts.journalPath())).isEqualTo("original");
    }

    @Test
    void dispatchIdentityCannotEscapeTheArtifactRoot() throws Exception {
        // Typed IDs may admit punctuation, but are never interpreted as paths.
        var artifacts = RunArtifacts.claimDispatched(directory, new RunId("../synthetic-run"));
        assertThat(artifacts.directory().getParent()).isEqualTo(directory);
        assertThat(artifacts.directory().getFileName().toString()).matches("run-[a-f0-9]{64}");
    }

    @Test
    void anOldUnownedJournalOrReportAlsoBlocksClaiming() throws Exception {
        for (String name : java.util.List.of("events.ndjson", "report.json", "report.html",
                "EVENTS.NDJSON", "REPORT.JSON", "Run-Owner")) {
            Path root = Files.createTempDirectory(directory, "legacy-");
            Files.writeString(root.resolve(name), "original");
            assertThatThrownBy(() -> RunArtifacts.claim(root, RunArtifacts.newRunId()))
                    .isInstanceOf(IOException.class);
            assertThat(Files.readString(root.resolve(name))).isEqualTo("original");
        }
    }

    @Test
    void oneOfTwoConcurrentClaimantsOwnsTheDirectory() throws Exception {
        Path root = Files.createDirectory(directory.resolve("shared"));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var attempts = workers.invokeAll(java.util.List.<java.util.concurrent.Callable<Integer>>of(
                    () -> claim(root), () -> claim(root)));
            assertThat(attempts.get(0).get() + attempts.get(1).get()).isEqualTo(1);
        }
        assertThat(root.resolve("run-owner")).exists();
    }

    private int claim(Path root) {
        try {
            RunArtifacts.claim(root, RunArtifacts.newRunId());
            return 1;
        } catch (IOException alreadyOwned) {
            return 0;
        }
    }
}
