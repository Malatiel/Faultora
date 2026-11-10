package dev.faultora.runner;

import dev.faultora.runner.protocol.Dispatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayProtectionTest {
    private static final long NOW = 1_000_000_000_000L;
    private static final long WINDOW = Dispatch.CLOCK_SKEW_ALLOWANCE_MS;

    @TempDir Path work;

    @Test
    void runIdAndNonceAreReservedAcrossReopeningWithoutStoringTheirValues() throws Exception {
        var first = dispatch("run-first-fixture", "nonce-first-fixture", NOW);
        assertThat(new ReplayProtection(work).claim(first, NOW)).isTrue();

        var reopened = new ReplayProtection(work);
        assertThat(reopened.claim(dispatch(first.runId(), "nonce-other", NOW), NOW)).isFalse();
        assertThat(reopened.claim(dispatch("run-other", first.nonce(), NOW), NOW)).isFalse();
        assertThat(reopened.claim(first, NOW)).isFalse();
        assertThat(reopened.claim(dispatch("run-other", "nonce-other", NOW), NOW)).isTrue();

        String persisted = Files.readString(snapshot(), StandardCharsets.ISO_8859_1);
        assertThat(persisted).doesNotContain(first.runId(), first.nonce(), "run-other", "nonce-other");
    }

    @Test
    void bothClockBoundsAreInclusiveAndAFutureDispatchKeepsItsEntireWindow() throws Exception {
        var store = new ReplayProtection(work);
        assertThat(store.claim(dispatch("run-too-old", "nonce-too-old", NOW - WINDOW - 1), NOW)).isFalse();
        assertThat(store.claim(dispatch("run-too-new", "nonce-too-new", NOW + WINDOW + 1), NOW)).isFalse();
        assertThat(store.claim(dispatch("run-old", "nonce-old", NOW - WINDOW), NOW)).isTrue();
        assertThat(store.claim(dispatch("run-new", "nonce-new", NOW + WINDOW), NOW)).isTrue();

        long expiry = NOW + 2 * WINDOW;
        var reopened = new ReplayProtection(work);
        assertThat(reopened.claim(dispatch("run-new-again", "nonce-new", expiry), expiry)).isFalse();
        assertThat(reopened.claim(dispatch("run-new-again", "nonce-new", expiry + 1), expiry + 1)).isTrue();
    }

    @Test
    void pruningDoesNotReopenAnOldDispatchAfterClockRollbackAndRestart() throws Exception {
        var original = dispatch("run-old", "nonce-old", NOW);
        assertThat(new ReplayProtection(work, 1).claim(original, NOW)).isTrue();

        long later = NOW + WINDOW + 1;
        assertThat(new ReplayProtection(work, 1).claim(dispatch("run-new", "nonce-new", later), later)).isTrue();

        var reopened = new ReplayProtection(work, 1);
        assertThat(reopened.claim(original, NOW)).isFalse();
        assertThat(reopened.claim(dispatch("run-new-again", "nonce-new", later), NOW)).isFalse();
    }

    @Test
    void timestampArithmeticRejectsOverflowAndKeepsTheUpperBoundary() throws Exception {
        var store = new ReplayProtection(work);
        assertThat(store.claim(dispatch("run-overflow", "nonce-overflow", Long.MIN_VALUE), 0)).isFalse();
        assertThat(store.claim(dispatch("run-far-away", "nonce-far-away", Long.MIN_VALUE), Long.MAX_VALUE)).isFalse();
        assertThat(store.claim(dispatch("run-edge", "nonce-edge", Long.MAX_VALUE), Long.MAX_VALUE - WINDOW)).isTrue();
        assertThat(new ReplayProtection(work).claim(
                dispatch("run-edge-again", "nonce-edge", Long.MAX_VALUE), Long.MAX_VALUE)).isFalse();
    }

    @Test
    void fullStoreRefusesWithoutEvictingLiveEntriesAndReusesExpiredCapacity() throws Exception {
        var store = new ReplayProtection(work, 1);
        var original = dispatch("run-first", "nonce-first", NOW);
        assertThat(store.claim(original, NOW)).isTrue();
        byte[] before = Files.readAllBytes(snapshot());

        assertThatThrownBy(() -> store.claim(dispatch("run-second", "nonce-second", NOW), NOW))
                .isInstanceOf(IOException.class).hasMessage("REPLAY_PROTECTION_FULL");
        assertThat(Files.readAllBytes(snapshot())).isEqualTo(before);
        assertThat(store.claim(original, NOW)).isFalse();

        long later = NOW + WINDOW + 1;
        assertThat(store.claim(dispatch("run-second", "nonce-second", later), later)).isTrue();
        assertThat(Files.size(snapshot())).isEqualTo(before.length);
    }

    @Test
    void staleStagingFileIsReusedAndMalformedSnapshotIsNotReset() throws Exception {
        var store = new ReplayProtection(work);
        assertThat(store.claim(dispatch("run-first", "nonce-first", NOW), NOW)).isTrue();
        Path staging = snapshot().resolveSibling("state.tmp");
        Files.write(staging, new byte[] {0, 1, 2});
        assertThat(store.claim(dispatch("run-second", "nonce-second", NOW), NOW)).isTrue();
        assertThat(staging).doesNotExist();

        byte[] corrupt = Files.readAllBytes(snapshot());
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(snapshot(), corrupt);
        assertThatThrownBy(() -> new ReplayProtection(work).claim(dispatch("run-third", "nonce-third", NOW), NOW))
                .isInstanceOf(IOException.class).hasMessage("REPLAY_PROTECTION_CORRUPT");
        assertThat(Files.readAllBytes(snapshot())).isEqualTo(corrupt);
    }

    @Test
    void evenAChecksummedSnapshotMustHaveAnExactRecordCount() throws Exception {
        assertThat(new ReplayProtection(work).claim(dispatch("run-first", "nonce-first", NOW), NOW)).isTrue();
        byte[] invalid = Files.readAllBytes(snapshot());
        ByteBuffer.wrap(invalid).putInt(Integer.BYTES + Long.BYTES, 2);
        int payload = invalid.length - 32;
        byte[] checksum = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(invalid, payload));
        System.arraycopy(checksum, 0, invalid, payload, checksum.length);
        Files.write(snapshot(), invalid);

        assertThatThrownBy(() -> new ReplayProtection(work).claim(dispatch("run-second", "nonce-second", NOW), NOW))
                .isInstanceOf(IOException.class).hasMessage("REPLAY_PROTECTION_CORRUPT");
        assertThat(Files.readAllBytes(snapshot())).isEqualTo(invalid);
    }

    @Test
    void concurrentClaimsHaveOneWinnerAndOverlappingStoreLockFailsClosed() throws Exception {
        var store = new ReplayProtection(work);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return store.claim(dispatch("run-first", "nonce-shared", NOW), NOW);
            });
            var second = pool.submit(() -> {
                start.await();
                return store.claim(dispatch("run-second", "nonce-shared", NOW), NOW);
            });
            start.countDown();
            assertThat(new boolean[] {first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)})
                    .containsExactlyInAnyOrder(true, false);
        }

        Path lock = snapshot().resolveSibling("state.lock");
        try (var channel = FileChannel.open(lock, StandardOpenOption.WRITE);
             var held = channel.lock()) {
            assertThatThrownBy(() -> new ReplayProtection(work).claim(dispatch("run-third", "nonce-third", NOW), NOW))
                    .isInstanceOf(IOException.class).hasMessage("REPLAY_PROTECTION_BUSY");
        }
        assertThat(new ReplayProtection(work).claim(dispatch("run-third", "nonce-third", NOW), NOW)).isTrue();
    }

    private Path snapshot() { return work.resolve("dispatch-replay").resolve("state.bin"); }

    private static Dispatch dispatch(String runId, String nonce, long issuedAt) {
        return new Dispatch(runId, issuedAt, nonce, null, null, null, null, null, 0,
                null, null, null, null);
    }
}
