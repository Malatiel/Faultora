package dev.faultora.extension.process;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** A new owner-only run directory containing one bounded, digest-checked artifact copy. */
public final class StagedArtifact implements AutoCloseable {
    public static final int MAX_BYTES = 128 * 1024 * 1024;
    private final Path directory;
    private final Path artifact;
    private final String digest;

    private StagedArtifact(Path directory, String digest) {
        this.directory = directory;
        artifact = directory.resolve("plugin.jar");
        this.digest = digest;
    }

    public static StagedArtifact copy(Path source, String expectedDigest, Path ownedRoot) throws IOException {
        if (expectedDigest == null || !expectedDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("An approved SHA-256 digest is required");
        }
        noLinks(ownedRoot);
        noLinks(source);
        if (!Files.isDirectory(ownedRoot, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("ARTIFACT_REFUSED");
        Path directory = Files.createTempDirectory(ownedRoot, "extension-");
        StagedArtifact staged = new StagedArtifact(directory, expectedDigest);
        boolean retained = false;
        try {
            privateDirectory(directory);
            MessageDigest digest = sha256();
            try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 FileChannel output = FileChannel.open(staged.artifact, StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                long size = 0;
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    size += count;
                    if (size > MAX_BYTES) throw new IOException("ARTIFACT_TOO_LARGE");
                    buffer.flip();
                    while (buffer.hasRemaining()) output.write(buffer);
                    buffer.clear();
                }
                output.force(true);
            }
            try (var copied = Files.newInputStream(staged.artifact, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                long size = 0;
                int read;
                while ((read = copied.read(buffer)) >= 0) {
                    size += read;
                    if (size > MAX_BYTES) throw new IOException("ARTIFACT_TOO_LARGE");
                    digest.update(buffer, 0, read);
                }
            }
            String actual = "sha256:" + HexFormat.of().formatHex(digest.digest());
            if (!expectedDigest.equals(actual)) throw new IOException("ARTIFACT_CHANGED");
            retained = true;
            return staged;
        } finally {
            if (!retained) staged.close();
        }
    }

    public Path directory() { return directory; }
    public Path artifact() { return artifact; }
    public String digest() { return digest; }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    static void noLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path checked = absolute.getRoot();
        for (Path part : absolute) {
            checked = checked.resolve(part);
            BasicFileAttributes attrs = Files.readAttributes(checked, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("ARTIFACT_PATH_REFUSED");
        }
    }

    private static void privateDirectory(Path directory) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } else {
            AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
            if (view == null) throw new IOException("PRIVATE_DIRECTORY_UNAVAILABLE");
            view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner())
                    .setPermissions(AclEntryPermission.values())
                    .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build()));
        }
    }

    @Override public void close() throws IOException {
        // Only these two names were created by this owner; never recurse through a changed directory.
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        noLinks(directory);
        Files.deleteIfExists(artifact);
        Files.delete(directory);
    }
}
