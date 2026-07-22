package dev.faultora.spi.contract;

/**
 * Thrown when a secret cannot be resolved.
 */
public class SecretResolutionException extends RuntimeException {
    private final String handleId;

    public SecretResolutionException(String handleId, String message) {
        super(message);
        this.handleId = handleId;
    }

    public SecretResolutionException(String handleId, String message, Throwable cause) {
        super(message, cause);
        this.handleId = handleId;
    }

    public String handleId() {
        return handleId;
    }
}
