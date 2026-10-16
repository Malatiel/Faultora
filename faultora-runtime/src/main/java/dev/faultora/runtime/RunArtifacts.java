package dev.faultora.runtime;

import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.ContentDigest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;

/** Exclusive ownership of one run's journal and reports. Ownership survives process restart. */
public record RunArtifacts(RunId runId, Path directory) {
    public static final class AlreadyOwned extends IOException {
        private AlreadyOwned() {
            super("Run artifact directory is already in use");
        }
    }

    public static RunId newRunId() {
        return new RunId("run-" + UUID.randomUUID());
    }

    /** Another run's artifacts are never removed; unrelated input files may share the directory. */
    public static RunArtifacts claim(Path directory, RunId runId) throws IOException {
        Files.createDirectories(directory);
        try (var entries = Files.list(directory)) {
            if (entries.anyMatch(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.equals("run-owner") || name.equals("events.ndjson") || name.startsWith("report.");
            })) {
                throw new AlreadyOwned();
            }
        }
        try {
            Files.writeString(directory.resolve("run-owner"), runId.value(),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException claimedConcurrently) {
            throw new AlreadyOwned();
        }
        return new RunArtifacts(runId, directory);
    }

    /** Dispatch IDs are data, never relative paths. The same ID cannot reclaim old artifacts. */
    public static RunArtifacts claimDispatched(Path root, RunId runId) throws IOException {
        return claim(dispatchedDirectory(root, runId), runId);
    }

    public static Path dispatchedDirectory(Path root, RunId runId) {
        String safeName = "run-" + ContentDigest.sha256Uri(runId.value()).substring("sha256:".length());
        return root.resolve(safeName);
    }

    public Path journalPath() {
        return directory.resolve("events.ndjson");
    }
}
