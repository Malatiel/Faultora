package dev.faultora.extension.process;

import dev.faultora.model.events.RunEvent;
import dev.faultora.spi.contract.ReportRenderer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.util.List;

import static dev.faultora.extension.process.ExtensionProcess.require;

/** Binds an approved report renderer to the trusted child endpoint. */
final class RendererPeer {
    private RendererPeer() { }

    static void run(ClassLoader verifiedArtifact, InputStream input, OutputStream output) throws IOException {
        ProviderPeer.run("renderer", name -> {
            ReportRenderer renderer = ProviderPeer.load(ReportRenderer.class, verifiedArtifact);
            require(name.equals(renderer.format()), "UNSUPPORTED_PROVIDER");
            return (request, frameLimit) -> {
                var events = RenderWire.readInput(request);
                return () -> RenderWire.result(render(renderer, events, frameLimit));
            };
        }, input, output);
    }

    private static String render(ReportRenderer renderer, List<RunEvent> events, int frameLimit) throws IOException {
        var output = new BoundedReport(frameLimit);
        renderer.render(events, output);
        require(!output.overflow, "MESSAGE_TOO_LARGE");
        return output.text.toString();
    }

    /** A character limit bounds buffering; the frame codec checks the exact encoded size. */
    private static final class BoundedReport extends Writer {
        private final int limit;
        private final StringBuilder text = new StringBuilder();
        private boolean overflow;

        BoundedReport(int limit) { this.limit = limit; }

        private void requireRoom(int length) throws IOException {
            overflow |= length > limit - text.length();
            require(!overflow, "MESSAGE_TOO_LARGE");
        }

        @Override public void write(int character) throws IOException {
            requireRoom(1);
            text.append((char) character);
        }

        @Override public void write(char[] characters, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, characters.length);
            requireRoom(length);
            text.append(characters, offset, length);
        }

        @Override public void write(String value, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, value.length());
            requireRoom(length);
            text.append(value, offset, offset + length);
        }

        @Override public void flush() { }

        @Override public void close() { }
    }
}
