# Runner protocol

## Version 1

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

The runner sends complete UTF-8 JSON object lines from the journal. A numeric
progress ACK advances only through the acknowledged lines. An unfinished or
malformed line blocks delivery from that point and leaves the valid prefix
available. A successful run is reported as `PASSED` only after the complete
journal has been acknowledged. If the mirror is incomplete or delivery stalls,
the runner reports `broke` with the reason `journal delivery incomplete`.
Version 1 keeps the cursor in memory during the run.

Rejected alternatives: deriving transport version from YAML couples independent
consumers; silently coercing malformed fields delays failures until dispatch;
choosing the numerically highest version discards the runner's deployment-tested
preference. Protocol 1 preserves runner preference and returns explicit refusals.

## Version 2 durable delivery

`faultora runner --durable-delivery` requests transport version `2` during
registration and requires the dispatcher to select it before accepting work.
The default runner continues to request version `1`. Version 2 uses the same
work and lease endpoints. The two delivery endpoints below require a version 2
session. The qualification dispatcher implements the receiver in a separate,
version-2-only fixture mode.

The attempt object carries the persistent spool `runnerId` UUID, the logical
`runId`, the execution `attemptId` UUID and the `dispatchDigest` fingerprint.
The spool UUID and the runner's registration name identify different things.
The qualification receiver records dispatch ownership before handing out work.
It fences the run ID from that point, including when no attempt has delivered
its first record. It binds delivery to the registered runner name and the
fingerprint of its authenticated TLS leaf certificate. Replaying on the fixture
requires the original certificate.

| POST endpoint | Request fields | Successful response |
|---|---|---|
| `/runner/v2/progress?session=…` | `attempt`, `fromPosition`, `eventLines` | `attempt`, exclusive `nextPosition`, `terminalDigest: null` |
| `/runner/v2/result?session=…` | `attempt`, `finalPosition`, `status`, `cleanupConfirmed`, `leaseExpired`, `reasonCode`, `terminalDigest` | `attempt`, `nextPosition` equal to `finalPosition`, matching `terminalDigest` |

Positions count committed records starting at zero and stop at 100,000.
Progress batches carry up to 64 records and at most 1 MiB of combined
UTF-8 event-line bytes, excluding line separators. Encoded requests are
limited to 3 MiB and ACKs to 4 KiB.
The terminal digest is SHA-256 of the UTF-8 text formed by joining the status,
decimal final position, cleanup flag, lease-expiry flag and reason code with
LF characters and no trailing LF. The digest uses the `sha256:` prefix.

The receiver compares overlapping records, persists the accepted prefix before
acknowledging it and accepts a terminal only after the full prefix is present.
Replaying the same terminal returns its ACK. A conflicting record, conflicting
terminal or gap is refused.

The sender delivers from the closed, verified spool. It records the
acknowledged position and terminal digest in `delivery-ack.json`, bound to
the attempt and original route. On reconnect or restart it resumes delivery
from the checkpoint. An attempt without a committed terminal stays available
for recovery inspection. Replay submits records and results only, with no
scenario execution or lease extension.

The sender verifies the spool sequentially under a shared lock before sending
any records. It then reads one batch at a time from the same file. A progress
ACK must identify the same attempt, have no terminal digest and advance within
the submitted batch. When it confirms only part of the batch, the sender saves
that position and retries from the first unacknowledged record. It loads the
next batch only after the current one is fully acknowledged. A terminal ACK
must match the attempt, final position and terminal digest exactly.
The batch limits apply to the sender. The qualification receiver keeps a
bounded per-attempt state file for its restart checks.

A checkpoint that cannot be verified against the spool or its configured
route blocks recovery and new dispatches. The CLI reports
`DURABLE_DELIVERY_STORAGE_UNAVAILABLE` and asks the operator to reconcile
the work directory before restarting.

A refusal before execution stays local to the runner. The version 2 fixture
retains the issued ID for reconciliation and accepts only a spool-backed
terminal on the durable result endpoint.

The checkpoint and qualification receiver use forced file writes and atomic
replacement for process restart. Power-loss durability and retention
automation require separate qualification.
