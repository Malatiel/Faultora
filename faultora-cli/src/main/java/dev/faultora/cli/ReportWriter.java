package dev.faultora.cli;

import dev.faultora.model.events.RunEvent;
import dev.faultora.spi.contract.ReportRenderer;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the requested reports from the run journal.
 * <p>
 * Reports are derived artifacts: they are produced from the recorded events
 * after the run, never from live engine state, so a report can always be
 * regenerated from the journal alone.
 */
final class ReportWriter {

    private ReportWriter() {
    }

    static void writeAll(
            List<String> formats,
            Map<String, ReportRenderer> renderers,
            List<RunEvent> events,
            Path outputDir
    ) throws IOException {
        for (String format : formats) {
            ReportRenderer renderer = renderers.get(format);
            if ("console".equals(format)) {
                renderer.render(events, new PrintWriter(System.out, true));
                continue;
            }
            Path reportPath = outputDir.resolve("report" + extensionOf(format));
            try (Writer writer = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8)) {
                renderer.render(events, writer);
            }
            System.out.println("Report written: " + reportPath);
        }
    }

    private static String extensionOf(String format) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "json" -> ".json";
            case "junit" -> ".xml";
            case "html" -> ".html";
            default -> "." + format;
        };
    }
}
