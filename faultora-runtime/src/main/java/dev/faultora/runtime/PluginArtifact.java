package dev.faultora.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.spi.extension.PluginManifest;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Where an extension came from, and what it says about itself.
 * <p>
 * A class name is not an identity. Two jars can offer the same class, one
 * reviewed and one not, and an operator who named the class has said nothing
 * about which — that is the gap between {@code ExtensionRegistry}'s javadoc
 * ("identity is checked by class name") and {@code ExtensionPolicy}'s
 * ("extension identity digests or names"). The digest of the artifact closes
 * it, and needs no process boundary to do so.
 * <p>
 * The digest is computed here rather than read from the manifest, because a
 * file declaring its own hash declares nothing. The manifest is the plugin's
 * own description; the digest is what makes it the description of <em>this</em>
 * plugin.
 *
 * @param location where the class was loaded from, for a diagnostic to name
 * @param digest   the artifact's digest, or null when it did not come from a
 *                 file — a class loaded from a directory during development
 *                 has no artifact to hash
 * @param manifest what it declares, or null when it declares nothing
 */
public record PluginArtifact(String location, String digest, PluginManifest manifest) {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * Find out where a discovered extension came from.
     * <p>
     * Never throws. Anything unreadable — a sealed jar, a class with no code
     * source, a manifest that does not parse — becomes an artifact that
     * declares nothing, and the policy decides what to do about a plugin it
     * cannot identify. Failing here would turn a diagnostic into a crash at
     * the moment somebody is trying to work out why their plugin was refused.
     */
    public static PluginArtifact of(Object extension) {
        URL source = codeSourceOf(extension);
        if (source == null) {
            return new PluginArtifact(extension.getClass().getName(), null, null);
        }
        Path file = fileAt(source);
        if (file == null || !Files.isRegularFile(file)) {
            // A directory of classes: development, not a shipped artifact.
            return new PluginArtifact(source.toString(), null, manifestNextTo(extension));
        }
        return new PluginArtifact(file.toString(), digestOf(file), manifestIn(file));
    }

    /** Whether this artifact can be identified by something other than its name. */
    public boolean isIdentifiable() {
        return digest != null;
    }

    private static URL codeSourceOf(Object extension) {
        try {
            var domain = extension.getClass().getProtectionDomain();
            return domain == null || domain.getCodeSource() == null
                    ? null : domain.getCodeSource().getLocation();
        } catch (SecurityException notAllowedToLook) {
            return null;
        }
    }

    private static Path fileAt(URL source) {
        try {
            return Path.of(source.toURI());
        } catch (Exception notAFile) {
            return null;
        }
    }

    private static String digestOf(Path artifact) {
        try {
            return ContentDigest.sha256Uri(Files.readAllBytes(artifact));
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static PluginManifest manifestIn(Path artifact) {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            var entry = jar.getEntry(PluginManifest.LOCATION);
            if (entry == null) {
                return null;
            }
            try (InputStream declared = jar.getInputStream(entry)) {
                return YAML.readValue(declared.readAllBytes(), PluginManifest.class);
            }
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** The manifest beside a class loaded from a directory rather than a jar. */
    private static PluginManifest manifestNextTo(Object extension) {
        try (InputStream declared = extension.getClass().getClassLoader()
                .getResourceAsStream(PluginManifest.LOCATION)) {
            return declared == null
                    ? null : YAML.readValue(declared.readAllBytes(), PluginManifest.class);
        } catch (Exception unreadable) {
            return null;
        }
    }
}
