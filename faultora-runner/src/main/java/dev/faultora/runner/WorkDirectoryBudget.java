package dev.faultora.runner;

import dev.faultora.model.identifier.RunId;
import dev.faultora.runtime.RunArtifacts;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Reserves aggregate storage before claiming a new run directory. */
final class WorkDirectoryBudget {
    private static final long MIB = 1024L * 1024;
    private static final long METADATA_PER_RUN = MIB;
    private static final long SHARED_METADATA = MIB;
    private static final long FILESYSTEM_HEADROOM = MIB;
    private static final int MAX_ARTIFACT_ENTRIES = 64;
    private static final Pattern RUN_DIRECTORY = Pattern.compile("run-[0-9a-f]{64}", Pattern.CASE_INSENSITIVE);
    private static final Set<Path> ACTIVE_ADMISSIONS = ConcurrentHashMap.newKeySet();
    static final Limits DEFAULTS = new Limits(8 * 1024 * MIB, 64, 512,
            2 * DurableRunSpool.Limits.defaults().maxBytes() + METADATA_PER_RUN);

    record Limits(long maxBytes, int maxRuns, int maxOwnedRuns, long perRunBytes) {
        Limits {
            if (maxRuns < 1 || maxOwnedRuns < maxRuns || maxOwnedRuns > 4096
                    || perRunBytes < 1 || maxBytes <= SHARED_METADATA
                    || maxBytes - SHARED_METADATA < perRunBytes) {
                throw new IllegalArgumentException("Invalid work directory limits");
            }
        }
    }

    @FunctionalInterface
    interface UsableSpace {
        long bytes(Path directory) throws IOException;
    }

    static final class Failure extends IOException {
        private Failure(String diagnostic) { super(diagnostic); }
    }

    private final Path work;
    private final Limits limits;
    private final UsableSpace usableSpace;

    WorkDirectoryBudget(Path workingDirectory) {
        this(workingDirectory, DEFAULTS, path -> Files.getFileStore(path).getUsableSpace());
    }

    WorkDirectoryBudget(Path workingDirectory, Limits limits, UsableSpace usableSpace) {
        this.work = workingDirectory.toAbsolutePath().normalize();
        this.limits = limits;
        this.usableSpace = usableSpace;
    }

    /** A claimed directory reserves space even if its spool has yet to open or was interrupted. */
    Optional<RunArtifacts> claim(RunId runId, BooleanSupplier reserveDispatch) throws Failure, RunArtifacts.AlreadyOwned {
        // A second channel can release this process's file lock when it closes on some filesystems.
        if (!ACTIVE_ADMISSIONS.add(work)) throw busy();
        try {
            prepareDirectory();
            Path lockPath = work.resolve("storage-budget.lock");
            var existing = attributes(lockPath);
            if (existing != null && !existing.isRegularFile()) throw invalid();
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) throw busy();
                if (channel.size() != 0) throw invalid();
                verifyAvailable();
                return reserveDispatch.getAsBoolean()
                        ? Optional.of(RunArtifacts.claimDispatched(work, runId)) : Optional.empty();
            }
        } catch (OverlappingFileLockException held) {
            throw busy();
        } catch (Failure | RunArtifacts.AlreadyOwned known) {
            throw known;
        } catch (IOException | DirectoryIteratorException | SecurityException unavailable) {
            throw new Failure("WORK_DIRECTORY_STORAGE_UNAVAILABLE");
        } finally {
            ACTIVE_ADMISSIONS.remove(work);
        }
    }

    private void prepareDirectory() throws IOException {
        Path existing = work;
        while (Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        DurableRunSpool.noLinks(existing);
        Files.createDirectories(work);
        DurableRunSpool.noLinks(work);
    }

    private void verifyAvailable() throws IOException {
        long charged = 0;
        long shared = 0;
        long unwritten = limits.perRunBytes();
        int runs = 0;
        int ownedRuns = 0;
        int entries = 0;
        try (var root = Files.newDirectoryStream(work)) {
            for (Path entry : root) {
                if (++entries > limits.maxOwnedRuns() + MAX_ARTIFACT_ENTRIES) throw full();
                var current = attributes(entry);
                if (current == null) continue;
                String name = entry.getFileName().toString();
                if (RUN_DIRECTORY.matcher(name).matches()) {
                    if (!current.isDirectory()) throw invalid();
                    // Reserve a future ownership slot before the attempt can add a terminal receipt.
                    if (++ownedRuns >= limits.maxOwnedRuns()) throw full();
                    DurableRunSpool.noLinks(entry);
                    boolean retired = DurableRunDelivery.isRetired(work, entry);
                    if (!retired && ++runs >= limits.maxRuns()) throw full();
                    long stored = storedBytes(entry, true);
                    long reserved = retired ? stored : Math.max(stored, limits.perRunBytes());
                    charged = add(charged, reserved);
                    unwritten = add(unwritten, reserved - stored);
                } else if (name.equalsIgnoreCase("dispatch-replay")) {
                    if (!current.isDirectory()) throw invalid();
                    DurableRunSpool.noLinks(entry);
                    shared = add(shared, storedBytes(entry, false));
                } else if (current.isRegularFile()) {
                    shared = add(shared, current.size());
                } else if (!current.isDirectory()) {
                    throw invalid();
                }
            }
        }
        long sharedCharge = Math.max(shared, SHARED_METADATA);
        charged = add(charged, sharedCharge);
        if (limits.perRunBytes() > limits.maxBytes() - charged) throw full();
        unwritten = add(unwritten, sharedCharge - shared);
        long available = usableSpace.bytes(work);
        if (available < FILESYSTEM_HEADROOM || unwritten > available - FILESYSTEM_HEADROOM) throw full();
    }

    private long storedBytes(Path directory, boolean mayContainFaults) throws IOException {
        long bytes = 0;
        int count = 0;
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (++count > MAX_ARTIFACT_ENTRIES) throw full();
                var current = attributes(child);
                if (current == null) continue;
                if (current.isRegularFile()) {
                    bytes = add(bytes, current.size());
                } else if (mayContainFaults && current.isDirectory()
                        && child.getFileName().toString().equalsIgnoreCase("fault-obligations")) {
                    DurableRunSpool.noLinks(child);
                    bytes = add(bytes, storedBytes(child, false));
                } else {
                    throw invalid();
                }
            }
        }
        return bytes;
    }

    private long add(long total, long bytes) throws Failure {
        if (bytes > limits.maxBytes() - total) throw full();
        return total + bytes;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException removed) {
            // Health and delivery checkpoint staging files may be atomically replaced during inventory.
            return null;
        }
    }

    private static Failure full() { return new Failure("WORK_DIRECTORY_STORAGE_FULL"); }
    private static Failure busy() { return new Failure("WORK_DIRECTORY_STORAGE_BUSY"); }
    private static Failure invalid() { return new Failure("WORK_DIRECTORY_STORAGE_INVALID"); }
}
