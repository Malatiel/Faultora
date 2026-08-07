package dev.faultora.runner;

import dev.faultora.runner.protocol.Refusal;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the far side is told a run came to.
 * <p>
 * A dispatcher reads this and nothing else about a run that never produced a
 * result, so a shape that says something went wrong without saying what leaves
 * an operator with a runner inside a private network and no way to ask.
 */
class RunnerAgentTest {

    @Test
    void aRunThatBrokeSaysWhat() {
        // It said {"broke":true} and no more — the one outcome with no other
        // diagnosis anywhere was the one that carried none. A refusal names its
        // reason and a finished run has a journal behind it; this has neither.
        DispatchedRun.Outcome broke = DispatchedRun.Outcome.broke(
                Path.of("run.ndjson"),
                "run 'run-1' stopped part-way: the ledger refused the connection");

        assertThat(RunnerAgent.describe(broke))
                .contains("\"broke\":true")
                .contains("the ledger refused the connection");
    }

    @Test
    void aRefusalTravelsAsItsReasonAndItsWords() {
        DispatchedRun.Outcome refused = DispatchedRun.Outcome.refused(
                Refusal.of(Refusal.Reason.DIGEST_MISMATCH,
                        "the scenario is not the one that was sent"));

        assertThat(RunnerAgent.describe(refused))
                .contains("DIGEST_MISMATCH")
                .contains("the scenario is not the one that was sent");
    }

    @Test
    void aRunThatEndedSaysHowAndWhetherItsPermissionRanOut() {
        DispatchedRun.Outcome ran = DispatchedRun.Outcome.ran(
                new dev.faultora.engine.run.RunResult(
                        new dev.faultora.model.identifier.RunId("run-1"),
                        dev.faultora.engine.run.RunResult.Status.PASSED,
                        3, 2, 0, java.util.Map.of(), 10, null),
                Path.of("run.ndjson"), true);

        assertThat(RunnerAgent.describe(ran))
                .contains("\"status\":\"PASSED\"", "\"leaseExpired\":true");
    }
}
