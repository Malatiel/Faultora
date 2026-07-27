package dev.faultora.spec.model;

/**
 * Hard limits of the scenario language.
 * <p>
 * Every limit exists to keep a scenario's worst-case traffic knowable before
 * execution starts, so the execution policy's request budget stays a real
 * control. Validation and plan compilation read them from here rather than
 * defining their own, so a document cannot pass one gate and fail the other on
 * a different number.
 */
public final class ScenarioLimits {

    /** Maximum attempts of a single retrying step, preventing retry storms. */
    public static final int MAX_RETRY_ATTEMPTS = 10;

    /** Maximum iterations of a single repeat group. */
    public static final int MAX_REPEAT_ITERATIONS = 100;

    /** Maximum polls of a single eventually group. */
    public static final int MAX_POLL_ATTEMPTS = 100;

    /** Poll interval used when an eventually step does not declare one. */
    public static final long DEFAULT_POLL_INTERVAL_MS = 1000;

    private ScenarioLimits() {
    }
}
