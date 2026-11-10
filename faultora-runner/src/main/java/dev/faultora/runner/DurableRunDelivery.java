package dev.faultora.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.identifier.RunId;
import dev.faultora.runner.protocol.DurableDelivery;
import dev.faultora.runner.protocol.DurableDelivery.Ack;
import dev.faultora.runner.protocol.DurableDelivery.Attempt;
import dev.faultora.runner.protocol.DurableDelivery.Progress;
import dev.faultora.runner.protocol.DurableDelivery.Terminal;
import dev.faultora.runner.RunnerAgent.DeliveryStorageFailure;
import dev.faultora.runtime.RunArtifacts;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Delivers committed facts from a closed spool. No execution or dispatch dependencies enter recovery. */
final class DurableRunDelivery {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CHECKPOINT = "delivery-ack.json";
    private final RunnerClient client;
    private final Path workingDirectory;
    private final String routeDigest;

    DurableRunDelivery(RunnerClient client, Path workingDirectory) {
        this.client = client;
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.routeDigest = client.deliveryRouteDigest();
    }

    private record Checkpoint(String routeDigest, Attempt attempt, long nextPosition, String terminalDigest) {
        private Checkpoint {
            if (!DurableDelivery.isDigest(routeDigest)) throw new IllegalArgumentException("Invalid delivery route");
            new Ack(attempt, nextPosition, terminalDigest);
        }
    }

    private record Pending(Checkpoint checkpoint, List<String> lines, Terminal terminal) { }

