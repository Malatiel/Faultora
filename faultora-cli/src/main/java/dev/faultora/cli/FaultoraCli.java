package dev.faultora.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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
 *   <li>{@code runner --dispatcher <url>} — serve a control plane from inside a
 *       private network, dialling out for work</li>
 *   <li>{@code health --file <status file>} — read what a runner says about
 *       itself, for a probe that cannot connect to one</li>
 *   <li>{@code migrate <path>...} — move documents to the current API
 *       version</li>
 *   <li>{@code diagnose --run <dir> --output <zip>} — a bundle for a bug
 *       report, reduced to what the run did and nothing it saw</li>
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

    private static final String VERSION = dev.faultora.runtime.FaultoraVersion.current();

    private final PrintWriter out;
    private final PrintWriter err;

    public FaultoraCli(PrintWriter out, PrintWriter err) {
        this.out = out;
        this.err = err;
    }

    private Map<String, Command> commands(CommandOutput output) {
        Map<String, Command> commands = new LinkedHashMap<>();
        commands.put("init", new InitCommand(output));
        commands.put("validate", new ValidateCommand(output));
        commands.put("discover", new DiscoverCommand(output));
        commands.put("test", new TestCommand(output));
        commands.put("runner", new RunnerCommand(output.out(), output.err()));
        commands.put("health", new HealthCommand(output.out(), output.err()));
        commands.put("migrate", new MigrateCommand(output.out(), output.err()));
        commands.put("diagnose", new DiagnoseCommand(output.out(), output.err()));
        return commands;
    }

    /**
     * CLI entry point.
     */
    public static void main(String[] args) {
        FaultoraCli cli = new FaultoraCli(
                new PrintWriter(System.out, true, StandardCharsets.UTF_8),
                new PrintWriter(System.err, true, StandardCharsets.UTF_8));
        int exitCode = cli.run(args);
        System.exit(exitCode);
    }

    /**
     * Run the CLI with the given arguments. Returns the exit code.
     */
    public int run(String[] args) {
        boolean machine = args.length > 0 && "--diagnostics=json".equals(args[0]);
        String[] commandLine = machine ? Arrays.copyOfRange(args, 1, args.length) : args;
        boolean mcp = commandLine.length > 0 && commandLine[0].equals("mcp");
        if (mcp && !machine) {
            return new McpCommand(System.in, out, err)
                    .execute(List.of(Arrays.copyOfRange(commandLine, 1, commandLine.length)));
        }
        CommandOutput output = new CommandOutput(out, err, machine);
        Map<String, Command> commands = commands(output);
        int exitCode;
        if (mcp) {
            output.error(CliDiagnostic.Code.INVALID_ARGUMENT, null, null);
            exitCode = EXIT_INVALID_CONFIG;
        } else {
            exitCode = execute(commandLine, commands, output);
        }
        String command = commandLine.length == 0 ? null : commandLine[0];
        if (command != null && !mcp && !commands.containsKey(command)) {
            command = switch (command) {
                case "--help", "-h" -> "help";
                case "--version", "-v" -> "version";
                default -> null;
            };
        }
        CommandOutput.Result result = output.finish(command, exitCode);
        if (machine) {
            try {
                out.println(new ObjectMapper().writeValueAsString(result));
                out.flush();
            } catch (JsonProcessingException impossible) {
                err.println("Cannot encode CLI diagnostics");
                return EXIT_RUNNER_FAILURE;
            }
        }
        return exitCode;
    }

    private int execute(String[] args, Map<String, Command> commands, CommandOutput output) {
        PrintWriter out = output.out();
        PrintWriter err = output.err();
        if (args.length == 0) {
            output.error(CliDiagnostic.Code.COMMAND_REQUIRED, null, null);
            printUsage(out);
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
                printUsage(out);
                return EXIT_PASS;
            }
        }

        Command command = commands.get(commandName);
        if (command == null) {
            output.error(CliDiagnostic.Code.UNKNOWN_COMMAND, null, null);
            err.println("Unknown command: " + commandName);
            printUsage(out);
            return EXIT_INVALID_CONFIG;
        }

        try {
            return command.execute(commandArgs);
        } catch (CliException e) {
            if (!output.hasErrors()) {
                output.error(CliDiagnostic.Code.INVALID_ARGUMENT, null, null);
            }
            err.println("Error: " + e.getMessage());
            return e.exitCode();
        } catch (Exception e) {
            output.error(CliDiagnostic.Code.COMMAND_FAILED, null, null);
            err.println("Unexpected error: " + e.getMessage());
            return EXIT_RUNNER_FAILURE;
        }
    }

    PrintWriter out() { return out; }
    PrintWriter err() { return err; }

    private void printUsage(PrintWriter out) {
        out.println("Usage: faultora [--diagnostics=json] <command> [options]");
        out.println();
        out.println("Commands:");
        out.println("  mcp        --config <file>                         Prepare and approve execution over MCP stdio");
        out.println("  init       --from-openapi <file> [--output <dir>]   Import OpenAPI and generate scenario");
        out.println("  validate   --scenario <file>                        Validate a scenario document");
        out.println("  discover   --from-openapi <file>                    List operations from OpenAPI");
        out.println("  test       --scenario <file> [options]              Run scenario against target");
        out.println("             --preview                                Describe the plan without executing");
        out.println("  runner     --dispatcher <url> [options]             Serve a control plane, dialling out");
        out.println("  health     --file <status file>                     Is the runner in this container all right?");
        out.println("  migrate    <file or directory>... [--write]        Move documents to the current API version");
        out.println("  diagnose   --run <dir> --output <bundle.zip>        A bug-report bundle with no payloads");
        out.println();
        out.println("Options:");
        out.println("  -v, --version   Show version");
        out.println("  -h, --help      Show help");
        out.println("  --diagnostics=json  Emit one machine diagnostic result (before the command)");
        out.println();
        out.println("Exit codes: 0 = pass, 1 = test failure, 2 = config error, 3 = runner error");
    }
}
