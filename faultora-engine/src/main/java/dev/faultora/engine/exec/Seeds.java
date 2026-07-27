package dev.faultora.engine.exec;

/**
 * Derives per-purpose seeds from the run seed.
 * <p>
 * Everything random in a run — retry jitter, generated values — comes from
 * here, so a run replays exactly from its recorded seed. Derivation uses
 * {@link String#hashCode()}, whose result the language specification fixes, so
 * the same seed yields the same values on any JVM.
 */
public final class Seeds {

    private Seeds() {
    }

    /**
     * A seed for one purpose within a run.
     *
     * @param runSeed the run's seed
     * @param parts   what distinguishes this purpose, such as a node ID and an
     *                input name
     */
    public static long derive(long runSeed, String... parts) {
        long derived = runSeed;
        for (String part : parts) {
            derived = derived * 31L + (part == null ? 0 : part.hashCode());
        }
        return derived;
    }
}
