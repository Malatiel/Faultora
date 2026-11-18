package dev.faultora.extension.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** An operator-requested cgroup v2 scope outside the assertion's Bubblewrap sandbox. */
final class LinuxAssertionScope implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(LinuxAssertionScope.class);
    private static final Path CGROUP_ROOT = Path.of("/sys/fs/cgroup");
    private static final Path SYSTEMD_RUN = Path.of("/usr/bin/systemd-run");
    private static final Path SYSTEMCTL = Path.of("/usr/bin/systemctl");
    private static final String SLICE = "app.slice";
    private static final int MAX_MANAGER_OUTPUT = 4096;
    private static final String GUARD = """
            fail() { printf '%s\\n' OS_RESOURCE_LIMITS_NOT_ENFORCED >&2; exit 125; }
            membership=$1
            expected=$2
            group=$3
            memory=$4
            quota=$5
            tasks=$6
            shift 6

            IFS= read -r actual < "$membership" || fail
            [ "$actual" = "$expected" ] || fail
            IFS= read -r memory_max < "$group/memory.max" || fail
            [ "$memory_max" = "$memory" ] || fail
            IFS= read -r swap_max < "$group/memory.swap.max" || fail
            [ "$swap_max" = 0 ] || fail
            IFS= read -r cpu_max < "$group/cpu.max" || fail
            [ "$cpu_max" = "$quota 100000" ] || fail
            if [ -e "$group/cpu.max.burst" ]; then
                IFS= read -r cpu_burst < "$group/cpu.max.burst" || fail
                [ "$cpu_burst" = 0 ] || fail
            fi
            if [ -n "$tasks" ]; then
                IFS= read -r tasks_max < "$group/pids.max" || fail
                [ "$tasks_max" = "$tasks" ] || fail
            fi
            exec "$@"
            """;

    private final String runtimeDirectory;
    private final String unit;
    private final Path membershipPath;
    private final Path group;
    private boolean cleanupAttempted;
    private IOException cleanupFailure;

    private LinuxAssertionScope(String runtimeDirectory, Path managerGroup) {
        this.runtimeDirectory = runtimeDirectory;
        unit = "faultora-assertion-" + UUID.randomUUID() + ".scope";
        membershipPath = managerGroup.resolve(SLICE).resolve(unit);
        group = CGROUP_ROOT.resolve(Path.of("/").relativize(membershipPath));
    }

    static LinuxAssertionScope open() throws IOException {
        String directory = System.getenv("XDG_RUNTIME_DIR");
        if (!System.getProperty("os.name").equals("Linux") || directory == null
                || !directory.matches("/run/user/[0-9]+") || !Files.isExecutable(SYSTEMD_RUN)
                || !Files.isExecutable(SYSTEMCTL) || !Files.getFileStore(CGROUP_ROOT).type().equals("cgroup2")) {
            throw new IOException("OS_RESOURCE_SCOPE_UNAVAILABLE");
        }
        ManagerResult result = manager(directory, "show", "--property=ControlGroup", "--value", "--", "-.slice");
        if (result.exitCode() != 0 || !result.output().matches("/[A-Za-z0-9_.@/\\\\-]+")) {
            throw new IOException("OS_RESOURCE_SCOPE_UNAVAILABLE");
        }
        Path managerGroup = Path.of(result.output());
        if (managerGroup.getNameCount() == 0 || !managerGroup.normalize().equals(managerGroup)) {
            throw new IOException("OS_RESOURCE_SCOPE_UNAVAILABLE");
        }
        return new LinuxAssertionScope(directory, managerGroup);
    }

    List<String> command(ExtensionResourceLimits limits, long lifetimeMillis, List<String> sandbox) {
        var command = new ArrayList<>(List.of(SYSTEMD_RUN.toString(), "--user", "--scope", "--quiet", "--collect",
                "--expand-environment=no", "--unit=" + unit, "--slice=" + SLICE,
                "--description=Faultora isolated assertion", "--property=MemoryMax=" + limits.memoryBytes(),
                "--property=MemorySwapMax=0", "--property=CPUQuota=" + limits.cpuPercent() + "%",
                "--property=CPUQuotaPeriodSec=100ms", "--property=RuntimeMaxSec=" + lifetimeMillis + "ms",
                "--property=TimeoutStopSec=2s", "--property=KillMode=control-group"));
        if (limits.maxTasks() != null) {
            command.add("--property=TasksMax=" + limits.maxTasks());
        }
        command.add("--no-ask-password");
        command.add("--");
        command.addAll(guardCommand(Path.of("/proc/self/cgroup"), "0::" + membershipPath, group, limits, sandbox));
        return command;
    }

    static List<String> guardCommand(Path membership, String expected, Path group, ExtensionResourceLimits limits,
                                     List<String> sandbox) {
        var command = new ArrayList<>(List.of("/bin/sh", "-ec", GUARD, "faultora-scope-guard",
                membership.toString(), expected, group.toString(), Long.toString(limits.memoryBytes()),
                Integer.toString(limits.quotaMicros()), limits.maxTasks() == null ? "" : limits.maxTasks().toString()));
        command.addAll(sandbox);
        return command;
    }

    void configure(ProcessBuilder builder) {
        builder.environment().put("XDG_RUNTIME_DIR", runtimeDirectory);
    }

    void watch(Process child) {
        child.onExit().thenRunAsync(() -> {
            try {
                close();
            } catch (IOException unconfirmed) {
                // close records and reports the bounded cleanup failure.
            }
        });
    }

    @Override public synchronized void close() throws IOException {
        if (!cleanupAttempted) {
            cleanupAttempted = true;
            try {
                ManagerResult stopped = manager(runtimeDirectory, "stop", "--", unit);
                if (stopped.exitCode() != 0) {
                    ManagerResult state = manager(runtimeDirectory, "show", "--property=LoadState", "--value", "--", unit);
                    if (state.exitCode() != 0 || !state.output().equals("not-found")) {
                        throw new IOException("OS_RESOURCE_SCOPE_CLEANUP_UNCONFIRMED");
                    }
                }
                requireEmptyGroup();
            } catch (IOException unconfirmed) {
                LOG.warn("Extension resource scope cleanup could not be confirmed");
                cleanupFailure = new IOException("OS_RESOURCE_SCOPE_CLEANUP_UNCONFIRMED");
            }
        }
        if (cleanupFailure != null) {
            throw new IOException(cleanupFailure.getMessage());
        }
    }

    private void requireEmptyGroup() throws IOException {
        try {
            if (!Files.readAllLines(group.resolve("cgroup.events")).contains("populated 0")) {
                throw new IOException("OS_RESOURCE_SCOPE_CLEANUP_UNCONFIRMED");
            }
        } catch (NoSuchFileException unavailable) {
            // Kernel removed the group, or the manager never created it.
            if (!Files.notExists(group)) {
                throw new IOException("OS_RESOURCE_SCOPE_CLEANUP_UNCONFIRMED");
            }
        }
    }

    private static ManagerResult manager(String runtimeDirectory, String... arguments) throws IOException {
        var command = new ArrayList<>(List.of(SYSTEMCTL.toString(), "--user", "--no-pager", "--no-ask-password"));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        builder.environment().put("XDG_RUNTIME_DIR", runtimeDirectory);
        Process process = builder.start();
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        try {
            while (process.isAlive()) {
                try {
                    if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        throw new IOException("OS_RESOURCE_SCOPE_MANAGER_TIMEOUT");
                    }
                } catch (InterruptedException cancelled) {
                    interrupted = true;
                }
            }
            try (var input = process.getInputStream()) {
                byte[] output = input.readNBytes(MAX_MANAGER_OUTPUT + 1);
                if (output.length > MAX_MANAGER_OUTPUT) {
                    throw new IOException("OS_RESOURCE_SCOPE_MANAGER_OUTPUT_INVALID");
                }
                return new ManagerResult(process.exitValue(), new String(output, StandardCharsets.UTF_8).strip());
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private record ManagerResult(int exitCode, String output) { }
}
