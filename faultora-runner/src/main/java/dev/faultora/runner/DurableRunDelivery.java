package dev.faultora.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.identifier.RunId;
import dev.faultora.runner.protocol.DurableDelivery;
import dev.faultora.runner.protocol.DurableDelivery.Ack;
import dev.faultora.runner.protocol.DurableDelivery.Attempt;
import dev.faultora.runner.protocol.DurableDelivery.Progress;
import dev.faultora.runner.protocol.DurableDelivery.Terminal;
import dev.faultora.runner.RunnerAgent.DeliveryStorageFailure;
import dev.faultora.runner.RunnerAgent.RetentionCandidate;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Delivers committed facts from a closed spool. No execution or dispatch dependencies enter recovery. */
final class DurableRunDelivery {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CHECKPOINT = "delivery-ack.json";
    private static final String PENDING_CHECKPOINT = "delivery-ack.pending";
    private static final String RECEIPT = "retention-receipt.json";
    private static final String PENDING_RECEIPT = "retention-receipt.pending";
    private static final String LOCK = "delivery-lock";
    private static final String SPOOL = "spool.bin";
    private static final String MIRROR = "events.ndjson";
    // Some systems release all process locks for a file when any channel to it closes.
    private static final Set<Path> ACTIVE_LOCKS = ConcurrentHashMap.newKeySet();
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

