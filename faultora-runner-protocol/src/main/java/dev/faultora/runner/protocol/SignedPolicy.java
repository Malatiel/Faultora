package dev.faultora.runner.protocol;

/**
 * The policy a run is permitted under, and the signature over it.
 * <p>
 * Signed rather than merely authenticated, because authentication says who is
 * speaking and a signature says what they said. In a private network something
 * terminating TLS in the middle is a proxy somebody operates, and a policy that
 * arrived through it should still be the policy that was issued.
 * <p>
 * What a runner does with this is the other half of the rule: it verifies the
 * signature, refuses an unsigned or unverifiable one, and then <b>narrows</b>
 * its own configured limits by it. A signed policy can never widen what a
 * deployment permits — that is what makes refusal independent of whoever is
 * dispatching. ADR-021 records it.
 *
 * @param policyJson the effective policy, exactly as the bytes that were signed
 * @param keyId      which verifying key this was signed with, so rotation can
 *                   overlap rather than requiring a synchronized swap
 * @param signature  the signature over {@code policyJson}, base64
 */
public record SignedPolicy(String policyJson, String keyId, String signature) {
}
