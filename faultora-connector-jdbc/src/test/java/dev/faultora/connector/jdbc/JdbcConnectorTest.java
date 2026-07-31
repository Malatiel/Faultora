package dev.faultora.connector.jdbc;

import dev.faultora.model.catalog.OperationDefinition;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.OperationId;
import dev.faultora.model.identifier.ProtocolId;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.model.security.SecretHandle;
import dev.faultora.net.DestinationPolicyViolation;
import dev.faultora.net.HostPolicy;
import dev.faultora.spi.context.ConnectorContext;
import dev.faultora.spi.contract.Connector;
import dev.faultora.spi.evidence.TableEvidence;
import dev.faultora.spi.result.OperationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the connector decides, against an in-process database.
 * <p>
 * H2 proves the connector's bounds and refusals — what it will run, what it
 * will not, how much it keeps — without Docker and without a network. What it
 * cannot prove is that a query written for one database runs on another: H2
 * accepts SQL PostgreSQL rejects, and the reverse. That the reference system's
 * queries actually run is what the end-to-end suite against a disposable
 * PostgreSQL is for.
 */
class JdbcConnectorTest {

    private static final String URL = "jdbc:h2:mem:observations;DB_CLOSE_DELAY=-1";

    private Connection fixture;

    @BeforeEach
    void createLedger() throws SQLException {
        fixture = DriverManager.getConnection(URL);
        try (Statement statement = fixture.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS ledger_entries");
            statement.execute("""
                    CREATE TABLE ledger_entries (
                        id INT PRIMARY KEY,
                        payment_id VARCHAR(64),
                        account VARCHAR(32),
                        amount INT)
                    """);
            statement.execute("""
                    INSERT INTO ledger_entries VALUES
                        (1, 'pay-1', 'receivable', 2500),
                        (2, 'pay-1', 'revenue', -2500),
                        (3, 'pay-2', 'receivable', 100)
                    """);
        }
    }

    @AfterEach
    void dropLedger() throws SQLException {
        if (fixture != null) {
            fixture.close();
        }
    }

    private static TargetDefinition ledger() {
        return new TargetDefinition(
                new TargetId("ledger"), "Ledger", URL,
                List.of(new ProtocolId(JdbcConnector.PROTOCOL)), List.of(), Map.of());
    }

    private static OperationDefinition observation(String sql) {
        return new OperationDefinition(
                new OperationId("entries-for"), new ProtocolId(JdbcConnector.PROTOCOL),
                new TargetId("ledger"), SafetyClassification.READ_ONLY,
                Map.of(), null, Map.of(), Map.of(JdbcConnector.SQL, sql));
    }

    private static ConnectorContext context(int maxRows) {
        return new ConnectorContext(
                new EvidencePolicy(true, true, Set.of(), 0, maxRows,
                        List.of(), Set.of(), "session"),
                name -> null, 2000, 10_000, 20_000, Map.of());
    }

    private OperationResult observe(String sql, Map<String, Object> inputs, int maxRows) {
        try (JdbcConnector connector = new JdbcConnector(HostPolicy.permissive())) {
            Connector.PreparedTarget target =
                    connector.prepare(ledger(), context(maxRows));
            try {
                return connector.execute(target, observation(sql), inputs, context(maxRows));
            } finally {
                connector.release(target);
            }
        }
    }

    @Test
    void anObservationReturnsItsRowsAsTabularEvidence() {
        OperationResult result = observe(
                "SELECT account, amount FROM ledger_entries "
                        + "WHERE payment_id = :paymentId ORDER BY id",
                Map.of("paymentId", "pay-1"), 100);

        assertThat(result.isSuccess()).isTrue();
        TableEvidence table = TableEvidence.observedIn(result.protocolEvidence());
        assertThat(table).isNotNull();
        assertThat(table.columns()).containsExactly("ACCOUNT", "AMOUNT");
        assertThat(table.rowCount()).isEqualTo(2);
        assertThat(table.truncated()).isFalse();
        assertThat(table.column("AMOUNT")).containsExactly(2500, -2500);
    }

