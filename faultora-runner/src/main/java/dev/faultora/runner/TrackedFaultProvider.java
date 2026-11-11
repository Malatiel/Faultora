package dev.faultora.runner;

import dev.faultora.model.security.ContentDigest;
import dev.faultora.spi.context.FaultContext;
import dev.faultora.spi.contract.FaultProvider;
import dev.faultora.spi.result.ActiveFault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Persists the obligation before delegating the side effect. */
record TrackedFaultProvider(FaultProvider delegate, FaultObligations obligations, String providerKeyDigest)
        implements FaultProvider {

    static Map<String, FaultProvider> wrap(Map<String, FaultProvider> providers, FaultObligations obligations) {
        var tracked = new LinkedHashMap<String, FaultProvider>();
        // The runtime uses the reserved local provider directly in its HTTP connector.
        providers.forEach((key, provider) -> tracked.put(key, "local".equals(key)
                ? provider : new TrackedFaultProvider(provider, obligations, ContentDigest.sha256Uri(key))));
        return tracked;
    }

    @Override public Set<String> capabilities() { return delegate.capabilities(); }

    @Override
    public ActiveFault inject(String faultType, Map<String, Object> params, FaultContext context) {
        String activationId = activationId(context);
        try {
            obligations.record(new FaultObligations.Entry(activationId, providerKeyDigest, context.hardExpiryMs()));
        } catch (IOException unavailable) {
            throw new UncheckedIOException("FAULT_OBLIGATION_UNAVAILABLE", unavailable);
        }
        return delegate.inject(faultType, params, context);
    }

    @Override
    public void rollback(ActiveFault fault, FaultContext context) {
        delegate.rollback(fault, context);
        try {
            obligations.rolledBack(activationId(context));
        } catch (IOException unavailable) {
            throw new UncheckedIOException("FAULT_OBLIGATION_UNAVAILABLE", unavailable);
        }
    }

    private static String activationId(FaultContext context) {
        return (String) context.config().get(FaultContext.ACTIVATION_ID);
    }
}
