package dev.faultora.extension.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.faultora.extension.protocol.ExtensionCodec;
import dev.faultora.extension.protocol.JsonFrameCodec;
import dev.faultora.process.SupervisedProcess;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single admitted protocol peer. Provider adapters and policy admission remain the caller's job. */
public final class ExtensionProcess implements AutoCloseable {
    public record Provider(String kind, String name) {
        public Provider {
            if (kind == null || !Set.of("assertion", "importer", "renderer").contains(kind)
                    || name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException("Only a named pure provider can be dispatched");
            }
        }
    }

    public record Declaration(String id, String version, Set<Provider> providers) {
        public Declaration {
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    || version == null || version.isBlank() || version.length() > 128
                    || providers == null || providers.isEmpty() || providers.size() > 32) {
                throw new IllegalArgumentException("A bounded plugin declaration is required");
            }
            providers = Set.copyOf(providers);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final SupervisedProcess child;
    private final Declaration declaration;
    private final String session;
    private final AtomicBoolean pending = new AtomicBoolean();
    private ExtensionCodec codec = new ExtensionCodec(JsonFrameCodec.MAX_FRAME_BYTES);
    private volatile boolean closed;
    private long nextId = 1;

    private ExtensionProcess(SupervisedProcess child, Declaration declaration) {
        this.child = child;
        this.declaration = declaration;
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        session = HexFormat.of().formatHex(nonce);
    }

    /** Runtime classpath must contain only operator-controlled bootstrap/SDK code, never the source plugin. */
    public static ExtensionProcess start(StagedArtifact artifact, String entrypoint, List<Path> runtimeClasspath,
                                         Declaration declaration, Duration lifetime) throws IOException, InterruptedException {
        if (entrypoint == null || !entrypoint.matches("[A-Za-z_$][A-Za-z0-9_$.]{0,255}")) {
            throw new IllegalArgumentException("A qualified protocol entrypoint is required");
        }
        String separator = System.getProperty("path.separator");
        List<String> paths = new ArrayList<>();
        for (Path path : runtimeClasspath) {
            String absolute = path.toAbsolutePath().normalize().toString();
            if (absolute.contains(separator)) throw new IllegalArgumentException("Invalid runtime classpath entry");
            paths.add(absolute);
        }
        String classpath = String.join(separator, paths);
        if (classpath.isEmpty() || classpath.length() > 16384) throw new IllegalArgumentException("Invalid runtime classpath");
        long runMs = lifetime.toMillis();
        if (runMs <= 0 || runMs > 300000) throw new IllegalArgumentException("Lifetime must be in 1..300000ms");
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", classpath, VerifiedJvmMain.class.getName(),
                artifact.artifact().toAbsolutePath().toString(), artifact.digest(), entrypoint);
        builder.directory(artifact.directory().toFile());
        String systemRoot = builder.environment().get("SystemRoot");
        builder.environment().clear();
        if (systemRoot != null) builder.environment().put("SystemRoot", systemRoot);
        var child = new SupervisedProcess(builder, runMs, 1000, 1000, 8192);
        var peer = new ExtensionProcess(child, declaration);
        try {
            peer.handshake();
            return peer;
        } catch (IOException | InterruptedException | RuntimeException failed) {
            try { child.close(); } catch (IOException cleanup) { failed.addSuppressed(cleanup); }
            throw failed;
        }
    }

    public long pid() { return child.process().pid(); }
    public boolean isAlive() { return child.process().isAlive(); }
    /** Direct child exit only; not process-tree containment or target-side cleanup. */
    public boolean directProcessExitConfirmed() { return child.directProcessExitConfirmed(); }

    private void handshake() throws IOException, InterruptedException {
        long started = System.nanoTime();
        int budget = budget(Duration.ofSeconds(5));
        ObjectNode data = JSON.createObjectNode().put("maxFrameBytes", JsonFrameCodec.MAX_FRAME_BYTES);
        data.putArray("versions").add("1.0");
        data.set("plugin", plugin());
        var providers = data.putArray("providers");
        declaration.providers().stream().sorted(java.util.Comparator.comparing(p -> p.kind() + "/" + p.name()))
                .forEach(provider -> providers.add(selector(provider)));
        JsonNode ready = exchange(envelope("hello", "0", data).putNull("version"), "0", Duration.ofMillis(budget));
        require(ready.path("kind").asText().equals("ready"), "HANDSHAKE_REFUSED");
        JsonNode selected = ready.get("data");
        fields(selected, "maxFrameBytes", "plugin", "providers");
        require(plugin().equals(selected.get("plugin")), "IDENTITY_MISMATCH");
        require(selected.get("providers").isArray()
                && selected.get("providers").size() == declaration.providers().size(), "UNSUPPORTED_PROVIDER");
        Set<Provider> seen = new HashSet<>();
        for (JsonNode provider : selected.get("providers")) {
            fields(provider, "kind", "name");
            require(provider.get("kind").isTextual() && provider.get("name").isTextual(), "UNSUPPORTED_PROVIDER");
            Provider decoded;
            try { decoded = new Provider(provider.get("kind").asText(), provider.get("name").asText()); }
            catch (IllegalArgumentException invalid) { throw new IOException("UNSUPPORTED_PROVIDER"); }
            require(seen.add(decoded), "UNSUPPORTED_PROVIDER");
        }
        require(seen.equals(declaration.providers()), "UNSUPPORTED_PROVIDER");
        JsonNode maximum = selected.get("maxFrameBytes");
        require(maximum.isIntegralNumber() && maximum.canConvertToInt()
                && maximum.asInt() >= 1024 && maximum.asInt() <= JsonFrameCodec.MAX_FRAME_BYTES, "LIMIT_MISMATCH");
        codec = new ExtensionCodec(maximum.asInt());
        require(beforeDeadline(started, budget), "DEADLINE_EXCEEDED");
    }

