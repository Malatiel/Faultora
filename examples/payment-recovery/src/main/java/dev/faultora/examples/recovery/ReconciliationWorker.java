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
 * payment simply stays unknown with no ledger entries — which is a payment
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

    private void reconcileOnce() throws SQLException {
        Map<String, Long> unresolved = unresolvedPayments();
        for (Map.Entry<String, Long> payment : unresolved.entrySet()) {
            if (provider.outcomeOf(payment.getKey()) == Provider.Outcome.ACCEPTED) {
                book(payment.getKey(), payment.getValue());
            }
        }
    }

    private Map<String, Long> unresolvedPayments() throws SQLException {
        Map<String, Long> unresolved = new LinkedHashMap<>();
        try (Connection connection = database.connection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT payment_id, amount FROM payments WHERE status = 'unknown'");
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
                    "INSERT INTO processed_messages (message_key) VALUES (?) "
                            + "ON CONFLICT DO NOTHING")) {
                claim.setString(1, OutboxRelay.COMMANDS_TOPIC + ":" + paymentId);
                if (claim.executeUpdate() != 1) {
                    // Somebody settled it while this pass was deciding to.
                    connection.rollback();
                    return;
                }
            }
            try (PreparedStatement entry = connection.prepareStatement(
                    "INSERT INTO ledger_entries (payment_id, account, amount) "
                            + "VALUES (?, ?, ?)")) {
                entry.setString(1, paymentId);
                entry.setString(2, "receivable");
                entry.setLong(3, amount);
                entry.executeUpdate();
                entry.setString(1, paymentId);
                entry.setString(2, "revenue");
                entry.setLong(3, -amount);
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
