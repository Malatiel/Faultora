# Extension wire protocol 1.0

## Status and scope

This is the normative data contract for the process-boundary slice of
[ADR-023](adr/ADR-023-extension-isolation.md). The fixtures, a **test-only**
conformance probe and the bounded `faultora-extension-protocol` codec exist.
The child lifecycle is implemented as a separate library; provider adapters
and policy enforcement remain pending. Requesting process isolation still
fails closed. This document does not claim an OS sandbox, a heap ceiling,
network enforcement or provider-side recovery merely because a child stops.

Only `AssertionProvider`, `SourceImporter` and `ReportRenderer` cross this
boundary. Connectors, fault providers and secret resolvers do not. There is no
callback to the parent, class name, serialized Java object, live evidence
handle, filesystem output path or credential-resolution operation on the wire.
The child's declaration never grants a capability or proves artifact identity.

## Framing and limits

One frame is an unsigned four-byte **big-endian** payload length followed by
exactly that many bytes of UTF-8 JSON. The length excludes the prefix. There
is no newline delimiter, BOM, compression, multipart message or chunking.
Consecutive frames need no separator. Reads may split anywhere, including
inside the prefix or a multibyte character; a read is not a frame boundary.

The absolute payload ceiling is **1048576 bytes (1 MiB), inclusive**. Read the
four-byte prefix before allocating or reading the payload. Length zero is
`MALFORMED_FRAME`; a larger length, including 0xffffffff, is
`MESSAGE_TOO_LARGE`. Do not drain it, allocate the advertised size, truncate
it or search for another frame. Close the channel after any refusal.

The handshake uses the absolute ceiling. The parent offers a ceiling between
1024 and 1048576 bytes, and the child selects one no larger than the offer.
After `ready`, that selected ceiling applies in both directions, including
control messages and errors. A document or report that cannot fit fails;
neither silent truncation nor a second stream is permitted in 1.0. JSON
escaping and base64 expansion count toward the actual encoded byte length.

JSON must contain one object and no trailing non-whitespace tokens. Reject
invalid UTF-8, duplicate keys at any depth, non-finite numbers and invalid JSON.
Maximum nesting is 64 objects/arrays and maximum numeric token length is 64
characters. All envelope and structural payload fields listed below are
required; unknown fields are refused. Explicit application maps (`params`,
`config`, schemas, metadata and assertion details) may contain plain JSON data.
Never enable Java polymorphic/default typing when reading them.

EOF before any prefix byte is a channel close, not a successful response. One
to three prefix bytes, or EOF inside a payload, is `TRUNCATED_FRAME`. Only EOF
after an acknowledged shutdown is an ordinary completed session. Unsolicited
stdout, logging text, wrong lengths and malformed JSON poison the session;
there is no resynchronization. Stderr is not another protocol: the supervisor
must drain it with bounded retention and sanitize any exported diagnostics.

## Envelope, versions and correlation

Every message has exactly these six fields:

| Field | Meaning |
|---|---|
| `protocol` | Literal `faultora.extension`, distinct from the runner/MCP protocols |
| `kind` | `hello`, `ready`, `request`, `result`, `error`, `shutdown` or `stopped` |
| `version` | Null for `hello`; the explicitly selected version afterwards |
| `sessionId` | Parent-generated 128-bit random nonce, 32 lowercase hex digits |
| `id` | Decimal string, to avoid JSON integer precision loss |
| `data` | Kind-specific object below |

The bootstrap is the bounded JSON envelope with `hello.version = null`.
`hello.data.versions` is a nonempty, duplicate-free list of at most eight
version strings. The only version specified here is `1.0`; select an exact
common version, never assume that an unknown major or minor is compatible.
The child replies with `ready.version = "1.0"` only if offered. No common
version is `INCOMPATIBLE_VERSION`. A future minor must be explicitly offered
and selected before its fields are used; current readers reject extra fields.
Wire version, plugin version and Faultora's manifest API range are independent.

`hello` and `ready` use ID `"0"`. Calls and shutdown use `"1"`, `"2"`, etc.,
without gaps or reuse, up to 9223372036854775807. No signs, leading zeros or
numeric JSON IDs. Retire the child before the counter overflows. Replies echo
the pending ID and session nonce; delayed, duplicate, wrong-session and
out-of-order replies cannot complete another call. The same provider selector
must appear in a request and its result. There is exactly **one pending call**;
there is no queue, batching, server notification or concurrent dispatch in 1.0.

## Handshake and lifecycle

1. Before starting a child, the parent checks approved artifact identity and
   manifest/API compatibility. The later lifecycle implementation must stage a
   private artifact copy, hash that copy and execute only those bytes. A child
   echoing identity is not evidence that this happened.
2. Parent sends `hello`: `versions`, `maxFrameBytes`, `plugin` and `providers`.
   `plugin` has `id` and `version` strings. A provider selector has `kind`
   (`assertion`, `importer`, `renderer`) and `name` (type/source type/format).
   Names use `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`. Plugin version is a nonempty
   opaque string of at most 128 characters. At most 32 unique selectors are
   offered, already narrowed by the parent policy and approved manifest.
