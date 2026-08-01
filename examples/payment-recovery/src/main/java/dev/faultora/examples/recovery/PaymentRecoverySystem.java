package dev.faultora.examples.recovery;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * The reference system a distributed invariant is proved against.
 * <p>
 * Five parts, each the smallest version of itself that still has the problem:
 * an HTTP API that writes a payment and its outbox row in one transaction, a
 * relay that publishes what the outbox holds, a consumer that settles a payment
 * once however often it is asked, a provider that can take a charge and lose
 * the answer, and a worker that reconciles what the lost answer left behind.
 * <p>
 * No part of it is a Faultora dependency. It is an ordinary application, which
 * is the point: the invariant a scenario proves — a ledger that balances, one
 * business effect per command, no payment taken and recorded nowhere — is a
 * property of the system, not of the tool that observes it.
 * <p>
 * Which version of it runs is {@link SystemConfig}. The correct one must pass
 * every gate scenario, and each broken one must fail the scenario about the
 * property it removes.
 */
public final class PaymentRecoverySystem implements AutoCloseable {

    private final SystemConfig config;
    private final Database database;
    private final String bootstrapServers;

    private ProviderSimulator provider;
    private PaymentsApi api;
    private OutboxRelay relay;
    private SettlementConsumer consumer;
    private ReconciliationWorker reconciler;

    public PaymentRecoverySystem(
            SystemConfig config, String jdbcUrl, String user, String password,
            String bootstrapServers) {
        this.config = config;
        this.database = new Database(jdbcUrl, user, password);
        this.bootstrapServers = bootstrapServers;
    }

    /** Create the tables and the read-only role the observations connect as. */
    public void createSchema(String readOnlyPassword) throws SQLException {
        try (Connection connection = database.connection()) {
            Schema.create(connection);
            Schema.grantReadOnly(connection, readOnlyPassword);
        }
    }

    /** Remove what an earlier scenario left behind. */
    public void emptyTables() throws SQLException {
        try (Connection connection = database.connection()) {
            Schema.empty(connection);
        }
    }

    /**
     * Start every part, and return once the consumer holds its partitions.
     * <p>
     * Returning early would let a scenario publish into a topic nobody is
     * reading yet, and the resulting silence would look exactly like the defect
     * the scenario is meant to find.
     */
    public void start() throws IOException, InterruptedException {
        provider = new ProviderSimulator(config.providerRespondsToTheCharge());
        provider.start();

        api = new PaymentsApi(database, config);
        api.start();

        relay = new OutboxRelay(database, bootstrapServers);
        relay.start();

        consumer = new SettlementConsumer(
                database, config, new ProviderClient(provider.baseUrl()), bootstrapServers);
        consumer.start();

        if (config.reconciling()) {
            reconciler = new ReconciliationWorker(
                    database, new ProviderClient(provider.baseUrl()));
            reconciler.start();
        }
    }

    /** The base URL of the payments API, for {@code --target}. */
    public String apiBaseUrl() {
        return api.baseUrl();
    }

    @Override
    public void close() {
        if (reconciler != null) reconciler.close();
        if (consumer != null) consumer.close();
        if (relay != null) relay.close();
        if (api != null) api.stop();
        if (provider != null) provider.close();
    }
}