    @Test
    void aValueIsBoundAndNeverAssembledIntoTheStatement() {
        // The classic injection, which a prepared statement makes into a
        // payment id nobody has rather than a second statement.
        OperationResult result = observe(
                "SELECT count(*) AS total FROM ledger_entries WHERE payment_id = :paymentId",
                Map.of("paymentId", "pay-1' OR '1'='1"), 100);

        assertThat(result.isSuccess()).isTrue();
        assertThat(TableEvidence.observedIn(result.protocolEvidence())
                .column("TOTAL")).containsExactly(0L);
    }

    @Test
    void aRowLimitTruncatesAndSaysSo() {
        // A scenario asserting "two rows" against a truncated three must not
        // read as satisfied, so the limit is visible in the evidence.
        OperationResult result = observe(
                "SELECT id FROM ledger_entries ORDER BY id", Map.of(), 2);

        TableEvidence table = TableEvidence.observedIn(result.protocolEvidence());
        assertThat(table.rowCount()).isEqualTo(2);
        assertThat(table.truncated()).isTrue();
    }

    @Test
    void aResultWithinTheLimitIsNotTruncated() {
        OperationResult result = observe(
                "SELECT id FROM ledger_entries ORDER BY id", Map.of(), 3);

        TableEvidence table = TableEvidence.observedIn(result.protocolEvidence());
        assertThat(table.rowCount()).isEqualTo(3);
        assertThat(table.truncated()).isFalse();
    }

    @Test
    void aStatementThatWritesIsRefusedBeforeAnythingIsOpened() {
        for (String write : List.of(
                "DELETE FROM ledger_entries",
                "UPDATE ledger_entries SET amount = 0",
                "INSERT INTO ledger_entries VALUES (9, 'x', 'y', 1)",
                "DROP TABLE ledger_entries")) {
            OperationResult result = observe(write, Map.of(), 100);

            assertThat(result.isSuccess()).as(write).isFalse();
            assertThat(result.error().code()).isEqualTo("OBSERVATION_REFUSED");
            assertThat(result.error().message()).contains("never writes");
        }
        assertThat(rowCount()).as("the table is untouched").isEqualTo(3);
    }

