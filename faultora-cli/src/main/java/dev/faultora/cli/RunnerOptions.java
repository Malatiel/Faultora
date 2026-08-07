package dev.faultora.cli;

import dev.faultora.model.catalog.SafetyClassification;
import dev.faultora.runner.LocalLimits;
import dev.faultora.spec.parser.DurationSyntax;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Parsed arguments of {@code faultora runner}.
 * <p>
 * This is where a deployment states what it permits, and the statement is the
 * runner's floor: a dispatched policy narrows it and can never widen it. That
 * is what "local refusal independent of controller behaviour" means in
 * practice, so every default here is a security decision and is written down
 * beside the option it belongs to.
 * <p>
 * <b>An empty allowlist does not mean the same thing in every dimension</b>,
 * and the difference is deliberate rather than an oversight to paper over:
 * <ul>
 *   <li>targets, environments and operation classes — empty means <em>no
 *       restriction</em>. A deployment that has not thought about which target
 *       ids exist should not thereby refuse every dispatch.</li>
 *   <li>fault types — empty means <em>none at all</em>. Breaking something is
 *       the capability that has to be granted deliberately, and a runner that
 *       injected faults because nobody had listed any would have the rule
 *       exactly backwards.</li>
 * </ul>
 *
 * @param dispatcherUrl      where the runner dials out to; nothing listens here
 * @param keystore           this runner's own identity, as a PKCS#12 file
 * @param truststore         the control planes it will speak to, and no others
 * @param tlsSecretId        handle the key material's password is resolved from
 * @param policyKeys         verifying certificate per key id — a policy signed
 *                           by anything else is refused
 * @param workDirectory      where journals are written; must be writable
 * @param runnerId           what this runner calls itself at registration
 * @param allowPrivate       whether this deployment may reach private ranges
 * @param limits             everything this runner permits, whatever it is told
 * @param toxiproxyUrl       admin endpoint enabling network faults, or null
 * @param allowedExtensions  classes of non-built-in extensions permitted here
 * @param once               take one dispatch and stop, rather than serving
 * @param helpRequested      whether the operator asked for help instead
 */
