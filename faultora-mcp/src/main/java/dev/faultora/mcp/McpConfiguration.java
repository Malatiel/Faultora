package dev.faultora.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.faultora.faults.local.LocalFaultProvider;
import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.runtime.CatalogAssembly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Operator-owned startup inputs. Tool arguments never become filesystem paths. */
public record McpConfiguration(Map<String, CatalogAssembly.Document> catalogs, TargetPolicy targets) {
    public McpConfiguration {
        catalogs = Map.copyOf(catalogs);
    }

    public static McpConfiguration load(Path file) throws IOException {
        try {
            JsonNode root = McpJson.MAPPER.readTree(readFile(file, 65_536));
            fields(root, Set.of("catalogs", "allowedTargets", "allowedOperationClasses", "allowedFaultTypes"));
            JsonNode declarations = root.path("catalogs");
            if (!declarations.isArray() || declarations.size() > 16) {
                throw new IllegalArgumentException();
            }
            Map<String, CatalogAssembly.Document> catalogs = new LinkedHashMap<>();
            int totalBytes = 0;
            for (JsonNode entry : declarations) {
                fields(entry, Set.of("id", "family", "path"));
                String id = text(entry, "id");
                String family = text(entry, "family");
                if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}") || catalogs.containsKey(id)
                        || !Set.of("openapi", "asyncapi", "observations").contains(family)) {
                    throw new IllegalArgumentException();
                }
                Path source = file.toAbsolutePath().getParent().resolve(text(entry, "path"));
                byte[] bytes = readFile(source, 1_048_576);
                totalBytes += bytes.length;
                if (totalBytes > 4_194_304) {
                    throw new IllegalArgumentException();
                }
                catalogs.put(id, new CatalogAssembly.Document(family, McpJson.utf8(bytes)));
            }
            Set<TargetId> targets = new LinkedHashSet<>();
            for (String target : strings(root, "allowedTargets", Set.of())) {
                targets.add(new TargetId(target));
            }
            Set<SafetyClassification> classes = new LinkedHashSet<>();
            for (String classification : strings(root, "allowedOperationClasses", Set.of("READ_ONLY"))) {
                classes.add(SafetyClassification.valueOf(classification));
            }
            if (classes.isEmpty()) {
                throw new IllegalArgumentException();
            }
            Set<String> faults = strings(root, "allowedFaultTypes", Set.of());
            if (!LocalFaultProvider.supportedFaultTypes().containsAll(faults)) {
                throw new IllegalArgumentException();
            }
            return new McpConfiguration(catalogs, new TargetPolicy(targets, classes,
                    1000, 10, 300_000, 1_048_576, faults, Set.of()));
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IOException("MCP configuration is invalid or a declared source is unavailable");
        }
    }

    private static byte[] readFile(Path file, int limit) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)
                || !absolute.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(absolute.toRealPath())) {
            throw new IOException("MCP source must be a regular file without redirected ancestors");
        }
        try (var input = Files.newInputStream(absolute, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException("MCP source exceeds its limit");
            }
            return bytes;
        }
    }

    static void fields(JsonNode object, Set<String> allowed) {
        if (object == null || !object.isObject()) {
            throw new IllegalArgumentException();
        }
        object.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException();
            }
        });
    }

    static String text(JsonNode object, String name) {
        JsonNode value = object.path(name);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException();
        }
        return value.asText();
    }

    private static Set<String> strings(JsonNode object, String name, Set<String> defaults) {
        JsonNode value = object.path(name);
        if (value.isMissingNode()) {
            return defaults;
        }
        if (!value.isArray() || value.size() > 128) {
            throw new IllegalArgumentException();
        }
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 128
                    || !result.add(item.asText())) {
                throw new IllegalArgumentException();
            }
        }
        return Set.copyOf(result);
    }
}
