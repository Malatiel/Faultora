package dev.faultora.engine.exec;

import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.catalog.TargetDefinition;
import dev.faultora.model.identifier.TargetId;
import dev.faultora.spi.context.ConnectorContext;

/**
 * Resolves the target an operation runs against.
 * <p>
 * The catalog is the source of truth: a target's identity, protocols, auth
 * schemes, and metadata come from the imported description. The operator may
 * redirect where that target actually lives — a test environment rather than
 * the URL written in the specification — and nothing else.
 * <p>
 * A redirect is either global or per target ID, which is what lets one scenario
 * address several systems once the catalog declares them. A global redirect
 * rebinds every target <em>that speaks its protocol</em>: once a run can span
 * HTTP and a broker, a single {@code --target http://localhost:8080} that
 * silently rebound the broker too would send event operations at a web server,
 * and the failure would surface as an unintelligible complaint about a
 * bootstrap list. A target of another protocol keeps what its description said
 * until the operator names it.
 */
public final class TargetResolver {

    /** Config key redirecting every target. */
    public static final String BASE_URL = "baseUrl";

    /** Config key prefix redirecting one target: {@code baseUrl.<targetId>}. */
    public static final String BASE_URL_PREFIX = "baseUrl.";

    private TargetResolver() {
    }

    /**
     * @return the target to connect to, or null when the catalog does not
     *         declare it and no redirect names it
     */
    public static TargetDefinition resolve(
            TargetId targetId, ApiCatalog catalog, ConnectorContext context) {
        TargetDefinition declared = catalog == null ? null : catalog.targets().stream()
                .filter(target -> target.id().equals(targetId))
                .findFirst().orElse(null);
        String redirect = redirectFor(targetId, declared, context);

        if (declared == null) {
            return redirect == null ? null : new TargetDefinition(
                    targetId, targetId.value(), redirect,
                    java.util.List.of(), java.util.List.of(), java.util.Map.of());
        }
        if (redirect == null || redirect.equals(declared.baseUrl())) {
            return declared;
        }
        return new TargetDefinition(
                declared.id(), declared.name(), redirect,
                declared.protocols(), declared.authSchemeIds(), declared.metadata());
    }

    /** The base URL the operator bound this target to, or null. */
    private static String redirectFor(
            TargetId targetId, TargetDefinition declared, ConnectorContext context) {
        Object perTarget = context.config().get(BASE_URL_PREFIX + targetId.value());
        if (perTarget instanceof String url && !url.isBlank()) {
            // Named explicitly: the operator meant this target, whatever it speaks.
            return url;
        }
        Object global = context.config().get(BASE_URL);
        if (!(global instanceof String url) || url.isBlank()) {
            return null;
        }
        return speaksThe(url, declared) ? url : null;
    }

    /**
     * Whether a redirect written as this URL is addressed to this target.
     * <p>
     * A target that declares no protocol is not excluded: a scenario-derived
     * catalog names none, and refusing to rebind it would break the simplest
     * way to run Faultora at all.
     */
    private static boolean speaksThe(String url, TargetDefinition declared) {
        if (declared == null || declared.protocols() == null || declared.protocols().isEmpty()) {
            return true;
        }
        String protocol = protocolOf(url);
        if (protocol == null) {
            return true;
        }
        return declared.protocols().stream()
                .anyMatch(declaredProtocol -> protocol.equals(declaredProtocol.value()));
    }

    /** The protocol a URL's scheme names, or null when it names none. */
    private static String protocolOf(String url) {
        int scheme = url.indexOf("://");
        if (scheme <= 0) {
            return null;
        }
        String name = url.substring(0, scheme).toLowerCase(java.util.Locale.ROOT);
        // A connector speaks one protocol; the transport it is secured with is
        // not a different one.
        return switch (name) {
            case "https" -> "http";
            case "kafka-secure" -> "kafka";
            default -> name;
        };
    }
}
