package dev.faultora.cli;

import dev.faultora.engine.plan.PlanDiagnostic;
import dev.faultora.engine.run.RunResult;
import dev.faultora.runtime.PlanPreview;
import dev.faultora.spec.parser.Diagnostic;
import dev.faultora.spi.result.AssertionResult;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Per-invocation output and typed diagnostics; never captures or swaps process streams. */
final class CommandOutput {
    private final PrintWriter out;
    private final PrintWriter err;
    private final List<CliDiagnostic> diagnostics = new ArrayList<>();
    private PlanPreview preview;

    CommandOutput(PrintWriter out, PrintWriter err, boolean machine) {
        this.out = machine ? new PrintWriter(Writer.nullWriter()) : out;
        this.err = machine ? new PrintWriter(Writer.nullWriter()) : err;
    }

    static CommandOutput human() {
        return new CommandOutput(new PrintWriter(System.out, true), new PrintWriter(System.err, true), false);
    }

    PrintWriter out() { return out; }
    PrintWriter err() { return err; }

    void error(CliDiagnostic.Code code, String node, String path) {
        diagnostics.add(CliDiagnostic.of(code, "ERROR", node, path));
    }

    void scenario(List<Diagnostic> source) {
        source.forEach(diagnostic -> diagnostics.add(CliDiagnostic.of(
                diagnostic.isError() ? CliDiagnostic.Code.SCENARIO_INVALID : CliDiagnostic.Code.SCENARIO_WARNING,
                diagnostic.severity().name(), null, diagnostic.path())));
    }

    void plan(List<PlanDiagnostic> source) {
        source.forEach(diagnostic -> diagnostics.add(CliDiagnostic.of(
                diagnostic.isError() ? CliDiagnostic.Code.PLAN_REFUSED : CliDiagnostic.Code.PLAN_WARNING,
                diagnostic.severity().name(), diagnostic.stepId(), diagnostic.phase())));
    }

    void preview(PlanPreview value) { preview = value; }

    void run(RunResult result) {
        result.nodeResults().values().stream()
                .sorted(Comparator.comparing(node -> node.nodeId().value()))
                .forEach(node -> {
                    if (node.status() == RunResult.Status.FAILED) {
                        boolean failed = node.assertions().stream().anyMatch(assertion ->
                                assertion.outcome() == AssertionResult.Outcome.FAIL);
                        boolean indeterminate = node.assertions().stream().anyMatch(assertion ->
                                assertion.outcome() == AssertionResult.Outcome.INDETERMINATE);
                        if (failed) { error(CliDiagnostic.Code.ASSERTION_FAILED, node.nodeId().value(), null); }
                        if (indeterminate) { error(CliDiagnostic.Code.ASSERTION_INDETERMINATE, node.nodeId().value(), null); }
                        if (!failed && !indeterminate) { error(CliDiagnostic.Code.NODE_FAILED, node.nodeId().value(), null); }
                    } else if (node.status() == RunResult.Status.ERROR) {
                        error(CliDiagnostic.Code.NODE_ERROR, node.nodeId().value(), null);
                    }
                });
        if (result.status() == RunResult.Status.ERROR) { error(CliDiagnostic.Code.RUN_ERROR, null, null); }
        if (result.status() == RunResult.Status.CANCELLED) { error(CliDiagnostic.Code.RUN_CANCELLED, null, null); }
        if (result.status() == RunResult.Status.FAILED && !hasErrors()) { error(CliDiagnostic.Code.RUN_FAILED, null, null); }
    }

    boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity().equals("ERROR"));
    }

    Result finish(String command, int exitCode) {
        if (exitCode != 0 && !hasErrors()) {
            error(exitCode == FaultoraCli.EXIT_INVALID_CONFIG
                    ? CliDiagnostic.Code.COMMAND_INVALID : CliDiagnostic.Code.COMMAND_FAILED, null, null);
        }
        out.flush();
        err.flush();
        return new Result(1, command, exitCode, diagnostics, preview);
    }

    record Result(int schemaVersion, String command, int exitCode,
                  List<CliDiagnostic> diagnostics, PlanPreview preview) {
        Result {
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