3. Child sends `ready`: `maxFrameBytes`, `plugin`, `providers`. Its plugin
   declaration must match; the selector **set** must equal the offer (order
   does not matter). Missing, additional or unsupported providers are refused.
   No request is sent or provider invoked before this acknowledgement.
4. Parent sends a `request`, child sends exactly one matching `result` or
   terminal `error`. A successful result returns the session to idle.
5. When idle, parent sends `shutdown` with `data.timeoutMs`; child returns
   `stopped` with empty `data`, then closes streams and exits. Shutdown while
   a call is pending is invalid. Cancellation while busy closes the channel;
   the future supervisor owns interruption, bounded grace and forced child
   termination. The caller must not wait forever for a protocol response.

The handshake budget is at most 5000 ms and is shortened by the parent's
remaining phase deadline. `request.data` contains `provider`, `timeoutMs` and
`input`. `timeoutMs` is an integer in 1..300000, narrowed to the remaining host
budget before transmission. Shutdown has the same timeout range. The parent
starts its **monotonic** deadline before writing the frame; writes, queueing,
reads and validation all consume it. A complete matching result arriving at
or after the deadline is `DEADLINE_EXCEEDED`, even if it says PASS. No partial
response, retry or new frame resets the deadline.

The child may use the relative timeout for cooperative cancellation from
receipt; no JVM monotonic-clock origin or wall-clock timestamp crosses the
wire. The parent deadline is authoritative. A timeout poisons the session;
late results are discarded and the child is not reused. No automatic retry
is implied, even for a pure provider. Actual timeout/exit/stream cleanup belongs
to the lifecycle slice, not to this specification's test oracle.

## Data-only calls

| Provider | Request `input` | Result `value` |
|---|---|---|
| `assertion` | `params` object, `evidence` EvidenceSnapshot, `context` AssertionContext | AssertionResult: `outcome`, `message`, `details` |
| `importer` | `sourceContent` string, data-only `context` | ImportResult: `catalog`, `errors`, `warnings`, `metadata` |
| `renderer` | `events` array of RunEvent | `text` string, the Writer's content |

`result.data` has exactly `provider` and `value`. Result type is chosen by the
pending request, never by a child-supplied Java type. The existing model's
JSON property names and enums are the payload definitions; fixtures pin their
1.0 representation. Encode the listed record components, not convenience
getters such as ImportResult's computed `success` property. Incompatible DTO
changes need a negotiated wire version.

For assertions, all EvidenceSnapshot fields are explicit: `statusCode`
(integer or null), `responseHeaders`, `responseBody` (base64 or null),
`bodyComplete` boolean, `durationMs`, `error`, `protocolEvidence`. Missing
`bodyComplete` must not become a default success. AssertionContext has `nodeId`,
`config`, `schema` (object or null). The parent sends only evidence admitted by
its policy; absent/withheld evidence stays absent. Body bytes are the source
of truth, not a second independently supplied parsed JSON tree. Snapshot
header readability keeps the existing conservative `asView()` semantics:
an omitted header cannot establish its absence. Verdict is
exactly `PASS`, `FAIL` or `INDETERMINATE`; an invalid/absent verdict or transport
failure is never PASS. Raw provider prose/details are untrusted in-memory
data: apply the existing EvidenceExport projection before persistence/export.

An import context contains `sourceType`, `maxRefDepth`, `maxDocSizeBytes` and
`config`; `maxRefDepth` is an integer in 0..64. It carries neither the parent's
workspaceRoot nor allowed domains, sockets or a resolver. The 1.0 profile
accepts self-contained documents only;
the importer adapter must refuse external references, rather than fetching
files or URLs implicitly. Host-approved reference preparation, if needed,
belongs to the later importer adapter and must preserve this data-only profile.
The bootstrap constructs a private local context with no external-reference
domains. `maxDocSizeBytes` cannot exceed the negotiated frame ceiling. It
limits the UTF-8 bytes of `sourceContent` after parsing its JSON string, not
the escaped JSON representation. A successful ImportResult has a non-null
catalog and no errors; an ordinary import failure has a null catalog and at
least one normalized error. Catalog content remains subject to parent-side
semantic validation, destination policy and operation admission.

Renderers receive only projected/exportable RunEvents. They return text, not
a filename, Writer, artifact handle or a replacement run verdict. The parent
owns the destination and enforces output policy. A renderer cannot change the
engine outcome. Large inputs/outputs fail the frame limit; 1.0 does not stream.

## Refusals and error ownership

`error.data` contains only `code`, from the table. There is no plugin exception
class, stack trace, raw message or reflected frame in a protocol error.
After an error, stop admitting messages and close the session. For malformed
input send at most one bounded error only when the reply channel and known
correlation are usable; otherwise close without trying to respond. In an error
before negotiation, `version` may be null; `sessionId`/`id` may be null when
they could not be established safely. A correlated error is still failure,
never a business verdict. Errors from a child are untrusted and projected to
fixed host diagnostics. Refusal is not proof that the process was terminated.

