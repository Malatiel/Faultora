package dev.faultora.integration;

import dev.faultora.cli.FaultoraCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcErrorExportE2ETest {
    @TempDir Path directory;

    @Test
    void aDriverQuotedValueIsAbsentFromCliDiagnosticsJournalAndAllReports() throws Exception {
        String marker = "SYNTHETIC_PRIVATE_JDBC_VALUE";
        Driver driver = new Driver() {
            public boolean acceptsURL(String url) {
                return url.startsWith("jdbc:synthetic-privacy:");
            }
            public Connection connect(String url, java.util.Properties properties) {
                if (!acceptsURL(url)) {
                    return null;
                }
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("prepareStatement")) {
                                return java.lang.reflect.Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[]{PreparedStatement.class}, (statement, call, values) -> {
                                            if (call.getName().equals("executeQuery")) {
                                                throw new SQLException("Cannot cast '" + marker + "' at " + url, "22018", 7);
                                            }
                                            return null;
                                        });
                            }
                            return null;
                        });
            }
            public DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties properties) {
                return new DriverPropertyInfo[0];
            }
            public int getMajorVersion() {
                return 1;
            }
            public int getMinorVersion() {
                return 0;
            }
            public boolean jdbcCompliant() {
                return false;
            }
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getAnonymousLogger();
            }
        };
        DriverManager.registerDriver(driver);
        try {
            Path scenario = directory.resolve("scenario.yaml");
            Path observations = directory.resolve("observations.yaml");
            Files.writeString(scenario, """
                    apiVersion: faultora.dev/v1
                    kind: Scenario
                    metadata:
                      name: synthetic-error
                    execute:
                      - id: observe
                        type: operation
                        operationId: observe
                        inputs:
                          paymentId: '%s'
                    """.formatted(marker));
            Files.writeString(observations, """
                    apiVersion: faultora.dev/v1
                    kind: Observations
                    servers:
                      ledger:
                        url: jdbc:synthetic-privacy://127.0.0.1/database
                    observations:
                      observe:
                        server: ledger
                        sql: SELECT CAST(:paymentId AS INTEGER)
                        parameters:
                          paymentId:
                            type: string
                            required: true
                    """);
            ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
            Path output = directory.resolve("reports");
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            int exit;
            try (PrintStream capture = new PrintStream(diagnosticBytes, true, StandardCharsets.UTF_8)) {
                System.setOut(capture);
                System.setErr(capture);
                exit = new FaultoraCli(new PrintWriter(capture, true), new PrintWriter(capture, true)).run(
                    new String[]{"test", "--scenario", scenario.toString(), "--observations", observations.toString(),
                            "--allow-private", "--seed", "42", "--format", "console,json,html,junit", "--output", output.toString()});
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
            String diagnostics = diagnosticBytes.toString(StandardCharsets.UTF_8);
            assertThat(exit).as(diagnostics).isEqualTo(FaultoraCli.EXIT_TEST_FAILURE);
            assertThat(diagnostics).contains("Result:", "OBSERVATION_FAILED").doesNotContain(marker, "Cannot cast");
            for (String file : java.util.List.of("events.ndjson", "report.json", "report.html", "report.xml")) {
                assertThat(Files.readString(output.resolve(file))).as(file).doesNotContain(marker, "Cannot cast");
            }
            assertThat(Files.readString(output.resolve("events.ndjson"))).contains("OBSERVATION_FAILED", "22018");
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }
}
