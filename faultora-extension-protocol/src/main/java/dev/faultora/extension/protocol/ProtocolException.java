package dev.faultora.extension.protocol;

import java.io.IOException;

/** Fixed wire failures: never retain a peer payload or an underlying I/O message. */
public final class ProtocolException extends IOException {
    public enum Code {
        MALFORMED_FRAME, TRUNCATED_FRAME, MESSAGE_TOO_LARGE, MALFORMED_MESSAGE,
        INCOMPATIBLE_VERSION, SESSION_MISMATCH, ID_MISMATCH, PEER_CLOSED
    }

    private final Code code;

    public ProtocolException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