    private record RetirementReceipt(String routeDigest, Terminal terminal, String acknowledgedAt) {
        private RetirementReceipt {
            if (!DurableDelivery.isDigest(routeDigest) || terminal == null || !terminal.cleanupConfirmed()) {
                throw new IllegalArgumentException("Invalid retention receipt");
            }
            Instant.parse(acknowledgedAt);
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

    Optional<RetentionCandidate> inspectRetention(RunId runId, Instant acknowledgedBefore) throws IOException {
        return retention(runId, acknowledgedBefore, false);
    }

    Optional<RetentionCandidate> retireAcknowledgedRun(RunId runId, Instant acknowledgedBefore) throws IOException {
        return retention(runId, acknowledgedBefore, true);
    }

    private Optional<RetentionCandidate> retention(RunId runId, Instant acknowledgedBefore, boolean retire)
            throws IOException {
        Path run = RunArtifacts.dispatchedDirectory(workingDirectory, runId);
        Path checkpointPath = run.resolve(CHECKPOINT);
        Path lockPath = run.resolve(LOCK);
        if (!ACTIVE_LOCKS.add(lockPath)) return Optional.empty();
        try {
            if (absent(checkpointPath) || absent(lockPath)) return Optional.empty();
            try (FileChannel channel = openExistingLock(lockPath, retire);
                 FileLock lock = channel.tryLock(0, Long.MAX_VALUE, !retire)) {
                if (lock == null) return Optional.empty();
                Instant acknowledgedAt = checkpointTime(checkpointPath);
                if (!acknowledgedAt.isBefore(acknowledgedBefore)) return Optional.empty();
                Checkpoint checkpoint = recoverCheckpoint(checkpointPath);
                var receipt = recoverReceipt(run, checkpoint, acknowledgedAt);
                if (checkpoint.terminalDigest() == null) return Optional.empty();
                Terminal terminal = retentionTerminal(run, checkpoint, receipt);
                if (terminal == null || !terminal.cleanupConfirmed() || !obligationsClear(run, terminal)) {
                    return Optional.empty();
                }
                if (retire) retireArtifacts(run, checkpoint, terminal, acknowledgedAt, receipt);
                return Optional.of(new RetentionCandidate(terminal, acknowledgedAt));
            }
        } catch (OverlappingFileLockException busy) {
            return Optional.empty();
        } catch (IOException | IllegalArgumentException | SecurityException unavailable) {
            throw new IOException(retire ? "DURABLE_RETIREMENT_FAILED" : "DURABLE_RETENTION_INSPECTION_FAILED");
        } finally {
            ACTIVE_LOCKS.remove(lockPath);
        }
    }

    /** Admission counts only verified, fully retired attempts at their current size. */
    static boolean isRetired(Path work, Path run) throws IOException {
        if (absent(run.resolve(RECEIPT)) || !artifactsAbsent(run)) return false;
        Path lockPath = run.resolve(LOCK);
        if (!ACTIVE_LOCKS.add(lockPath)) return false;
        try {
            if (absent(lockPath)) return false;
            try (FileChannel channel = openExistingLock(lockPath, false);
                 FileLock lock = channel.tryLock(0, Long.MAX_VALUE, true)) {
                if (lock == null || !artifactsAbsent(run)) return false;
                Path checkpointPath = run.resolve(CHECKPOINT);
                var receipt = recoverReceipt(run, readOwnedCheckpoint(work, checkpointPath),
                        checkpointTime(checkpointPath));
                return receipt != null && obligationsClear(run, receipt.terminal());
            }
        } catch (OverlappingFileLockException busy) {
            return false;
        } finally {
            ACTIVE_LOCKS.remove(lockPath);
        }
    }

    private static FileChannel openExistingLock(Path path, boolean write) throws IOException {
        DurableRunSpool.noLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("DURABLE_DELIVERY_LOCK_UNAVAILABLE");
        }
        return FileChannel.open(path, write ? StandardOpenOption.WRITE : StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean artifactsAbsent(Path run) {
        return absent(run.resolve(SPOOL)) && absent(run.resolve(MIRROR));
    }

    private static boolean absent(Path path) {
        return Files.notExists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static Instant checkpointTime(Path path) throws IOException {
        DurableRunSpool.noLinks(path);
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("DURABLE_CHECKPOINT_UNAVAILABLE");
        return attributes.lastModifiedTime().toInstant();
    }

    private static RetirementReceipt recoverReceipt(Path run, Checkpoint checkpoint, Instant acknowledgedAt)
            throws IOException {
        Path path = run.resolve(RECEIPT);
        if (absent(path)) return null;
        DurableRunSpool.noLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("DURABLE_RETIREMENT_RECEIPT_INVALID");
        }
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            RetirementReceipt receipt = DurableDelivery.decode(input.readNBytes(DurableDelivery.MAX_ACK_BYTES + 1),
                    RetirementReceipt.class, DurableDelivery.MAX_ACK_BYTES);
            Terminal terminal = receipt.terminal();
            if (!checkpoint.routeDigest().equals(receipt.routeDigest())
                    || !checkpoint.attempt().equals(terminal.attempt())
                    || !terminal.terminalDigest().equals(checkpoint.terminalDigest())
                    || terminal.finalPosition() != checkpoint.nextPosition()
                    || !acknowledgedAt.toString().equals(receipt.acknowledgedAt())) {
                throw new IOException("DURABLE_RETIREMENT_RECEIPT_INVALID");
            }
            return receipt;
        }
    }

    private static Terminal retentionTerminal(Path run, Checkpoint checkpoint, RetirementReceipt receipt)
            throws IOException {
        Path path = run.resolve(SPOOL);
        if (receipt != null && absent(path)) return receipt.terminal();
        try (var spool = openSpool(path)) {
            if (spool == null) return null;
            Terminal terminal = verifiedTerminal(checkpoint, spool.snapshot());
            if (receipt != null && !receipt.terminal().equals(terminal)) {
                throw new IOException("DURABLE_RETIREMENT_RECEIPT_INVALID");
            }
            return terminal;
        }
    }

    private static boolean obligationsClear(Path run, Terminal terminal) throws IOException {
        return new FaultObligations(run, terminal.attempt().attemptId()).pending().isEmpty();
    }

    private static void retireArtifacts(Path run, Checkpoint checkpoint, Terminal terminal,
                                        Instant acknowledgedAt, RetirementReceipt receipt) throws IOException {
        Path spool = run.resolve(SPOOL);
        Path mirror = run.resolve(MIRROR);
        boolean removeSpool = removableArtifact(spool);
        boolean removeMirror = removableArtifact(mirror);
        if (receipt == null) {
            writeAtomic(run.resolve(RECEIPT), PENDING_RECEIPT,
                    new RetirementReceipt(checkpoint.routeDigest(), terminal, acknowledgedAt.toString()), false);
        }
        if (removeSpool || removeMirror) {
            // POSIX needs the installed receipt's directory entry forced before unlinking either artifact.
            if (hasPosixAttributes(run)) {
                try (FileChannel directory = FileChannel.open(run, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    directory.force(true);
                }
            }
            if (removeMirror) Files.delete(mirror);
            if (removeSpool) Files.delete(spool);
        }
    }

    private static boolean removableArtifact(Path path) throws IOException {
        if (absent(path)) return false;
        DurableRunSpool.noLinks(path);
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > DurableRunSpool.Limits.defaults().maxBytes()) {
            throw new IOException("DURABLE_RETIREMENT_ARTIFACT_INVALID");
        }
        return true;
    }

    private void resume(String sessionId, Path checkpointPath) throws IOException, InterruptedException {
        Path lockPath = checkpointPath.resolveSibling(LOCK);
        if (!ACTIVE_LOCKS.add(lockPath)) return;
        try (FileChannel channel = openLock(checkpointPath); FileLock lock = tryLock(channel)) {
            if (lock == null) return;
            Checkpoint checkpoint;
            try {
                checkpoint = recoverCheckpoint(checkpointPath);
                Path run = checkpointPath.getParent();
                if (!absent(run.resolve(RECEIPT))) {
                    var receipt = recoverReceipt(run, checkpoint, checkpointTime(checkpointPath));
                    if (receipt == null) throw new IOException("DURABLE_RETIREMENT_RECEIPT_INVALID");
                    retentionTerminal(run, checkpoint, receipt);
                    return;
                }
            } catch (IOException unavailable) {
                throw new DeliveryStorageFailure(unavailable);
            }
            try (var spool = openSpool(checkpointPath.resolveSibling(SPOOL))) {
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
        } finally {
            ACTIVE_LOCKS.remove(lockPath);
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
            return FileChannel.open(checkpointPath.resolveSibling(LOCK), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unavailable) {
            throw new DeliveryStorageFailure(unavailable);
        }
    }

    private Checkpoint recoverCheckpoint(Path checkpointPath) throws IOException {
        Checkpoint checkpoint = readOwnedCheckpoint(workingDirectory, checkpointPath);
        if (!routeDigest.equals(checkpoint.routeDigest())) {
            throw new IOException("DURABLE_CHECKPOINT_ROUTE_MISMATCH");
        }
        return checkpoint;
    }

    private static Checkpoint readOwnedCheckpoint(Path work, Path checkpointPath) throws IOException {
        DurableRunSpool.noLinks(checkpointPath);
        Checkpoint checkpoint;
        try (var input = Files.newInputStream(checkpointPath, LinkOption.NOFOLLOW_LINKS)) {
            checkpoint = DurableDelivery.decode(input.readNBytes(DurableDelivery.MAX_ACK_BYTES + 1),
                    Checkpoint.class, DurableDelivery.MAX_ACK_BYTES);
        }
        Attempt attempt = checkpoint.attempt();
        if (!RunArtifacts.dispatchedDirectory(work, new RunId(attempt.runId()))
                .equals(checkpointPath.getParent())
                || !DurableRunSpool.ownersMatch(work, checkpointPath.getParent(),
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
        Terminal terminal = verifiedTerminal(checkpoint, spool);
        return checkpoint.terminalDigest() == null ? terminal : null;
    }

    private static Terminal verifiedTerminal(Checkpoint checkpoint, DurableRunSpool.Snapshot spool) throws IOException {
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
            // The initial writer owns the new spool. Later writers hold the delivery lock.
            writeAtomic(path, PENDING_CHECKPOINT, checkpoint, true);
        } catch (IOException unavailable) {
            throw new DeliveryStorageFailure(unavailable);
        }
    }

    private static void writeAtomic(Path path, String stagingName, Object record, boolean replace) throws IOException {
        Path temporary = path.resolveSibling(stagingName);
        if (!absent(temporary)) {
            DurableRunSpool.noLinks(temporary);
            if (!Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("DURABLE_DELIVERY_STAGING_UNAVAILABLE");
            }
            Files.delete(temporary);
        }
        FileAttribute<?>[] attributes = hasPosixAttributes(temporary)
                ? new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")) }
                : new FileAttribute<?>[0];
        boolean created = false;
        try {
            try (FileChannel output = FileChannel.open(temporary,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    attributes)) {
                created = true;
                var bytes = ByteBuffer.wrap(JSON.writeValueAsBytes(record));
                while (bytes.hasRemaining()) output.write(bytes);
                output.force(true);
            }
            if (replace) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } else {
                if (!absent(path)) throw new IOException("DURABLE_RETIREMENT_RECEIPT_EXISTS");
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            }
            created = false;
        } finally {
            if (created) Files.deleteIfExists(temporary);
        }
    }

    private static boolean hasPosixAttributes(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }
}
