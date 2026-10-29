package dev.faultora.mcp;

import dev.faultora.process.LifecycleSupervisor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

/** MCP ownership marker and current-process termination policy over the shared supervisor. */
final class McpRunShutdown {
    static final long GRACE_MS = 5000;
    static final int EXIT_UNCONFIRMED = 3;
    static final String REASON = "SHUTDOWN_DEADLINE_EXCEEDED";
    private final String runId;
    private final Path directory;
    private final LifecycleSupervisor supervisor;

    McpRunShutdown(String runId, Path directory, AtomicBoolean cancellation,
                   long runMs, long cleanupMs, long graceMs) {
        this.runId = runId;
        this.directory = directory;
        supervisor = new LifecycleSupervisor(cancellation, runMs, cleanupMs, graceMs,
                this::writeMarker,
                () -> System.err.println("MCP termination is unconfirmed; marker could not be written"),
                () -> Runtime.getRuntime().halt(EXIT_UNCONFIRMED));
    }

    void start() { supervisor.start(); }
    void complete() { supervisor.complete(); }
    boolean unconfirmed() { return supervisor.unconfirmed(); }
    void join(Thread worker) throws InterruptedException { supervisor.join(worker); }

    private void writeMarker() throws IOException {
        McpConfiguration.requireDirectory(directory);
        String owner = McpJson.utf8(McpConfiguration.readFile(directory.resolve("run-owner"), 128));
        if (!owner.equals(runId)) {
            throw new IOException("Run ownership could not be verified");
        }
        var marker = McpJson.MAPPER.createObjectNode().put("runId", runId).put("status", "ERROR")
                .put("cleanupConfirmed", false).put("code", REASON);
        byte[] bytes = McpJson.MAPPER.writeValueAsString(marker).getBytes(StandardCharsets.UTF_8);
        try (var output = Files.newOutputStream(directory.resolve("termination.json"),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            output.write(bytes);
        }
    }
}
