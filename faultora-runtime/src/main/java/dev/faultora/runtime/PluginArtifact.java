package dev.faultora.runtime;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.spi.extension.PluginManifest;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    static final long MAX_ARTIFACT_BYTES = 128L * 1024 * 1024;
    static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int MAX_MANIFEST_DEPTH = 16;
    private static final int MAX_VERSION_LENGTH = 128;
    private static final ObjectMapper YAML = manifestMapper();

    private static ObjectMapper manifestMapper() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(MAX_MANIFEST_BYTES);
        options.setNestingDepthLimit(MAX_MANIFEST_DEPTH);
        options.setMaxAliasesForCollections(0);
        return new ObjectMapper(YAMLFactory.builder()
                .loaderOptions(options)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_MANIFEST_DEPTH)
                        .maxStringLength(MAX_MANIFEST_BYTES).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

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
        return of(extension.getClass());
    }

    /** Inspect a provider's artifact without constructing or initializing it. */
    public static PluginArtifact of(Class<?> extensionType) {
        return inspect(extensionType).artifact();
    }

    /** Keep inspection failures separate from the public artifact identity. */
    static Inspection inspect(Class<?> extensionType) {
        URL source = codeSourceOf(extensionType);
        if (source == null) {
            return refused(extensionType.getName(), null, "its artifact origin cannot be verified");
        }
        String location = source.toExternalForm();
        Path file = fileAt(source);
        if (file == null) {
            return refused(location, null, "its artifact is not a local file");
        }
        String digest = null;
        try {
            location = file.toString();
            PluginManifest manifest;
            if (Files.isDirectory(file)) {
                // Use this provider's directory, never a parent loader's manifest.
                Path declared = file.resolve(PluginManifest.LOCATION);
                if (!Files.exists(declared)) {
                    return new Inspection(new PluginArtifact(location, null, null), null);
                }
                if (!Files.isRegularFile(declared)) {
                    return refused(location, null, "its manifest is not a regular file");
                }
                try (InputStream input = Files.newInputStream(declared)) {
                    manifest = readManifest(input);
                }
            } else {
                if (!Files.isRegularFile(file)) {
                    return refused(location, null, "its artifact is not a readable regular file");
                }
                digest = digestOf(file);
                try (JarFile jar = new JarFile(file.toFile())) {
                    var entry = jar.getEntry(PluginManifest.LOCATION);
                    if (entry == null) {
                        return new Inspection(new PluginArtifact(location, digest, null), null);
                    }
                    try (InputStream declared = jar.getInputStream(entry)) {
                        manifest = readManifest(declared);
                    }
                }
            }
            return new Inspection(new PluginArtifact(location, digest, manifest), null);
        } catch (InspectionException invalid) {
            return refused(location, digest, invalid.getMessage());
        } catch (IOException | SecurityException unreadable) {
            return refused(location, digest, "its artifact or manifest cannot be read");
        }
    }

    record Inspection(PluginArtifact artifact, String refusal) {
    }

    private static Inspection refused(String location, String digest, String reason) {
        return new Inspection(new PluginArtifact(location, digest, null), reason);
    }

    /** Whether this artifact can be identified by something other than its name. */
    public boolean isIdentifiable() {
        return digest != null;
    }

    private static URL codeSourceOf(Class<?> extensionType) {
        try {
            var domain = extensionType.getProtectionDomain();
            return domain == null || domain.getCodeSource() == null
                    ? null : domain.getCodeSource().getLocation();
        } catch (SecurityException notAllowedToLook) {
            return null;
        }
    }

    private static Path fileAt(URL source) {
        try {
            return Path.of(source.toURI());
        } catch (URISyntaxException | IllegalArgumentException
                 | FileSystemNotFoundException | SecurityException notAFile) {
            return null;
        }
    }

    private static String digestOf(Path artifact) throws IOException {
        if (Files.size(artifact) > MAX_ARTIFACT_BYTES) {
            throw new InspectionException("its artifact exceeds maximum size of "
                    + MAX_ARTIFACT_BYTES + " bytes");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is not available", unavailable);
        }
        try (InputStream input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[8192];
            long read = 0;
            int count;
            while ((count = input.read(buffer, 0,
                    (int) Math.min(buffer.length, MAX_ARTIFACT_BYTES - read + 1))) != -1) {
                read += count;
                if (read > MAX_ARTIFACT_BYTES) {
                    throw new InspectionException("its artifact exceeds maximum size of "
                            + MAX_ARTIFACT_BYTES + " bytes");
                }
                digest.update(buffer, 0, count);
            }
        }
        return ContentDigest.SHA256_PREFIX + HexFormat.of().formatHex(digest.digest());
    }

    private static PluginManifest readManifest(InputStream declared) throws IOException {
        byte[] bytes = declared.readNBytes(MAX_MANIFEST_BYTES + 1);
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw new InspectionException("its manifest exceeds maximum size of "
                    + MAX_MANIFEST_BYTES + " bytes");
        }
        PluginManifest manifest;
        try {
            manifest = YAML.readValue(bytes, PluginManifest.class);
        } catch (IOException | IllegalArgumentException malformed) {
            // Parser messages can contain the plugin's untrusted input.
            throw new InspectionException("its manifest is malformed or exceeds parser limits");
        }
        if (manifest == null || manifest.id() == null || manifest.id().isBlank()
                || manifest.version() == null || manifest.version().isBlank()
                || !validRange(manifest.requiresApi())) {
            throw new InspectionException("its manifest must declare nonblank id and version"
                    + " and a valid requiresApi range");
        }
        return manifest;
    }

    private static boolean validRange(PluginManifest.VersionRange range) {
        if (range == null || range.from() == null && range.to() == null) {
            return false;
        }
        return (range.from() == null || validVersion(range.from()))
                && (range.to() == null || validVersion(range.to()))
                && (range.from() == null || range.admits(range.from()));
    }

    private static boolean validVersion(String version) {
        if (version.length() > MAX_VERSION_LENGTH
                || !version.matches("[0-9]+(?:\\.[0-9]+)*(?:[-+][A-Za-z0-9][A-Za-z0-9.-]*)?")) {
            return false;
        }
        try {
            for (String part : version.split("[-+]", 2)[0].split("\\.")) {
                Integer.parseInt(part);
            }
            return true;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static final class InspectionException extends IOException {
        InspectionException(String reason) {
            super(reason);
        }
    }
}
