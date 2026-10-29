package dev.faultora.architecture;

import dev.faultora.cli.StructuredLogEncoder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every log call in Faultora's own code writes one approved, fixed event.
 * <p>
 * The encoder withholds any message it does not recognise, so a new call site
 * with values in it would not leak them — it would write nothing useful. This
 * keeps the two in step: a call must pass a single string literal, with no
 * placeholder and no concatenation, that the encoder approves, and the
 * encoder approves nothing no call site uses.
 */
class LogEventInventoryTest {
    private static final Path ROOT = Path.of(System.getProperty("faultora.projectRoot"));
    private static final Pattern CALL = Pattern.compile(
            "\\b(?:LOG|log|logger|LOGGER)\\.(?:trace|debug|info|warn|error)\\(([^;]*)\\);");
    private static final Pattern LITERAL = Pattern.compile("\\s*\"([^\"\\\\]*)\"\\s*");

    @Test
    void everyLogCallWritesAnApprovedFixedEvent() throws IOException {
        List<String> violations = new ArrayList<>();
        Set<String> used = new TreeSet<>();
        int calls = 0;
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().replace('\\', '/').contains("/src/main/java/"))
                    .toList()) {
                Matcher call = CALL.matcher(Files.readString(file));
                while (call.find()) {
                    calls++;
                    String where = ROOT.relativize(file) + ": " + call.group();
                    Matcher literal = LITERAL.matcher(call.group(1));
                    if (!literal.matches()) {
                        violations.add(where + " — not a single string literal");
                    } else if (literal.group(1).contains("{}")) {
                        violations.add(where + " — a placeholder");
                    } else if (!StructuredLogEncoder.APPROVED_EVENTS.contains(literal.group(1))) {
                        violations.add(where + " — not in StructuredLogEncoder.APPROVED_EVENTS");
                    } else {
                        used.add(literal.group(1));
                    }
                }
            }
        }
        assertThat(calls).as("the scan finds the known call sites").isGreaterThanOrEqualTo(9);
        assertThat(violations).isEmpty();
        assertThat(used).as("every approved event has a call site")
                .containsExactlyInAnyOrderElementsOf(StructuredLogEncoder.APPROVED_EVENTS);
    }
}
