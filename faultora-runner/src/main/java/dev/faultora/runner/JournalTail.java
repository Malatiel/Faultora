package dev.faultora.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** The valid, complete lines after the last acknowledged journal position. */
final class JournalTail {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    private final Path path;
    private long byteOffset;
    private long position;

    JournalTail(Path path) {
        this.path = path;
    }

    /** Each byte end includes its newline. A fragment or malformed line leaves atEnd false. */
    record Batch(long fromPosition, List<String> lines, List<Long> byteEnds, boolean atEnd) {

        boolean isEmpty() {
            return lines.isEmpty();
        }
    }

    /** A writer can flush half a line. Keep it pending until its LF reaches disk. */
    Batch next() throws IOException {
        ByteBuffer unread;
        try (SeekableByteChannel channel = Files.newByteChannel(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long available = channel.size() - byteOffset;
            if (available <= 0) {
                return new Batch(position, List.of(), List.of(), available == 0);
            }
            if (available > Integer.MAX_VALUE) {
                throw new IOException("Journal mirror is too large");
            }
            unread = ByteBuffer.allocate((int) available);
            channel.position(byteOffset);
            while (unread.hasRemaining() && channel.read(unread) > 0) {
                // Reading until the buffer is full or the file ends.
            }
        } catch (NoSuchFileException notYetWritten) {
            return new Batch(position, List.of(), List.of(), false);
        }

        List<String> lines = new ArrayList<>();
        List<Long> byteEnds = new ArrayList<>();
        var utf8 = StandardCharsets.UTF_8.newDecoder();
        int start = 0;
        for (int end = 0; end < unread.position(); end++) {
            if (unread.array()[end] != '\n') continue;
            try {
                String line = utf8.decode(ByteBuffer.wrap(unread.array(), start, end - start)).toString();
                var event = JSON.readTree(line);
                if (event == null || !event.isObject()) break;
                lines.add(line);
                byteEnds.add(byteOffset + end + 1);
                start = end + 1;
            } catch (CharacterCodingException | JsonProcessingException corrupt) {
                break;
            }
        }
        return new Batch(position, List.copyOf(lines), List.copyOf(byteEnds),
                !unread.hasRemaining() && start == unread.position());
    }

    /** Advance only by the acknowledged prefix and return the suffix for retry. */
    Batch delivered(Batch batch, long acknowledged) throws IOException {
        if (batch.fromPosition() != position || acknowledged < position
                || acknowledged > position + batch.lines().size()) {
            throw new IOException("Invalid journal progress acknowledgment");
        }
        if (acknowledged == position) return batch;

        int consumed = (int) (acknowledged - position);
        byteOffset = batch.byteEnds().get(consumed - 1);
        position = acknowledged;
        return new Batch(position, batch.lines().subList(consumed, batch.lines().size()),
                batch.byteEnds().subList(consumed, batch.byteEnds().size()), batch.atEnd());
    }

    /** How many lines have been delivered. */
    long position() {
        return position;
    }
}