record RunnerOptions(
        URI dispatcherUrl,
        Path keystore,
        Path truststore,
        String tlsSecretId,
        Map<String, Path> policyKeys,
        Path workDirectory,
        String runnerId,
        boolean allowPrivate,
        LocalLimits limits,
        String toxiproxyUrl,
        List<String> allowedExtensions,
        boolean once,
        boolean helpRequested
) {
    /** Where journals go when the operator names nowhere. */
    static final Path DEFAULT_WORK_DIRECTORY = Path.of("faultora-runner-work");

    /**
     * What a runner permits before anything is narrowed.
     * <p>
     * The same numbers a local run is bounded by, so a scenario that fits here
     * fits there. Destructive operations are absent and so are faults: both are
     * granted by naming them.
     */
    private static final Set<SafetyClassification> DEFAULT_OPERATION_CLASSES =
            Set.of(SafetyClassification.READ_ONLY, SafetyClassification.MUTATING);
    private static final int DEFAULT_MAX_CONCURRENCY = 10;
    private static final long DEFAULT_MAX_DURATION_MS = 300_000;
    private static final int DEFAULT_MAX_REQUESTS = 1000;
    private static final long DEFAULT_MAX_PAYLOAD_BYTES = 1_048_576;

    /**
     * Parse command arguments.
     *
     * @throws CliException when an option is unknown, incomplete, or malformed
     */
    static RunnerOptions parse(List<String> args) {
        URI dispatcherUrl = null;
        Path keystore = null;
        Path truststore = null;
        String tlsSecretId = null;
        Map<String, Path> policyKeys = new LinkedHashMap<>();
        Path workDirectory = DEFAULT_WORK_DIRECTORY;
        String runnerId = null;
        boolean allowPrivate = false;
        Set<String> allowedTargets = new LinkedHashSet<>();
        Set<String> allowedEnvironments = new LinkedHashSet<>();
        Set<SafetyClassification> allowedClasses = new LinkedHashSet<>();
        Set<String> allowedFaults = new LinkedHashSet<>();
        int maxConcurrency = DEFAULT_MAX_CONCURRENCY;
        long maxDurationMs = DEFAULT_MAX_DURATION_MS;
        int maxRequests = DEFAULT_MAX_REQUESTS;
        long maxPayloadBytes = DEFAULT_MAX_PAYLOAD_BYTES;
        String toxiproxyUrl = null;
        List<String> allowedExtensions = new java.util.ArrayList<>();
        boolean once = false;

        var remaining = args.iterator();
        while (remaining.hasNext()) {
            String option = remaining.next();
            switch (option) {
                case "--help", "-h" -> {
                    return help();
                }
                case "--dispatcher" -> dispatcherUrl = uri(value(remaining, option), option);
                case "--keystore" -> keystore = Path.of(value(remaining, option));
                case "--truststore" -> truststore = Path.of(value(remaining, option));
                case "--tls-secret-id" -> tlsSecretId = value(remaining, option);
                case "--policy-key" -> namedKey(policyKeys, value(remaining, option));
                case "--work-dir" -> workDirectory = Path.of(value(remaining, option));
                case "--runner-id" -> runnerId = value(remaining, option);
                case "--allow-private" -> allowPrivate = true;
                case "--allow-target" -> allowedTargets.add(value(remaining, option));
                case "--allow-environment" -> allowedEnvironments.add(value(remaining, option));
                case "--allow-operation-class" ->
                        allowedClasses.add(operationClass(value(remaining, option)));
                case "--allow-fault" -> allowedFaults.add(value(remaining, option));
                case "--max-concurrency" ->
                        maxConcurrency = (int) positive(value(remaining, option), option);
                case "--max-duration" -> maxDurationMs = duration(value(remaining, option));
                case "--max-requests" ->
                        maxRequests = (int) positive(value(remaining, option), option);
                case "--max-payload-bytes" ->
                        maxPayloadBytes = positive(value(remaining, option), option);
                case "--toxiproxy-url" -> toxiproxyUrl = value(remaining, option);
                case "--allow-extension" -> allowedExtensions.add(value(remaining, option));
                case "--once" -> once = true;
                default -> throw new CliException(
                        "Unknown option: " + option, FaultoraCli.EXIT_INVALID_CONFIG);
            }
        }

        require(dispatcherUrl, "--dispatcher", "the address this runner dials out to");
        require(keystore, "--keystore", "this runner's own key material");
        require(truststore, "--truststore",
                "the control planes this runner will speak to");
        require(tlsSecretId, "--tls-secret-id",
                "the handle that key material's password is resolved from");
        if (policyKeys.isEmpty()) {
            throw new CliException(
                    "Missing --policy-key <id>=<file>: without a verifying key a runner "
                            + "would accept whatever policy it was sent, which is the one "
                            + "thing mutual TLS does not prevent",
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }

        return new RunnerOptions(
                dispatcherUrl, keystore, truststore, tlsSecretId, policyKeys,
                workDirectory,
                runnerId == null ? defaultRunnerId() : runnerId,
                allowPrivate,
                new LocalLimits(
                        allowedTargets,
                        allowedClasses.isEmpty() ? DEFAULT_OPERATION_CLASSES : allowedClasses,
                        allowedEnvironments, allowedFaults,
                        maxConcurrency, maxDurationMs, maxRequests, maxPayloadBytes),
                toxiproxyUrl, List.copyOf(allowedExtensions), once, false);
    }

    private static RunnerOptions help() {
        return new RunnerOptions(null, null, null, null, Map.of(), DEFAULT_WORK_DIRECTORY,
                null, false, null, null, List.of(), false, true);
    }

    /**
     * The name this runner registers under when nobody chose one.
     * <p>
     * The host, because a deployment with several runners needs to tell them
     * apart in a refusal, and the host is the thing an operator can act on.
     */
    private static String defaultRunnerId() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception noName) {
            return "faultora-runner";
        }
    }

    private static void namedKey(Map<String, Path> keys, String value) {
        int separator = value.indexOf('=');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new CliException(
                    "--policy-key expects <id>=<file>, and the id is the one the "
                            + "dispatcher names in the policy it signs; got: " + value,
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }
        keys.put(value.substring(0, separator), Path.of(value.substring(separator + 1)));
    }

    private static SafetyClassification operationClass(String value) {
        try {
            return SafetyClassification.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAClass) {
            throw new CliException(
                    "--allow-operation-class expects one of READ_ONLY, MUTATING, "
                            + "DESTRUCTIVE; got: " + value,
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }
    }

    private static long duration(String value) {
        OptionalLong millis = DurationSyntax.parseMillis(value);
        if (millis.isEmpty() || millis.getAsLong() <= 0) {
            throw new CliException(
                    "--max-duration expects " + DurationSyntax.ACCEPTED_FORMS
                            + "; got: " + value,
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return millis.getAsLong();
    }

    private static long positive(String value, String option) {
        try {
            long number = Long.parseLong(value.trim());
            if (number <= 0) {
                throw new NumberFormatException(value);
            }
            return number;
        } catch (NumberFormatException notANumber) {
            throw new CliException(
                    option + " expects a positive number; got: " + value,
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }
    }

    private static URI uri(String value, String option) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException notAUri) {
            throw new CliException(
                    option + " expects a URL; got: " + value, FaultoraCli.EXIT_INVALID_CONFIG);
        }
    }

    private static String value(java.util.Iterator<String> remaining, String option) {
        if (!remaining.hasNext()) {
            throw new CliException(
                    option + " needs a value", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        return remaining.next();
    }

    private static void require(Object value, String option, String what) {
        if (value == null) {
            throw new CliException(
                    "Missing " + option + ": " + what, FaultoraCli.EXIT_INVALID_CONFIG);
        }
    }
}
