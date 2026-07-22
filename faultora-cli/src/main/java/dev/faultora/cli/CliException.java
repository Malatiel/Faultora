package dev.faultora.cli;

/**
 * CLI-specific exception with exit code.
 */
public class CliException extends RuntimeException {

    private final int exitCode;

    public CliException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public CliException(String message, int exitCode, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
