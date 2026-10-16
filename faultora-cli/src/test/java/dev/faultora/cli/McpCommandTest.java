package dev.faultora.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpCommandTest {
    @TempDir Path directory;

    @Test
    void commandUsesStdioAndEofWithoutWritingAHumanPreamble() throws Exception {
        Path config = directory.resolve("operator.json");
        Files.writeString(config, "{\"catalogs\":[]}");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        var command = new McpCommand(new ByteArrayInputStream(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}\n".getBytes(StandardCharsets.UTF_8)),
                new PrintWriter(stdout), new PrintWriter(stderr));
        assertThat(command.execute(List.of("--config", config.toString()))).isZero();
        assertThat(stdout.toString().lines()).hasSize(1);
        assertThat(new ObjectMapper().readTree(stdout.toString()).path("id").asInt()).isEqualTo(7);
        assertThat(stderr.toString()).isEmpty();
        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("operator.json");
        }
    }

    @Test
    void startupRefusalsNeverEchoPathsOrInvalidValues() {
        for (List<String> arguments : List.of(List.<String>of(), List.of("--unknown"),
                List.of("--config", directory.resolve("SYNTHETIC_PRIVATE_VALUE").toString()))) {
            StringWriter stdout = new StringWriter();
            StringWriter stderr = new StringWriter();
            var command = new McpCommand(new ByteArrayInputStream(new byte[0]),
                    new PrintWriter(stdout), new PrintWriter(stderr));
            assertThat(command.execute(arguments)).isEqualTo(2);
            assertThat(stdout.toString()).isEmpty();
            assertThat(stderr.toString()).doesNotContain("SYNTHETIC_PRIVATE_VALUE", directory.toString());
        }
    }

    @Test
    void machineDiagnosticsCannotBeMixedWithMcpFrames() throws Exception {
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = new FaultoraCli(new PrintWriter(stdout), new PrintWriter(stderr))
                .run(new String[]{"--diagnostics=json", "mcp", "--config", "SYNTHETIC_PRIVATE_VALUE"});
        assertThat(exit).isEqualTo(2);
        var result = new ObjectMapper().readTree(stdout.toString());
        assertThat(result.path("command").asText()).isEqualTo("mcp");
        assertThat(result.path("diagnostics").get(0).path("code").asText()).isEqualTo("INVALID_ARGUMENT");
        assertThat(stdout.toString()).doesNotContain("SYNTHETIC_PRIVATE_VALUE");
        assertThat(stderr.toString()).isEmpty();
    }

    @Test
    void mcpHelpIsWrittenToStderr() {
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = new FaultoraCli(new PrintWriter(stdout), new PrintWriter(stderr)).run(new String[]{"mcp", "--help"});
        assertThat(exit).isZero();
        assertThat(stdout.toString()).isEmpty();
        assertThat(stderr.toString()).contains("--config");
    }

    @Test
    void inputIoFailureUsesTheTransportExitCodeAndWithholdsExceptionData() throws Exception {
        Path config = directory.resolve("operator.json");
        Files.writeString(config, "{\"catalogs\":[]}");
        var broken = new java.io.InputStream() {
            public int read() throws java.io.IOException {
                throw new java.io.IOException("SYNTHETIC_PRIVATE_VALUE");
            }
        };
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = new McpCommand(broken, new PrintWriter(stdout), new PrintWriter(stderr))
                .execute(List.of("--config", config.toString()));
        assertThat(exit).isEqualTo(3);
        assertThat(stdout.toString()).isEmpty();
        assertThat(stderr.toString()).contains("transport unavailable").doesNotContain("SYNTHETIC_PRIVATE_VALUE");
    }
}
