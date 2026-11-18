package dev.faultora.runtime;

import dev.faultora.extension.process.ExtensionProcess;
import dev.faultora.extension.process.ExtensionReportRenderer;
import dev.faultora.extension.process.StagedArtifact;
import dev.faultora.model.events.RunEvent;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Operator approval for one local text renderer in a separate JVM. */
public record IsolatedRenderer(String format, String digest, Path artifact) {
    private static final Set<String> BUILT_INS = Set.of("console", "json", "junit", "html");
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration LIFETIME = Duration.ofSeconds(15);

    public IsolatedRenderer {
        if (format == null || !format.matches("[a-z][a-z0-9-]{0,31}") || BUILT_INS.contains(format)
                || digest == null || !digest.matches("sha256:[0-9a-f]{64}") || artifact == null) {
            throw new IllegalArgumentException("A custom format, approved SHA-256 digest and renderer JAR are required");
        }
        artifact = artifact.toAbsolutePath().normalize();
    }

    public static final class Refused extends IllegalStateException {
        Refused(String message) {
            super(message);
        }
    }

    /** Verify the reviewed provider before execution and retain its staged artifact for rendering. */
    public Prepared prepare() {
        try {
            Path bootstrap = IsolatedPluginArtifact.packagedBootstrap().orElseThrow(
                    () -> new Refused("Isolated renderers require the packaged Faultora CLI JAR"));
            StagedArtifact staged = IsolatedPluginArtifact.stage(artifact, digest);
            try {
                var manifest = PluginArtifact.stagedManifest(staged.artifact());
                if (!IsolatedPluginArtifact.compatibleAndCapabilityFree(manifest)) {
                    throw new Refused("The renderer extension must support this Faultora version and declare no capabilities");
                }
                var declaration = new ExtensionProcess.Declaration(manifest.id(), manifest.version(),
                        Set.of(new ExtensionProcess.Provider("renderer", format)));
                var prepared = new Prepared(format, staged, bootstrap, declaration);
                try (var preflight = prepared.start()) {
                    // A successful handshake verified the advertised format. Close before any scenario work.
                }
                return prepared;
            } catch (IOException | InterruptedException | RuntimeException | Error refused) {
                try {
                    staged.close();
                } catch (IOException cleanup) {
                    refused.addSuppressed(cleanup);
                }
                throw refused;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Refused("Renderer extension startup was interrupted");
        } catch (IOException | IllegalArgumentException invalid) {
            throw new Refused("Renderer extension JAR, digest, manifest or provider could not be verified");
        }
    }

    public static final class Prepared implements AutoCloseable {
        private final String format;
        private final StagedArtifact staged;
        private final Path bootstrap;
        private final ExtensionProcess.Declaration declaration;

        private Prepared(String format, StagedArtifact staged, Path bootstrap, ExtensionProcess.Declaration declaration) {
            this.format = format;
            this.staged = staged;
            this.bootstrap = bootstrap;
            this.declaration = declaration;
        }

        public String format() {
            return format;
        }

        /** Supply the same export-projected events written by the run journal. */
        public void render(List<RunEvent> events, Writer output) throws IOException {
            try (var renderer = start()) {
                renderer.render(events, output);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Renderer extension startup was interrupted");
            }
        }

        private ExtensionReportRenderer start() throws IOException, InterruptedException {
            return ExtensionReportRenderer.start(staged, List.of(bootstrap), declaration, CALL_TIMEOUT, LIFETIME);
        }

        @Override
        public void close() throws IOException {
            staged.close();
        }
    }
}
