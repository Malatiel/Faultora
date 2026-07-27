package dev.faultora.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parsed arguments of {@code faultora test}.
 * <p>
 * Argument syntax lives here and nowhere else: the command itself deals in a
 * validated value object, and every default is visible in one place.
 *
 * @param scenarioPath  scenario document to run
 * @param openApiPath   OpenAPI document to import, or null to derive a catalog
 *                      from the scenario
 * @param targetUrl     base URL every catalog target is bound to
 * @param targetUrls    per-target base URLs, keyed by catalog target ID
 * @param formats       report formats to render
 * @param outputDir     directory receiving the journal and reports
 * @param seed          run seed; identical seeds reproduce generated values
 * @param allowPrivate  whether private and loopback destinations are allowed
 * @param authSecretId  secret handle supplying the bearer token, or null
 * @param toxiproxyUrl  Toxiproxy admin endpoint enabling network faults, or null
 * @param inputs        values for declared scenario inputs
 * @param helpRequested whether the user asked for help instead of a run
 */
record TestOptions(
        Path scenarioPath,
        Path openApiPath,
        String targetUrl,
        Map<String, String> targetUrls,
        List<String> formats,
        Path outputDir,
        long seed,
        boolean allowPrivate,
        String authSecretId,
        String toxiproxyUrl,
        Map<String, Object> inputs,
        boolean helpRequested
) {
    static final String DEFAULT_TARGET_URL = "http://localhost:8080";

    /**
     * Parse command arguments.
     *
     * @throws CliException when an option is unknown, incomplete, or malformed
     */
    static TestOptions parse(List<String> args) {
        Path scenarioPath = null;
        Path openApiPath = null;
        String targetUrl = DEFAULT_TARGET_URL;
        Map<String, String> targetUrls = new LinkedHashMap<>();
        List<String> formats = List.of("console");
        Path outputDir = Path.of("faultora-results");
        long seed = System.currentTimeMillis();
        boolean allowPrivate = false;
        String authSecretId = null;
        String toxiproxyUrl = null;
        Map<String, Object> inputs = new LinkedHashMap<>();

        Iterator<String> it = args.iterator();
        while (it.hasNext()) {
            String arg = it.next();
            switch (arg) {
                case "--scenario", "-s" -> scenarioPath = Path.of(requireNext(it, "--scenario"));
                case "--openapi", "-o" -> openApiPath = Path.of(requireNext(it, "--openapi"));
                case "--target", "-t" -> {
                    String value = requireNext(it, "--target");
                    String targetId = targetIdOf(value);
                    if (targetId == null) {
                        targetUrl = value;
                    } else {
                        targetUrls.put(targetId, value.substring(targetId.length() + 1));
                    }
                }
                case "--format", "-f" -> formats = parseFormats(requireNext(it, "--format"));
                case "--output" -> outputDir = Path.of(requireNext(it, "--output"));
                case "--seed" -> seed = parseSeed(requireNext(it, "--seed"));
                case "--allow-private" -> allowPrivate = true;
                case "--auth-secret-id" -> authSecretId = requireNext(it, "--auth-secret-id");
                case "--toxiproxy-url" -> toxiproxyUrl = requireNext(it, "--toxiproxy-url");
                case "--input", "-i" -> {
                    String pair = requireNext(it, "--input");
                    int separator = pair.indexOf('=');
                    if (separator <= 0) {
                        throw new CliException(
                                "Option --input requires key=value, got: " + pair,
                                FaultoraCli.EXIT_INVALID_CONFIG);
                    }
                    inputs.put(pair.substring(0, separator),
                            parseInputValue(pair.substring(separator + 1)));
                }
                case "--help", "-h" -> {
                    return help();
                }
                default -> throw new CliException(
                        "Unknown option: " + arg, FaultoraCli.EXIT_INVALID_CONFIG);
            }
        }

        if (scenarioPath == null) {
            throw new CliException("--scenario is required", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        if (formats.isEmpty()) {
            throw new CliException("--format requires at least one format",
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }

        return new TestOptions(
                scenarioPath, openApiPath, targetUrl, Map.copyOf(targetUrls), formats,
                outputDir, seed, allowPrivate, authSecretId, toxiproxyUrl,
                Map.copyOf(inputs), false);
    }

    /**
     * The catalog target ID a {@code --target} value binds, or null when the
     * value is a plain URL binding every target. A leading {@code id=} counts
     * only when the prefix cannot be part of a URL, so query strings such as
     * {@code http://host/?a=b} stay whole.
     */
    private static String targetIdOf(String value) {
        int separator = value.indexOf('=');
        if (separator <= 0) {
            return null;
        }
        String prefix = value.substring(0, separator);
        return prefix.contains(":") || prefix.contains("/") ? null : prefix;
    }

    private static TestOptions help() {
        return new TestOptions(
                null, null, DEFAULT_TARGET_URL, Map.of(), List.of(), Path.of("."),
                0, false, null, null, Map.of(), true);
    }

    private static List<String> parseFormats(String value) {
        List<String> formats = new ArrayList<>(Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(format -> !format.isEmpty())
                .map(format -> format.toLowerCase(Locale.ROOT))
                .distinct()
                .toList());
        return List.copyOf(formats);
    }

    private static long parseSeed(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new CliException(
                    "Option --seed requires an integer", FaultoraCli.EXIT_INVALID_CONFIG);
        }
    }

    /** Input values keep the type the user typed: booleans and numbers stay so. */
    private static Object parseInputValue(String raw) {
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return Boolean.parseBoolean(raw);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException notLong) {
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException notDouble) {
                return raw;
            }
        }
    }

    private static String requireNext(Iterator<String> it, String flag) {
        if (!it.hasNext()) {
            throw new CliException(
                    "Option " + flag + " requires a value", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return it.next();
    }
}
