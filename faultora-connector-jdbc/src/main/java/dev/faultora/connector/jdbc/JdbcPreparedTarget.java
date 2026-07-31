package dev.faultora.connector.jdbc;

import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.spi.contract.Connector;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * One observation's connection.
 * <p>
 * Opened when the observation needs it and closed when the observation ends. A
 * connection belongs to one thread, so it is not shared and not pooled: two
 * steps of a parallel group observing the same database open two connections,
 * which is the cost of a client that cannot be shared.
 */
final class JdbcPreparedTarget implements Connector.PreparedTarget {

    private final TargetDefinition target;
    private final JdbcUrl url;
    private final Supplier<Connection> open;

    private Connection connection;

    JdbcPreparedTarget(TargetDefinition target, JdbcUrl url, Supplier<Connection> open) {
        this.target = target;
        this.url = url;
        this.open = open;
    }

    @Override
    public TargetDefinition targetDefinition() {
        return target;
    }

    JdbcUrl url() {
        return url;
    }

    Connection connection() {
        if (connection == null) {
            connection = open.get();
        }
        return connection;
    }

    void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException alreadyGone) {
            // A connection that cannot be closed is one the database has
            // already given up on; there is nothing left to release.
        } finally {
            connection = null;
        }
    }
}
