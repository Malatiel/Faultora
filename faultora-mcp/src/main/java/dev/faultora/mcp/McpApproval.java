package dev.faultora.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

/** An out-of-band operator signature, bound to one session preparation and run. */
public final class McpApproval {
    private static final String DOMAIN = "faultora.mcp.approval.v1\n";

    private McpApproval() {
    }

    static PublicKey publicKey(String encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }

    /** Read only an operator-supplied PKCS#8 DER key; it never enters the MCP session. */
    public static String sign(Path key, String preparedId, String digest, String runId, Duration lifetime)
            throws IOException, GeneralSecurityException {
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Approval lifetime must be between one and 300 seconds");
        }
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
                new PKCS8EncodedKeySpec(McpConfiguration.readFile(key, 4096)));
        return sign(privateKey, preparedId, digest, runId, Instant.now().plus(lifetime));
    }

    static String sign(PrivateKey key, String preparedId, String digest, String runId, Instant expires)
            throws IOException, GeneralSecurityException {
        validateIdentity(preparedId, digest, runId);
        var payload = McpJson.MAPPER.createObjectNode().put("preparedId", preparedId)
                .put("preparationDigest", digest).put("runId", runId).put("expiresAt", expires.getEpochSecond());
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                McpJson.MAPPER.writeValueAsBytes(payload));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update((DOMAIN + encoded).getBytes(StandardCharsets.US_ASCII));
        return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }

    static boolean verify(String token, PublicKey key, String preparedId, String digest, String runId, Instant now) {
        try {
            if (token.length() > 2048) {
                return false;
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) {
                return false;
            }
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update((DOMAIN + parts[0]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[1]))) {
                return false;
            }
            var payload = McpJson.MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
            McpConfiguration.fields(payload, Set.of("preparedId", "preparationDigest", "runId", "expiresAt"));
            var expiry = payload.path("expiresAt");
            return preparedId.equals(McpConfiguration.text(payload, "preparedId"))
                    && digest.equals(McpConfiguration.text(payload, "preparationDigest"))
                    && runId.equals(McpConfiguration.text(payload, "runId"))
                    && expiry.isIntegralNumber() && expiry.canConvertToLong()
                    && expiry.longValue() > now.getEpochSecond()
                    && expiry.longValue() <= now.plusSeconds(300).getEpochSecond();
        } catch (IOException | GeneralSecurityException | IllegalArgumentException invalid) {
            return false;
        }
    }

    private static void validateIdentity(String preparedId, String digest, String runId) {
        if (!preparedId.matches("[0-9a-f-]{36}") || !digest.matches("sha256:[0-9a-f]{64}")
                || !runId.matches("run-[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("Approval identity is invalid");
        }
    }
}
