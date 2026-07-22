package dev.faultora.connector.http;

/**
 * Thrown when a request violates the destination policy.
 * Indicates SSRF protection or allowlist enforcement blocked the request.
 */
public class DestinationPolicyViolation extends RuntimeException {

    public DestinationPolicyViolation(String message) {
        super(message);
    }
}
