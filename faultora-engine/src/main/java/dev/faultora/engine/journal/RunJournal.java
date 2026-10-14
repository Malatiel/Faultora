package dev.faultora.engine.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.events.RunEvent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only NDJSON run journal.
 * Each event is written as one JSON line to events.ndjson.
 * Thread-safe for concurrent writes.
 */
public class RunJournal implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path journalPath;
    private final List<RunEvent> events;
    private final Writer writer;
    private final boolean autoFlush;
    private boolean closed;

    /**
     * Create a run journal that writes to the given path.
     *
     * @param journalPath path to the NDJSON file
     * @param autoFlush   flush after every write
     * @throws IOException if the file cannot be created
     */
    public RunJournal(Path journalPath, boolean autoFlush) throws IOException {
        this(journalPath, autoFlush, openWriter(journalPath));
    }

    /** Use an owned writer; closing the journal closes it even after a flush failure. */
    public RunJournal(Path journalPath, boolean autoFlush, Writer writer) {
        this.journalPath = journalPath;
        this.events = new ArrayList<>();
        this.autoFlush = autoFlush;
        this.writer = java.util.Objects.requireNonNull(writer, "writer");
    }

    private static Writer openWriter(Path journalPath) throws IOException {
        Files.createDirectories(journalPath.getParent());
        return new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(journalPath.toFile(), true),
                        StandardCharsets.UTF_8
                )
        );
    }

    /**
     * Append an event to the journal.
     *
     * @param event the run event
     * @throws IOException if writing fails
     */
    public synchronized void append(RunEvent event) throws IOException {
        events.add(event);
        String json = MAPPER.writeValueAsString(event);
        writer.write(json);
        writer.write('\n');
        if (autoFlush) {
            writer.flush();
        }
    }

    /**
     * Get all events in memory (for reporting).
     * Includes events whose writes failed; this snapshot does not prove durability.
     */
    public synchronized List<RunEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Flush pending writes.
     *
     * @throws IOException if flushing fails
     */
    public synchronized void flush() throws IOException {
        writer.flush();
    }

    /**
     * Close the journal.
     *
     * @throws IOException if closing fails
     */
    @Override
    public synchronized void close() throws IOException {
        if (closed) { return; }
        closed = true;
        try { flush(); }
        finally { writer.close(); }
    }

    /**
     * Get the journal file path.
     */
    public Path path() {
        return journalPath;
    }
}
