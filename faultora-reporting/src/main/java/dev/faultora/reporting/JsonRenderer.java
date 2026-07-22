package dev.faultora.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.faultora.model.events.RunEvent;
import dev.faultora.spi.contract.ReportRenderer;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Renders run results as formatted JSON.
 */
public class JsonRenderer implements ReportRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Override
    public String format() {
        return "json";
    }

    @Override
    public void render(List<RunEvent> events, Writer output) throws IOException {
        output.write(MAPPER.writeValueAsString(events));
        output.write('\n');
    }
}
