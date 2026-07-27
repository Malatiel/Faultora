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
 * A redirect is either global (every target) or per target ID, which is what
 * lets one scenario address several systems once the catalog declares them.
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
        String redirect = redirectFor(targetId, context);

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
    private static String redirectFor(TargetId targetId, ConnectorContext context) {
        Object perTarget = context.config().get(BASE_URL_PREFIX + targetId.value());
        if (perTarget instanceof String url && !url.isBlank()) {
            return url;
        }
        Object global = context.config().get(BASE_URL);
        return global instanceof String url && !url.isBlank() ? url : null;
    }
}
