package dev.faultora.runtime;

import dev.faultora.extension.process.ExtensionProcess;
import dev.faultora.extension.process.StagedArtifact;
import dev.faultora.spi.extension.PluginManifest;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class IsolatedPluginArtifact {
    private IsolatedPluginArtifact() { }

    static StagedArtifact stage(Path artifact, String digest) throws IOException {
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        return StagedArtifact.copy(artifact, digest, temporary);
    }

    static boolean compatibleAndCapabilityFree(PluginManifest manifest) {
        return manifest.requiresApi().admits(FaultoraVersion.current()) && manifest.capabilities().asked().isEmpty();
    }

    static Optional<Path> packagedBootstrap() throws IOException {
        var runtime = IsolatedPluginArtifact.class.getProtectionDomain().getCodeSource();
        var child = ExtensionProcess.class.getProtectionDomain().getCodeSource();
        if (runtime == null || child == null
                || !runtime.getLocation().toExternalForm().equals(child.getLocation().toExternalForm())) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(runtime.getLocation().toURI()).toRealPath();
            return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        } catch (URISyntaxException | IllegalArgumentException unavailable) {
            throw new IOException("The packaged extension bootstrap cannot be located");
        }
    }
}
