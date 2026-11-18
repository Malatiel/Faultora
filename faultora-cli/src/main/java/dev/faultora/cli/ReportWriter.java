package dev.faultora.cli;

import dev.faultora.model.events.RunEvent;
import dev.faultora.runtime.IsolatedRenderer;
import dev.faultora.spi.contract.ReportRenderer;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
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
 * Reports are derived artifacts, produced from the run's event snapshot.
 * Normally it matches the durable journal; after a write failure it also
 * retains events that could not reach disk, including the infrastructure
 * failure. Such partial reports cannot be regenerated from the damaged file.
 */
final class ReportWriter {

    private ReportWriter() {
    }

    static void writeIsolated(
            IsolatedRenderer.Prepared renderer, List<RunEvent> events, Path outputDir, PrintWriter out
    ) throws IOException {
        StringWriter text = new StringWriter();
        renderer.render(events, text);
        Path reportPath = outputDir.resolve("report." + renderer.format() + ".txt");
        Path staged = Files.createTempFile(outputDir, ".report-", ".tmp");
        try {
            Files.writeString(staged, text.toString(), StandardCharsets.UTF_8);
            Files.move(staged, reportPath);
        } finally {
            Files.deleteIfExists(staged);
        }
        out.println("Report written: " + reportPath);
    }

    static void writeAll(
            List<String> formats,
            Map<String, ReportRenderer> renderers,
            List<RunEvent> events,
            Path outputDir,
            PrintWriter out
    ) throws IOException {
        for (String format : formats) {
            ReportRenderer renderer = renderers.get(format);
            if ("console".equals(format)) {
                renderer.render(events, out);
                continue;
            }
            Path reportPath = outputDir.resolve("report" + extensionOf(format));
            try (Writer writer = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8)) {
                renderer.render(events, writer);
            }
            out.println("Report written: " + reportPath);
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
