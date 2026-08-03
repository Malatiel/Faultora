package dev.faultora.runner.protocol;

import dev.faultora.model.security.ContentDigest;

import java.util.List;
import java.util.Map;

/**
 * One run, as everything a runner needs to produce it.
 * <p>
 * Not the compiled plan. A {@code PlanNode} cannot be read back from JSON
 * today — the sealed hierarchy carries no type information — so putting the
 * plan on the wire would mean annotating nine node kinds and freezing all of
 * them as contract at 1.0. The inputs are a far smaller permanent surface, and
 * the same compiler over the same bytes with the same seed produces the same
 * plan. ADR-020 records the choice and how it was measured.
 * <p>
 * Two fields exist because a digest cannot cover them. {@link #inputs} are the
 * {@code --input} values, which bind into the expression context and are
 * nowhere in the scenario source. {@link #targetRedirects} are {@code --target}
 * values, which live in the connector configuration rather than in the policy.
 * <b>The digests prove the documents matched; these prove nothing and travel
 * explicitly</b>, because two dispatches with equal digests and different
 * inputs are different runs.
 *
 * @param runId           the run's identity, and what makes a replay
 *                        detectable
 * @param issuedAtEpochMs when the dispatcher issued this, for the replay window
 * @param nonce           a value unique to this dispatch
 * @param scenario        the scenario document, as written
 * @param documents       the descriptions it compiles against, <b>in the order
 *                        they were named</b> — the catalog digest is taken over
 *                        their digests in that order, so a different order is a
 *                        different digest
 * @param inputs          runtime input values, by declared name
 * @param targetRedirects target id to base URL, with the empty key holding the
 *                        protocol-scoped global redirect
 * @param seed            the run seed, which every derived value comes from
 * @param policy          the effective policy, signed
 * @param lease           how long this run may take before it must stop
 * @param scenarioDigest  what the dispatcher hashed the scenario to
 * @param catalogDigest   what the dispatcher hashed the documents to
 */
public record Dispatch(
        String runId,
        long issuedAtEpochMs,
        String nonce,
        String scenario,
        List<DispatchedDocument> documents,
        Map<String, String> inputs,
        Map<String, String> targetRedirects,
        long seed,
        SignedPolicy policy,
        Lease lease,
        String scenarioDigest,
        String catalogDigest
) {
    /** How far a dispatch's issue time may be from the runner's clock. */
    public static final long CLOCK_SKEW_ALLOWANCE_MS = 5 * 60 * 1000L;

    public Dispatch {
        documents = documents == null ? List.of() : List.copyOf(documents);
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        targetRedirects = targetRedirects == null ? Map.of() : Map.copyOf(targetRedirects);
    }

    /**
     * The digest a dispatch's {@link #catalogDigest} is, defined once.
     * <p>
     * Over each document's digest, in the order they were named, <b>always in
     * that list form — including for one document and for none</b>. The
     * temptation is to pass a single document's digest through unchanged, which
     * is what the catalog loader does for a catalog <em>version</em>; a
     * dispatcher that borrowed that rule while the runner used this one would
     * refuse correct single-document dispatches, and only those. ADR-020 named
     * that failure shape before it existed, so it is closed by there being one
     * function rather than two rules that agree today.
     * <p>
     * This is deliberately not the catalog version the loader computes. That
     * one is taken over imported catalogs' versions; this one over the bytes
     * that travelled. They answer different questions and are allowed to
     * differ — what is not allowed is two answers to <em>this</em> question.
     */
    public static String digestOfDocuments(List<DispatchedDocument> documents) {
        StringBuilder digests = new StringBuilder();
        if (documents != null) {
            for (DispatchedDocument document : documents) {
                digests.append(ContentDigest.sha256Uri(document.content())).append('\n');
            }
        }
        return ContentDigest.sha256Uri(digests.toString());
    }

    /**
     * Whether this dispatch was issued close enough to now to be acted on.
     * <p>
     * Both directions: a dispatch from the past may be a replay, and one from
     * the future is a clock nobody promised to synchronize. The allowance is
     * what makes the rule usable between two hosts rather than a way to refuse
     * everything an unsynchronized pair produces.
     */
    public boolean issuedNear(long nowEpochMs) {
        return Math.abs(nowEpochMs - issuedAtEpochMs) <= CLOCK_SKEW_ALLOWANCE_MS;
    }
}
