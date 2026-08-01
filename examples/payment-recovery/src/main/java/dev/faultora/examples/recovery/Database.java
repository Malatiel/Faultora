package dev.faultora.examples.recovery;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Where the reference system's connections come from.
 * <p>
 * One connection per unit of work, opened and closed around it. A pool would be
 * the right thing in an application that mattered and the wrong thing here: it
 * would be a second piece of machinery to get right in code whose only job is
 * to make an invariant observable.
 *
 * @param url      the JDBC URL of the database the system owns
 * @param user     the account the system reads and writes with
 * @param password that account's password
 */
public record Database(String url, String user, String password) {

    /** A connection that commits only when told to. */
    public Connection transaction() throws SQLException {
        Connection connection = DriverManager.getConnection(url, user, password);
        connection.setAutoCommit(false);
        return connection;
    }

    /** A connection for work that is one statement long. */
    public Connection connection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
