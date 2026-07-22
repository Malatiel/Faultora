# ADR-007: Secret value lifecycle

## Status

Accepted

## Context

Faultora handles API tokens, client certificates, and database credentials.
These values must never be serialized into plans, manifests, logs, reports, or
diagnostic bundles. The model must represent secrets opaquely.

## Decision

- Secrets are represented by `SecretHandle` records that contain only a
  handle ID, redacted representation, source type, and expiry.
- `SecretResolver` returns `SecretHandle`, never raw `String` values.
- Secret values cannot be serialized through `toString()`, Jackson, or
  exception messages.
- Redaction is defense in depth; the primary control is preventing secret
  values from entering observability data.
- Initial resolution sources: environment variables and mounted files.
  Future sources: Kubernetes secrets, HashiCorp Vault.

## Rejected alternatives

- **String + annotation**: Error-prone. Developers must remember to annotate
  every secret field.
- **char[] instead of String**: Provides marginal memory benefit but doesn't
  solve serialization.
- **Encrypted values in model**: Adds encryption dependency to the model
  module. Opaque handles are simpler.

## Consequences

- The model module has a `SecretHandle` type that is safe to serialize.
- Extensions that need secrets receive them through a scoped resolver, not
  as constructor parameters.
- The `SecretHandle.toString()` method shows only the redacted form.
- Architecture tests must verify that no secret-bearing field is serialized
  into evidence or reports.
