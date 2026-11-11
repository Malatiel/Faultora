package dev.faultora.engine.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

    private static final int MAX_EVENTS = 100_000;
    private static final long MAX_EVENT_BYTES = 64L * 1024 * 1024;
    private static final int CLEANUP_EVENTS = 256;
    private static final long CLEANUP_BYTES = 64 * 1024;
    private static final int TERMINAL_EVENTS = 2;
    private static final long TERMINAL_BYTES = 16 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final Path journalPath;
    private final List<RunEvent> events;
    private final Writer writer;
    private final boolean autoFlush;
    private final int ordinaryEvents;
    private final long ordinaryBytes;
    private long eventBytes;
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
        this(journalPath, autoFlush, writer, MAX_EVENTS - CLEANUP_EVENTS - TERMINAL_EVENTS,
                MAX_EVENT_BYTES - CLEANUP_BYTES - TERMINAL_BYTES);
    }

    RunJournal(Path journalPath, boolean autoFlush, Writer writer, int ordinaryEvents, long ordinaryBytes) {
        this.journalPath = journalPath;
        this.events = new ArrayList<>();
        this.autoFlush = autoFlush;
        this.writer = java.util.Objects.requireNonNull(writer, "writer");
        this.ordinaryEvents = ordinaryEvents;
        this.ordinaryBytes = ordinaryBytes;
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
        String json = MAPPER.writeValueAsString(event);
        if (!boundedBySpool()) reserve(event, json.getBytes(StandardCharsets.UTF_8).length + 1L);
        events.add(event);
        writer.write(json);
        writer.write('\n');
        if (autoFlush) {
            writer.flush();
        }
    }

    /** A runner mirror receives only events admitted by its durable spool. */
    protected boolean boundedBySpool() { return false; }

    private void reserve(RunEvent event, long bytes) throws EventBudgetExceeded {
        int limit = ordinaryEvents;
        long byteLimit = ordinaryBytes;
        if (event instanceof RunEvent.RunFailed || event instanceof RunEvent.RunCompleted) {
            limit += CLEANUP_EVENTS + TERMINAL_EVENTS;
            byteLimit += CLEANUP_BYTES + TERMINAL_BYTES;
        } else if (event instanceof RunEvent.CleanupStarted || event instanceof RunEvent.CleanupCompleted
                || event instanceof RunEvent.FaultRolledBack) {
            limit += CLEANUP_EVENTS;
            byteLimit += CLEANUP_BYTES;
        }
        if (events.size() >= limit || bytes > byteLimit - eventBytes) {
            throw new EventBudgetExceeded("RUN_EVENT_BUDGET_EXCEEDED");
        }
        eventBytes += bytes;
    }

    public static final class EventBudgetExceeded extends IOException {
        public EventBudgetExceeded(String message) { super(message); }
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
        if (closed) {
            return;
        }
        closed = true;
        try {
            flush();
        } finally {
            writer.close();
        }
    }

    /**
     * Get the journal file path.
     */
    public Path path() {
        return journalPath;
    }
}
