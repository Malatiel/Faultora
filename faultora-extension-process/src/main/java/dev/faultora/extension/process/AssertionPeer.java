package dev.faultora.extension.process;

import dev.faultora.spi.contract.AssertionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static dev.faultora.extension.process.ExtensionProcess.require;

/** Binds the assertion contract to the trusted child endpoint. */
final class AssertionPeer {
    private AssertionPeer() { }

    static void run(ClassLoader verifiedArtifact, InputStream input, OutputStream output) throws IOException {
        ProviderPeer.run("assertion", name -> {
            var provider = ProviderPeer.load(AssertionProvider.class, verifiedArtifact);
            require(name.equals(provider.type()), "UNSUPPORTED_PROVIDER");
            return (request, frameLimit) -> {
                var call = AssertionWire.readInput(request);
                return () -> AssertionWire.result(provider.evaluate(name, call.params(), call.evidence(), call.context()));
            };
        }, input, output);
    }
}
