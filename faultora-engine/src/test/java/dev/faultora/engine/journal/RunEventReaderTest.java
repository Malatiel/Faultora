package dev.faultora.engine.journal;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import dev.faultora.model.events.RunEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reader checks every field of a known event against its Java type
 * before binding it. A type it has no rule for must be a decision, not a
 * fallback: treating it as an object would refuse a valid list or number,
 * or accept the wrong shape for a record.
 */
class RunEventReaderTest {

    record WithList(List<String> values) {
    }

    record WithBoxedNumber(Integer count) {
    }

    record WithNestedList(WithList inner) {
    }

    @Test
    void everyJournalFieldHasAnExplicitRule() {
        List<String> unsupported = new ArrayList<>();
        for (JsonSubTypes.Type subtype : RunEvent.class.getAnnotation(JsonSubTypes.class).value()) {
            for (RecordComponent component : subtype.value().getRecordComponents()) {
                if (!RunEventReader.supports(component.getType())) {
                    unsupported.add(subtype.name() + "." + component.getName()
                            + ": " + component.getType().getSimpleName());
                }
            }
        }

        assertThat(unsupported)
                .as("""
                        A journal event has a field type RunEventReader does not validate. \
                        Add an explicit rule to RunEventReader for it, and a contract \
                        fixture, before the event can ship.""")
                .isEmpty();
    }

    @Test
    void aTypeWithoutARuleIsNotTakenForAnObject() {
        assertThat(RunEventReader.supports(WithList.class)).isFalse();
        assertThat(RunEventReader.supports(WithBoxedNumber.class)).isFalse();
        assertThat(RunEventReader.supports(WithNestedList.class))
                .as("an unsupported type nested in a record is still unsupported")
                .isFalse();
        assertThat(RunEventReader.supports(double.class)).isFalse();
    }

    @Test
    void theTypesTheJournalUsesTodayAreSupported() {
        assertThat(Arrays.asList(String.class, long.class, int.class, boolean.class,
                java.util.Map.class, dev.faultora.model.identifier.RunId.class,
                dev.faultora.model.catalog.NormalizedError.class))
                .allMatch(RunEventReader::supports);
    }

    @Test
    void aMapFieldThatIsNotAnObjectIsRefused() {
        String line = """
                {"eventType":"RUN_STARTED","timestamp":1,"runId":"run-1",\
                "scenarioDigest":"sha256:a","catalogDigest":"sha256:b","seed":7,\
                "resolvedConfig":["not","a","map"]}""";

        assertThatThrownBy(() -> RunEventReader.readLine(line))
                .isInstanceOf(IOException.class)
                .hasMessage("Invalid journal field type");
    }

    @Test
    void aWellFormedKnownEventIsRead() throws IOException {
        String line = """
                {"eventType":"RUN_STARTED","timestamp":1,"runId":"run-1",\
                "scenarioDigest":"sha256:a","catalogDigest":"sha256:b","seed":7,\
                "resolvedConfig":{"requestBudgetUnit":"connector-attempt"}}""";

        assertThat(RunEventReader.readLine(line)).get()
                .isInstanceOf(RunEvent.RunStarted.class);
    }
}
