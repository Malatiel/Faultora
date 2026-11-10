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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(first.atEnd()).isFalse();
        tail.delivered(first, first.lines().size());

        // Nothing new is offered while the fragment is still a fragment.
        assertThat(tail.next().isEmpty())
                .as("and it is not offered again as something else").isTrue();

        append(journal, "aaaa\"}\n");
        JournalTail.Batch second = tail.next();

        assertThat(second.fromPosition()).isEqualTo(1);
        assertThat(second.atEnd()).isTrue();
        assertThat(second.lines())
                .as("it arrives once, whole, when it is whole")
                .containsExactly(
                        "{\"eventType\":\"EVIDENCE_CAPTURED\",\"body\":\"aaaaaaaa\"}");
    }

    @Test
    void whatHasBeenTakenIsNotTakenAgain() throws Exception {
        Path journal = directory.resolve("events.ndjson");
        append(journal, "{\"n\":1}\n{\"n\":2}\n");

        JournalTail tail = new JournalTail(journal);
        JournalTail.Batch first = tail.next();
        tail.delivered(first, 2);

        append(journal, "{\"n\":3}\n");
        JournalTail.Batch second = tail.next();
        tail.delivered(second, 3);

        assertThat(first.lines()).containsExactly("{\"n\":1}", "{\"n\":2}");
        assertThat(second.fromPosition()).isEqualTo(2);
        assertThat(second.lines()).containsExactly("{\"n\":3}");
        assertThat(tail.position()).isEqualTo(3);
        assertThat(tail.next().isEmpty())
                .as("a journal nobody has added to has nothing to send").isTrue();
    }

    @Test
    void aPartialAcknowledgmentConsumesOnlyItsUtf8Lines() throws Exception {
        Path journal = directory.resolve("events.ndjson");
        String first = "{\"text\":\"строка — 元帳\"}\r";
        String second = "{\"text\":\"ещё одна\"}";
        append(journal, first + "\n" + second + "\n");

        JournalTail tail = new JournalTail(journal);
        tail.delivered(tail.next(), 1);

        assertThat(tail.next().fromPosition()).isEqualTo(1);
        assertThat(tail.next().lines()).containsExactly(second);
        tail.delivered(tail.next(), 2);
        assertThat(tail.next().isEmpty()).isTrue();
    }

    @Test
    void noProgressAndOutOfRangeAcknowledgmentsLeaveTheCursorInPlace() throws Exception {
        Path journal = directory.resolve("events.ndjson");
        append(journal, "{\"n\":1}\n{\"n\":2}\n");

        JournalTail tail = new JournalTail(journal);
        tail.delivered(tail.next(), 1);
        JournalTail.Batch pending = tail.next();
        tail.delivered(pending, 1);
        assertThatThrownBy(() -> tail.delivered(pending, 0)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> tail.delivered(pending, 3)).isInstanceOf(IOException.class);

        assertThat(tail.next().fromPosition()).isEqualTo(1);
        assertThat(tail.next().lines()).containsExactly("{\"n\":2}");
    }

    @Test
    void aCompleteMalformedUtf8LineStopsDeliveryAtTheValidPrefix() throws Exception {
        Path journal = directory.resolve("events.ndjson");
        append(journal, "{\"n\":1}\n{\"text\":\"");
        Files.write(journal, new byte[]{(byte) 0xc3, 0x28}, StandardOpenOption.APPEND);
        append(journal, "\"}\n{\"n\":3}\n");

        assertOnlyPrefixIsOffered(journal);
    }

    @Test
    void extraJsonValuesOnOneLineStopDeliveryAtTheValidPrefix() throws Exception {
        Path journal = directory.resolve("events.ndjson");
        append(journal, "{\"n\":1}\n{\"n\":2} {}\n{\"n\":3}\n");

        assertOnlyPrefixIsOffered(journal);
    }

    @Test
    void aBlankLineStopsDeliveryAtTheValidPrefix() throws Exception {
        Path journal = directory.resolve("events.ndjson");
        append(journal, "{\"n\":1}\n\n{\"n\":3}\n");

        assertOnlyPrefixIsOffered(journal);
    }

    private static void assertOnlyPrefixIsOffered(Path journal) throws IOException {
        JournalTail tail = new JournalTail(journal);
        JournalTail.Batch prefix = tail.next();
        assertThat(prefix.lines()).containsExactly("{\"n\":1}");
        assertThat(prefix.atEnd()).isFalse();
        tail.delivered(prefix, 1);
        assertThat(tail.next().fromPosition()).isEqualTo(1);
        assertThat(tail.next().isEmpty()).isTrue();
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
