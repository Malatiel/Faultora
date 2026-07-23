package dev.faultora.spi.result;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationResultTest {

    @Test
    void responseDataIsDefensivelyCopied() {
        byte[] body = "safe".getBytes(StandardCharsets.UTF_8);
        List<String> values = new ArrayList<>(List.of("application/json"));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("content-type", values);

        OperationResult result = OperationResult.success(200, headers, body, 10, Map.of());
        body[0] = 'X';
        values.set(0, "text/plain");
        byte[] returnedBody = result.body();
        returnedBody[1] = 'X';

        assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("safe");
        assertThat(result.headers()).containsEntry(
                "content-type", List.of("application/json"));
        assertThatThrownBy(() -> result.headers().put("x", List.of("y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
