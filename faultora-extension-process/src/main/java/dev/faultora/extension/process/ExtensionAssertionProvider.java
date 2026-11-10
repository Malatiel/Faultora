package dev.faultora.extension.process;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.result.AssertionResult;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Evaluates one admitted assertion type in a verified child. The caller owns the staged
 * artifact, approves the declaration and supplies an evidence view already narrowed by policy.
 */
public final class ExtensionAssertionProvider implements AssertionProvider, AutoCloseable {
    private final ExtensionProcess peer;
    private final ExtensionProcess.Provider selector;
    private final Duration timeout;

    ExtensionAssertionProvider(ExtensionProcess peer, ExtensionProcess.Provider selector, Duration timeout) {
        this.peer = peer;
        this.selector = selector;
        this.timeout = timeout;
    }

    public static ExtensionAssertionProvider start(StagedArtifact artifact, List<Path> runtimeClasspath,
                                                    ExtensionProcess.Declaration declaration, Duration timeout,
                                                    Duration lifetime) throws IOException, InterruptedException {
        if (declaration.providers().size() != 1 || timeout == null || timeout.toMillis() < 1) {
            throw new IllegalArgumentException("One assertion selector and a positive timeout are required");
        }
        ExtensionProcess.Provider selector = declaration.providers().iterator().next();
        if (!selector.kind().equals("assertion")) {
            throw new IllegalArgumentException("An assertion selector is required");
        }
        ExtensionProcess peer = ExtensionProcess.startAssertion(artifact, runtimeClasspath, declaration, lifetime);
        return new ExtensionAssertionProvider(peer, selector, timeout);
    }

    @Override public String type() { return selector.name(); }

    @Override
    public AssertionResult evaluate(String assertionType, Map<String, Object> params, EvidenceView evidence,
                                    AssertionContext context) {
        if (!type().equals(assertionType) || evidence == null || context == null) return refused();
        try {
            var input = AssertionWire.input(params, evidence, context);
            return peer.request(selector, input, timeout, AssertionWire::readResult);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return refused();
        } catch (IOException | IllegalArgumentException invalid) {
            return refused();
        }
    }

    private static AssertionResult refused() {
        return AssertionResult.indeterminate("Extension assertion could not be evaluated");
    }

    /** Gracefully stop an idle child. An active call is cancelled by the peer. */
    @Override public void close() throws IOException {
        try {
            peer.stop(timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("CANCELLED");
        }
    }
}
