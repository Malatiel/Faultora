package dev.faultora.extension.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static dev.faultora.extension.protocol.ProtocolException.Code.*;

/**
 * Length-prefixed strict UTF-8 JSON objects. This codec owns neither stream.
 * Discard the channel after any failure; it does not drain or resynchronize.
 * Blocking I/O deadlines and process supervision belong to the caller.
 */
public final class JsonFrameCodec {
    public static final int MAX_FRAME_BYTES = 1048576;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(64).maxNumberLength(64).build()).build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final int maximum;

    public JsonFrameCodec(int maximum) {
        if (maximum < 1024 || maximum > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Frame ceiling must be in 1024..1048576");
        }
        this.maximum = maximum;
    }

    /** Null is a clean frame-boundary EOF, not a successful protocol response. */
    public JsonNode read(InputStream input) throws IOException {
        try {
            byte[] prefix = new byte[4];
            int first = input.read();
            if (first < 0) return null;
            prefix[0] = (byte) first;
            readFully(input, prefix, 1, 3);
            long length = Integer.toUnsignedLong(ByteBuffer.wrap(prefix).getInt());
            check(length != 0, MALFORMED_FRAME);
            check(length <= maximum, MESSAGE_TOO_LARGE);
            byte[] payload = new byte[(int) length];
            readFully(input, payload, 0, payload.length);
            return parse(payload);
        } catch (ProtocolException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new ProtocolException(PEER_CLOSED);
        }
    }

    /** Build a bounded complete payload before sending any prefix or body bytes. */
    public void write(OutputStream output, JsonNode object) throws IOException {
        check(object != null && object.isObject(), MALFORMED_MESSAGE);
        validateTree(object, 0);
        var payload = new LimitedBuffer(maximum);
        try {
            JSON.writeValue(payload, object);
        } catch (IOException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ProtocolException known) throw known;
            }
            throw new ProtocolException(MALFORMED_MESSAGE);
        }
        try {
            output.write(ByteBuffer.allocate(4).putInt(payload.size).array());
            output.write(payload.bytes, 0, payload.size);
        } catch (IOException failure) {
            throw new ProtocolException(PEER_CLOSED);
        }
    }

    private static JsonNode parse(byte[] payload) throws ProtocolException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
            check(!text.startsWith("\ufeff"), MALFORMED_MESSAGE);
            try (var tokens = JSON.createParser(text)) {
                while (tokens.nextToken() != null) {
                    check(!tokens.currentToken().isNumeric() || tokens.getText().length() <= 64,
                            MALFORMED_MESSAGE);
                }
            }
            try (var tokens = JSON.createParser(text)) {
                JsonNode value = JSON.readTree(tokens);
                check(value != null && value.isObject() && tokens.nextToken() == null, MALFORMED_MESSAGE);
                return value;
            }
        } catch (ProtocolException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new ProtocolException(MALFORMED_MESSAGE);
        }
    }

    private static void validateTree(JsonNode value, int depth) throws ProtocolException {
        check(value != null, MALFORMED_MESSAGE);
        if (value.isContainerNode()) {
            check(depth < 64, MALFORMED_MESSAGE);
            for (JsonNode child : value) validateTree(child, depth + 1);
        } else if (value.isNumber()) {
            check(value.asText().length() <= 64 && (!(value.isDouble() || value.isFloat())
                    || Double.isFinite(value.doubleValue())), MALFORMED_MESSAGE);
        } else {
            check(value.isTextual() || value.isBoolean() || value.isNull(), MALFORMED_MESSAGE);
        }
    }

    private static void readFully(InputStream input, byte[] target, int offset, int count) throws IOException {
        int end = offset + count;
        while (offset < end) {
            int read = input.read(target, offset, end - offset);
            if (read < 0) throw new ProtocolException(TRUNCATED_FRAME);
            if (read == 0) {
                int next = input.read();
                if (next < 0) throw new ProtocolException(TRUNCATED_FRAME);
                target[offset++] = (byte) next;
            } else offset += read;
        }
    }

    static void check(boolean condition, ProtocolException.Code code) throws ProtocolException {
        if (!condition) throw new ProtocolException(code);
    }

    private static final class LimitedBuffer extends OutputStream {
        private final int maximum;
        private byte[] bytes;
        private int size;

        private LimitedBuffer(int maximum) {
            this.maximum = maximum;
            bytes = new byte[Math.min(256, maximum)];
        }

        private void reserve(int count) throws ProtocolException {
            check(count <= maximum - size, MESSAGE_TOO_LARGE);
            if (size + count > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.min(maximum, Math.max(size + count, bytes.length * 2)));
            }
        }

        @Override public void write(int value) throws IOException {
            reserve(1);
            bytes[size++] = (byte) value;
        }

        @Override public void write(byte[] values, int offset, int count) throws IOException {
            reserve(count);
            System.arraycopy(values, offset, bytes, size, count);
            size += count;
        }
    }
}
