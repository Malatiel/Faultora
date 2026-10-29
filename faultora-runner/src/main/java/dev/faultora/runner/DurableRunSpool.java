package dev.faultora.runner;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.RunId;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/** Local committed evidence, independent of the live transport and without execution replay. */
public final class DurableRunSpool implements AutoCloseable {
    private static final byte[] MAGIC = "FTSP001\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int IDENTITY = 1;
    private static final int EVENT = 2;
    private static final int TERMINAL = 3;
    private static final int OVERHEAD = 49;
    private static final int COMMIT = 0x434d4954;
    private static final int TERMINAL_RESERVE = 8192;
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxNumberLength(64).build()).build())
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();

    public record Limits(long maxBytes, long maxEvents, int maxRecordBytes) {
        public Limits {
            if (maxBytes < 16384 || maxBytes > 64L * 1024 * 1024 || maxEvents < 1 || maxEvents > 100000
                    || maxRecordBytes < 1024 || maxRecordBytes > 1024 * 1024) {
                throw new IllegalArgumentException("Invalid spool limits");
            }
        }
        public static Limits defaults() { return new Limits(64L * 1024 * 1024, 100000, 1024 * 1024); }
    }

    public record Identity(String runnerId, String runId, String attemptId, String dispatchDigest, long leaseDeadlineEpochMs) {
        public Identity {
            new RunId(runId);
            if (!uuid(runnerId) || !uuid(attemptId)
                    || dispatchDigest == null || !dispatchDigest.matches("sha256:[0-9a-f]{64}")
                    || leaseDeadlineEpochMs <= 0) throw new IllegalArgumentException("Invalid spool identity");
        }
    }

    private static boolean uuid(String value) {
        return value != null && UUID.fromString(value).toString().equals(value);
    }

    /** Persistent local owner. A partial or conflicting owner file is refused, never replaced. */
    static String runnerIdentity(Path directory) throws IOException {
        noLinks(directory);
        Path owner = directory.resolve("runner-identity");
        try (FileChannel file = FileChannel.open(owner, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock held = file.lock()) {
            String id = UUID.randomUUID().toString();
            write(file, ByteBuffer.wrap(id.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            file.force(true);
            return id;
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            noLinks(owner);
            try (FileChannel file = FileChannel.open(owner, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                FileLock held;
                try { held = file.tryLock(0, Long.MAX_VALUE, true); }
                catch (OverlappingFileLockException busy) { throw failure("SPOOL_IN_USE"); }
                if (held == null) throw failure("SPOOL_IN_USE");
                try (held) {
                    ByteBuffer bytes = ByteBuffer.allocate(36);
                    if (file.size() != 36 || !read(file, bytes)) throw failure("SPOOL_OWNER_INVALID");
                    String id = new String(bytes.array(), java.nio.charset.StandardCharsets.US_ASCII);
                    try { if (uuid(id)) return id; }
                    catch (IllegalArgumentException invalid) { /* Refuse the existing owner. */ }
                    throw failure("SPOOL_OWNER_INVALID");
                }
            }
        } catch (IOException failed) { throw fixed(failed); }
    }

    private record Descriptor(int version, Identity identity, Limits limits) { }

    public record Terminal(RunResult.Status status, boolean cleanupConfirmed, long finalPosition,
                           boolean leaseExpired, String reasonCode) { }

    public record Snapshot(Identity identity, Limits limits, long events, Terminal terminal,
                           long verifiedBytes, boolean uncommittedTail) {
        public boolean recoveryRequired() { return terminal == null; }
    }

    @FunctionalInterface
    public interface EventReader { void accept(RunEvent event) throws IOException; }

    @FunctionalInterface
    interface Force { void force(FileChannel channel) throws IOException; }

    private final Path path;
    private final Identity identity;
    private final Limits limits;
    private final FileChannel channel;
    private final FileLock lock;
    private final Force force;
    private byte[] previous = new byte[32];
    private long nextSequence;
    private long committedBytes;
    private long events;
    private Terminal terminal;
    private boolean eventsClosed;
    private boolean broken;
    private boolean closed;

    public static DurableRunSpool create(Path path, Identity identity) throws IOException {
        return create(path, identity, Limits.defaults(), channel -> channel.force(true));
    }

    static DurableRunSpool create(Path path, Identity identity, Limits limits, Force force) throws IOException {
        return new DurableRunSpool(path, identity, limits, force);
    }

    private DurableRunSpool(Path path, Identity identity, Limits limits, Force force) throws IOException {
        this.path = path.toAbsolutePath().normalize();
        this.identity = java.util.Objects.requireNonNull(identity);
        this.limits = java.util.Objects.requireNonNull(limits);
        this.force = java.util.Objects.requireNonNull(force);
        noLinks(this.path.getParent());
        channel = FileChannel.open(this.path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw failure("SPOOL_IN_USE");
            lock = acquired;
            write(channel, ByteBuffer.wrap(MAGIC));
            append(IDENTITY, serialize(new Descriptor(1, identity, limits), limits.maxRecordBytes()));
        } catch (IOException | RuntimeException failed) {
            if (acquired != null) acquired.release();
            channel.close();
            throw fixed(failed);
        }
    }

    public Path path() { return path; }

    public synchronized Snapshot snapshot() {
        return new Snapshot(identity, limits, events, terminal, committedBytes, false);
    }

    /** Returns only after the complete record has been forced; only then may a mirror be written/sent. */
    public synchronized void appendEvent(RunEvent event) throws IOException {
        writable();
        if (eventsClosed || events >= limits.maxEvents()) {
            eventsClosed = true;
            throw failure("SPOOL_QUOTA_EXCEEDED");
        }
        if (event == null || !identity.runId().equals(event.runId().value())) throw failure("SPOOL_EVENT_OWNER_MISMATCH");
        byte[] payload = serialize(event, limits.maxRecordBytes());
        if (committedBytes + payload.length + OVERHEAD > limits.maxBytes() - TERMINAL_RESERVE) {
            eventsClosed = true;
            throw failure("SPOOL_QUOTA_EXCEEDED");
        }
        append(EVENT, payload);
        events++;
    }

    /** Immutable structural completion. A disk failure never sets the in-memory committed terminal. */
    public synchronized void commitTerminal(RunResult.Status status, boolean cleanupConfirmed, boolean leaseExpired) throws IOException {
        writable();
        Terminal value = new Terminal(java.util.Objects.requireNonNull(status), cleanupConfirmed && status != RunResult.Status.ERROR, events,
                leaseExpired, status == RunResult.Status.ERROR ? "INFRASTRUCTURE_ERROR" : "NONE");
        byte[] payload = serialize(value, 4096);
        append(TERMINAL, payload);
        terminal = value;
    }

    private void writable() throws IOException {
        if (closed || broken) throw failure("SPOOL_UNAVAILABLE");
        if (terminal != null) throw failure("SPOOL_TERMINAL_EXISTS");
    }

    private void append(int kind, byte[] payload) throws IOException {
        byte[] checksum = checksum(previous, kind, nextSequence, payload);
        ByteBuffer frame = ByteBuffer.allocate(payload.length + OVERHEAD - 4).putInt(payload.length)
                .put((byte) kind).putLong(nextSequence).put(payload).put(checksum);
        frame.flip();
        try {
            if (channel.position() + frame.remaining() + 4 > limits.maxBytes()) throw failure("SPOOL_QUOTA_EXCEEDED");
            write(channel, frame);
            force.force(channel);
            // A complete certificate proves payload force returned; even a full unforced frame is not a commit.
            ByteBuffer commit = ByteBuffer.allocate(4).putInt(COMMIT);
            commit.flip();
            write(channel, commit);
            force.force(channel);
            committedBytes = channel.position();
            previous = checksum;
            nextSequence++;
        } catch (IOException failed) {
            broken = true;
            throw fixed(failed);
        }
    }

    public static Snapshot recover(Path path) throws IOException { return recover(path, event -> { }); }

    /** Read-only inspection of a closed spool. It never resumes a writer or invokes business operations. */
    public static Snapshot recover(Path path, EventReader reader) throws IOException {
        noLinks(path);
        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            FileLock readLock;
            try { readLock = input.tryLock(0, Long.MAX_VALUE, true); }
            catch (OverlappingFileLockException inUse) { throw failure("SPOOL_IN_USE"); }
            if (readLock == null) throw failure("SPOOL_IN_USE");
            try (readLock) {
                // Do not expose a prefix to a caller before ruling out later corruption.
                scan(input, event -> { });
                input.position(0);
                return scan(input, reader);
            }
        } catch (IOException | IllegalArgumentException malformed) {
            throw fixed(malformed);
        }
    }

    private static Snapshot scan(FileChannel input, EventReader reader) throws IOException {
        if (input.size() > Limits.defaults().maxBytes()) throw failure("SPOOL_TOO_LARGE");
        ByteBuffer magic = ByteBuffer.allocate(MAGIC.length);
        if (!read(input, magic) || !Arrays.equals(magic.array(), MAGIC)) throw failure("SPOOL_CORRUPT");
        Descriptor descriptor = null;
        Terminal terminal = null;
        long sequence = 0;
        long events = 0;
        long verified = MAGIC.length;
        byte[] previous = new byte[32];
        boolean tail = false;
        while (input.position() < input.size()) {
            if (terminal != null) throw failure("SPOOL_CORRUPT");
            ByteBuffer length = ByteBuffer.allocate(4);
            if (!read(input, length)) { tail = true; break; }
            int size = length.flip().getInt();
            int maximum = descriptor == null ? Limits.defaults().maxRecordBytes() : descriptor.limits().maxRecordBytes();
            if (size < 1 || size > maximum) throw failure("SPOOL_CORRUPT");
            if (input.size() - input.position() < size + OVERHEAD - 4) { tail = true; break; }
            ByteBuffer frame = ByteBuffer.allocate(size + OVERHEAD - 4);
            if (!read(input, frame)) { tail = true; break; }
            frame.flip();
            int kind = frame.get();
            if (frame.getLong() != sequence) throw failure("SPOOL_CORRUPT");
            byte[] payload = new byte[size]; frame.get(payload);
            byte[] stored = new byte[32]; frame.get(stored);
            if (frame.getInt() != COMMIT) throw failure("SPOOL_CORRUPT");
            if (!MessageDigest.isEqual(stored, checksum(previous, kind, sequence, payload))) throw failure("SPOOL_CORRUPT");
            if (descriptor == null) {
                if (kind != IDENTITY) throw failure("SPOOL_CORRUPT");
                descriptor = decode(payload, Descriptor.class);
                if (descriptor.version() != 1 || descriptor.identity() == null || descriptor.limits() == null
                        || input.size() > descriptor.limits().maxBytes()) throw failure("SPOOL_CORRUPT");
            } else if (kind == EVENT) {
                RunEvent event = decode(payload, RunEvent.class);
                if (!descriptor.identity().runId().equals(event.runId().value()) || ++events > descriptor.limits().maxEvents()) {
                    throw failure("SPOOL_CORRUPT");
                }
                reader.accept(event);
            } else if (kind == TERMINAL) {
                terminal = decode(payload, Terminal.class);
                if (terminal.status() == null || terminal.finalPosition() != events
                        || (terminal.status() == RunResult.Status.ERROR && terminal.cleanupConfirmed())
                        || !("NONE".equals(terminal.reasonCode()) || "INFRASTRUCTURE_ERROR".equals(terminal.reasonCode()))) {
                    throw failure("SPOOL_CORRUPT");
                }
            } else throw failure("SPOOL_CORRUPT");
            sequence++;
            previous = stored;
            verified = input.position();
        }
        if (descriptor == null) throw failure("SPOOL_CORRUPT");
        return new Snapshot(descriptor.identity(), descriptor.limits(), events, terminal, verified, tail);
    }

    private static <T> T decode(byte[] payload, Class<T> type) throws IOException {
        try {
            String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
            if (type == RunEvent.class) {
                JSON.readTree(text); // The shared event reader follows these strict parser bounds.
                return type.cast(dev.faultora.engine.journal.RunEventReader.readLine(text)
                        .orElseThrow(() -> failure("SPOOL_CORRUPT")));
            }
            T value = JSON.readValue(text, type);
            if (value == null) throw failure("SPOOL_CORRUPT");
            return value;
        }
        catch (IOException | IllegalArgumentException malformed) { throw failure("SPOOL_CORRUPT"); }
    }

    private static byte[] serialize(Object value, int maximum) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            JSON.writeValue(new OutputStream() {
                private int size;
                private void reserve(int count) throws IOException {
                    if (count > maximum - size) throw failure("SPOOL_RECORD_TOO_LARGE");
                    size += count;
                }
                @Override public void write(int value) throws IOException { reserve(1); bytes.write(value); }
                @Override public void write(byte[] source, int offset, int count) throws IOException {
                    reserve(count); bytes.write(source, offset, count);
                }
            }, value);
        } catch (IOException malformed) {
            for (Throwable cause = malformed; cause != null; cause = cause.getCause()) {
                if (cause instanceof Failure known) throw known;
            }
            throw failure("SPOOL_SERIALIZATION_FAILED");
        }
        return bytes.toByteArray();
    }

    private static byte[] checksum(byte[] previous, int kind, long sequence, byte[] payload) {
        MessageDigest hash;
        try { hash = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
        hash.update(previous);
        hash.update(ByteBuffer.allocate(9).put((byte) kind).putLong(sequence).array());
        return hash.digest(payload);
    }

    private static void write(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }

    private static boolean read(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) if (channel.read(bytes) < 0) return false;
        return true;
    }

    private static void noLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path checked = absolute.getRoot();
        for (Path part : absolute) {
            checked = checked.resolve(part);
            BasicFileAttributes attributes = Files.readAttributes(checked, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw failure("SPOOL_PATH_REFUSED");
        }
    }

    static final class Failure extends IOException {
        private Failure(String code) { super(code); }
    }

    private static Failure failure(String code) { return new Failure(code); }
    private static IOException fixed(Exception failed) {
        return failed instanceof Failure known ? known : failure("SPOOL_IO_FAILED");
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); }
        finally { channel.close(); }
    }
}
