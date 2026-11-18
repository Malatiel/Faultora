package dev.faultora.extension.process;

import dev.faultora.model.events.RunEvent;
import dev.faultora.spi.contract.ReportRenderer;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Renders export-projected events through one admitted selector in a verified child JVM. */
public final class ExtensionReportRenderer implements ReportRenderer, AutoCloseable {
    private final ExtensionProcess peer;
    private final ExtensionProcess.Provider selector;
    private final Duration timeout;

    private ExtensionReportRenderer(ExtensionProcess peer, ExtensionProcess.Provider selector, Duration timeout) {
        this.peer = peer;
        this.selector = selector;
        this.timeout = timeout;
    }

    /**
     * Start an operator-approved artifact with a trusted runtime classpath.
     * The caller owns artifact and output policy, including export projection of all events.
     */
    public static ExtensionReportRenderer start(StagedArtifact artifact, List<Path> runtimeClasspath,
                                                ExtensionProcess.Declaration declaration, Duration timeout,
                                                Duration lifetime) throws IOException, InterruptedException {
        if (declaration.providers().size() != 1 || timeout == null || timeout.toMillis() < 1) {
            throw new IllegalArgumentException("One renderer selector and a positive timeout are required");
        }
        var selector = declaration.providers().iterator().next();
        if (!selector.kind().equals("renderer")) throw new IllegalArgumentException("A renderer selector is required");
        return new ExtensionReportRenderer(ExtensionProcess.startRenderer(artifact, runtimeClasspath, declaration, lifetime),
                selector, timeout);
    }

    @Override public String format() { return selector.name(); }

    @Override
    public void render(List<RunEvent> events, Writer output) throws IOException {
        final String report;
        try {
            var input = RenderWire.input(events, peer.frameLimit());
            report = peer.request(selector, input, timeout, RenderWire::readResult);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("EXTENSION_RENDER_CANCELLED");
        } catch (IOException | IllegalArgumentException failed) {
            throw new IOException("EXTENSION_RENDER_FAILED");
        }
        output.write(report);
    }

    @Override public void close() throws IOException {
        try {
            peer.stop(timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("CANCELLED");
        }
    }
}
