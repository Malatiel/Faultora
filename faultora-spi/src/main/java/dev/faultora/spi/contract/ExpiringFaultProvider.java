package dev.faultora.spi.contract;

import dev.faultora.spi.context.FaultContext;

/**
 * A provider whose external system enforces the supplied hard deadline
 * independently of the faulting process. It binds the deadline to
 * {@link FaultContext#ACTIVATION_ID} before applying the fault.
 */
public interface ExpiringFaultProvider extends FaultProvider {
    /**
     * Query the external system without mutating it. Return true only when it
     * confirms expiry and restoration for the exact activation and deadline.
     * Unknown activations and unverified outcomes remain unconfirmed.
     */
    boolean confirmsExpiry(String activationId, long hardExpiryMs);
}
