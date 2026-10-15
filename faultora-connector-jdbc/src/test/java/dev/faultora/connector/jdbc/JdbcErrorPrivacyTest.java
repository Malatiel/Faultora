package dev.faultora.connector.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.model.catalog.*;
import dev.faultora.model.identifier.*;
import dev.faultora.model.security.EvidencePolicy;
import dev.faultora.net.HostPolicy;
import dev.faultora.spi.context.ConnectorContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcErrorPrivacyTest {
    private static final String PRIVATE_MARKER = "SYNTHETIC_PASSWORD_URL_VALUE";

    static Stream<SQLException> failures() {
        return Stream.of(new SQLException(PRIVATE_MARKER, "22018", 7),
                new SQLTransientException(PRIVATE_MARKER, "08006", 8),
                new SQLTimeoutException(PRIVATE_MARKER, "57014", 9));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void queryFailureExportsOnlyTheDriverClassification(SQLException failure) throws Exception {
        failure.setNextException(new SQLException("chained " + PRIVATE_MARKER));
        failure.initCause(new IllegalStateException("caused by " + PRIVATE_MARKER));
        NormalizedError error = JdbcErrors.of(failure);
        assertThat(new ObjectMapper().writeValueAsString(error)).doesNotContain(PRIVATE_MARKER, "chained", "caused by");
        assertThat(error.metadata()).containsEntry("sqlState", failure.getSQLState())
                .containsEntry("vendorCode", failure.getErrorCode());
        assertThat(error.retryable()).isEqualTo(failure instanceof SQLTransientException);
        assertThat(error.code()).isEqualTo(failure instanceof SQLTimeoutException ? "OBSERVATION_TIMEOUT"
                : failure instanceof SQLTransientException ? "DATABASE_TRANSIENT_ERROR" : "OBSERVATION_FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYNTHETIC_PASSWORD_URL_VALUE", "08006\nSYNTHETIC_PASSWORD_URL_VALUE", "", "abcde"})
    void malformedSqlStateCannotSmuggleDriverTextIntoMetadata(String state) {
        assertThat(JdbcErrors.of(new SQLException(PRIVATE_MARKER, state, 1)).metadata())
                .containsOnlyKeys("vendorCode");
    }

    @ParameterizedTest
    @ValueSource(strings = {"connect", "readOnly", "autoCommit", "close"})
    void aRealDriverManagerConnectionFailureDoesNotExportTheMessageOrUrl(String phase) throws Exception {
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        Driver driver = new Driver() {
            public Connection connect(String url, java.util.Properties properties) throws SQLException {
                if (!acceptsURL(url)) { return null; }
                if (phase.equals("connect")) { throw new SQLException(PRIVATE_MARKER + " " + url, "08001", 42); }
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("close")) {
                                closed.incrementAndGet();
                                if (phase.equals("close")) { throw new SQLException(PRIVATE_MARKER); }
                                return null;
                            }
                            if (method.getName().equals("setReadOnly") && (phase.equals("readOnly") || phase.equals("close"))
                                    || method.getName().equals("setAutoCommit") && phase.equals("autoCommit")) {
                                throw new SQLException(PRIVATE_MARKER + " " + url, "08001", 42);
                            }
                            return null;
                        });
            }
            public boolean acceptsURL(String url) { return url.startsWith("jdbc:synthetic:"); }
            public DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties properties) { return new DriverPropertyInfo[0]; }
            public int getMajorVersion() { return 1; }
            public int getMinorVersion() { return 0; }
            public boolean jdbcCompliant() { return false; }
            public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getAnonymousLogger(); }
        };
        DriverManager.registerDriver(driver);
        try (JdbcConnector connector = new JdbcConnector(HostPolicy.permissive())) {
            TargetDefinition target = new TargetDefinition(new TargetId("synthetic"), "Synthetic",
                    "jdbc:synthetic://127.0.0.1/" + PRIVATE_MARKER + "?password=" + PRIVATE_MARKER,
                    List.of(new ProtocolId("jdbc")), List.of(), Map.of());
            ConnectorContext context = new ConnectorContext(EvidencePolicy.MINIMAL, key -> null, 1000, 1000, 5000, Map.of());
            var prepared = connector.prepare(target, context);
            try {
                OperationDefinition operation = new OperationDefinition(new OperationId("observe"), new ProtocolId("jdbc"),
                        target.id(), SafetyClassification.READ_ONLY, Map.of(), null, Map.of(), Map.of("sql", "SELECT 1"));
                var error = connector.execute(prepared, operation, Map.of(), context).error();
                assertThat(error.code()).isEqualTo("DATABASE_UNAVAILABLE");
                assertThat(error.metadata()).containsEntry("sqlState", "08001").containsEntry("vendorCode", 42);
                assertThat(new ObjectMapper().writeValueAsString(error)).doesNotContain(PRIVATE_MARKER, "jdbc:synthetic", "password=");
                assertThat(closed.get()).isEqualTo(phase.equals("connect") ? 0 : 1);
                if (phase.equals("close")) { assertThat(error.metadata()).containsEntry("connectionCloseFailed", true); }
            } finally { connector.release(prepared); }
        } finally { DriverManager.deregisterDriver(driver); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYNTHETIC_PASSWORD_URL_VALUE", "jdbc:synthetic:SYNTHETIC_PASSWORD_URL_VALUE"})
    void malformedDatabaseUrlsAreRefusedWithoutEchoingTheInput(String url) {
        assertThatThrownBy(() -> JdbcUrl.parse(url, HostPolicy.permissive()))
                .hasMessageNotContaining(PRIVATE_MARKER);
    }
}
