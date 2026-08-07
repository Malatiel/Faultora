package dev.faultora.runner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The finished part of a journal that is still being written.
 * <p>
 * Two things make this more than a file read, and both come from the journal
 * being read by one thread while another appends to it.
 * <p>
 * <b>A line is only a line once its newline is on disk.</b> The journal writes
 * through a buffer, so an event larger than the buffer reaches the file in
 * pieces, and a reader arriving between them sees a truncated line. Reading
 * lines would take that half as a whole one — and because delivery is by
 * position, the far side would move past it and the fragment would be in its
 * journal for good. A re-send cannot repair it: the overlap rule drops exactly
 * the lines that would. So this cuts at the last newline and leaves the rest
 * for the next call, where it will be complete.
 * <p>
 * <b>It reads from where it stopped.</b> Re-reading the whole file every few
 * hundred milliseconds is quadratic in the length of the run, which a long run
 * is precisely what this exists for.
 * <p>
 * Bytes rather than characters throughout, and that is what makes the cut safe:
 * a newline byte cannot occur inside a UTF-8 sequence, so the boundary never
 * lands in the middle of a character.
 */
final class JournalTail {

    private final Path path;
    private long byteOffset;
    private long position;

    JournalTail(Path path) {
        this.path = path;
    }

    /**
     * Lines that are whole, and where they sit in the journal.
     *
     * @param fromPosition the position of the first line, as the far side counts
     * @param lines        the lines themselves, none of them a fragment
     * @param bytes        how much of the file they account for
     */
    record Batch(long fromPosition, List<String> lines, long bytes) {

        boolean isEmpty() {
            return lines.isEmpty();
        }
    }

    /** Whatever has been finished since the last batch was taken. */
    Batch next() throws IOException {
        if (!Files.exists(path)) {
            return new Batch(position, List.of(), 0);
        }
        byte[] unread;
        try (SeekableByteChannel channel =
                     Files.newByteChannel(path, StandardOpenOption.READ)) {
            long available = channel.size() - byteOffset;
            if (available <= 0) {
                return new Batch(position, List.of(), 0);
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(available, Integer.MAX_VALUE));
            channel.position(byteOffset);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // Reading until the buffer is full or the file ends.
            }
            unread = new byte[buffer.position()];
            buffer.flip();
            buffer.get(unread);
        }

        int lastNewline = -1;
        for (int index = unread.length - 1; index >= 0; index--) {
            if (unread[index] == '\n') {
                lastNewline = index;
                break;
            }
        }
        if (lastNewline < 0) {
            // Something is being written and has not finished. It is not lost;
            // it is not a line yet.
            return new Batch(position, List.of(), 0);
        }

        String whole = new String(unread, 0, lastNewline + 1, StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>();
        for (String line : whole.split("\n", -1)) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return new Batch(position, lines, lastNewline + 1L);
    }

    /**
     * Move past a batch the far side has taken.
     * <p>
     * The position becomes the one the far side reported rather than the one
     * that was sent. Those agree with anything implementing the protocol; where
     * they would not, a batch starting past what the far side holds is a hole,
     * and its rule is to refuse it — loudly, rather than leaving a journal
     * quietly missing its middle.
     */
    void delivered(Batch batch, long acknowledged) {
        byteOffset += batch.bytes();
        position = acknowledged;
    }

    /** How many lines have been delivered. */
    long position() {
        return position;
    }
}
