package dev.faultora.cli;

import dev.faultora.mcp.McpApproval;
import dev.faultora.mcp.McpConfiguration;
import dev.faultora.mcp.McpStdioServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            err.println("       faultora mcp approve --private-key <PKCS8 DER> --prepared-id <id>"
                    + " --digest <sha256:digest> --run-id <id> [--expires-in <seconds, default 60>]");
            err.flush();
            return FaultoraCli.EXIT_PASS;
        }
        if (!args.isEmpty() && args.getFirst().equals("approve")) {
            return approve(args.subList(1, args.size()));
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

    private int approve(List<String> args) {
        try {
            Map<String, String> options = new LinkedHashMap<>();
            Set<String> allowed = Set.of("--private-key", "--prepared-id", "--digest", "--run-id", "--expires-in");
            if (args.size() % 2 != 0) {
                throw new IllegalArgumentException();
            }
            for (int index = 0; index < args.size(); index += 2) {
                String name = args.get(index);
                if (!allowed.contains(name) || options.putIfAbsent(name, args.get(index + 1)) != null) {
                    throw new IllegalArgumentException();
                }
            }
            if (!options.keySet().containsAll(Set.of("--private-key", "--prepared-id", "--digest", "--run-id"))) {
                throw new IllegalArgumentException();
            }
            String token = McpApproval.sign(Path.of(options.get("--private-key")), options.get("--prepared-id"),
                    options.get("--digest"), options.get("--run-id"),
                    Duration.ofSeconds(Long.parseLong(options.getOrDefault("--expires-in", "60"))));
            out.println(token);
            out.flush();
            return out.checkError() ? FaultoraCli.EXIT_RUNNER_FAILURE : FaultoraCli.EXIT_PASS;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException invalid) {
            err.println("MCP approval requires a valid operator key, preparation identity and bounded lifetime");
            err.flush();
            return FaultoraCli.EXIT_INVALID_CONFIG;
        }
    }
}
