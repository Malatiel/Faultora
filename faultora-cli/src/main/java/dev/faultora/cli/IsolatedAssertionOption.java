package dev.faultora.cli;

import dev.faultora.runtime.IsolatedAssertion;

import java.nio.file.Path;
import java.util.Iterator;

final class IsolatedAssertionOption {
    static final String FLAG = "--isolated-assertion";

    private IsolatedAssertionOption() { }

    static IsolatedAssertion parse(Iterator<String> args, IsolatedAssertion previous) {
        if (previous != null) {
            throw new CliException(FLAG + " may be used once", FaultoraCli.EXIT_INVALID_CONFIG);
        }
        try {
            String type = next(args);
            String digest = next(args);
            return new IsolatedAssertion(type, digest, Path.of(next(args)));
        } catch (IllegalArgumentException invalid) {
            throw new CliException(FLAG + " requires a type, SHA-256 digest and JAR",
                    FaultoraCli.EXIT_INVALID_CONFIG);
        }
    }

    private static String next(Iterator<String> args) {
        if (!args.hasNext()) {
            throw new IllegalArgumentException("Missing isolated assertion argument");
        }
        return args.next();
    }
}
