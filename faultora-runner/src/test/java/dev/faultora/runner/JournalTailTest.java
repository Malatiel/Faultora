package dev.faultora.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a file somebody else is still writing.
 * <p>
 * The defect this closes is quiet and permanent. The journal writes through a
 * buffer, so an event bigger than the buffer lands on disk in pieces; a reader
 * taking lines would take the first piece as a line, send it, and the far side
 * would move its position past it. The rest arrives as a second line, and the
 * one rule that repairs a bad delivery — re-send from an earlier position —
 * drops exactly the overlap that would have fixed it. A journal with a
 * fragment in the middle reads as a complete account of a run that did
 * something else.
 */
class JournalTailTest {

    @TempDir
    Path directory;

    private static void append(Path path, String text) throws IOException {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    void aLineIsALineOnlyOnceItsNewlineIsOnDisk() throws Exception {
        Path journal = directory.resolve("events.ndjson");
        append(journal, "{\"eventType\":\"RUN_STARTED\"}\n");
        // A large event, caught mid-flush: the writer has put half of it on
        // disk and has not reached the newline.
        append(journal, "{\"eventType\":\"EVIDENCE_CAPTURED\",\"body\":\"aaaa");

        JournalTail tail = new JournalTail(journal);
        JournalTail.Batch first = tail.next();

        assertThat(first.lines())
                .as("the half-written event is not a line yet")
                .containsExactly("{\"eventType\":\"RUN_STARTED\"}");
        tail.delivered(first, first.lines().size());

        // Nothing new is offered while the fragment is still a fragment.
        assertThat(tail.next().isEmpty())
                .as("and it is not offered again as something else").isTrue();

        append(journal, "aaaa\"}\n");
        JournalTail.Batch second = tail.next();

        assertThat(second.fromPosition()).isEqualTo(1);
        assertThat(second.lines())
                .as("it arrives once, whole, when it is whole")
                .containsExactly(
                        "{\"eventType\":\"EVIDENCE_CAPTURED\",\"body\":\"aaaaaaaa\"}");
    }

    @Test
    void whatHasBeenTakenIsNotTakenAgain() throws Exception {
        Path journal = directory.resolve("events.ndjson");
        append(journal, "one\ntwo\n");

        JournalTail tail = new JournalTail(journal);
        JournalTail.Batch first = tail.next();
        tail.delivered(first, 2);

        append(journal, "three\n");
        JournalTail.Batch second = tail.next();
        tail.delivered(second, 3);

        assertThat(first.lines()).containsExactly("one", "two");
        assertThat(second.fromPosition()).isEqualTo(2);
        assertThat(second.lines()).containsExactly("three");
        assertThat(tail.position()).isEqualTo(3);
        assertThat(tail.next().isEmpty())
                .as("a journal nobody has added to has nothing to send").isTrue();
    }

    @Test
    void aCharacterIsNeverCutInHalf() throws Exception {
        // The cut is made on a newline byte, which cannot occur inside a UTF-8
        // sequence — so a message in any script survives the boundary. Reading
        // a fixed number of bytes instead would have mangled it.
        Path journal = directory.resolve("events.ndjson");
        String message = "{\"message\":\"перевод не сошёлся — 元帳\"}";
        append(journal, message + "\n");
        append(journal, "{\"message\":\"ещё не дописано");

        JournalTail.Batch batch = new JournalTail(journal).next();

        assertThat(batch.lines()).containsExactly(message);
    }

    @Test
    void aJournalThatDoesNotExistYetIsNotAFailure() throws Exception {
        // The run may not have written anything when the first heartbeat goes
        // out, and a heartbeat that threw would stop renewing the lease — which
        // would end the run for no reason at all.
        JournalTail tail = new JournalTail(directory.resolve("not-yet.ndjson"));

        assertThat(tail.next().lines()).isEqualTo(List.of());
        assertThat(tail.position()).isZero();
    }
}
