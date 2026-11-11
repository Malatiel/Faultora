package dev.faultora.extension.process;

import dev.faultora.spi.contract.SourceImporter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static dev.faultora.extension.process.ExtensionProcess.require;

/** Binds the source importer contract to the trusted child endpoint. */
final class ImporterPeer {
    private ImporterPeer() { }

    static void run(ClassLoader verifiedArtifact, InputStream input, OutputStream output) throws IOException {
        ProviderPeer.run("importer", name -> {
            var importer = ProviderPeer.load(SourceImporter.class, verifiedArtifact);
            var supported = importer.supportedTypes();
            require(supported != null && supported.contains(name), "UNSUPPORTED_PROVIDER");
            return (request, frameLimit) -> {
                var call = ImportWire.readInput(request, name, frameLimit);
                return () -> ImportWire.result(importer.importSource(call.source(), call.context()));
            };
        }, input, output);
    }
}
