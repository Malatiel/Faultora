package dev.faultora.runtime;

import dev.faultora.extension.process.ExtensionAssertionProvider;
import dev.faultora.extension.process.ExtensionProcess;
import dev.faultora.extension.process.StagedArtifact;
import dev.faultora.spi.contract.AssertionProvider;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Operator approval for one assertion provider in a separate JVM. */
public record IsolatedAssertion(String type, String digest, Path artifact) {
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration LIFETIME = Duration.ofMinutes(5);

    public IsolatedAssertion {
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
            Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            StagedArtifact staged = StagedArtifact.copy(artifact, digest, temporary);
            opened.add(staged);
            var manifest = PluginArtifact.stagedManifest(staged.artifact());
            if (!manifest.requiresApi().admits(FaultoraVersion.current())
                    || !manifest.capabilities().asked().isEmpty()) {
                throw new Refused("The assertion extension must support this Faultora version and declare no capabilities");
            }
            var declaration = new ExtensionProcess.Declaration(manifest.id(), manifest.version(),
                    Set.of(new ExtensionProcess.Provider("assertion", type)));
            var provider = ExtensionAssertionProvider.start(staged, List.of(bootstrap), declaration,
                    CALL_TIMEOUT, LIFETIME);
            opened.add(provider);
            return provider;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Refused("Assertion extension startup was interrupted");
        } catch (IOException | IllegalArgumentException invalid) {
            throw new Refused("Assertion extension JAR, digest, manifest or provider could not be verified");
        }
    }

    private static Path bootstrapJar() {
        var runtime = IsolatedAssertion.class.getProtectionDomain().getCodeSource();
        var child = ExtensionProcess.class.getProtectionDomain().getCodeSource();
        if (runtime != null && child != null
                && runtime.getLocation().toExternalForm().equals(child.getLocation().toExternalForm())) {
            try {
                Path path = Path.of(runtime.getLocation().toURI()).toRealPath();
                if (Files.isRegularFile(path)) {
                    return path;
                }
            } catch (IOException | URISyntaxException | IllegalArgumentException unavailable) {
                throw new Refused("The packaged assertion bootstrap cannot be located");
            }
        }
        throw new Refused("Isolated assertions require the packaged Faultora CLI JAR");
    }
}