    void opened(DurableRunSpool.Identity identity, Path spool) throws DeliveryStorageFailure {
        Path path = spool.resolveSibling(CHECKPOINT);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new DeliveryStorageFailure(new IOException("DURABLE_CHECKPOINT_ALREADY_EXISTS"));
        }
        save(path, new Checkpoint(routeDigest, attempt(identity), 0, null));
    }

    void resumePending(String sessionId) throws IOException, InterruptedException {
        if (Files.notExists(workingDirectory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var runs = openRuns()) {
            for (Path run : runs) {
                Path path = run.resolve(CHECKPOINT);
                if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) resume(sessionId, path);
            }
        } catch (DirectoryIteratorException failed) {
            throw new DeliveryStorageFailure(failed.getCause());
        }
    }

    void completed(String sessionId, Path journal) throws IOException, InterruptedException {
        resume(sessionId, journal.resolveSibling(CHECKPOINT));
    }

    private void resume(String sessionId, Path checkpointPath) throws IOException, InterruptedException {
        try (FileChannel channel = openLock(checkpointPath); FileLock lock = tryLock(channel)) {
            if (lock == null) return;
            Pending pending;
            try {
                pending = recover(checkpointPath);
            } catch (IOException unavailable) {
                throw new DeliveryStorageFailure(unavailable);
            }
            if (pending == null) return;
            var checkpoint = pending.checkpoint();
            send(sessionId, checkpointPath, checkpoint.attempt(), Math.toIntExact(checkpoint.nextPosition()),
                    pending.lines(), pending.terminal());
        }
    }

    private DirectoryStream<Path> openRuns() throws DeliveryStorageFailure {
        try {
            DurableRunSpool.noLinks(workingDirectory);
            return Files.newDirectoryStream(workingDirectory, "run-*");
        } catch (IOException unavailable) {
            throw new DeliveryStorageFailure(unavailable);
        }
    }

    private static FileChannel openLock(Path checkpointPath) throws DeliveryStorageFailure {
        try {
            DurableRunSpool.noLinks(checkpointPath);
            return FileChannel.open(checkpointPath.resolveSibling("delivery-lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unavailable) {
            throw new DeliveryStorageFailure(unavailable);
        }
    }

    private Pending recover(Path checkpointPath) throws IOException {
        Checkpoint checkpoint;
        try (var input = Files.newInputStream(checkpointPath, LinkOption.NOFOLLOW_LINKS)) {
            checkpoint = DurableDelivery.decode(input.readNBytes(DurableDelivery.MAX_ACK_BYTES + 1),
                    Checkpoint.class, DurableDelivery.MAX_ACK_BYTES);
        }
        if (!routeDigest.equals(checkpoint.routeDigest())) {
            throw new IOException("DURABLE_CHECKPOINT_ROUTE_MISMATCH");
        }

        Attempt attempt = checkpoint.attempt();
        Path owner = workingDirectory.resolve("runner-identity");
        Path runOwner = checkpointPath.resolveSibling("run-owner");
        DurableRunSpool.noLinks(owner);
        DurableRunSpool.noLinks(runOwner);
        if (!RunArtifacts.dispatchedDirectory(workingDirectory, new RunId(attempt.runId()))
                .equals(checkpointPath.getParent())
                || Files.size(owner) != 36
                || !attempt.runnerId().equals(Files.readString(owner, StandardCharsets.US_ASCII))
                || Files.size(runOwner) != attempt.runId().length()
                || !attempt.runId().equals(Files.readString(runOwner, StandardCharsets.US_ASCII))) {
            throw new IOException("DURABLE_CHECKPOINT_OWNER_MISMATCH");
        }

        var lines = new ArrayList<String>();
        DurableRunSpool.Snapshot spool;
        try {
            spool = DurableRunSpool.recoverLines(checkpointPath.resolveSibling("spool.bin"), lines::add);
        } catch (DurableRunSpool.Failure failure) {
            if ("SPOOL_IN_USE".equals(failure.getMessage())) return null;
            throw failure;
        }
        if (!attempt.equals(attempt(spool.identity())) || checkpoint.nextPosition() > spool.events()) {
            throw new IOException("DURABLE_CHECKPOINT_SPOOL_MISMATCH");
        }
        if (spool.recoveryRequired()) {
            if (checkpoint.nextPosition() != 0 || checkpoint.terminalDigest() != null) {
                throw new IOException("DURABLE_CHECKPOINT_SPOOL_MISMATCH");
            }
            return null;
        }
        var result = spool.terminal();
        var terminal = Terminal.of(attempt, result.finalPosition(), result.status().name(),
                result.cleanupConfirmed(), result.leaseExpired(), result.reasonCode());
        if (checkpoint.terminalDigest() != null) {
            if (!checkpoint.terminalDigest().equals(terminal.terminalDigest())
                    || checkpoint.nextPosition() != terminal.finalPosition()) {
                throw new IOException("DURABLE_CHECKPOINT_SPOOL_MISMATCH");
            }
            return null;
        }
        return new Pending(checkpoint, lines, terminal);
    }

    private void send(String sessionId, Path path, Attempt attempt, int from, List<String> lines, Terminal terminal)
            throws IOException, InterruptedException {
        while (from < lines.size()) {
            int end = from;
            int bytes = 0;
            while (end < lines.size() && end - from < DurableDelivery.MAX_BATCH_EVENTS) {
                int size = lines.get(end).getBytes(StandardCharsets.UTF_8).length;
                if (size > DurableDelivery.MAX_BATCH_BYTES - bytes) break;
                bytes += size;
                end++;
            }
            if (end == from) throw new IOException("DURABLE_DELIVERY_MESSAGE_TOO_LARGE");
            Progress progress = new Progress(attempt, from, lines.subList(from, end));
            accept(path, client.acknowledgeProgress(sessionId, progress), new Ack(attempt, end, null));
            from = end;
        }
        accept(path, client.acknowledgeTerminal(sessionId, terminal),
                new Ack(attempt, terminal.finalPosition(), terminal.terminalDigest()));
    }

    private void accept(Path path, Ack actual, Ack expected) throws IOException {
        if (!expected.equals(actual)) throw new IOException("DURABLE_DELIVERY_ACK_MISMATCH");
        save(path, new Checkpoint(routeDigest, actual.attempt(), actual.nextPosition(), actual.terminalDigest()));
    }

    private static Attempt attempt(DurableRunSpool.Identity identity) {
        return new Attempt(identity.runnerId(), identity.runId(), identity.attemptId(), identity.dispatchDigest());
    }

    private static FileLock tryLock(FileChannel channel) throws DeliveryStorageFailure {
        try { return channel.tryLock(); }
        catch (OverlappingFileLockException active) { return null; }
        catch (IOException unavailable) { throw new DeliveryStorageFailure(unavailable); }
    }

    private static void save(Path path, Checkpoint checkpoint) throws DeliveryStorageFailure {
        try {
            Path temporary = Files.createTempFile(path.getParent(), "delivery-", ".tmp");
            try {
                try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    var bytes = ByteBuffer.wrap(JSON.writeValueAsBytes(checkpoint));
                    while (bytes.hasRemaining()) output.write(bytes);
                    output.force(true);
                }
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException unavailable) {
            throw new DeliveryStorageFailure(unavailable);
        }
    }
}