    /** Requests stay opaque data here; typed provider adapters validate their result before use. */
    public JsonNode request(Provider provider, JsonNode input, Duration timeout) throws IOException, InterruptedException {
        if (closed || !pending.compareAndSet(false, true)) throw new IOException("INVALID_STATE");
        long started = System.nanoTime();
        try {
            require(declaration.providers().contains(provider), "UNSUPPORTED_PROVIDER");
            String id = nextId();
            int budget = budget(timeout);
            ObjectNode data = JSON.createObjectNode().put("timeoutMs", budget);
            data.set("provider", selector(provider));
            data.set("input", input);
            JsonNode reply = exchange(envelope("request", id, data), id, Duration.ofMillis(budget));
            require(reply.path("kind").asText().equals("result"), "PROVIDER_ERROR");
            fields(reply.get("data"), "provider", "value");
            require(selector(provider).equals(reply.path("data").get("provider")), "ID_MISMATCH");
            require(beforeDeadline(started, budget), "DEADLINE_EXCEEDED");
            return reply.path("data").get("value");
        } catch (IOException | InterruptedException failed) {
            abort(failed);
            throw failed;
        } finally { pending.set(false); }
    }

    public void stop(Duration timeout) throws IOException, InterruptedException {
        if (closed) return;
        if (!pending.compareAndSet(false, true)) { close(); throw new IOException("INVALID_STATE"); }
        long started = System.nanoTime();
        try {
            int budget = budget(timeout);
            String id = nextId();
            JsonNode reply = exchange(envelope("shutdown", id, JSON.createObjectNode().put("timeoutMs", budget)),
                    id, Duration.ofMillis(budget));
            require(reply.path("kind").asText().equals("stopped"), "INVALID_STATE");
            fields(reply.get("data"));
            child.awaitExit(Duration.ofNanos(Math.max(0, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budget)
                    - (System.nanoTime() - started))));
            require(beforeDeadline(started, budget), "DEADLINE_EXCEEDED");
        } finally {
            closed = true;
            pending.set(false);
            child.close();
        }
    }

    private int budget(Duration timeout) throws IOException {
        long budget = Math.min(300000, Math.min(timeout.toMillis(), child.remainingMillis()));
        require(budget > 0, "DEADLINE_EXCEEDED");
        return (int) budget;
    }

    private String nextId() throws IOException {
        require(nextId > 0, "ID_MISMATCH");
        String id = Long.toString(nextId);
        nextId = nextId == Long.MAX_VALUE ? 0 : nextId + 1;
        return id;
    }

    private JsonNode exchange(JsonNode message, String id, Duration timeout) throws IOException, InterruptedException {
        return child.call(() -> {
            codec.write(child.process().getOutputStream(), message);
            child.process().getOutputStream().flush();
            return codec.readReply(child.process().getInputStream(), session, id);
        }, timeout);
    }

    private ObjectNode envelope(String kind, String id, ObjectNode data) {
        ObjectNode message = JSON.createObjectNode().put("protocol", "faultora.extension").put("kind", kind)
                .put("version", "1.0").put("sessionId", session).put("id", id);
        message.set("data", data);
        return message;
    }

    private ObjectNode plugin() { return JSON.createObjectNode().put("id", declaration.id()).put("version", declaration.version()); }
    private static ObjectNode selector(Provider provider) {
        return JSON.createObjectNode().put("kind", provider.kind()).put("name", provider.name());
    }

    private static void fields(JsonNode value, String... expected) throws IOException {
        require(value != null && value.isObject(), "MALFORMED_MESSAGE");
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(Set.of(expected)), "MALFORMED_MESSAGE");
    }

    private static void require(boolean condition, String code) throws IOException {
        if (!condition) throw new IOException(code);
    }

    private static boolean beforeDeadline(long started, int budgetMs) {
        return System.nanoTime() - started < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budgetMs);
    }

    private void abort(Exception failure) {
        closed = true;
        try { child.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
    }

    /** Closing during a pending call cancels it; no protocol shutdown is sent into a busy session. */
    @Override public void close() throws IOException {
        closed = true;
        child.close();
    }

}
