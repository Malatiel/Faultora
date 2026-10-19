package dev.faultora.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** A single connection of bounded UTF-8 JSON-RPC lines; session close owns run cleanup. */
public final class McpStdioServer {
    public static final String PROTOCOL_VERSION = "2025-11-25";
    private final McpConfiguration configuration;
    private boolean initialized;
    private boolean ready;

    private static final class InvalidFrame extends IOException {
        private InvalidFrame(String message) {
            super(message);
        }
    }

    public McpStdioServer(McpConfiguration configuration) {
        this.configuration = configuration;
    }

    /** EOF cancels and joins owned runs before dropping preparations. Neither stream belongs to this adapter. */
    public void serve(InputStream input, PrintWriter output) throws IOException {
        initialized = false;
        ready = false;
        var buffered = new BufferedInputStream(input);
        var tools = new McpTools(configuration);
        Thread shutdown = new Thread(tools::close, "faultora-mcp-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            while (true) {
                byte[] line;
                try {
                    line = readLine(buffered);
                } catch (InvalidFrame malformedFrame) {
                    write(output, error(NullNode.instance, -32700, "Invalid or oversized frame"));
                    return;
                }
                if (line == null) {
                    return;
                }
                JsonNode request;
                try {
                    request = McpJson.MAPPER.readTree(McpJson.utf8(line));
                    if (request == null || request.isMissingNode()) {
                        write(output, error(NullNode.instance, -32700, "Parse error"));
                        continue;
                    }
                } catch (IOException malformed) {
                    write(output, error(NullNode.instance, -32700, "Parse error"));
                    continue;
                }
                JsonNode response = answer(request, tools);
                if (response != null) {
                    write(output, response);
                }
            }
        } finally {
            try {
                tools.close();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException shuttingDown) {
                    // The registered hook owns completion while the JVM is shutting down.
                }
            }
        }
    }

    private JsonNode answer(JsonNode request, McpTools tools) throws IOException {
        if (request == null || !request.isObject() || !request.path("jsonrpc").isTextual()
                || !request.path("jsonrpc").asText().equals("2.0")
                || !request.path("method").isTextual() || request.path("method").asText().isBlank()) {
            return error(NullNode.instance, -32600, "Invalid request");
        }
        JsonNode id = request.get("id");
        if (id == null) {
            if (initialized && request.path("method").asText().equals("notifications/initialized")
                    && (!request.has("params") || request.path("params").isObject())) {
                ready = true;
            }
            return null;
        }
        if (!(id.isIntegralNumber() && id.canConvertToLong())
                && !(id.isTextual() && !id.asText().isBlank() && id.asText().length() <= 128)) {
            return error(NullNode.instance, -32600, "Invalid request ID");
        }
        String method = request.path("method").asText();
        JsonNode params = request.has("params") ? request.get("params") : McpJson.MAPPER.createObjectNode();
        if (!params.isObject()) {
            return error(id, -32602, "Invalid parameters");
        }
        try {
            JsonNode result;
            if (method.equals("initialize")) {
                if (initialized || !params.path("protocolVersion").isTextual()
                        || params.path("protocolVersion").asText().isBlank()
                        || !params.path("capabilities").isObject() || !params.path("clientInfo").isObject()
                        || !params.path("clientInfo").path("name").isTextual()
                        || !params.path("clientInfo").path("version").isTextual()) {
                    return error(id, -32602, "Invalid initialization");
                }
                var response = McpJson.MAPPER.createObjectNode().put("protocolVersion", PROTOCOL_VERSION);
                response.putObject("capabilities").putObject("tools").put("listChanged", false);
                response.putObject("serverInfo").put("name", "faultora").put("version", version());
                initialized = true;
                result = response;
            } else if (method.equals("ping")) {
                result = McpJson.MAPPER.createObjectNode();
            } else if (!ready) {
                return error(id, -32000, "Session is not initialized");
            } else if (method.equals("tools/list")) {
                if (params.has("cursor")) {
                    return error(id, -32602, "Pagination is unavailable");
                }
                result = tools.definitions();
            } else if (method.equals("tools/call")) {
                if (!params.path("name").isTextual()) {
                    return error(id, -32602, "Invalid tool call");
                }
                String name = params.path("name").asText();
                if (!tools.names().contains(name)) {
                    return error(id, -32602, "Unknown tool");
                }
                JsonNode arguments = params.has("arguments")
                        ? params.get("arguments") : McpJson.MAPPER.createObjectNode();
                result = tools.call(name, arguments);
            } else {
                return error(id, -32601, "Method not found");
            }
            var response = McpJson.MAPPER.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", result);
            return response;
        } catch (RuntimeException internal) {
            return error(id, -32603, "Internal error");
        }
    }

    private static String version() {
        return dev.faultora.runtime.FaultoraVersion.current();
    }

    private static JsonNode error(JsonNode id, int code, String message) {
        var response = McpJson.MAPPER.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }

    private static void write(PrintWriter output, JsonNode response) throws IOException {
        String json = McpJson.MAPPER.writeValueAsString(response);
        if (json.getBytes(StandardCharsets.UTF_8).length > 262_144) {
            json = McpJson.MAPPER.writeValueAsString(error(response.path("id"), -32603, "Result too large"));
        }
        output.println(json);
        output.flush();
        if (output.checkError()) {
            throw new IOException("MCP output unavailable");
        }
    }

    private static byte[] readLine(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value == -1) {
                if (bytes.size() == 0) {
                    return null;
                }
                throw new InvalidFrame("Incomplete frame");
            }
            if (value == '\n') {
                return bytes.toByteArray();
            }
            if (bytes.size() >= McpJson.MAX_REQUEST_BYTES) {
                throw new InvalidFrame("Oversized frame");
            }
            bytes.write(value);
        }
    }
}
