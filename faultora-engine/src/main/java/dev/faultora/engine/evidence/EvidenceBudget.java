package dev.faultora.engine.evidence;

import dev.faultora.spi.context.ConnectorContext;

import java.util.concurrent.atomic.AtomicLong;

/** Shared admission limit for content kept by one execution. */
public final class EvidenceBudget {
    public static final String MAX_RUN_EVIDENCE_BYTES = "maxRunEvidenceBytes";
    public static final long DEFAULT_BYTES = 64L * 1024 * 1024;

    private final AtomicLong remaining;

    public EvidenceBudget(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Evidence budget cannot be negative");
        remaining = new AtomicLong(bytes);
    }

    /** A sealed connector setting can narrow the engine ceiling. */
    public static EvidenceBudget forContext(ConnectorContext context) {
        Object configured = context.config() == null ? null : context.config().get(MAX_RUN_EVIDENCE_BYTES);
        if (configured == null) return new EvidenceBudget(DEFAULT_BYTES);
        if (!(configured instanceof Integer || configured instanceof Long)) {
            throw new IllegalArgumentException("maxRunEvidenceBytes must be an integer");
        }
        return new EvidenceBudget(Math.min(DEFAULT_BYTES, ((Number) configured).longValue()));
    }

    public boolean admit(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Evidence size cannot be negative");
        return remaining.getAndUpdate(available -> bytes <= available ? available - bytes : available) >= bytes;
    }

    long remaining() { return remaining.get(); }
}
