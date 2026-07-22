package dev.faultora.cli;

import java.util.List;

/**
 * Command interface for CLI commands.
 */
public interface Command {

    /**
     * Execute the command with the given arguments.
     *
     * @param args command-specific arguments
     * @return exit code (0=pass, 1=test failure, 2=invalid config, 3=runner failure)
     */
    int execute(List<String> args);
}
