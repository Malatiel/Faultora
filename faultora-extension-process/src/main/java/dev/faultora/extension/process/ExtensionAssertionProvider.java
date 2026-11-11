package dev.faultora.extension.process;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.result.AssertionResult;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Evaluates one admitted assertion type in a verified child. The caller owns the staged
 * artifact, approves the declaration and supplies an evidence view already narrowed by policy.
 */
public final class ExtensionAssertionProvider implements AssertionProvider, AutoCloseable {
    private static final String SYNTHETIC_HANDLES = "syntheticSecretHandles";
    private static final Pattern SYNTHETIC_ID = Pattern.compile("synthetic\\.[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final int MAX_HANDLES = 32;
    private static final int MAX_HANDLE_LENGTH = 128;

    private final ExtensionProcess peer;
    private final ExtensionProcess.Provider selector;
    private final Duration timeout;
    private final List<String> syntheticHandles;

    ExtensionAssertionProvider(ExtensionProcess peer, ExtensionProcess.Provider selector, Duration timeout) {
        this(peer, selector, timeout, List.of());
    }

    private ExtensionAssertionProvider(ExtensionProcess peer, ExtensionProcess.Provider selector, Duration timeout,
                                       List<String> syntheticHandles) {
        this.peer = peer;
        this.selector = selector;
        this.timeout = timeout;
        this.syntheticHandles = syntheticHandles;
    }

    public static ExtensionAssertionProvider start(StagedArtifact artifact, List<Path> runtimeClasspath,
                                                    ExtensionProcess.Declaration declaration, Duration timeout,
                                                    Duration lifetime) throws IOException, InterruptedException {
        return start(artifact, runtimeClasspath, declaration, timeout, lifetime, Set.of(), Set.of());
    }

    /**
     * Admit value-less synthetic handles declared by the plugin and allowed by
     * the caller. The child receives their IDs through the assertion context.
     */
    public static ExtensionAssertionProvider start(StagedArtifact artifact, List<Path> runtimeClasspath,
                                                    ExtensionProcess.Declaration declaration, Duration timeout,
                                                    Duration lifetime, Set<String> requestedHandles,
                                                    Set<String> allowedSyntheticHandles)
            throws IOException, InterruptedException {
        if (declaration.providers().size() != 1 || timeout == null || timeout.toMillis() < 1) {
            throw new IllegalArgumentException("One assertion selector and a positive timeout are required");
        }
        ExtensionProcess.Provider selector = declaration.providers().iterator().next();
        if (!selector.kind().equals("assertion")) {
            throw new IllegalArgumentException("An assertion selector is required");
        }
        var requested = checkedSyntheticHandles(requestedHandles);
        var allowed = checkedSyntheticHandles(allowedSyntheticHandles);
        if (!allowed.containsAll(requested)) throw new IOException("SECRET_HANDLE_REFUSED");
        var admitted = requested.stream().sorted().toList();
        ExtensionProcess peer = ExtensionProcess.startAssertion(artifact, runtimeClasspath, declaration, lifetime);
        return new ExtensionAssertionProvider(peer, selector, timeout, admitted);
    }

    private static Set<String> checkedSyntheticHandles(Set<String> handles) throws IOException {
        if (handles == null || handles.size() > MAX_HANDLES) throw new IOException("SECRET_HANDLE_REFUSED");
        var copy = new HashSet<>(handles);
        if (copy.size() > MAX_HANDLES || copy.stream().anyMatch(handle ->
                handle == null || handle.length() > MAX_HANDLE_LENGTH || !SYNTHETIC_ID.matcher(handle).matches())) {
            throw new IOException("SECRET_HANDLE_REFUSED");
        }
        return copy;
    }

    @Override public String type() { return selector.name(); }

    @Override
    public AssertionResult evaluate(String assertionType, Map<String, Object> params, EvidenceView evidence,
                                    AssertionContext context) {
        if (!type().equals(assertionType) || evidence == null || context == null) return refused();
        try {
            var config = context.config();
            if (config == null || config.containsKey(SYNTHETIC_HANDLES)) return refused();
            if (!syntheticHandles.isEmpty()) {
                config = new LinkedHashMap<>(config);
                config.put(SYNTHETIC_HANDLES, syntheticHandles);
                context = new AssertionContext(context.nodeId(), config, context.schema());
            }
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
