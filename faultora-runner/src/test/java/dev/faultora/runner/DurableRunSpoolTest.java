package dev.faultora.runner;

import dev.faultora.engine.run.RunResult;
import dev.faultora.model.events.RunEvent;
import dev.faultora.model.identifier.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurableRunSpoolTest {
    @TempDir Path directory;
    private static final RunId RUN = new RunId("run-spool-fixture");

    static DurableRunSpool.Identity identity() {
        return new DurableRunSpool.Identity("123e4567-e89b-12d3-a456-426614174000", RUN.value(),
                "123e4567-e89b-12d3-a456-426614174001", "sha256:" + "1".repeat(64), 123456789L);
    }

    static RunEvent event() {
        return new RunEvent.RunStarted("RUN_STARTED", 10, RUN, "sha256:scenario", "sha256:catalog", 42, Map.of());
    }

    @Test void committedFactsAndTerminalAreRecoveredWithoutRunningAnything() throws Exception {
        Path file = directory.resolve("spool.bin");
        try (var spool = DurableRunSpool.create(file, identity())) {
            spool.appendEvent(event());
            spool.commitTerminal(RunResult.Status.FAILED, true, false);
            assertThat(spool.snapshot().events()).isEqualTo(1);
            assertThat(spool.snapshot().terminal().finalPosition()).isEqualTo(1);
            assertThatThrownBy(() -> spool.appendEvent(event())).hasMessage("SPOOL_TERMINAL_EXISTS");
            assertThatThrownBy(() -> spool.commitTerminal(RunResult.Status.PASSED, true, false))
                    .hasMessage("SPOOL_TERMINAL_EXISTS");
            assertThatThrownBy(() -> DurableRunSpool.recover(file)).hasMessage("SPOOL_IN_USE");
        }
        var events = new ArrayList<RunEvent>();
        var recovered = DurableRunSpool.recover(file, events::add);
        assertThat(events).containsExactly(event());
        assertThat(recovered.identity()).isEqualTo(identity());
        assertThat(recovered.terminal().status()).isEqualTo(RunResult.Status.FAILED);
        assertThat(recovered.recoveryRequired()).isFalse();
        assertThat(recovered.uncommittedTail()).isFalse();
        assertThat(recovered.verifiedBytes()).isEqualTo(Files.size(file));
        assertThatThrownBy(() -> DurableRunSpool.create(file, identity())).isInstanceOf(IOException.class);
    }

    @Test void quotaReservesAnInfrastructureTerminalAndCancelsBusiness() throws Exception {
        Path file = directory.resolve("quota.bin");
        var cancelled = new AtomicBoolean();
        try (var spool = DurableRunSpool.create(file, identity(), new DurableRunSpool.Limits(16384, 1, 1024),
                channel -> channel.force(true));
             var journal = new SpoolingRunJournal(directory.resolve("events.ndjson"), spool, cancelled)) {
            journal.append(event());
            assertThatThrownBy(() -> journal.append(event())).hasMessage("MANDATORY_SPOOL_OR_JOURNAL_FAILED");
            assertThat(cancelled).isTrue();
            assertThat(journal.events()).containsExactly(event());
            assertThat(Files.readAllLines(journal.path())).hasSize(1);
            spool.commitTerminal(RunResult.Status.ERROR, false, false);
        }
        var recovered = DurableRunSpool.recover(file);
        assertThat(recovered.events()).isEqualTo(1);
        assertThat(recovered.terminal().reasonCode()).isEqualTo("INFRASTRUCTURE_ERROR");
        assertThat(recovered.terminal().cleanupConfirmed()).isFalse();
    }

    @Test void byteQuotaAndOversizedRecordsLeaveSpaceForTheTerminal() throws Exception {
        try (var spool = DurableRunSpool.create(directory.resolve("bytes.bin"), identity(),
                new DurableRunSpool.Limits(16384, 100, 1024), channel -> channel.force(true))) {
            RunEvent large = new RunEvent.NodeSkipped("NODE_SKIPPED", 11, RUN,
                    new dev.faultora.model.identifier.NodeId("node"), "x".repeat(2000));
            assertThatThrownBy(() -> spool.appendEvent(large)).hasMessage("SPOOL_RECORD_TOO_LARGE");
            boolean full = false;
            for (int count = 0; count < 100; count++) {
                try { spool.appendEvent(event()); }
                catch (IOException quota) { assertThat(quota).hasMessage("SPOOL_QUOTA_EXCEEDED"); full = true; break; }
            }
            assertThat(full).isTrue();
            assertThat(spool.snapshot().events()).isBetween(1L, 99L);
            spool.commitTerminal(RunResult.Status.ERROR, false, false);
            assertThat(Files.size(spool.path())).isLessThanOrEqualTo(16384);
        }
    }

    @Test void failedPayloadForceIsNeverMirroredOrMistakenForCommittedData() throws Exception {
        Path file = directory.resolve("failed.bin");
        var forces = new AtomicInteger();
        var cancelled = new AtomicBoolean();
        try (var spool = DurableRunSpool.create(file, identity(), DurableRunSpool.Limits.defaults(), channel -> {
            if (forces.incrementAndGet() == 3) throw new IOException("SYNTHETIC_PRIVATE_DISK_PATH");
            channel.force(true);
        }); var journal = new SpoolingRunJournal(directory.resolve("events.ndjson"), spool, cancelled)) {
            assertThatThrownBy(() -> journal.append(event())).hasMessage("MANDATORY_SPOOL_OR_JOURNAL_FAILED");
            assertThat(cancelled).isTrue();
            assertThat(Files.readString(journal.path())).isEmpty();
            assertThat(spool.snapshot().events()).isZero();
            assertThatThrownBy(() -> spool.commitTerminal(RunResult.Status.PASSED, true, false))
                    .hasMessage("SPOOL_UNAVAILABLE");
        }
        var recovered = DurableRunSpool.recover(file);
        assertThat(recovered.events()).isZero();
        assertThat(recovered.uncommittedTail()).isTrue();
        assertThat(recovered.recoveryRequired()).isTrue();
    }

    @Test void failedCertificateForceDoesNotPublishSuccessEvenIfRecoverySeesTheCommit() throws Exception {
        Path file = directory.resolve("certificate.bin");
        var forces = new AtomicInteger();
        try (var spool = DurableRunSpool.create(file, identity(), DurableRunSpool.Limits.defaults(), channel -> {
            if (forces.incrementAndGet() == 4) throw new IOException("SYNTHETIC_PRIVATE_DISK_PATH");
            channel.force(true);
        })) {
            assertThatThrownBy(() -> spool.commitTerminal(RunResult.Status.FAILED, true, false))
                    .hasMessage("SPOOL_IO_FAILED");
            assertThat(spool.snapshot().terminal()).isNull();
        }
        // The certificate may survive an unacknowledged force. Its payload was forced first.
        assertThat(DurableRunSpool.recover(file).terminal().status()).isEqualTo(RunResult.Status.FAILED);
    }

    @Test void aBrokenMirrorCannotConcatenateAnotherEventOntoItsPartialLine() throws Exception {
        var visible = new StringBuilder();
        var cancelled = new AtomicBoolean();
        var writer = new java.io.Writer() {
            @Override public void write(char[] chars, int offset, int length) throws IOException {
                visible.append(chars, offset, Math.min(7, length));
                throw new IOException("PRIVATE_MIRROR_MARKER");
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Path file = directory.resolve("mirror.bin");
        try (var spool = DurableRunSpool.create(file, identity());
             var journal = new SpoolingRunJournal(directory.resolve("events.ndjson"), spool, cancelled, writer)) {
            assertThatThrownBy(() -> journal.append(event())).hasMessage("MANDATORY_SPOOL_OR_JOURNAL_FAILED");
            String partial = visible.toString();
            assertThatThrownBy(() -> journal.append(event())).hasMessage("MANDATORY_SPOOL_OR_JOURNAL_FAILED");
            assertThat(visible.toString()).isEqualTo(partial).doesNotContain("\n");
            assertThat(cancelled).isTrue();
            assertThat(spool.snapshot().events()).isEqualTo(2);
            spool.commitTerminal(RunResult.Status.ERROR, true, false);
        }
        var recovered = DurableRunSpool.recover(file);
        assertThat(recovered.events()).isEqualTo(2);
        assertThat(recovered.terminal().cleanupConfirmed()).isFalse();
    }

    @Test void truncatedTailIsReadOnlyButCorruptionIsRefused() throws Exception {
        Path file = directory.resolve("partial.bin");
        long prefix;
        try (var spool = DurableRunSpool.create(file, identity())) {
            spool.appendEvent(event()); prefix = spool.snapshot().verifiedBytes();
            spool.appendEvent(event());
        }
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.truncate(Files.size(file) - 1); }
        long size = Files.size(file);
        var recovered = DurableRunSpool.recover(file);
        assertThat(recovered.events()).isEqualTo(1);
        assertThat(recovered.verifiedBytes()).isEqualTo(prefix);
        assertThat(recovered.uncommittedTail()).isTrue();
        assertThat(Files.size(file)).isEqualTo(size);
        byte original = Files.readAllBytes(file)[(int) prefix - 5];
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.position(prefix - 5); channel.write(ByteBuffer.wrap(new byte[]{(byte) (original ^ 1)}));
        }
        assertThatThrownBy(() -> DurableRunSpool.recover(file)).hasMessage("SPOOL_CORRUPT");
    }

    @Test void foreignRunAndInvalidOwnerAreRefused() throws Exception {
        String owner = DurableRunSpool.runnerIdentity(directory);
        assertThat(UUID.fromString(owner)).isNotNull();
        assertThat(DurableRunSpool.runnerIdentity(directory)).isEqualTo(owner);
        Files.writeString(directory.resolve("runner-identity"), "partial");
        assertThatThrownBy(() -> DurableRunSpool.runnerIdentity(directory)).hasMessage("SPOOL_OWNER_INVALID");
        try (var spool = DurableRunSpool.create(directory.resolve("owner.bin"), identity())) {
            var foreign = new RunEvent.CleanupStarted("CLEANUP_STARTED", 10, new RunId("other-run"), 0);
            assertThatThrownBy(() -> spool.appendEvent(foreign)).hasMessage("SPOOL_EVENT_OWNER_MISMATCH");
        }
    }

    @Test void actualProcessCrashesPreserveOnlyTheCommittedPrefixBeforeAnySend() throws Exception {
        for (String point : List.of("identity", "payload", "event", "terminal")) {
            Path file = directory.resolve(point + ".bin");
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), CrashWriter.class.getName(), file.toString(), point);
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            builder.environment().remove("JDK_JAVA_OPTIONS");
            builder.environment().remove("_JAVA_OPTIONS");
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process child = builder.start();
            try {
                assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue();
                assertThat(child.exitValue()).isEqualTo(23);
            } finally {
                if (child.isAlive()) child.destroyForcibly();
                assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
            var recovered = DurableRunSpool.recover(file);
            assertThat(recovered.events()).isEqualTo(point.equals("event") || point.equals("terminal") ? 1 : 0);
            assertThat(recovered.recoveryRequired()).isEqualTo(!point.equals("terminal"));
            assertThat(recovered.uncommittedTail()).isEqualTo(point.equals("payload"));
        }
    }

    public static final class CrashWriter {
        public static void main(String[] args) throws Exception {
            var forces = new AtomicInteger();
            try (var spool = DurableRunSpool.create(Path.of(args[0]), identity(), DurableRunSpool.Limits.defaults(), channel -> {
                channel.force(true);
                if (args[1].equals("payload") && forces.incrementAndGet() == 3) Runtime.getRuntime().halt(23);
            })) {
                if (args[1].equals("identity")) Runtime.getRuntime().halt(23);
                spool.appendEvent(event());
                if (args[1].equals("event")) Runtime.getRuntime().halt(23);
                spool.commitTerminal(RunResult.Status.FAILED, true, false);
                Runtime.getRuntime().halt(23);
            }
        }
    }
}
