package dev.faultora.runner.protocol;

import java.util.List;

/**
 * What the runner has learned, from a known position in its journal.
 * <p>
 * The events are the run journal's own — the runner streams what it already
 * writes, and the dispatcher appends it to a journal of the same shape. A
 * second event schema for the same facts would be a second thing to keep true,
 * and the journal's format is committed anyway: it is written to disk and read
 * by the report renderers whether or not a runner exists.
 * <p>
 * {@link #fromPosition} is what makes delivery survivable. The runner keeps
 * writing while disconnected and re-sends from the last position acknowledged,
 * so events are delivered at least once and the position makes that idempotent.
 * A runner that stopped cleanly and lost what it had already found would fail
 * the gate from the other side.
 *
 * @param runId        which run these belong to
 * @param fromPosition the journal position of the first line here, counted from
 *                     zero
 * @param eventLines   journal lines, as written
 */
public record Progress(String runId, long fromPosition, List<String> eventLines) {

    public Progress {
        eventLines = eventLines == null ? List.of() : List.copyOf(eventLines);
    }

    /** The position the next batch starts at. */
    public long nextPosition() {
        return fromPosition + eventLines.size();
    }
}
