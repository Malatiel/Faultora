package dev.faultora.runtime;

import dev.faultora.extension.process.ExtensionAssertionProvider;
import dev.faultora.extension.process.ExtensionProcess;
import dev.faultora.extension.process.ExtensionResourceLimits;
import dev.faultora.extension.process.StagedArtifact;
import dev.faultora.spi.contract.AssertionProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Operator approval for one assertion provider in a separate JVM. */
public record IsolatedAssertion(
        String type, String digest, Path artifact, boolean requireOsSandbox, ExtensionResourceLimits resourceLimits) {
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration LIFETIME = Duration.ofMinutes(5);

    public IsolatedAssertion(String type, String digest, Path artifact) {
        this(type, digest, artifact, false);
    }

    public IsolatedAssertion(String type, String digest, Path artifact, boolean requireOsSandbox) {
        this(type, digest, artifact, requireOsSandbox, null);
    }

    public IsolatedAssertion(String type, String digest, Path artifact, int memoryMiB, int cpuPercent) {
        this(type, digest, artifact, memoryMiB, cpuPercent, null);
    }

    public IsolatedAssertion(String type, String digest, Path artifact, int memoryMiB, int cpuPercent,
                             Integer maxTasks) {
        this(type, digest, artifact, true, new ExtensionResourceLimits(memoryMiB, cpuPercent, maxTasks));
    }

    public IsolatedAssertion {
        if (resourceLimits != null && !requireOsSandbox) {
            throw new IllegalArgumentException("Extension resource limits require OS sandboxing");
        }
        new ExtensionProcess.Provider("assertion", type);
        if (digest == null || !digest.matches("sha256:[0-9a-f]{64}") || artifact == null) {
            throw new IllegalArgumentException("An assertion JAR and its approved SHA-256 digest are required");
        }
        artifact = artifact.toAbsolutePath().normalize();
    }

    public static final class Refused extends IllegalStateException {
        Refused(String message) {
            super(message);
        }
    }

    AssertionProvider open(List<AutoCloseable> opened) {
        Path bootstrap = bootstrapJar();
        try {
            StagedArtifact staged = IsolatedPluginArtifact.stage(artifact, digest);
            opened.add(staged);
            var manifest = PluginArtifact.stagedManifest(staged.artifact());
            if (!IsolatedPluginArtifact.compatibleAndCapabilityFree(manifest)) {
                throw new Refused("The assertion extension must support this Faultora version and declare no capabilities");
            }
            var declaration = new ExtensionProcess.Declaration(manifest.id(), manifest.version(),
                    Set.of(new ExtensionProcess.Provider("assertion", type)));
            ExtensionAssertionProvider provider;
            if (resourceLimits != null) {
                provider = ExtensionAssertionProvider.startSandboxed(
                        staged, bootstrap, declaration, CALL_TIMEOUT, LIFETIME, resourceLimits);
            } else if (requireOsSandbox) {
                provider = ExtensionAssertionProvider.startSandboxed(staged, bootstrap, declaration, CALL_TIMEOUT, LIFETIME);
            } else {
                provider = ExtensionAssertionProvider.start(staged, List.of(bootstrap), declaration, CALL_TIMEOUT, LIFETIME);
            }
            opened.add(provider);
            return provider;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Refused("Assertion extension startup was interrupted");
        } catch (IOException | IllegalArgumentException invalid) {
            if (resourceLimits != null) {
                throw new Refused("Assertion extension could not start with the required Linux sandbox and resource limits");
            }
            throw new Refused(requireOsSandbox
                    ? "Assertion extension could not start with the required Linux sandbox"
                    : "Assertion extension JAR, digest, manifest or provider could not be verified");
        }
    }

    private static Path bootstrapJar() {
        try {
            return IsolatedPluginArtifact.packagedBootstrap().orElseThrow(
                    () -> new Refused("Isolated assertions require the packaged Faultora CLI JAR"));
        } catch (IOException unavailable) {
            throw new Refused("The packaged assertion bootstrap cannot be located");
        }
    }
}