| Code | Cause |
|---|---|
| `INCOMPATIBLE_VERSION` | No common version or message uses another selected version |
| `IDENTITY_MISMATCH` | Ready declaration differs from the expected plugin |
| `UNSUPPORTED_PROVIDER` | Selector kind/name is not among the approved three-family set |
| `LIMIT_MISMATCH` | Child selects a ceiling larger than offered |
| `SESSION_MISMATCH` | Reply nonce differs |
| `ID_MISMATCH` | Wrong, reused or out-of-order ID/provider correlation |
| `INVALID_STATE` | Wrong direction, request before ready, overlap or unexpected message |
| `MALFORMED_FRAME` | Zero length |
| `TRUNCATED_FRAME` | Partial prefix/payload |
| `MESSAGE_TOO_LARGE` | Frame or source exceeds its byte limit |
| `MALFORMED_MESSAGE` | Invalid UTF-8/JSON, duplicate key, type, field or payload shape |
| `DEADLINE_EXCEEDED` | Parent's remaining budget was consumed |
| `PROVIDER_ERROR` | Callback threw or could not produce a valid result |
| `PEER_CLOSED` | Channel closed before the expected terminal response |

## Compatibility evidence and remaining work

The source checkout's `faultora-spi/src/test/resources/extension-wire-v1/`
fixtures cover handshake, three call families, all assertion outcomes, refusal
and shutdown.
Framing vectors include literal bytes; the SPI tests check DTO round trips,
fragmented/concatenated frames, exact/overflow byte limits, malformed UTF-8/JSON,
version/provider/correlation/state errors and virtual-time late responses.
The test-only probe is an executable interpretation of this specification;
it is deliberately not a production codec and is not loaded by the runtime.

The bounded codec validates framing and envelope fields, including reply
correlation, in a module that depends only on Jackson. Its streams are owned
by the caller, which must discard the channel after a refusal and enforce I/O
deadlines. Handshake state, provider payloads, process start/stop and policy
enforcement are not implemented by the codec.

The next slices are the three adapters, resource/network/secret enforcement and packaged plugin
qualification. Existing ADR-023 policy refusals remain until those controls
are implemented. Do not infer isolation from passing these conformance tests.

## Child lifecycle implementation

`faultora-extension-process` starts an admitted protocol peer using an
absolute Java launcher and an argument list, without a shell. The caller
supplies an approved artifact digest and declaration and an operator-owned
runtime classpath. Manifest/API/capability admission and typed provider
adapters remain separate responsibilities; an arbitrary class on a classpath
does not acquire provider authority through this API.

`StagedArtifact.copy` creates an owner-only run directory, rejects link/reparse
paths, bounds the copy at 128 MiB and computes its SHA-256 after writing and
forcing it. The source path is never used by the child. The trusted bootstrap
then reads that copy into bounded memory and checks the expected digest before
loading any plugin class. Classes and resources are resolved from those same
verified JAR bytes, not by reopening the file; manifest Class-Path references
are not followed. Expanded entries are bounded at 8 MiB each, 128 MiB total and
4096 entries. Replacing the source after staging has no effect; replacing the
copy before launch refuses before plugin initialization.

The parent validates ready identity, selector set, exact version, nonce and
negotiated size. Requests are sequential and correlated; malformed/oversized,
late or foreign replies poison the peer. Shutdown requires both `stopped` and
actual process exit. Closing a busy peer cancels its channel and terminates
the child. The caller owns and closes both the peer and staged artifact;
cleanup removes only the two names staging created, without recursive deletion.

`faultora-process` supplies the shared monotonic lifecycle supervisor. Request
I/O consumes a bounded budget, and an independent process lifecycle prevents
an abandoned peer from living forever. Stderr is drained, discarded and capped
at 8192 bytes; it is never copied into a failure report. Deadline, crash,
blocked writes, ignored shutdown and stderr overflow terminate the child and
check its exit. `directProcessExitConfirmed` on this low-level peer confirms
only the direct JVM represented by its `Process`, not a process tree or
target-side effects, cleanup or rollback. Termination attempts include a
best-effort snapshot of visible descendants, but a plugin can spawn a process
and exit before that snapshot; an orphan can survive `stop` and `close`.
This pure-JDK lifecycle provides no process-group, job-object or cgroup
containment. A caller must not use direct-child exit as cleanup confirmation;
the isolation policy refusals remain until the required controls exist.

MCP uses that same deadline core through its ownership-marker/current-process
halt adapter. MCP execution still runs in the MCP process; this slice does
not introduce an MCP child transport or recompile an approved run elsewhere.
No second independent shutdown timer was added. Process permission to write
the trusted runtime or tamper with another operator's memory remains outside
this boundary; there is still no complete OS isolation guarantee.
