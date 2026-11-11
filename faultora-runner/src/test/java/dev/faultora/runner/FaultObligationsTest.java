package dev.faultora.runner;

import dev.faultora.model.security.ContentDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultObligationsTest {
    private static final String ATTEMPT = new UUID(0, 1).toString();
    private static final String PROVIDER_DIGEST = ContentDigest.sha256Uri("synthetic-provider-route");
    private static final long EXPIRY = 1_000_000_000_000L;

    @TempDir Path run;

    @Test
    void reopenedStoreKeepsUnconfirmedIntentsAndConfirmedRollback() throws IOException {
        var first = intent(1);
        var second = intent(2);
        var store = store();
        assertThat(store.pending()).isEmpty();
        assertThat(ledger().getParent()).doesNotExist();
        store.record(first);
        store.record(second);
        assertThat(store().pending()).containsExactly(first, second);

        store().rolledBack(first.activationId());
        assertThat(store().pending()).containsExactly(second);
        byte[] confirmed = Files.readAllBytes(ledger());
        store().rolledBack(first.activationId());
        assertThat(Files.readAllBytes(ledger())).isEqualTo(confirmed);
        assertThatThrownBy(() -> store().record(first)).isInstanceOf(IOException.class)
                .hasMessage("FAULT_OBLIGATIONS_DUPLICATE_INTENT");
    }

    @Test
    void invalidInputCannotCreateAnIntentOrARollback() throws IOException {
        var store = store();
        String id = intent(1).activationId();
        for (var invalid : new FaultObligations.Entry[] {
                new FaultObligations.Entry("invalid", PROVIDER_DIGEST, EXPIRY),
                new FaultObligations.Entry(id, "synthetic-provider-route", EXPIRY),
                new FaultObligations.Entry(id, PROVIDER_DIGEST, 0)}) {
            assertThatThrownBy(() -> store.record(invalid)).isInstanceOf(IOException.class)
                    .hasMessage("FAULT_OBLIGATIONS_INVALID_INTENT");
        }
        assertThatThrownBy(() -> store.rolledBack(id)).isInstanceOf(IOException.class)
                .hasMessage("FAULT_OBLIGATIONS_UNKNOWN_INTENT");
        assertThat(ledger().getParent()).doesNotExist();
    }

    @Test
    void capacityPreservesIntentsAndStillAllowsRollback() throws IOException {
        var store = new FaultObligations(run, ATTEMPT, 1);
        var first = intent(1);
        store.record(first);
        byte[] before = Files.readAllBytes(ledger());
        assertThatThrownBy(() -> store.record(intent(2))).isInstanceOf(IOException.class)
                .hasMessage("FAULT_OBLIGATIONS_FULL");
        assertThat(Files.readAllBytes(ledger())).isEqualTo(before);
        assertThat(store.pending()).containsExactly(first);

        store.rolledBack(first.activationId());
        assertThat(store.pending()).isEmpty();
        assertThatThrownBy(() -> store.record(intent(2))).isInstanceOf(IOException.class)
                .hasMessage("FAULT_OBLIGATIONS_FULL");
    }

    @Test
    void aTornAppendBlocksRecoveryAndPreservesTheOriginalBytes() throws IOException {
        var first = intent(1);
        store().record(first);
        Files.write(ledger(), new byte[] {0, 1, 2}, StandardOpenOption.APPEND);
        byte[] torn = Files.readAllBytes(ledger());

        assertThatThrownBy(() -> store().pending()).isInstanceOf(IOException.class)
                .hasMessage("FAULT_OBLIGATIONS_CORRUPT");
        assertThatThrownBy(() -> store().rolledBack(first.activationId())).isInstanceOf(IOException.class)
                .hasMessage("FAULT_OBLIGATIONS_CORRUPT");
        assertThatThrownBy(() -> store().record(intent(2))).isInstanceOf(IOException.class)
                .hasMessage("FAULT_OBLIGATIONS_CORRUPT");
        assertThat(Files.readAllBytes(ledger())).isEqualTo(torn);
    }

    @Test
    void aCorruptedOrCopiedRecordCannotProveAnObligation() throws IOException {
        store().record(intent(1));
        var anotherAttempt = new FaultObligations(run, new UUID(0, 2).toString());
        assertThatThrownBy(anotherAttempt::pending).isInstanceOf(IOException.class)
                .hasMessage("FAULT_OBLIGATIONS_CORRUPT");

        byte[] damaged = Files.readAllBytes(ledger());
        damaged[damaged.length - 1] ^= 1;
        Files.write(ledger(), damaged);
        assertThatThrownBy(() -> store().pending()).isInstanceOf(IOException.class)
                .hasMessage("FAULT_OBLIGATIONS_CORRUPT");
    }

    @Test
    void anEmptyLedgerOrNonFilePathCannotBeMistakenForNoObligations() throws IOException {
        Files.createDirectories(ledger().getParent());
        Files.createFile(ledger());
        assertThatThrownBy(() -> store().pending()).isInstanceOf(IOException.class)
                .hasMessage("FAULT_OBLIGATIONS_CORRUPT");
        Files.delete(ledger());
        Files.createDirectory(ledger());
        assertThatThrownBy(() -> store().record(intent(1))).isInstanceOf(IOException.class)
                .hasMessage("FAULT_OBLIGATIONS_CORRUPT");
    }

    private FaultObligations store() {
        return new FaultObligations(run, ATTEMPT);
    }

    private Path ledger() {
        return run.resolve("fault-obligations").resolve("state.bin");
    }

    private static FaultObligations.Entry intent(long id) {
        return new FaultObligations.Entry(new UUID(1, id).toString(), PROVIDER_DIGEST, EXPIRY);
    }
}
