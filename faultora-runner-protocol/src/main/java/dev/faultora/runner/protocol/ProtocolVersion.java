package dev.faultora.runner.protocol;

import java.util.List;

/**
 * Which version of the runner protocol two sides agree to speak.
 * <p>
 * 1.0 freezes this protocol, and its real consumer — a controller that shards
 * a run across workers — arrives at 2.0 wanting things nobody has designed yet.
 * Negotiation is what lets that controller add a second version beside the
 * first instead of breaking every runner already deployed in somebody's private
 * network, where upgrading is not a thing anyone can do quickly.
 * <p>
 * So the rule is stated once, here, and it is the rule the code implements: a
 * runner advertises every version it can speak <b>in its own order of
 * preference</b>, the dispatcher takes the first of those it also speaks, and a
 * pair with nothing in common produces a <em>named refusal</em> rather than a
 * connection that fails somewhere later with a parse error.
 * <p>
 * The runner's preference wins rather than "the highest both speak" because the
 * runner is the side inside somebody's private network — the side that cannot
 * be upgraded on a whim, and the side that knows which of its versions it has
 * actually been run with. With one version in existence the two rules agree;
 * by the time there are two they will not, which is why this says which one it
 * is now rather than when it starts to matter.
 */
public final class ProtocolVersion {

    /** The version this build speaks. */
    public static final String CURRENT = "1";

    /** Every version this build can speak, newest first. */
    public static final List<String> SUPPORTED = List.of(CURRENT);

    private ProtocolVersion() {
    }

    /**
     * The version both sides can speak, or null when there is none.
     *
     * @param offered what the other side said it speaks, in its order of
     *                preference
     * @return the first offered version this build also speaks — the other
     *         side's preference wins, because the side being connected to is
     *         the one that can be upgraded on purpose
     */
    public static String negotiate(List<String> offered) {
        if (offered == null) {
            return null;
        }
        for (String candidate : offered) {
            if (SUPPORTED.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
