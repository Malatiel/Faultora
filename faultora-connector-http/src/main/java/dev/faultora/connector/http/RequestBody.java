package dev.faultora.connector.http;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Encode one repeatable UTF-8 body without constructing an unbounded intermediate value. */
final class RequestBody {
    static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestBody() { }

    static int limit(Object configured) {
        if (configured == null) {
            return MAX_BYTES;
        }
        if (configured instanceof Number number) {
            try {
                long value = new BigDecimal(number.toString()).longValueExact();
                if (value > 0 && value <= MAX_BYTES) {
                    return (int) value;
                }
            } catch (ArithmeticException | NumberFormatException invalidNumber) {
                throw new InvalidLimit();
            }
        }
        throw new InvalidLimit();
    }

    static byte[] encode(Object body, int limit) throws IOException {
        if (body == null) {
            return new byte[0];
        }
        BoundedOutput output = new BoundedOutput(limit);
        try (var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            if (body instanceof String text) {
                for (int offset = 0; offset < text.length(); offset += 4096) {
                    writer.write(text, offset, Math.min(4096, text.length() - offset));
                }
            } else {
                MAPPER.writeValue(writer, body);
            }
        } catch (IOException | RuntimeException failure) {
            // Jackson may wrap an output failure while adding its property path.
            if (output.exceeded) {
                throw new TooLarge(limit);
            }
            throw failure;
        }
        if (output.exceeded) {
            throw new TooLarge(limit);
        }
        return Arrays.copyOf(output.bytes, output.size);
    }

    static final class InvalidLimit extends IllegalArgumentException {
        InvalidLimit() {
            super("maxRequestBytes must be an integer between 1 and " + MAX_BYTES);
        }
    }

    static final class TooLarge extends IOException {
        private final int limit;
        TooLarge(int limit) {
            super("Request exceeds maximum payload size of " + limit + " bytes");
            this.limit = limit;
        }
        int limit() { return limit; }
    }

    private static final class BoundedOutput extends OutputStream {
        private final int limit;
        private byte[] bytes;
        private int size;
        private boolean exceeded;

        BoundedOutput(int limit) {
            if (limit <= 0 || limit > MAX_BYTES) {
                throw new InvalidLimit();
            }
            this.limit = limit;
            this.bytes = new byte[Math.min(8192, limit)];
        }

        private void reserve(int length) throws TooLarge {
            if (exceeded || length > limit - size) {
                exceeded = true;
                throw new TooLarge(limit);
            }
            int required = size + length;
            if (required > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.min(limit, Math.max(required, bytes.length * 2)));
            }
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            bytes[size++] = (byte) value;
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, source.length);
            reserve(length);
            System.arraycopy(source, offset, bytes, size, length);
            size += length;
        }
    }
}
