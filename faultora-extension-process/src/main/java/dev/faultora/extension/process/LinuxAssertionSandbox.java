package dev.faultora.extension.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** The runner's filesystem and network profile for one assertion peer. */
final class LinuxAssertionSandbox {
    private static final Path BWRAP = Path.of("/usr/bin/bwrap");
    private static final String JAVA = "/jre/bin/java";
    private static final String RUNTIME = "/faultora/runtime.jar";
    private static final String PLUGIN = "/faultora/plugin.jar";
    private static final List<String> LIBRARIES = List.of(
            "libc.so.6", "libdl.so.2", "libm.so.6", "libpthread.so.0", "librt.so.1",
            "libgcc_s.so.1", "libstdc++.so.6", "libz.so.1");

    private LinuxAssertionSandbox() { }

    static List<String> command(Path javaHome, Path runtime, StagedArtifact artifact, List<String> arguments)
            throws IOException {
        if (!System.getProperty("os.name").equals("Linux") || !Files.isExecutable(BWRAP)) {
            throw new IOException("OS_SANDBOX_UNAVAILABLE");
        }
        Path launcher = BWRAP.toRealPath();
        if (((int) Files.getAttribute(launcher, "unix:mode") & 06000) != 0) {
            throw new IOException("OS_SANDBOX_REQUIRES_UNPRIVILEGED_BWRAP");
        }
        Path jre = javaHome.toRealPath();
        if (jre.getNameCount() < 2 || !Files.readAttributes(jre.resolve("lib/modules"),
                BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
            throw new IOException("OS_SANDBOX_REQUIRES_DEDICATED_JAVA_HOME");
        }
        String architecture = switch (System.getProperty("os.arch")) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new IOException("OS_SANDBOX_UNSUPPORTED_ARCHITECTURE");
        };
        var command = new ArrayList<>(List.of(launcher.toString(),
                "--unshare-user", "--unshare-pid", "--unshare-net", "--unshare-ipc", "--unshare-uts",
                "--disable-userns", "--cap-drop", "ALL", "--clearenv", "--new-session", "--die-with-parent"));
        for (String loader : List.of("/lib/ld-musl-" + architecture + ".so.1",
                architecture.equals("x86_64") ? "/lib64/ld-linux-x86-64.so.2" : "/lib/ld-linux-aarch64.so.1")) {
            bindLibrary(command, Path.of(loader));
        }
        for (String directory : List.of("/lib/" + architecture + "-linux-gnu",
                "/usr/lib/" + architecture + "-linux-gnu", "/lib64", "/usr/lib64", "/lib", "/usr/lib")) {
            for (String library : LIBRARIES) {
                bindLibrary(command, Path.of(directory, library));
            }
        }
        command.addAll(List.of(
                "--ro-bind", jre.toString(), "/jre",
                "--ro-bind", runtime.toRealPath().toString(), RUNTIME,
                "--ro-bind", artifact.artifact().toAbsolutePath().toString(), PLUGIN,
                "--proc", "/proc", "--remount-ro", "/proc",
                "--dev", "/dev", "--remount-ro", "/dev",
                "--size", "16777216", "--tmpfs", "/tmp", "--remount-ro", "/",
                "--setenv", "LD_LIBRARY_PATH", "/jre/lib:/jre/lib/server",
                "--chdir", "/tmp", "--", JAVA, "-XX:-UsePerfData", "-XX:+DisableAttachMechanism"));
        command.addAll(arguments);
        command.addAll(List.of("-cp", RUNTIME, VerifiedJvmMain.class.getName(), PLUGIN, artifact.digest(),
                VerifiedJvmMain.ASSERTION_MODE));
        return command;
    }

    private static void bindLibrary(List<String> command, Path library) throws IOException {
        if (Files.isRegularFile(library)) {
            command.addAll(List.of("--ro-bind", library.toRealPath().toString(), library.toString()));
        }
    }
}
