package dev.faultora.net;

/**
 * Thrown when a destination the run was asked to reach is refused by policy.
 * <p>
 * Every connector faces the same refusal — a base URL, a bootstrap server, a
 * connection string — so they raise the same exception rather than one apiece.
 */
public class DestinationPolicyViolation extends RuntimeException {

    public DestinationPolicyViolation(String message) {
        super(message);
    }
}
