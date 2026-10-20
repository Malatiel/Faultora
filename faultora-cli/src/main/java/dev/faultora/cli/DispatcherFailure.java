package dev.faultora.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.faultora.runner.TlsMaterial;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Why the dispatcher could not be spoken to, in words an operator can act on.
 * <p>
 * The runner used to print the exception's message, and the JDK's HTTP client
 * raises a refused connection with none — so the one line an operator had to go
 * on read "Could not register: null". The cause is now named by its kind, in
 * fixed text that carries no address, header or certificate detail, and says
 * what to check.
 */
final class DispatcherFailure {

    private DispatcherFailure() {
    }

    /**
     * The most specific recognisable cause anywhere in the chain, described;
     * never null.
     * <p>
     * Most specific, not outermost: the JDK's HTTP client reports a name that
     * does not resolve as a ConnectException wrapping another one wrapping
     * {@link UnresolvedAddressException}, and reading the outer layer told an
     * operator with a mistyped host to go and start the dispatcher. A bare
     * connection failure is the answer only when nothing below it says more.
     */
    static String describe(Throwable failure) {
        List<Throwable> chain = chain(failure);
        for (Kind kind : Kind.values()) {
            for (Throwable cause : chain) {
                if (kind.matches(cause)) {
                    return kind.describe(cause);
                }
            }
        }
        return failure instanceof IOException
                ? "the connection failed (" + failure.getClass().getSimpleName() + ")"
                : "an unexpected error (" + failure.getClass().getSimpleName() + ")";
    }

    private static List<Throwable> chain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }

    /** In order of precedence: the first kind found anywhere in the chain wins. */
    private enum Kind {
        // The TLS material's own message was written for this operator: it
        // names the files, never the password.
        TLS_MATERIAL(TlsMaterial.TlsUnavailable.class) {
            @Override
            String describe(Throwable cause) {
                return cause.getMessage();
            }
        },
        UNRESOLVED("the dispatcher's host name does not resolve; check --dispatcher",
                UnknownHostException.class, UnresolvedAddressException.class),
        TLS_HANDSHAKE("the TLS handshake failed; check that --truststore trusts the dispatcher's"
                + " certificate and that the dispatcher accepts this runner's --keystore identity",
                SSLException.class),
        CONNECT_TIMEOUT("the connection timed out; check the network path to the dispatcher",
                HttpConnectTimeoutException.class),
        ANSWER_TIMEOUT("the dispatcher accepted the connection but did not answer in time",
                HttpTimeoutException.class, SocketTimeoutException.class),
        NOT_THE_PROTOCOL("the dispatcher's answer is not a runner protocol response;"
                + " check that --dispatcher is the control plane", JsonProcessingException.class),
        REFUSED("the connection was refused; check that the dispatcher is running"
                + " and that --dispatcher points at it", ConnectException.class);

        private final String text;
        private final List<Class<?>> types;

        Kind(String text, Class<?>... types) {
            this.text = text;
            this.types = List.of(types);
        }

        Kind(Class<?>... types) {
            this(null, types);
        }

        boolean matches(Throwable cause) {
            return types.stream().anyMatch(type -> type.isInstance(cause));
        }

        String describe(Throwable cause) {
            return text;
        }
    }
}
