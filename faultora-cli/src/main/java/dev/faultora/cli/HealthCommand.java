package dev.faultora.cli;

import dev.faultora.spec.parser.DurationSyntax;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalLong;

/**
 * {@code faultora health} — read a runner's status file and say whether it is
 * all right.
 * <p>
 * A command rather than an endpoint, because a runner has no port and adding
 * one to answer probes would give up the property the whole deployment shape
 * exists for. A container platform runs this as an exec probe; it needs no
 * shell and no network.
 * <p>
 * Two questions, and they are not the same question:
 * <ul>
 *   <li><b>Live</b> — the status file is fresh, so the runner's own timer is
 *       still running. A wedged process fails this and should be restarted.</li>
 *   <li><b>Ready</b> ({@code --require-registered}) — a dispatcher has accepted
 *       this runner. It fails while the control plane is unreachable, which is
 *       information and not a reason to restart anything.</li>
 * </ul>
 * <b>An unreachable control plane must never fail liveness.</b> Restarting does
 * not make it reachable, and a hiccup would otherwise restart every runner in a
 * fleet at the same moment.
 */
final class HealthCommand implements Command {

    /** Comfortably more than the runner's own write interval. */
    private static final long DEFAULT_MAX_AGE_MS = 30_000;

    private final PrintWriter out;
    private final PrintWriter err;

    HealthCommand(PrintWriter out, PrintWriter err) {
        this.out = out;
        this.err = err;
    }

    @Override
    public int execute(List<String> args) {
        Path file = null;
        long maxAgeMs = DEFAULT_MAX_AGE_MS;
        boolean requireRegistered = false;

        Iterator<String> remaining = args.iterator();
        while (remaining.hasNext()) {
            String option = remaining.next();
            switch (option) {
                case "--help", "-h" -> {
                    printUsage();
                    return FaultoraCli.EXIT_PASS;
                }
                case "--file" -> file = Path.of(value(remaining, option));
                case "--max-age" -> maxAgeMs = duration(value(remaining, option));
                case "--require-registered" -> requireRegistered = true;
                default -> throw new CliException(
                        "Unknown option: " + option, FaultoraCli.EXIT_INVALID_CONFIG);
            }
        }
        if (file == null) {
            throw new CliException(
                    "Missing --file: the status file the runner writes",
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }

        RunnerHealth.Status status;
        try {
            status = RunnerHealth.read(file);
        } catch (Exception unreadable) {
            err.println("Not healthy: " + file + " could not be read — "
                    + unreadable.getMessage());
            return FaultoraCli.EXIT_RUNNER_FAILURE;
        }

        if (!status.isFresh(System.currentTimeMillis(), maxAgeMs)) {
            err.println("Not healthy: " + status.runnerId() + " last wrote "
                    + (System.currentTimeMillis() - status.updatedAtEpochMs())
                    + "ms ago, and " + maxAgeMs + "ms is the most this allows");
            return FaultoraCli.EXIT_RUNNER_FAILURE;
        }
        if (requireRegistered && !status.registered()) {
            // Deliberately not a liveness answer. The runner is fine; the
            // control plane is not reachable, and restarting this would not
            // change that.
            err.println("Not ready: " + status.runnerId() + " is " + status.state()
                    + " and has no session with a dispatcher");
            return FaultoraCli.EXIT_RUNNER_FAILURE;
        }

        out.println(status.runnerId() + " " + status.state()
                + (status.currentRunId() == null ? "" : " running " + status.currentRunId())
                + " — " + status.runsServed() + " served");
        return FaultoraCli.EXIT_PASS;
    }

    private static long duration(String value) {
        OptionalLong millis = DurationSyntax.parseMillis(value);
        if (millis.isEmpty() || millis.getAsLong() <= 0) {
            throw new CliException(
                    "--max-age expects " + DurationSyntax.ACCEPTED_FORMS + "; got: " + value,
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return millis.getAsLong();
    }

    private static String value(Iterator<String> remaining, String option) {
        if (!remaining.hasNext()) {
            throw new CliException(option + " needs a value", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return remaining.next();
    }

    private void printUsage() {
        out.println("Usage: faultora health --file <status file> [options]");
        out.println();
        out.println("Reads the file `faultora runner` writes. Exit 0 means healthy.");
        out.println();
        out.println("  --file <path>           The runner's status file");
        out.println("  --max-age <duration>    How stale the file may be (default 30s)");
        out.println("  --require-registered    Also require a session with a dispatcher;");
        out.println("                          use for readiness, never for liveness");
    }
}
