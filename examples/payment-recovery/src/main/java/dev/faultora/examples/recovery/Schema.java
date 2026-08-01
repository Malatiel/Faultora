package dev.faultora.examples.recovery;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The tables the reference system keeps, and the role Faultora reads them with.
 * <p>
 * Four tables and one role, which is the smallest shape that still has the
 * problem: a payment, the outbox row written with it, the ledger entries a
 * settlement books, and the record of which messages have already been
 * processed. Nothing here is a framework; the point is that the invariant is
 * visible in ordinary SQL an operator could have written.
 * <p>
 * {@link #grantReadOnly} is the part worth reading twice. {@code SECURITY.md}
 * and ADR-017 both say the guarantee that survives a defect in Faultora is the
 * grant, and a gate that ran as the owner would leave that the one claim in the
 * section nothing demonstrates. So the observations connect as a role that
 * holds {@code SELECT} and nothing else.
 */
public final class Schema {

    /** The role database observations connect as. */
    public static final String READ_ONLY_ROLE = "faultora_readonly";

    private Schema() {
    }

    /** Create the tables, dropping whatever an earlier run left. */
    public static void create(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS ledger_entries, processed_messages, "
                    + "outbox, payments");
            statement.execute("""
                    CREATE TABLE payments (
                        payment_id   VARCHAR(64) PRIMARY KEY,
                        amount       BIGINT      NOT NULL,
                        currency     VARCHAR(3)  NOT NULL,
                        status       VARCHAR(16) NOT NULL,
                        created_at   TIMESTAMPTZ NOT NULL DEFAULT now())
                    """);
            // The outbox: an event written in the transaction that causes it,
            // so a payment and its event are committed together or not at all.
            statement.execute("""
                    CREATE TABLE outbox (
                        id           BIGSERIAL   PRIMARY KEY,
                        payment_id   VARCHAR(64) NOT NULL,
                        payload      TEXT        NOT NULL,
                        published_at TIMESTAMPTZ)
                    """);
            // Double entry: a booking is two rows summing to zero. An invariant
            // no single request can show, which is why it is the gate.
            // provider_reference is nullable on purpose. An entry booked
            // straight through carries none, and only the receivable entry of
            // a recovered charge carries one — so a uniqueness check over this
            // column meets the SQL semantics it implements, where two rows
            // with no value are not duplicates of each other.
            statement.execute("""
                    CREATE TABLE ledger_entries (
                        id                 BIGSERIAL   PRIMARY KEY,
                        payment_id         VARCHAR(64) NOT NULL,
                        account            VARCHAR(32) NOT NULL,
                        amount             BIGINT      NOT NULL,
                        provider_reference VARCHAR(64),
                        created_at         TIMESTAMPTZ NOT NULL DEFAULT now())
                    """);
            // What makes the consumer idempotent: the key is inserted in the
            // same transaction as the booking, so a redelivery collides. The
            // collision is counted rather than discarded, so a scenario can
            // assert "processed once, seen twice" rather than inferring the
            // second half of it from the ledger.
            statement.execute("""
                    CREATE TABLE processed_messages (
                        message_key  VARCHAR(128) PRIMARY KEY,
                        payment_id   VARCHAR(64)  NOT NULL,
                        deliveries   INT          NOT NULL DEFAULT 1,
                        processed_at TIMESTAMPTZ  NOT NULL DEFAULT now())
                    """);
        }
    }

    /**
     * Give the observation role the one privilege it needs.
     * <p>
     * {@code SELECT} on the tables and nothing else: no insert, no update, no
     * delete, and no rights on anything created later. A statement that slipped
     * past the connector's own check reaches a database that will not run it.
     */
    public static void grantReadOnly(Connection connection, String password)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Created once and kept. Dropping it between runs fails as soon as
            // it holds a grant, and re-granting is idempotent anyway — which
            // matters, because the tables it may read are recreated each time.
            statement.execute(
                    "DO $$ BEGIN"
                            + " IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '"
                            + READ_ONLY_ROLE + "') THEN"
                            + " CREATE ROLE " + READ_ONLY_ROLE
                            + " LOGIN PASSWORD '" + password + "';"
                            + " END IF;"
                            + " END $$;");
            statement.execute("GRANT CONNECT ON DATABASE "
                    + connection.getCatalog() + " TO " + READ_ONLY_ROLE);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + READ_ONLY_ROLE);
            statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO "
                    + READ_ONLY_ROLE);
        }
    }

    /** Remove everything an earlier scenario left, so runs cannot read it. */
    public static void empty(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE ledger_entries, processed_messages, outbox, payments");
        }
    }
}
