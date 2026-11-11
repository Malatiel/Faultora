package dev.faultora.extension.process;

import dev.faultora.model.catalog.NormalizedError;
import dev.faultora.spi.context.ImportContext;
import dev.faultora.spi.contract.SourceImporter;
import dev.faultora.spi.result.ImportResult;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Imports self-contained sources through one admitted selector in a verified child. */
public final class ExtensionSourceImporter implements SourceImporter, AutoCloseable {
    private final ExtensionProcess peer;
    private final ExtensionProcess.Provider selector;
    private final Duration timeout;

    ExtensionSourceImporter(ExtensionProcess peer, ExtensionProcess.Provider selector, Duration timeout) {
        this.peer = peer;
        this.selector = selector;
        this.timeout = timeout;
    }

    public static ExtensionSourceImporter start(StagedArtifact artifact, List<Path> runtimeClasspath,
                                               ExtensionProcess.Declaration declaration, Duration timeout,
                                               Duration lifetime) throws IOException, InterruptedException {
        if (declaration.providers().size() != 1 || timeout == null || timeout.toMillis() < 1) {
            throw new IllegalArgumentException("One importer selector and a positive timeout are required");
        }
        var selector = declaration.providers().iterator().next();
        if (!selector.kind().equals("importer")) throw new IllegalArgumentException("An importer selector is required");
        return new ExtensionSourceImporter(ExtensionProcess.startImporter(artifact, runtimeClasspath, declaration, lifetime),
                selector, timeout);
    }

    @Override public Set<String> supportedTypes() { return Set.of(selector.name()); }

    @Override
    public ImportResult importSource(String sourceContent, ImportContext context) {
        try {
            var input = ImportWire.input(sourceContent, context, selector.name(), peer.frameLimit());
            return peer.request(selector, input, timeout, ImportWire::readResult);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(NormalizedError.ErrorCategory.CANCELLED, "EXTENSION_IMPORT_CANCELLED");
        } catch (ImportWire.InvalidSource refused) {
            return failed(NormalizedError.ErrorCategory.VALIDATION, refused.getMessage());
        } catch (IOException | IllegalArgumentException failed) {
            return failed(NormalizedError.ErrorCategory.INTERNAL, "EXTENSION_IMPORT_FAILED");
        }
    }

    private static ImportResult failed(NormalizedError.ErrorCategory category, String code) {
        return ImportResult.failure(List.of(new NormalizedError(category, code,
                "Extension source could not be imported", false, Map.of())));
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
