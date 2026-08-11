package dev.faultora.spec.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a {@code faultora.dev/v1} document may contain, held as a list.
 * <p>
 * A freeze that lives only in a document is a sentence the code drifts away
 * from. This is the freeze as something that can fail: the shape of the parsed
 * document is derived from the model itself and compared with a committed
 * snapshot, so a field added to a record — or an assertion type added to an
 * enum — stops the build until somebody writes it down.
 * <p>
 * <b>Updating the snapshot is the decision, not the chore.</b> From 1.0 the
 * only permitted change is an addition that an older 1.x release would have
 * ignored rather than refused; anything else — a rename, a removal, a type
 * change, a field that becomes required — is a new API version. The failure
 * message says so, because whoever hits it is usually not the person who
 * decided it.
 * <p>
 * What this does not check is behaviour. Two releases can accept identical
 * documents and mean different things by them, which is what the semantics
 * ADRs and their tests are for; this only holds the surface still.
 */
class ScenarioSurfaceTest {

    /** The committed snapshot, beside this test. */
    private static final String SNAPSHOT = "/v1-scenario-surface.txt";

    @Test
    void theDocumentSurfaceIsTheOneThatWasFrozen() throws IOException {
        List<String> surface = surfaceOf(ScenarioDocument.class);

        assertThat(surface)
                .as("""
                        The shape of a scenario document has changed.

                        faultora.dev/v1 is frozen: the only change a 1.x release may make \
                        is adding something an older 1.x would have ignored rather than \
                        refused. A rename, a removal, a type change, or a field that \
                        becomes required is a new apiVersion — see ApiVersions and \
                        ADR-022.

                        If this addition was decided, update \
                        faultora-spec/src/test/resources%s and say so in the changelog. \
                        If it was not, this is the test doing its job.""".formatted(SNAPSHOT))
                .isEqualTo(committedSurface());
    }

    @Test
    void theSnapshotNoticesOneFieldGoingMissing() throws IOException {
        // The comparison above passes, and a comparison that has never failed
        // says nothing about what it would catch. One line removed from the
        // committed list is the smallest change a freeze has to notice — an
        // added field is the same comparison from the other side.
        List<String> withoutOne = new ArrayList<>(committedSurface());
        String dropped = withoutOne.remove(withoutOne.size() / 2);

        assertThat(surfaceOf(ScenarioDocument.class))
                .as("dropping " + dropped + " has to be visible")
                .isNotEqualTo(withoutOne);
    }

    @Test
    void theFrozenVersionIsTheOneWrittenAndTheOldOneIsStillRead() {
        // The other half of the freeze, and the half a snapshot cannot hold:
        // what this release writes, what it still reads, and until when.
        assertThat(ApiVersions.CURRENT).isEqualTo("faultora.dev/v1");
        assertThat(ApiVersions.accepted()).contains("faultora.dev/v1alpha1");
        assertThat(ApiVersions.isDeprecated("faultora.dev/v1alpha1")).isTrue();
        assertThat(ApiVersions.deprecationNotice("faultora.dev/v1alpha1"))
                .as("a deprecation nobody can act on is a warning people learn to skip")
                .contains("faultora migrate", ApiVersions.SUNSET, ApiVersions.CURRENT);
    }

    /**
     * The document's shape, as lines a person can read in a diff.
     * <p>
     * Records become their components, enums become their constants, and the
     * walk follows every model type reachable from the root. Sorted, because
     * the order Java reports members in is not a promise and a snapshot that
     * churned would be a snapshot nobody reads.
     */
    private static List<String> surfaceOf(Class<?> root) {
        Set<Class<?>> seen = new LinkedHashSet<>();
        Set<String> lines = new TreeSet<>();
        describe(root, seen, lines);
        return new ArrayList<>(lines);
    }

    private static void describe(Class<?> type, Set<Class<?>> seen, Set<String> lines) {
        if (!seen.add(type)) {
            return;
        }
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                lines.add(type.getSimpleName() + " = " + constant);
            }
            return;
        }
        if (!type.isRecord()) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            lines.add(type.getSimpleName() + "." + component.getName()
                    + ": " + nameOf(component));
            for (Class<?> reachable : modelTypesIn(component)) {
                describe(reachable, seen, lines);
            }
        }
    }

    /** A component's type, with its generic arguments, and without packages. */
    private static String nameOf(RecordComponent component) {
        return component.getGenericType().getTypeName()
                .replaceAll("[a-z0-9_]+\\.", "")
                .replace('$', '.');
    }

    /** The model types a component mentions, directly or inside a collection. */
    private static List<Class<?>> modelTypesIn(RecordComponent component) {
        List<Class<?>> types = new ArrayList<>();
        collect(component.getGenericType(), types);
        return types.stream()
                .filter(type -> type.getName().startsWith("dev.faultora.spec.model"))
                .toList();
    }

    private static void collect(java.lang.reflect.Type type, List<Class<?>> into) {
        if (type instanceof Class<?> raw) {
            into.add(raw);
        } else if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
            collect(parameterized.getRawType(), into);
            for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
                collect(argument, into);
            }
        }
    }

    private static List<String> committedSurface() throws IOException {
        try (InputStream snapshot = ScenarioSurfaceTest.class.getResourceAsStream(SNAPSHOT)) {
            if (snapshot == null) {
                throw new IllegalStateException("No snapshot at " + SNAPSHOT);
            }
            return new String(snapshot.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
    }
}
