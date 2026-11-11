package dev.faultora.runner;

import dev.faultora.model.security.ContentDigest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Append-only fault intents and confirmations. The caller owns the corresponding spool lock. */
final class FaultObligations {
    private static final int MAX_INTENTS = 1024;
    private static final int INTENT = 0x464f4931; // FOI1
    private static final int ROLLBACK = 0x464f5231; // FOR1
    private static final int HASH_BYTES = 32;
    private static final int PAYLOAD_BYTES = 2 * Integer.BYTES + 5 * Long.BYTES + HASH_BYTES;
    private static final int RECORD_BYTES = PAYLOAD_BYTES + HASH_BYTES;
    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern PROVIDER_DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");

    private final Path directory;
    private final Path ledger;
    private final UUID attemptId;
    private final int capacity;

    FaultObligations(Path runDirectory, String attemptId) {
        this(runDirectory, attemptId, MAX_INTENTS);
    }

    FaultObligations(Path runDirectory, String attemptId, int capacity) {
        this.directory = runDirectory.toAbsolutePath().normalize().resolve("fault-obligations");
        this.ledger = directory.resolve("state.bin");
        this.attemptId = uuid(attemptId);
        if (this.attemptId == null || capacity < 1 || capacity > MAX_INTENTS) {
            throw new IllegalArgumentException("Invalid fault obligation store configuration");
        }
        this.capacity = capacity;
    }

    record Entry(String activationId, String providerKeyDigest, long hardExpiryMs) { }

    private record Obligation(Entry intent, boolean rolledBack) { }

    private record State(int records, Map<String, Obligation> obligations) { }

    /** Returns only after the intent has been forced. The provider may activate after that point. */
    synchronized void record(Entry intent) throws IOException {
        UUID activationId = intent == null ? null : uuid(intent.activationId());
        if (activationId == null || intent.providerKeyDigest() == null || intent.hardExpiryMs() <= 0
                || !PROVIDER_DIGEST.matcher(intent.providerKeyDigest()).matches()) {
            throw new Failure("FAULT_OBLIGATIONS_INVALID_INTENT");
        }
        try (FileChannel file = open(true)) {
            if (file == null) {
                Files.createDirectory(directory);
                try (FileChannel created = FileChannel.open(ledger, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    append(created, INTENT, 0, activationId, intent);
                }
                return;
            }
            State state = read(file);
            if (state.obligations().containsKey(intent.activationId())) {
                throw new Failure("FAULT_OBLIGATIONS_DUPLICATE_INTENT");
            }
            if (state.obligations().size() == capacity) throw new Failure("FAULT_OBLIGATIONS_FULL");
            append(file, INTENT, state.records(), activationId, intent);
        } catch (IOException | SecurityException failed) {
            throw fixed(failed);
        }
    }

    /** Record provider confirmation. A repeated confirmation preserves the original record. */
    synchronized void rolledBack(String activationId) throws IOException {
        UUID id = uuid(activationId);
        if (id == null) throw new Failure("FAULT_OBLIGATIONS_UNKNOWN_INTENT");
        try (FileChannel file = open(true)) {
            if (file == null) throw new Failure("FAULT_OBLIGATIONS_UNKNOWN_INTENT");
            State state = read(file);
            Obligation obligation = state.obligations().get(activationId);
            if (obligation == null) throw new Failure("FAULT_OBLIGATIONS_UNKNOWN_INTENT");
            if (!obligation.rolledBack()) append(file, ROLLBACK, state.records(), id, obligation.intent());
        } catch (IOException | SecurityException failed) {
            throw fixed(failed);
        }
    }

    synchronized List<Entry> pending() throws IOException {
        try (FileChannel file = open(false)) {
            if (file == null) return List.of();
            return read(file).obligations().values().stream()
                    .filter(obligation -> !obligation.rolledBack()).map(Obligation::intent).toList();
        } catch (IOException | SecurityException failed) {
            throw fixed(failed);
        }
    }

    private FileChannel open(boolean write) throws IOException {
        DurableRunSpool.noLinks(directory.getParent());
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        DurableRunSpool.noLinks(ledger);
        if (!Files.isRegularFile(ledger, LinkOption.NOFOLLOW_LINKS)) throw corrupt();
        return write ? FileChannel.open(ledger, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                : FileChannel.open(ledger, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    private State read(FileChannel file) throws IOException {
        long size = file.size();
        if (size == 0 || size > (long) capacity * 2 * RECORD_BYTES || size % RECORD_BYTES != 0) throw corrupt();

        var contents = ByteBuffer.allocate((int) size);
        while (contents.hasRemaining()) {
            if (file.read(contents) < 0) throw corrupt();
        }
        contents.flip();

        int records = (int) size / RECORD_BYTES;
        Map<String, Obligation> obligations = new LinkedHashMap<>();
        for (int index = 0; index < records; index++) {
            var payload = contents.slice(contents.position(), PAYLOAD_BYTES);
            contents.position(contents.position() + PAYLOAD_BYTES);
            byte[] expected = new byte[HASH_BYTES];
            contents.get(expected);
            if (!MessageDigest.isEqual(checksum(payload.duplicate()), expected)) throw corrupt();

            int type = payload.getInt();
            if (payload.getInt() != index || !readUuid(payload).equals(attemptId)) throw corrupt();
            String activationId = readUuid(payload).toString();
            byte[] digest = new byte[HASH_BYTES];
            payload.get(digest);
            long expiry = payload.getLong();
            if (expiry <= 0) throw corrupt();
            var intent = new Entry(activationId, ContentDigest.SHA256_PREFIX + HEX.formatHex(digest), expiry);

            Obligation previous = obligations.get(activationId);
            if (type == INTENT && previous == null && obligations.size() < capacity) {
                obligations.put(activationId, new Obligation(intent, false));
            } else if (type == ROLLBACK && previous != null && !previous.rolledBack() && previous.intent().equals(intent)) {
                obligations.put(activationId, new Obligation(intent, true));
            } else {
                throw corrupt();
            }
        }
        return new State(records, obligations);
    }

    private void append(FileChannel file, int type, int sequence, UUID activationId, Entry intent) throws IOException {
        var record = ByteBuffer.allocate(RECORD_BYTES).putInt(type).putInt(sequence);
        putUuid(record, attemptId);
        putUuid(record, activationId);
        record.put(HEX.parseHex(intent.providerKeyDigest(), ContentDigest.SHA256_PREFIX.length(),
                intent.providerKeyDigest().length())).putLong(intent.hardExpiryMs());
        record.put(checksum(record.duplicate().flip())).flip();
        while (record.hasRemaining()) file.write(record);
        file.force(true);
    }

    private static UUID readUuid(ByteBuffer data) {
        return new UUID(data.getLong(), data.getLong());
    }

    private static void putUuid(ByteBuffer data, UUID id) {
        data.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
    }

    private static UUID uuid(String value) {
        if (value == null || value.length() != 36) return null;
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equals(value) ? id : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static byte[] checksum(ByteBuffer data) {
        try {
            var sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(data);
            return sha256.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static Failure corrupt() { return new Failure("FAULT_OBLIGATIONS_CORRUPT"); }

    private static IOException fixed(Exception failure) {
        return failure instanceof Failure known ? known : new Failure("FAULT_OBLIGATIONS_UNAVAILABLE");
    }

    private static final class Failure extends IOException {
        private Failure(String code) { super(code); }
    }
}
