package dev.faultora.spi.contract;

import dev.faultora.model.events.RunEvent;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Renders run results into various output formats.
 * Renderers consume the normalized run result and evidence index.
 * No renderer may alter the execution outcome.
 */
public interface ReportRenderer {

    /**
     * The format this renderer produces (e.g. "console", "json", "junit", "html").
     */
    String format();

    /**
     * Render a report from the run events.
     *
     * @param events    ordered run events
     * @param output    writer to render to
     * @throws IOException if rendering fails
     */
    void render(List<RunEvent> events, Writer output) throws IOException;
}
