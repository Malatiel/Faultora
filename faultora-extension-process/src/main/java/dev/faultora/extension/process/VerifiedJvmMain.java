package dev.faultora.extension.process;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Trusted bootstrap: authenticate the complete copy before reading any plugin class or resource. */
public final class VerifiedJvmMain {
    private VerifiedJvmMain() { }

    public static void main(String[] args) {
        try {
            if (args.length != 3 || !args[1].matches("sha256:[0-9a-f]{64}")
                    || !args[2].matches("[A-Za-z_$][A-Za-z0-9_$.]{0,255}")) throw new IOException("INVALID_LAUNCH");
            Path artifact = Path.of(args[0]);
            StagedArtifact.noLinks(artifact);
            byte[] bytes;
            try (var input = Files.newInputStream(artifact, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(StagedArtifact.MAX_BYTES + 1);
            }
            if (bytes.length > StagedArtifact.MAX_BYTES) throw new IOException("ARTIFACT_TOO_LARGE");
            String actual = "sha256:" + HexFormat.of().formatHex(StagedArtifact.sha256().digest(bytes));
            if (!actual.equals(args[1])) throw new IOException("ARTIFACT_CHANGED");
            MemoryJar loader = new MemoryJar(bytes);
            Class<?> entry = Class.forName(args[2], false, loader);
            if (entry.getClassLoader() != loader) throw new IOException("ENTRYPOINT_NOT_IN_ARTIFACT");
            Thread.currentThread().setContextClassLoader(loader);
            entry.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } catch (Throwable refused) {
            System.err.println("EXTENSION_START_OR_EXECUTION_FAILED");
            System.exit(70);
        }
    }

    private static final class MemoryJar extends ClassLoader {
        private final Map<String, byte[]> entries = new HashMap<>();

        private MemoryJar(byte[] verified) throws IOException {
            super(VerifiedJvmMain.class.getClassLoader());
            long total = 0;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(verified))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory()) continue;
                    if (entries.size() >= 4096 || name.startsWith("/") || name.contains("\\")
                            || name.contains(":") || java.util.Arrays.asList(name.split("/")).contains("..")) {
                        throw new IOException("ARTIFACT_STRUCTURE_REFUSED");
                    }
                    byte[] content = zip.readNBytes(8 * 1024 * 1024 + 1);
                    total += content.length;
                    if (content.length > 8 * 1024 * 1024 || total > 128L * 1024 * 1024
                            || entries.putIfAbsent(name, content) != null) throw new IOException("ARTIFACT_STRUCTURE_REFUSED");
                }
            }
        }

        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = entries.get(name.replace('.', '/') + ".class");
            if (bytes == null) throw new ClassNotFoundException();
            return defineClass(name, bytes, 0, bytes.length);
        }

        @Override protected URL findResource(String name) {
            byte[] bytes = entries.get(name);
            if (bytes == null) return null;
            try {
                return new URL(null, "verified-memory:/" + name, new URLStreamHandler() {
                    @Override protected URLConnection openConnection(URL url) {
                        return new URLConnection(url) {
                            @Override public void connect() { }
                            @Override public java.io.InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
                        };
                    }
                });
            } catch (java.net.MalformedURLException invalid) { return null; }
        }

        @Override protected java.util.Enumeration<URL> findResources(String name) {
            URL resource = findResource(name);
            return java.util.Collections.enumeration(resource == null ? java.util.List.of() : java.util.List.of(resource));
        }
    }
}
