package dev.faultora.connector.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.spi.context.ConnectorContext;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A validated record snapshot; the limit counts key, value, header names and values. */
record RecordPayload(byte[] key, byte[] value, String keyText,
                     List<Header> headers, Map<String, String> headerText) {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static RecordPayload encode(Map<String, Object> inputs, ConnectorContext context) {
        Budget budget = new Budget(limit(context));
        Text key = inputs.get(EventPublisher.KEY) == null ? null : budget.text(inputs.get(EventPublisher.KEY));
        byte[] value = budget.value(inputs.get(EventPublisher.BODY));
        Map<String, EncodedHeader> normalized = new LinkedHashMap<>();
        if (inputs.get(EventPublisher.HEADERS) instanceof Map<?, ?> declared) {
            for (var entry : declared.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                Text name = budget.previewText(entry.getKey());
                EncodedHeader previous = normalized.get(name.text());
                if (previous != null) {
                    budget.remaining += previous.size();
                }
                budget.take(name.bytes().length);
                Text content = budget.text(entry.getValue());
                normalized.put(name.text(), new EncodedHeader(name, content));
            }
        }
        Map<String, String> headerText = new LinkedHashMap<>();
        List<Header> headers = normalized.values().stream().map(header -> {
            headerText.put(header.name().text(), header.value().text());
            return (Header) new RecordHeader(header.name().text(), header.value().bytes());
        }).toList();
        return new RecordPayload(key == null ? null : key.bytes(), value,
                key == null ? null : key.text(), headers, headerText);
    }

    private record Text(String text, byte[] bytes) { }
    private record EncodedHeader(Text name, Text value) {
        int size() { return name.bytes().length + value.bytes().length; }
    }

    private static int limit(ConnectorContext context) {
        Object configured = context == null || context.config() == null
                ? null : context.config().get(KafkaConnector.MAX_RECORD_BYTES);
        if (configured == null) {
            return MAX_BYTES;
        }
        if (configured instanceof Number number) {
            try {
                long value = new BigDecimal(number.toString()).longValueExact();
                if (value > 0 && value <= MAX_BYTES) {
                    return (int) value;
                }
            } catch (ArithmeticException | NumberFormatException invalid) {
                throw new InvalidLimit();
            }
        }
        throw new InvalidLimit();
    }

    static final class InvalidLimit extends IllegalArgumentException {
        InvalidLimit() {
            super("maxRecordBytes must be an integer between 1 and " + MAX_BYTES);
        }
    }

    static final class TooLarge extends RuntimeException {
        private final int limit;
        TooLarge(int limit) {
            super("Kafka record exceeds maximum payload size of " + limit + " bytes");
            this.limit = limit;
        }
        int limit() { return limit; }
    }

    private static final class Budget {
        private final int limit;
        private int remaining;

        Budget(int limit) { this.limit = limit; this.remaining = limit; }

        void take(int bytes) {
            if (bytes > remaining) { throw new TooLarge(limit); }
            remaining -= bytes;
        }

        Text previewText(Object input) {
            byte[] encoded = encodeText(asText(input), limit);
            return new Text(new String(encoded, StandardCharsets.UTF_8), encoded);
        }

        Text text(Object input) {
            byte[] encoded = encodeText(asText(input), remaining);
            take(encoded.length);
            return new Text(new String(encoded, StandardCharsets.UTF_8), encoded);
        }

        private CharSequence asText(Object input) {
            return input instanceof CharSequence text ? text : input.toString();
        }

        private byte[] encodeText(CharSequence text, int available) {
            BoundedOutput output = new BoundedOutput(available, limit);
            try (var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                char[] chunk = new char[4096];
                int length = text.length();
                for (int offset = 0; offset < length;) {
                    int count = Math.min(chunk.length, length - offset);
                    for (int index = 0; index < count; index++) { chunk[index] = text.charAt(offset + index); }
                    writer.write(chunk, 0, count);
                    offset += count;
                }
            } catch (IOException failure) {
                throw new IllegalArgumentException("Message text cannot be encoded", failure);
            }
            if (output.exceeded) { throw new TooLarge(limit); }
            return output.bytes();
        }

        byte[] value(Object input) {
            if (input == null) { return null; }
            if (input instanceof byte[] bytes) {
                take(bytes.length);
                return bytes.clone();
            }
            if (input instanceof CharSequence text) {
                byte[] bytes = encodeText(text, remaining);
                take(bytes.length);
                return bytes;
            }
            BoundedOutput output = new BoundedOutput(remaining, limit);
            try {
                // Keep Kafka's existing UTF-8 JSON representation, including binary base64.
                MAPPER.writeValue(output, input);
            } catch (IOException | RuntimeException failure) {
                if (output.exceeded) { throw new TooLarge(limit); }
                throw new IllegalArgumentException("Message payload cannot be serialized", failure);
            }
            if (output.exceeded) { throw new TooLarge(limit); }
            byte[] encoded = output.bytes();
            take(encoded.length);
            return encoded;
        }
    }

    private static final class BoundedOutput extends OutputStream {
        private final int available;
        private final int limit;
        private byte[] buffer;
        private int size;
        private boolean exceeded;

        BoundedOutput(int available, int limit) {
            this.available = available;
            this.limit = limit;
            this.buffer = new byte[Math.min(64, available)];
        }

        private void reserve(int length) {
            if (exceeded || length > available - size) {
                exceeded = true;
                throw new TooLarge(limit);
            }
            int required = size + length;
            if (required > buffer.length) {
                buffer = Arrays.copyOf(buffer, Math.min(available, Math.max(required, buffer.length * 2)));
            }
        }

        @Override public void write(int value) {
            reserve(1);
            buffer[size++] = (byte) value;
        }

        @Override public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            reserve(length);
            System.arraycopy(bytes, offset, buffer, size, length);
            size += length;
        }

        byte[] bytes() { return Arrays.copyOf(buffer, size); }
    }
}