    @Test
    void aSecondStatementSmuggledAfterASemicolonIsRefused() {
        OperationResult result = observe(
                "SELECT 1; DELETE FROM ledger_entries", Map.of(), 100);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error().message()).contains("one statement");
        assertThat(rowCount()).isEqualTo(3);
    }

    @Test
    void aTrailingSemicolonIsOrdinary() {
        assertThat(observe("SELECT id FROM ledger_entries;", Map.of(), 100).isSuccess())
                .isTrue();
    }

    @Test
    void aCastIsNotMistakenForAParameter() {
        // ':' begins a placeholder and '::' begins a cast. Binding into a cast
        // would put a value where the author wrote a type.
        ReadOnlyStatement statement = ReadOnlyStatement.of(
                "SELECT amount::varchar FROM ledger_entries WHERE payment_id = :paymentId");

        assertThat(statement.parameters()).containsExactly("paymentId");
        assertThat(statement.prepared()).contains("::varchar").contains("= ?");
    }

    @Test
    void aColonInsideALiteralIsNotAParameter() {
        ReadOnlyStatement statement = ReadOnlyStatement.of(
                "SELECT 'a:b' AS label FROM ledger_entries WHERE account = :account");

        assertThat(statement.parameters()).containsExactly("account");
    }

    @Test
    void aParameterTheStepDoesNotSupplyIsNamed() {
        OperationResult result = observe(
                "SELECT id FROM ledger_entries WHERE payment_id = :paymentId",
                Map.of(), 100);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error().code()).isEqualTo("PARAMETER_MISSING");
        assertThat(result.error().message()).contains("paymentId");
    }

    @Test
    void aDatabaseOnAPrivateNetworkIsRefused() {
        try (JdbcConnector connector = new JdbcConnector(HostPolicy.defaultPolicy())) {
            TargetDefinition local = new TargetDefinition(
                    new TargetId("ledger"), "Ledger",
                    "jdbc:postgresql://localhost:5432/payments",
                    List.of(new ProtocolId("jdbc")), List.of(), Map.of());

            assertThatThrownBy(() -> connector.prepare(local, context(100)))
                    .isInstanceOf(DestinationPolicyViolation.class)
                    .hasMessageContaining("localhost");
        }
    }

    @Test
    void anInProcessDatabaseNeedsNoDestinationPolicy() {
        // Nothing leaves this machine, so there is nothing to classify.
        try (JdbcConnector connector = new JdbcConnector(HostPolicy.defaultPolicy())) {
            assertThat(connector.prepare(ledger(), context(100))).isNotNull();
        }
    }

    @Test
    void aFailingQueryNamesTheDatabaseAndNotItsProperties() {
        // A JDBC URL is a common place to find a password: H2 and SQL Server
        // both take one as a ';' property. A diagnostic names the driver and
        // the host, never the configuration behind them.
        OperationResult result = observe("SELECT nope FROM ledger_entries", Map.of(), 100);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error().code()).isEqualTo("OBSERVATION_FAILED");
        assertThat(result.error().message()).doesNotContain("DB_CLOSE_DELAY");
        assertThat(result.error().message()).contains("jdbc:h2:mem:observations");
    }

    @Test
    void aPasswordInAUrlNeverReachesADiagnostic() {
        try (JdbcConnector connector = new JdbcConnector(HostPolicy.permissive())) {
            TargetDefinition withPassword = new TargetDefinition(
                    new TargetId("ledger"), "Ledger",
                    "jdbc:h2:mem:absent;PASSWORD=hunter2;IFEXISTS=TRUE",
                    List.of(new ProtocolId("jdbc")), List.of(), Map.of());

            assertThatThrownBy(() -> connector.prepare(withPassword, context(100))
                    .targetDefinition().baseUrl()
                    .transform(url -> connector.execute(
                            connector.prepare(withPassword, context(100)),
                            observation("SELECT 1"), Map.of(), context(100))
                            .error().message())
                    .transform(message -> {
                        assertThat(message).doesNotContain("hunter2");
                        throw new IllegalStateException(message);
                    }))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void aStatementThatBeginsByReadingAndThenWritesIsRefused() {
        // The first word is not the statement. PostgreSQL runs a data-modifying
        // common table expression, and SELECT … INTO creates a table; both open
        // with a keyword that reads.
        for (String write : List.of(
                "WITH gone AS (DELETE FROM ledger_entries RETURNING *) SELECT * FROM gone",
                "WITH more AS (INSERT INTO ledger_entries VALUES (9,'x','y',1) RETURNING *)"
                        + " SELECT * FROM more",
                "SELECT * INTO copy_of_ledger FROM ledger_entries")) {
            OperationResult result = observe(write, Map.of(), 100);

            assertThat(result.isSuccess()).as(write).isFalse();
            assertThat(result.error().code()).isEqualTo("OBSERVATION_REFUSED");
            assertThat(result.error().message()).as(write).contains("which writes");
        }
        assertThat(rowCount()).as("the table is untouched").isEqualTo(3);
    }

    @Test
    void anOrdinaryReadIsNotRefusedForContainingAWordThatCanBeginAWrite() {
        // 'comment' is an ordinary column name and 'replace' an ordinary
        // function. Neither can write from inside a statement that began by
        // reading, and refusing them would make the check broken rather than
        // strict.
        assertThatCode(() -> ReadOnlyStatement.of(
                "SELECT replace(account, ' ', '') AS account, comment "
                        + "FROM ledger_entries WHERE payment_id = :paymentId"))
                .doesNotThrowAnyException();
    }

    @Test
    void credentialsInAUrlAreNotMistakenForItsHost() {
        // In '//user:pw@10.0.0.5/db' the host is what follows the '@'.
        // Classifying 'user' would check a name nobody connects to.
        assertThatThrownBy(() -> JdbcUrl.parse(
                "jdbc:mysql://reader:hunter2@10.0.0.5:3306/payments",
                HostPolicy.defaultPolicy()))
                .isInstanceOf(DestinationPolicyViolation.class)
                .hasMessageContaining("10.0.0.5")
                .hasMessageNotContaining("hunter2");
    }

    @Test
    void aCommentIsNotASecondStatement() {
        // A ';' inside a comment ends nothing. Refusing it would make an
        // operator strip the comments out of a document written to be read.
        assertThat(observe("SELECT id FROM ledger_entries -- one per entry; really\n"
                + "ORDER BY id", Map.of(), 100).isSuccess()).isTrue();
        assertThat(observe("SELECT id /* not a write: DELETE; */ FROM ledger_entries",
                Map.of(), 100).isSuccess()).isTrue();
    }

    @Test
    void aUrlWhoseHostThisCannotFindIsRefused() {
        // Oracle's thin driver writes its host after an '@' rather than after
        // '//'. Reading "no '//', so nothing to classify" would have let a run
        // reach an internal database with the destination policy never asked.
        assertThatThrownBy(() -> JdbcUrl.parse(
                "jdbc:oracle:thin:@db.internal:1521:ORCL", HostPolicy.defaultPolicy()))
                .isInstanceOf(DestinationPolicyViolation.class)
                .hasMessageContaining("Cannot find the host")
                .hasMessageNotContaining("db.internal");
    }

    @Test
    void anExpiredSecretIsNotRetried() {
        SecretHandle expired = new SecretHandle(
                "ledger-password", "***", "env", System.currentTimeMillis() - 1000,
                () -> "hunter2".toCharArray());
        ConnectorContext context = new ConnectorContext(
                new EvidencePolicy(true, true, Set.of(), 0, 100,
                        List.of(), Set.of(), "session"),
                name -> expired, 2000, 10_000, 20_000,
                Map.of(JdbcConnector.USER, "reader",
                        JdbcConnector.SECRET_ID, "ledger-password"));

        OperationResult result = execute("SELECT 1", Map.of(), context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error().code()).isEqualTo("SECRET_UNAVAILABLE");
        assertThat(result.error().retryable()).isFalse();
        assertThat(result.error().message()).doesNotContain("hunter2");
    }

    @Test
    void aPolicyThatCapturesNoBodiesCountsRowsWithoutKeepingThem() {
        ConnectorContext withheld = new ConnectorContext(
                new EvidencePolicy(false, false, Set.of(), 0, 100,
                        List.of(), Set.of(), "session"),
                name -> null, 2000, 10_000, 20_000, Map.of());

        OperationResult result = execute(
                "SELECT account, amount FROM ledger_entries ORDER BY id",
                Map.of(), withheld);

        TableEvidence table = TableEvidence.observedIn(result.protocolEvidence());
        assertThat(table.rowCount()).as("a count is not content").isEqualTo(3);
        assertThat(table.valuesWithheld()).isTrue();
        assertThat(table.column("amount")).containsOnlyNulls();
    }

    @Test
    void aColumnTheEvidencePolicyRedactsIsNotKept() {
        ConnectorContext redacting = new ConnectorContext(
                new EvidencePolicy(true, true, Set.of(), 0, 100,
                        List.of("$.AMOUNT"), Set.of(), "session"),
                name -> null, 2000, 10_000, 20_000, Map.of());

        OperationResult result = execute(
                "SELECT account, amount FROM ledger_entries ORDER BY id",
                Map.of(), redacting);

        TableEvidence table = TableEvidence.observedIn(result.protocolEvidence());
        assertThat(table.column("AMOUNT")).containsOnly("***");
        assertThat(table.column("ACCOUNT")).contains("receivable");
    }

    private OperationResult execute(
            String sql, Map<String, Object> inputs, ConnectorContext context) {
        try (JdbcConnector connector = new JdbcConnector(HostPolicy.permissive())) {
            Connector.PreparedTarget target = connector.prepare(ledger(), context);
            try {
                return connector.execute(target, observation(sql), inputs, context);
            } finally {
                connector.release(target);
            }
        }
    }

    private int rowCount() {
        try (Statement statement = fixture.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM ledger_entries")) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException unreadable) {
            throw new AssertionError(unreadable);
        }
    }
}
