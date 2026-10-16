package dev.faultora.mcp;

import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runtime.CatalogAssembly;

import java.util.Map;
import java.util.Set;

final class McpFixtures {
    static final String MARKER = "SYNTHETIC_PRIVATE_VALUE";
    static final String CATALOG = """
            openapi: 3.0.3
            info: {title: synthetic, version: '1'}
            servers:
              - url: http://api.example.com/SYNTHETIC_PRIVATE_VALUE
            paths:
              /read:
                get:
                  operationId: read
                  responses: {'200': {description: synthetic}}
            """;
    static final String SCENARIO = """
            apiVersion: faultora.dev/v1
            kind: Scenario
            metadata: {name: synthetic}
            execute:
              - id: read
                type: operation
                operationId: read
            """;
    static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
            "capabilities":{},"clientInfo":{"name":"fixture-client","version":"1"}}}
            """.replace("\n", "");
    static final String READY = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

    private McpFixtures() {
    }

    static McpConfiguration configuration() {
        return new McpConfiguration(Map.of("demo", new CatalogAssembly.Document("openapi", CATALOG)), policy());
    }

    static TargetPolicy policy() {
        return new TargetPolicy(Set.of(), Set.of(SafetyClassification.READ_ONLY), 1000, 10, 300_000,
                1_048_576, Set.of("http-latency"), Set.of());
    }
}
