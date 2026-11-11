package dev.faultora.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.engine.run.RunResult;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.runner.protocol.DurableDelivery;
import dev.faultora.runtime.RunArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkDirectoryBudgetTest {
    private static final long MIB = 1024L * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path work;

    @Test
    void countLimitPreservesActiveAndUnacknowledgedSpoolsAcrossReopening() throws Exception {
        var limits = new WorkDirectoryBudget.Limits(1024 * MIB, 2, 8, 129 * MIB);
        var storage = new WorkDirectoryBudget(work, limits, path -> Long.MAX_VALUE);
        var completed = claim(storage, new RunId("run-completed"));
        Path terminalPath = completed.directory().resolve("spool.bin");
        try (var spool = DurableRunSpool.create(terminalPath, identity(completed.runId()))) {
            spool.commitTerminal(RunResult.Status.PASSED, true, false);
        }
        var terminal = DurableRunSpool.recover(terminalPath).terminal();
        byte[] original = Files.readAllBytes(terminalPath);

        var active = claim(storage, new RunId("run-active"));
        Path activePath = active.directory().resolve("spool.bin");
        try (var spool = DurableRunSpool.create(activePath, identity(active.runId()))) {
            var reopened = new WorkDirectoryBudget(work, limits, path -> Long.MAX_VALUE);
            var refused = new RunId("run-refused");
            assertThatThrownBy(() -> claim(reopened, refused)).isInstanceOf(WorkDirectoryBudget.Failure.class)
                    .hasMessage("WORK_DIRECTORY_STORAGE_FULL");
            assertThat(RunArtifacts.dispatchedDirectory(work, refused)).doesNotExist();
            assertThat(activePath).isRegularFile();
        }

        assertThat(Files.readAllBytes(terminalPath)).isEqualTo(original);
        assertThat(DurableRunSpool.recover(terminalPath).terminal()).isEqualTo(terminal);
        assertThat(DurableRunSpool.recover(activePath).recoveryRequired()).isTrue();
    }

    @Test
    void byteLimitCountsOversizedExistingArtifactsAndSharedState() throws Exception {
        var existing = RunArtifacts.claimDispatched(work, new RunId("run-existing"));
        Files.write(existing.journalPath(), new byte[70]);
        Path faults = Files.createDirectory(existing.directory().resolve("fault-obligations"));
        Files.write(faults.resolve("state.bin"), new byte[45]);
        Files.write(existing.directory().resolve("delivery-ack.json"), new byte[20]);
        Path replay = Files.createDirectory(work.resolve("dispatch-replay"));
        Files.write(replay.resolve("state.bin"), new byte[(int) MIB + 12]);
        long existingBytes = MIB + 70 + 45 + 20 + 12 + Files.size(existing.directory().resolve("run-owner"));

        var atLimit = new WorkDirectoryBudget(work, new WorkDirectoryBudget.Limits(existingBytes + 100, 3, 8, 100),
                path -> Long.MAX_VALUE);
        var belowLimit = new WorkDirectoryBudget(work, new WorkDirectoryBudget.Limits(existingBytes + 99, 3, 8, 100),
                path -> Long.MAX_VALUE);
        var next = new RunId("run-next");
        assertThatThrownBy(() -> claim(belowLimit, next)).hasMessage("WORK_DIRECTORY_STORAGE_FULL");
        assertThat(RunArtifacts.dispatchedDirectory(work, next)).doesNotExist();
        assertThat(claim(atLimit, next).directory().resolve("run-owner")).hasContent(next.value());
    }

    @Test
    void usableSpaceMustCoverUnwrittenReservationsAndNewRunTogether() throws Exception {
        var existing = RunArtifacts.claimDispatched(work, new RunId("run-partial"));
        long stored = Files.size(existing.directory().resolve("run-owner"));
        long needed = 2 * MIB + 200 - stored;
        var limits = new WorkDirectoryBudget.Limits(MIB + 500, 4, 8, 100);
        var shortOnSpace = new WorkDirectoryBudget(work, limits, path -> needed - 1);
        var next = new RunId("run-next");

        assertThatThrownBy(() -> claim(shortOnSpace, next)).hasMessage("WORK_DIRECTORY_STORAGE_FULL");
        assertThat(RunArtifacts.dispatchedDirectory(work, next)).doesNotExist();
        assertThat(existing.directory().resolve("run-owner")).hasContent(existing.runId().value());
        assertThat(claim(new WorkDirectoryBudget(work, limits, path -> needed), next).directory()).exists();
    }

    @Test
    void onlyCompletedRetirementReleasesTheRunSlotAndChargesTheActualFiles() throws Exception {
        var existing = RunArtifacts.claimDispatched(work, new RunId("run-retired"));
        Path receipt = retirementReceipt(existing);
        Path spool = existing.directory().resolve("spool.bin");
        Files.writeString(spool, "synthetic spool");
        Files.writeString(existing.journalPath(), "synthetic journal");
        long stored = Files.size(receipt) + Files.size(existing.directory().resolve("run-owner"))
                + Files.size(existing.directory().resolve("delivery-ack.json"));
        long budget = MIB + stored + 4096;
        long usable = 2 * MIB + 4096 - Files.size(work.resolve("runner-identity"));
        var atLimit = new WorkDirectoryBudget(work, new WorkDirectoryBudget.Limits(budget, 1, 4, 4096),
                path -> usable);
        var next = new RunId("run-next");

        assertThatThrownBy(() -> claim(atLimit, next)).hasMessage("WORK_DIRECTORY_STORAGE_FULL");
        Files.delete(existing.journalPath());
        assertThatThrownBy(() -> claim(atLimit, next)).hasMessage("WORK_DIRECTORY_STORAGE_FULL");
        Files.delete(spool);

        var belowLimit = new WorkDirectoryBudget(work, new WorkDirectoryBudget.Limits(budget - 1, 1, 4, 4096),
                path -> Long.MAX_VALUE);
        assertThatThrownBy(() -> claim(belowLimit, next)).hasMessage("WORK_DIRECTORY_STORAGE_FULL");
        assertThat(claim(atLimit, next).directory()).exists();
        assertThatThrownBy(() -> RunArtifacts.claimDispatched(work, existing.runId()))
                .isInstanceOf(RunArtifacts.AlreadyOwned.class);
    }

    @Test
    void retirementStillConsumesTheBoundedOwnershipSlot() throws Exception {
        var existing = RunArtifacts.claimDispatched(work, new RunId("run-retired"));
        Path receipt = retirementReceipt(existing);
        var storage = new WorkDirectoryBudget(work, new WorkDirectoryBudget.Limits(1024 * MIB, 1, 1, 4096),
                path -> Long.MAX_VALUE);

        assertThatThrownBy(() -> claim(storage, new RunId("run-next"))).hasMessage("WORK_DIRECTORY_STORAGE_FULL");
        assertThat(receipt).isRegularFile();
        assertThat(existing.directory().resolve("run-owner")).hasContent(existing.runId().value());
    }

    @Test
    void mismatchedRetirementReceiptCannotReleaseCapacity() throws Exception {
        var existing = RunArtifacts.claimDispatched(work, new RunId("run-retired"));
        Path receipt = retirementReceipt(existing);
        var mismatched = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(receipt.toFile());
        mismatched.put("routeDigest", ContentDigest.sha256Uri("different synthetic route"));
        JSON.writeValue(receipt.toFile(), mismatched);
        var storage = new WorkDirectoryBudget(work, new WorkDirectoryBudget.Limits(1024 * MIB, 1, 4, 4096),
                path -> Long.MAX_VALUE);
        var next = new RunId("run-next");

        assertThatThrownBy(() -> claim(storage, next)).isInstanceOf(WorkDirectoryBudget.Failure.class);
        assertThat(RunArtifacts.dispatchedDirectory(work, next)).doesNotExist();
        assertThat(receipt).isRegularFile();
    }

    @Test
    void concurrentClaimCannotSpendTheSameCapacityTwice() throws Exception {
        var checking = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var limits = new WorkDirectoryBudget.Limits(MIB + 100, 1, 8, 100);
        var first = new WorkDirectoryBudget(work, limits, path -> {
            checking.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture lock wait expired");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            return Long.MAX_VALUE;
        });
        var second = new WorkDirectoryBudget(work, limits, path -> Long.MAX_VALUE);
        var next = new RunId("run-second");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> claim(first, new RunId("run-first")));
            try {
                assertThat(checking.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> claim(second, next)).hasMessage("WORK_DIRECTORY_STORAGE_BUSY");
            } finally {
                release.countDown();
            }
            assertThat(pending.get(5, TimeUnit.SECONDS).directory()).exists();
        }
        assertThatThrownBy(() -> claim(second, next)).hasMessage("WORK_DIRECTORY_STORAGE_FULL");
        assertThat(RunArtifacts.dispatchedDirectory(work, next)).doesNotExist();
    }

    @Test
    void unsafeArtifactShapeOrStorageErrorUsesFixedDiagnostic() throws Exception {
        var existing = RunArtifacts.dispatchedDirectory(work, new RunId("run-existing"));
        Files.writeString(existing, "synthetic file");
        var budget = new WorkDirectoryBudget(work, WorkDirectoryBudget.DEFAULTS, path -> Long.MAX_VALUE);
        assertThatThrownBy(() -> claim(budget, new RunId("run-next"))).hasMessage("WORK_DIRECTORY_STORAGE_INVALID");
        assertThat(existing).hasContent("synthetic file");

        var elsewhere = work.resolve("other-work");
        var unavailable = new WorkDirectoryBudget(elsewhere, WorkDirectoryBudget.DEFAULTS, path -> {
            throw new IOException("SYNTHETIC_PRIVATE_VOLUME_ERROR");
        });
        assertThatThrownBy(() -> claim(unavailable, new RunId("run-next")))
                .hasMessage("WORK_DIRECTORY_STORAGE_UNAVAILABLE").hasNoCause();
    }

    private static RunArtifacts claim(WorkDirectoryBudget budget, RunId runId) throws IOException {
        return budget.claim(runId, () -> true).orElseThrow();
    }

    private Path retirementReceipt(RunArtifacts artifacts) throws IOException {
        var owner = identity(artifacts.runId());
        var attempt = new DurableDelivery.Attempt(owner.runnerId(), owner.runId(), owner.attemptId(), owner.dispatchDigest());
        var terminal = DurableDelivery.Terminal.of(attempt, 0, "PASSED", true, false, "NONE");
        String routeDigest = ContentDigest.sha256Uri("synthetic route");
        Path checkpoint = artifacts.directory().resolve("delivery-ack.json");
        JSON.writeValue(checkpoint.toFile(), Map.of("routeDigest", routeDigest, "attempt", attempt,
                "nextPosition", terminal.finalPosition(), "terminalDigest", terminal.terminalDigest()));
        Files.createFile(artifacts.directory().resolve("delivery-lock"));
        Path receipt = artifacts.directory().resolve("retention-receipt.json");
        JSON.writeValue(receipt.toFile(), Map.of("routeDigest", routeDigest, "terminal", terminal,
                "acknowledgedAt", Files.getLastModifiedTime(checkpoint).toInstant().toString()));
        return receipt;
    }

    private DurableRunSpool.Identity identity(RunId runId) throws IOException {
        return new DurableRunSpool.Identity(DurableRunSpool.runnerIdentity(work), runId.value(),
                UUID.randomUUID().toString(), ContentDigest.sha256Uri("synthetic dispatch"), 1);
    }
}
