package dev.faultora.cli;

import java.io.PrintWriter;
import java.util.*;

/**
 * Faultora CLI — command-line interface for the reliability testing platform.
 * <p>
 * Commands:
 * <ul>
 *   <li>{@code init --from-openapi <file>} — import an OpenAPI document and generate a scenario</li>
 *   <li>{@code validate --scenario <file>} — validate a scenario document</li>
 *   <li>{@code discover --from-openapi <file>} — list operations from an OpenAPI document</li>
 *   <li>{@code test --scenario <file> [--target <url>] [--format <fmt>]} — run scenario</li>
 * </ul>
 * <p>
 * Exit codes: 0 = pass, 1 = test failure, 2 = invalid configuration, 3 = runner failure.
 */
public class FaultoraCli {

    /** Exit code: all tests passed or command succeeded. */
    public static final int EXIT_PASS = 0;
    /** Exit code: test failure (assertion failed). */
    public static final int EXIT_TEST_FAILURE = 1;
    /** Exit code: invalid configuration or input. */
    public static final int EXIT_INVALID_CONFIG = 2;
    /** Exit code: runner/engine failure. */
    public static final int EXIT_RUNNER_FAILURE = 3;

    private static final String VERSION = Optional.ofNullable(
            FaultoraCli.class.getPackage().getImplementationVersion()).orElse("0.8.0");

    private final PrintWriter out;
    private final PrintWriter err;
    private final Map<String, Command> commands;

    public FaultoraCli(PrintWriter out, PrintWriter err) {
        this.out = out;
        this.err = err;
        this.commands = new LinkedHashMap<>();
        commands.put("init", new InitCommand());
        commands.put("validate", new ValidateCommand());
        commands.put("discover", new DiscoverCommand());
        commands.put("test", new TestCommand());
    }

    /**
     * CLI entry point.
     */
    public static void main(String[] args) {
        FaultoraCli cli = new FaultoraCli(
                new PrintWriter(System.out, true),
                new PrintWriter(System.err, true));
        int exitCode = cli.run(args);
        System.exit(exitCode);
    }

    /**
     * Run the CLI with the given arguments. Returns the exit code.
     */
    public int run(String[] args) {
        if (args.length == 0) {
            printUsage();
            return EXIT_INVALID_CONFIG;
        }

        String commandName = args[0];
        List<String> commandArgs = List.of(Arrays.copyOfRange(args, 1, args.length));

        switch (commandName) {
            case "--version", "-v" -> {
                out.println("faultora " + VERSION);
                return EXIT_PASS;
            }
            case "--help", "-h" -> {
                printUsage();
                return EXIT_PASS;
            }
        }

        Command command = commands.get(commandName);
        if (command == null) {
            err.println("Unknown command: " + commandName);
            printUsage();
            return EXIT_INVALID_CONFIG;
        }

        try {
            return command.execute(commandArgs);
        } catch (CliException e) {
            err.println("Error: " + e.getMessage());
            return e.exitCode();
        } catch (Exception e) {
            err.println("Unexpected error: " + e.getMessage());
            return EXIT_RUNNER_FAILURE;
        }
    }

    PrintWriter out() { return out; }
    PrintWriter err() { return err; }

    private void printUsage() {
        out.println("Usage: faultora <command> [options]");
        out.println();
        out.println("Commands:");
        out.println("  init       --from-openapi <file> [--output <dir>]   Import OpenAPI and generate scenario");
        out.println("  validate   --scenario <file>                        Validate a scenario document");
        out.println("  discover   --from-openapi <file>                    List operations from OpenAPI");
        out.println("  test       --scenario <file> [options]              Run scenario against target");
        out.println();
        out.println("Options:");
        out.println("  -v, --version   Show version");
        out.println("  -h, --help      Show help");
        out.println();
        out.println("Exit codes: 0 = pass, 1 = test failure, 2 = config error, 3 = runner error");
    }
}
