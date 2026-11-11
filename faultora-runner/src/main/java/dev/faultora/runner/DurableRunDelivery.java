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
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

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
            Checkpoint checkpoint;
            try {
                checkpoint = recoverCheckpoint(checkpointPath);
            } catch (IOException unavailable) {
                throw new DeliveryStorageFailure(unavailable);
            }
            try (var spool = openSpool(checkpointPath.resolveSibling("spool.bin"))) {
                if (spool == null) return;
                Terminal terminal;
                try {
                    terminal = pendingTerminal(checkpoint, spool.snapshot());
                } catch (IOException unavailable) {
                    throw new DeliveryStorageFailure(unavailable);
                }
                if (terminal != null) send(sessionId, checkpointPath, checkpoint, spool, terminal);
            } catch (DurableRunSpool.Failure unavailable) {
                throw new DeliveryStorageFailure(unavailable);
            }
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

    private Checkpoint recoverCheckpoint(Path checkpointPath) throws IOException {
        Checkpoint checkpoint;
        try (var input = Files.newInputStream(checkpointPath, LinkOption.NOFOLLOW_LINKS)) {
            checkpoint = DurableDelivery.decode(input.readNBytes(DurableDelivery.MAX_ACK_BYTES + 1),
                    Checkpoint.class, DurableDelivery.MAX_ACK_BYTES);
        }
        if (!routeDigest.equals(checkpoint.routeDigest())) {
            throw new IOException("DURABLE_CHECKPOINT_ROUTE_MISMATCH");
        }

        Attempt attempt = checkpoint.attempt();
        if (!RunArtifacts.dispatchedDirectory(workingDirectory, new RunId(attempt.runId()))
                .equals(checkpointPath.getParent())
                || !DurableRunSpool.ownersMatch(workingDirectory, checkpointPath.getParent(),
                        attempt.runnerId(), attempt.runId())) {
            throw new IOException("DURABLE_CHECKPOINT_OWNER_MISMATCH");
        }
        return checkpoint;
    }

    private static DurableRunSpool.VerifiedReader openSpool(Path path) throws IOException {
        try {
            return DurableRunSpool.openVerified(path);
        } catch (DurableRunSpool.Failure failure) {
            if ("SPOOL_IN_USE".equals(failure.getMessage())) return null;
            throw failure;
        }
    }

    private static Terminal pendingTerminal(Checkpoint checkpoint, DurableRunSpool.Snapshot spool) throws IOException {
        Attempt attempt = checkpoint.attempt();
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
        Terminal terminal;
        try {
            terminal = Terminal.of(attempt, result.finalPosition(), result.status().name(),
                    result.cleanupConfirmed(), result.leaseExpired(), result.reasonCode());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("DURABLE_CHECKPOINT_SPOOL_MISMATCH");
        }
        if (checkpoint.terminalDigest() != null) {
            if (!checkpoint.terminalDigest().equals(terminal.terminalDigest())
                    || checkpoint.nextPosition() != terminal.finalPosition()) {
                throw new IOException("DURABLE_CHECKPOINT_SPOOL_MISMATCH");
            }
            return null;
        }
        return terminal;
    }

    private void send(String sessionId, Path path, Checkpoint checkpoint,
                      DurableRunSpool.VerifiedReader spool, Terminal terminal) throws IOException, InterruptedException {
        Attempt attempt = checkpoint.attempt();
        long from = checkpoint.nextPosition();
        while (from < terminal.finalPosition()) {
            long batchStart = from;
            var batch = spool.nextBatch(from, DurableDelivery.MAX_BATCH_EVENTS, DurableDelivery.MAX_BATCH_BYTES);
            long batchEnd = batchStart + batch.size();
            do {
                int offset = Math.toIntExact(from - batchStart);
                var progress = new Progress(attempt, from, batch.subList(offset, batch.size()));
                Ack ack = client.acknowledgeProgress(sessionId, progress);
                if (!attempt.equals(ack.attempt()) || ack.terminalDigest() != null
                        || ack.nextPosition() <= from || ack.nextPosition() > batchEnd) {
                    throw new IOException("DURABLE_DELIVERY_ACK_MISMATCH");
                }
                save(path, new Checkpoint(routeDigest, attempt, ack.nextPosition(), null));
                from = ack.nextPosition();
            } while (from < batchEnd);
        }
        Ack expected = new Ack(attempt, terminal.finalPosition(), terminal.terminalDigest());
        if (!expected.equals(client.acknowledgeTerminal(sessionId, terminal))) {
            throw new IOException("DURABLE_DELIVERY_ACK_MISMATCH");
        }
        save(path, new Checkpoint(routeDigest, attempt, expected.nextPosition(), expected.terminalDigest()));
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
