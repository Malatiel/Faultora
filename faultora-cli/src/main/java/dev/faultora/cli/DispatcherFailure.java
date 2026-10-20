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

    /** The first recognisable cause in the chain, described; never null. */
    static String describe(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String known = known(cause);
            if (known != null) {
                return known;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return failure instanceof IOException
                ? "the connection failed (" + failure.getClass().getSimpleName() + ")"
                : "an unexpected error (" + failure.getClass().getSimpleName() + ")";
    }

    private static String known(Throwable cause) {
        // The TLS material's own message was written for this operator: it
        // names the files, never the password.
        if (cause instanceof TlsMaterial.TlsUnavailable unusable) {
            return unusable.getMessage();
        }
        if (cause instanceof UnknownHostException) {
            return "the dispatcher's host name does not resolve; check --dispatcher";
        }
        if (cause instanceof ConnectException) {
            return "the connection was refused; check that the dispatcher is running"
                    + " and that --dispatcher points at it";
        }
        if (cause instanceof HttpConnectTimeoutException) {
            return "the connection timed out; check the network path to the dispatcher";
        }
        if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
            return "the dispatcher accepted the connection but did not answer in time";
        }
        if (cause instanceof SSLException) {
            return "the TLS handshake failed; check that --truststore trusts the dispatcher's"
                    + " certificate and that the dispatcher accepts this runner's --keystore identity";
        }
        if (cause instanceof JsonProcessingException) {
            return "the dispatcher's answer is not a runner protocol response;"
                    + " check that --dispatcher is the control plane";
        }
        return null;
    }
}
