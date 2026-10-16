package dev.faultora.cli;

import dev.faultora.mcp.McpConfiguration;
import dev.faultora.mcp.McpStdioServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

/** Operator startup arguments are separate from every untrusted tool request. */
final class McpCommand implements Command {
    private final InputStream input;
    private final PrintWriter out;
    private final PrintWriter err;

    McpCommand(InputStream input, PrintWriter out, PrintWriter err) {
        this.input = input;
        this.out = out;
        this.err = err;
    }

    @Override
    public int execute(List<String> args) {
        if (args.equals(List.of("--help"))) {
            err.println("Usage: faultora mcp --config <operator JSON file>");
            err.flush();
            return FaultoraCli.EXIT_PASS;
        }
        if (args.size() != 2 || !args.getFirst().equals("--config")) {
            err.println("MCP requires --config <operator JSON file>");
            err.flush();
            return FaultoraCli.EXIT_INVALID_CONFIG;
        }
        McpConfiguration configuration;
        try {
            configuration = McpConfiguration.load(Path.of(args.get(1)));
        } catch (IOException | IllegalArgumentException invalid) {
            err.println("MCP configuration is invalid or a declared source is unavailable");
            err.flush();
            return FaultoraCli.EXIT_INVALID_CONFIG;
        }
        try {
            new McpStdioServer(configuration).serve(input, out);
            return FaultoraCli.EXIT_PASS;
        } catch (IOException unavailable) {
            err.println("MCP transport unavailable");
            err.flush();
            return FaultoraCli.EXIT_RUNNER_FAILURE;
        }
    }
}
