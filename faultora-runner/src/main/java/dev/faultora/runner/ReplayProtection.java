package dev.faultora.runner;

import dev.faultora.model.security.ContentDigest;
import dev.faultora.runner.protocol.Dispatch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** Persists dispatch admission before target work, independently of the delivery protocol. */
final class ReplayProtection {
    private static final int MAX_ENTRIES = 4096;
    private static final int MAGIC = 0x46525031; // FRP1
    private static final int HASH_BYTES = 32;
    private static final int HEADER_BYTES = Integer.BYTES + Long.BYTES + Integer.BYTES;
    private static final int ENTRY_BYTES = Long.BYTES + 2 * HASH_BYTES;
    private static final long WINDOW_MS = Dispatch.CLOCK_SKEW_ALLOWANCE_MS;
    private static final HexFormat HEX = HexFormat.of();

    private final Path work;
    private final Path directory;
    private final int capacity;

    ReplayProtection(Path workingDirectory) {
        this(workingDirectory, MAX_ENTRIES);
    }

    ReplayProtection(Path workingDirectory, int capacity) {
        if (capacity < 1 || capacity > MAX_ENTRIES) throw new IllegalArgumentException("Invalid replay capacity");
        this.work = workingDirectory.toAbsolutePath().normalize();
        this.directory = work.resolve("dispatch-replay");
        this.capacity = capacity;
    }

    private record Entry(long expiresAt, String runDigest, String nonceDigest) { }

    private record State(long highWater, List<Entry> entries) { }

    /**
     * The caller verifies the dispatch before admission. A positive result means the
     * claim was forced and atomically installed. Refused claims never evict live entries.
     */
    synchronized boolean claim(Dispatch dispatch, long nowEpochMs) throws IOException {
        try {
            prepareDirectory();
            Path lockPath = directory.resolve("state.lock");
            if (!Files.notExists(lockPath, LinkOption.NOFOLLOW_LINKS)) requireRegular(lockPath);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) throw new Failure("REPLAY_PROTECTION_BUSY");
                if (channel.size() != 0) throw corrupt();
                return reserve(dispatch, nowEpochMs);
            }
        } catch (Failure refused) {
            throw refused;
        } catch (OverlappingFileLockException busy) {
            throw new Failure("REPLAY_PROTECTION_BUSY");
        } catch (IOException | SecurityException unavailable) {
            throw new Failure("REPLAY_PROTECTION_UNAVAILABLE");
        }
    }

    private void prepareDirectory() throws IOException {
        Path existing = work;
        while (Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        DurableRunSpool.noLinks(existing);
        Files.createDirectories(directory);
        DurableRunSpool.noLinks(directory);
    }

    private boolean reserve(Dispatch dispatch, long nowEpochMs) throws IOException {
        Path snapshot = directory.resolve("state.bin");
        State state = read(snapshot);
        long now = Math.max(nowEpochMs, state.highWater());
        if (!dispatch.issuedNear(now)) return false;

        String run = ContentDigest.sha256Hex(dispatch.runId());
        String nonce = ContentDigest.sha256Hex(dispatch.nonce());
        var live = state.entries();
        live.removeIf(entry -> entry.expiresAt() < now);
        for (Entry entry : live) {
            if (entry.runDigest().equals(run) || entry.nonceDigest().equals(nonce)) return false;
        }
        if (live.size() == capacity) throw new Failure("REPLAY_PROTECTION_FULL");
        live.add(new Entry(plus(dispatch.issuedAtEpochMs(), WINDOW_MS), run, nonce));
        save(snapshot, new State(now, live));
        return true;
    }

    private State read(Path snapshot) throws IOException {
        if (Files.notExists(snapshot, LinkOption.NOFOLLOW_LINKS)) {
            return new State(Long.MIN_VALUE, new ArrayList<>());
        }
        requireRegular(snapshot);
        int maximum = HEADER_BYTES + capacity * ENTRY_BYTES + HASH_BYTES;
        byte[] bytes;
        try (var input = Files.newInputStream(snapshot, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(maximum + 1);
        }
        if (bytes.length < HEADER_BYTES + ENTRY_BYTES + HASH_BYTES || bytes.length > maximum) throw corrupt();
        int payloadSize = bytes.length - HASH_BYTES;
        var data = ByteBuffer.wrap(bytes, 0, payloadSize);
        if (!MessageDigest.isEqual(checksum(data.duplicate()), Arrays.copyOfRange(bytes, payloadSize, bytes.length))
                || data.getInt() != MAGIC) {
            throw corrupt();
        }
        long highWater = data.getLong();
        int count = data.getInt();
        if (count < 1 || count > capacity || data.remaining() != count * ENTRY_BYTES) throw corrupt();

        var entries = new ArrayList<Entry>(count);
        var runs = new HashSet<String>();
        var nonces = new HashSet<String>();
        long latestExpiry = plus(highWater, 2 * WINDOW_MS);
        for (int index = 0; index < count; index++) {
            long expiry = data.getLong();
            String run = readDigest(data);
            String nonce = readDigest(data);
            if (expiry < highWater || expiry > latestExpiry || !runs.add(run) || !nonces.add(nonce)) throw corrupt();
            entries.add(new Entry(expiry, run, nonce));
        }
        return new State(highWater, entries);
    }

    private void save(Path snapshot, State state) throws IOException {
        int payloadSize = HEADER_BYTES + state.entries().size() * ENTRY_BYTES;
        var data = ByteBuffer.allocate(payloadSize + HASH_BYTES);
        data.putInt(MAGIC).putLong(state.highWater()).putInt(state.entries().size());
        for (Entry entry : state.entries()) {
            data.putLong(entry.expiresAt()).put(HEX.parseHex(entry.runDigest())).put(HEX.parseHex(entry.nonceDigest()));
        }
        data.put(checksum(data.duplicate().flip())).flip();

        // One fixed staging name under the store lock bounds leftovers after repeated process deaths.
        Path staging = directory.resolve("state.tmp");
        if (!Files.notExists(staging, LinkOption.NOFOLLOW_LINKS)) {
            requireRegular(staging);
            Files.delete(staging);
        }
        try {
            try (FileChannel output = FileChannel.open(staging, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                while (data.hasRemaining()) output.write(data);
                output.force(true);
            }
            Files.move(staging, snapshot, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static void requireRegular(Path path) throws IOException {
        DurableRunSpool.noLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw corrupt();
    }

    private static String readDigest(ByteBuffer data) {
        byte[] hash = new byte[HASH_BYTES];
        data.get(hash);
        return HEX.formatHex(hash);
    }

    private static byte[] checksum(ByteBuffer data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(data);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static long plus(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static Failure corrupt() { return new Failure("REPLAY_PROTECTION_CORRUPT"); }

    private static final class Failure extends IOException {
        private Failure(String code) { super(code); }
    }
}
