package dev.faultora.runtime;

import java.util.List;

/**
 * A policy asked for something nothing here enforces.
 * <p>
 * {@code ExtensionPolicy} has described process isolation, a memory ceiling, a
 * network allowlist and a set of permitted secret handles since it was written,
 * and until M6-02 lands them nothing reads any of the four. A field nobody
 * reads is worse than a missing one: it reads as configured safety, and an
 * operator who set {@code requireProcessIsolation} would have had a run
 * proceed in-process while believing otherwise.
 * <p>
 * So asking for one of them stops the run and says which. That is the honest
 * behaviour of a control that does not exist yet — and it disappears on its own
 * as each is implemented, rather than needing somebody to remember to remove a
 * warning.
 */
public final class UnenforceablePolicy extends RuntimeException {

    private final transient List<String> requests;

    UnenforceablePolicy(List<String> requests) {
        super("this build does not enforce " + String.join(", ", requests)
                + ". A run must not proceed as though it did; see ADR-023 for "
                + "which of these arrives when.");
        this.requests = List.copyOf(requests);
    }

    /** What was asked for that nothing implements. */
    public List<String> requests() {
        return requests;
    }
}
