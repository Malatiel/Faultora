package dev.faultora.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.runner.protocol.Dispatch;
import dev.faultora.runner.protocol.DurableDelivery;
import dev.faultora.runner.protocol.DurableDelivery.Ack;
import dev.faultora.runner.protocol.DurableDelivery.Attempt;
import dev.faultora.runner.protocol.DurableDelivery.Progress;
import dev.faultora.runner.protocol.DurableDelivery.Terminal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** Process-restart receiver for qualification only. Each bounded state replacement precedes its ACK. */
final class QualificationDeliveryStore implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final long MAX_RAW_BYTES = 64L * 1024 * 1024;
    private static final int MAX_STORED_BYTES = (int) (2 * MAX_RAW_BYTES + 1024 * 1024);
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;

    record Peer(String runnerName, String certificateDigest) {
        Peer {
            if (runnerName == null || runnerName.isBlank() || !DurableDelivery.isDigest(certificateDigest)) {
                throw new IllegalArgumentException("Invalid delivery peer");
            }
        }
    }

    private record State(Peer peer, String offerDigest, Attempt attempt, List<String> events,
                         long bytes, Terminal terminal) {
        private State {
            if (peer == null || !DurableDelivery.isDigest(offerDigest) || events == null
                    || events.size() > DurableDelivery.MAX_ATTEMPT_EVENTS || bytes < 0 || bytes > MAX_RAW_BYTES
                    || (attempt == null && (!events.isEmpty() || terminal != null))
                    || (terminal != null && (!terminal.attempt().equals(attempt)
                            || terminal.finalPosition() != events.size()))) {
                throw new IllegalArgumentException("Invalid receiver state");
            }
            events = List.copyOf(events);
        }
    }

    private record StoredState(State state, String digest) { }

    QualificationDeliveryStore(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Path ancestor = this.directory.getRoot();
        for (Path part : this.directory) {
            ancestor = ancestor.resolve(part);
            if (Files.notExists(ancestor, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(ancestor);
            var attributes = Files.readAttributes(ancestor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isOther() || attributes.isSymbolicLink()) {
                throw new IOException("DURABLE_RECEIVER_STORAGE_REFUSED");
            }
        }
        channel = FileChannel.open(this.directory.resolve("receiver.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IOException("DURABLE_RECEIVER_STORAGE_IN_USE");
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw new IOException("DURABLE_RECEIVER_STORAGE_UNAVAILABLE");
        }
    }

    synchronized void authorize(Peer peer, Dispatch dispatch) throws IOException {
        requireOpen();
        Path file = file(dispatch.runId());
        // Once an offer is committed, a lost work response cannot establish whether execution started.
        if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) throw conflict();
        String digest = ContentDigest.sha256Uri(JSON.writeValueAsBytes(dispatch));
        save(file, new State(peer, digest, null, List.of(), 0, null));
    }

    synchronized Ack progress(Peer peer, Progress progress) throws IOException {
        requireOpen();
        Attempt attempt = progress.attempt();
        Path file = file(attempt.runId());
        State state = authorized(file, peer, attempt);
        if (progress.fromPosition() > state.events().size()
                || (state.terminal() != null && progress.endPosition() > state.events().size())) throw conflict();
        int offset = Math.toIntExact(progress.fromPosition());
        var newEvents = new ArrayList<String>();
        long bytes = state.bytes();
        for (String line : progress.eventLines()) {
            if (!DurableDelivery.eventBelongsTo(line, attempt)) throw conflict();
            if (offset < state.events().size()) {
                if (!state.events().get(offset).equals(line)) throw conflict();
            } else {
                bytes += line.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > MAX_RAW_BYTES) throw conflict();
                newEvents.add(line);
            }
            offset++;
        }
        if (!newEvents.isEmpty()) {
            var events = new ArrayList<>(state.events());
            events.addAll(newEvents);
            save(file, new State(peer, state.offerDigest(), attempt, events, bytes, state.terminal()));
        }
        // Even when the receiver is ahead, acknowledge only the submitted prefix.
        return new Ack(attempt, progress.endPosition(), null);
    }

    synchronized Ack terminal(Peer peer, Terminal terminal) throws IOException {
        requireOpen();
        Attempt attempt = terminal.attempt();
        Path file = file(attempt.runId());
        State state = authorized(file, peer, attempt);
        if (state.events().size() != terminal.finalPosition()
                || (state.terminal() != null && !state.terminal().equals(terminal))) throw conflict();
        if (state.terminal() == null) {
            save(file, new State(peer, state.offerDigest(), attempt, state.events(), state.bytes(), terminal));
        }
        return new Ack(attempt, terminal.finalPosition(), terminal.terminalDigest());
    }

    synchronized Terminal terminalOf(String runId) throws IOException {
        requireOpen();
        return read(file(runId)).terminal();
    }

    synchronized List<String> journalOf(String runId) throws IOException {
        requireOpen();
        return read(file(runId)).events();
    }

    private void requireOpen() throws IOException {
        if (!lock.isValid()) throw new IOException("DURABLE_RECEIVER_STORAGE_CLOSED");
    }

    private State authorized(Path file, Peer peer, Attempt attempt) throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) throw conflict();
        State state = read(file);
        if (!peer.equals(state.peer()) || (state.attempt() != null && !attempt.equals(state.attempt()))) throw conflict();
        return state;
    }

    private Path file(String runId) {
        return directory.resolve(ContentDigest.sha256Uri(new RunId(runId).value()).substring(7) + ".json");
    }

    private static State read(Path path) throws IOException {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            StoredState stored = DurableDelivery.decode(input.readNBytes(MAX_STORED_BYTES + 1),
                    StoredState.class, MAX_STORED_BYTES);
            if (stored.state() == null || !digest(stored.state()).equals(stored.digest())) {
                throw new IOException("DURABLE_RECEIVER_STORAGE_CORRUPT");
            }
            return stored.state();
        }
    }

    private static void save(Path path, State state) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), "receiver-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(JSON.writeValueAsBytes(new StoredState(state, digest(state))));
                if (bytes.remaining() > MAX_STORED_BYTES) throw new IOException("DURABLE_RECEIVER_STORAGE_LIMIT");
                while (bytes.hasRemaining()) output.write(bytes);
                output.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String digest(State state) throws IOException {
        return ContentDigest.sha256Uri(JSON.writeValueAsBytes(state));
    }

    static final class Conflict extends IOException {
        private Conflict() { super("DURABLE_RECEIVER_CONFLICT"); }
    }

    private static Conflict conflict() { return new Conflict(); }

    @Override public synchronized void close() throws IOException {
        try { lock.release(); }
        finally { channel.close(); }
    }
}
