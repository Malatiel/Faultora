package dev.faultora.cli;

/** Stable machine diagnostics. Hints never interpolate document values or exception text. */
record CliDiagnostic(Code code, String severity, String node, String path, String hint) {
    enum Code {
        COMMAND_REQUIRED("Choose a command; use --help to list commands."),
        UNKNOWN_COMMAND("Choose one of the commands listed by --help."),
        INVALID_ARGUMENT("Check command options with <command> --help."),
        FILE_READ_FAILED("Check that the input file exists and is readable."),
        SCENARIO_INVALID("Correct the scenario at the document path, then validate again."),
        SCENARIO_WARNING("Review the scenario at the document path."),
        PLAN_REFUSED("Review the node, catalog references and effective execution policy."),
        PLAN_WARNING("Review the node and effective execution budgets."),
        CATALOG_IMPORT_FAILED("Check catalog syntax, source family and duplicate identifiers."),
        CAPABILITY_UNAVAILABLE("Choose an installed capability admitted by the deployment policy."),
        INPUT_UNKNOWN("Use only inputs declared by the scenario."),
        INPUT_REQUIRED("Supply the declared input with --input name=value."),
        ARTIFACT_WRITE_FAILED("Check that the artifact directory is writable."),
        REPORT_WRITE_FAILED("Check report formats and artifact directory permissions."),
        PREPARATION_FAILED("Check scenario, catalog and deployment configuration before retrying."),
        ASSERTION_FAILED("Review the assertion and the permitted evidence for this node."),
        ASSERTION_INDETERMINATE("Review evidence availability and assertion configuration."),
        NODE_FAILED("Review this node's result and dependencies in the run journal."),
        NODE_ERROR("Review this node's configuration and permitted target capabilities."),
        RUN_FAILED("Review the failed nodes in the run journal."),
        RUN_ERROR("Review deployment configuration and the run journal before retrying."),
        RUN_CANCELLED("Check cancellation, deadlines and lease state before retrying."),
        COMMAND_INVALID("Review command configuration and input files."),
        COMMAND_FAILED("Review command configuration and deployment health.");

        private final String hint;

        Code(String hint) {
            this.hint = hint;
        }
    }

    static CliDiagnostic of(Code code, String severity, String node, String path) {
        return new CliDiagnostic(code, severity, location(node), location(path), code.hint);
    }

    /** Locations identify public topology, never filesystem paths or arbitrary provider prose. */
    private static String location(String value) {
        return value != null && value.length() <= 256
                && value.matches("[A-Za-z_][A-Za-z0-9_.\\[\\]-]*") ? value : null;
    }
}
