# Runner protocol v1

The transport version is the string `1`, independent of scenario YAML
(`faultora.dev/v1` or its legacy alias) and of the Faultora build version.
Dispatchers choose the first version in the runner's ordered preferences that
they support. No common version returns `UNSUPPORTED_PROTOCOL_VERSION` before
dispatch; unknown positive integer major versions can coexist with version 1.

Registration requires a nonempty runner ID (up to 128 ASCII identifier characters),
a nonblank agent version (up to 128 characters), and 1–16 distinct positive
integer protocol strings. Capabilities are optional and bounded to 256 names of
up to 128 ASCII identifier characters. Missing, duplicate, malformed or oversized
input returns `MALFORMED_REGISTRATION`; diagnostics do not echo invalid values.
The JSON boundary accepts additive fields, rejects trailing JSON and duplicate
properties, and limits registration to 64 KiB. The qualification dispatcher
uses this same decoder over the actual TLS transport.

Version 1 messages retain the fields of Registration, Session, Dispatch, Lease,
SignedPolicy, DispatchedDocument and Progress. Round-trip and golden registration
tests guard these shapes. New optional fields are additive; mandatory fields,
type or meaning changes, and incompatible enum changes require a new negotiated
major. The malformed-registration reason is introduced before this boundary is
frozen and is a closed-enum change for older strict readers.

Rejected alternatives: deriving transport version from YAML couples independent
consumers; silently coercing malformed fields delays failures until dispatch;
choosing the numerically highest version discards the runner's deployment-tested
preference. Protocol 1 preserves runner preference and returns explicit refusals.
