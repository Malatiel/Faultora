package dev.faultora.runner;

import dev.faultora.engine.journal.RunJournal;
import dev.faultora.model.events.RunEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** The ordinary journal is a delivery mirror of the already committed spool prefix. */
final class SpoolingRunJournal extends RunJournal {
    private final DurableRunSpool spool;
    private final AtomicBoolean cancellation;
    private boolean mirrorFailed;

    SpoolingRunJournal(Path path, DurableRunSpool spool, AtomicBoolean cancellation) throws IOException {
        super(path, true);
        this.spool = spool;
        this.cancellation = cancellation;
    }

    SpoolingRunJournal(Path path, DurableRunSpool spool, AtomicBoolean cancellation, java.io.Writer writer) {
        super(path, true, writer);
        this.spool = spool;
        this.cancellation = cancellation;
    }

    @Override public synchronized void append(RunEvent event) throws IOException {
        try {
            spool.appendEvent(event);
            if (mirrorFailed) throw new IOException("JOURNAL_MIRROR_UNAVAILABLE");
            try { super.append(event); }
            catch (IOException failed) { mirrorFailed = true; throw failed; }
        } catch (IOException unavailable) {
            cancellation.set(true);
            throw new IOException("MANDATORY_SPOOL_OR_JOURNAL_FAILED");
        }
    }
}
