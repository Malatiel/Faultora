package dev.faultora.examples.recovery;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finishes what a lost response left undone.
 * <p>
 * A payment whose provider outcome is unknown is the one state this system
 * cannot resolve by trying harder: charging again might take the money twice,
 * and giving up might lose it. The only resolution is to ask the provider what
 * it knows, which is what this worker does — and then book the ledger for the
 * charges the provider says it accepted.
 * <p>
 * It books through the same claim key the consumer uses, so a payment that the
 * consumer settled after all is not booked a second time here. Two workers
 * racing the same payment is not a hypothetical: it is what happens whenever a
 * lost response turns out to have been delivered late.
 * <p>
 * {@link SystemConfig#reconciling()} false is the system without it. Nothing
 * else changes: the charge is still taken, the response is still lost, and the
 * payment simply stays unbooked — which is a payment
 * taken from a customer and recorded nowhere.
 */
final class ReconciliationWorker implements AutoCloseable {

    private static final Duration INTERVAL = Duration.ofMillis(200);

    private final Database database;
    private final Provider provider;
    private volatile boolean running;
    private Thread worker;

    ReconciliationWorker(Database database, Provider provider) {
        this.database = database;
        this.provider = provider;
    }

    void start() {
        running = true;
        worker = new Thread(this::reconcileUntilStopped, "reconciliation-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void reconcileUntilStopped() {
        while (running) {
            try {
                reconcileOnce();
                Thread.sleep(INTERVAL.toMillis());
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            } catch (SQLException retryNextPass) {
                // The unresolved payments are still unresolved next time.
            }
        }
    }

    /**
     * One pass, resolving both ways.
     * <p>
     * A charge the provider has is booked; a charge it does not have is marked
     * failed. Leaving the second case alone would be the reconciler that only
     * looks one way — it would clear the ledger's arithmetic while leaving
     * payments in a state nobody ever reaches a decision about.
     */
    private void reconcileOnce() throws SQLException {
        for (Map.Entry<String, Long> payment : unresolvedPayments().entrySet()) {
            switch (provider.outcomeOf(payment.getKey())) {
                case ACCEPTED -> book(payment.getKey(), payment.getValue());
                case REFUSED -> mark(payment.getKey(), "refused");
                case UNKNOWN -> {
                    // The provider could not be reached at all. Nothing is
                    // decided, and the next pass asks again.
                }
            }
        }
    }

    private void mark(String paymentId, String status) throws SQLException {
        try (Connection connection = database.connection();
             PreparedStatement mark = connection.prepareStatement(
                     "UPDATE payments SET status = ? WHERE payment_id = ?")) {
            mark.setString(1, status);
            mark.setString(2, paymentId);
            mark.executeUpdate();
        }
    }

    /**
     * Payments that have settled down without being booked, and are not
     * already decided.
     * <p>
     * Read from the ledger rather than from a status column, because the ledger
     * is what the invariant is about and a status is something a process had to
     * remember to write. A payment the consumer is still working on is excluded
     * by the grace period, and one that slips through anyway is harmless: the
     * claim key means whichever of the two commits first books it, and the
     * other rolls back.
     * <p>
     * {@code refused} is the one status this does read, and only to stop:
     * a payment the provider says it never took is decided, and asking it again
     * every 200 ms forever would be a question nobody is waiting for an answer
     * to.
     */
    private Map<String, Long> unresolvedPayments() throws SQLException {
        Map<String, Long> unresolved = new LinkedHashMap<>();
        try (Connection connection = database.connection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT p.payment_id, p.amount FROM payments p "
                             + "LEFT JOIN ledger_entries l ON l.payment_id = p.payment_id "
                             + "WHERE l.id IS NULL "
                             + "AND p.status <> 'refused' "
                             + "AND p.created_at < now() - interval '1 second'");
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                unresolved.put(rows.getString("payment_id"), rows.getLong("amount"));
            }
        }
        return unresolved;
    }

    private void book(String paymentId, long amount) throws SQLException {
        try (Connection connection = database.transaction()) {
            try (PreparedStatement claim = connection.prepareStatement(
                    "INSERT INTO processed_messages (message_key, payment_id) "
                            + "VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                claim.setString(1, OutboxRelay.COMMANDS_TOPIC + ":" + paymentId);
                claim.setString(2, paymentId);
                if (claim.executeUpdate() != 1) {
                    // Somebody settled it while this pass was deciding to.
                    connection.rollback();
                    return;
                }
            }
            try (PreparedStatement entry = connection.prepareStatement(
                    "INSERT INTO ledger_entries "
                            + "(payment_id, account, amount, provider_reference) "
                            + "VALUES (?, ?, ?, ?)")) {
                // Only the receivable entry carries the provider's reference:
                // it is the side of the booking the charge corresponds to, and
                // putting it on both would make two rows share a value that
                // identifies one charge.
                entry.setString(1, paymentId);
                entry.setString(2, "receivable");
                entry.setLong(3, amount);
                entry.setString(4, "charge-" + paymentId);
                entry.executeUpdate();
                entry.setString(1, paymentId);
                entry.setString(2, "revenue");
                entry.setLong(3, -amount);
                entry.setNull(4, java.sql.Types.VARCHAR);
                entry.executeUpdate();
            }
            try (PreparedStatement mark = connection.prepareStatement(
                    "UPDATE payments SET status = 'settled' WHERE payment_id = ?")) {
                mark.setString(1, paymentId);
                mark.executeUpdate();
            }
            connection.commit();
        }
    }

    @Override
    public void close() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
