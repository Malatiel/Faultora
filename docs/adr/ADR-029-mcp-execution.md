# ADR-029: Exact operator approval and session-owned MCP runs

Status: Accepted

## Context

ADR-028 supplies an inert preparation and digest. A client controlling scenario
text must not gain authority by returning that digest or setting confirmed=true.
Execution must also outlive a short RPC reply without outliving its owning session.

## Decision

An optional operator configuration supplies an Ed25519 verification key, existing
artifact root and private-network permission. A separate CLI approval command
uses the operator's private key. Its domain-separated signature binds the exact
prepared ID, preparation digest, run ID and bounded expiry. Verification and
PreparedRun.requireCurrent happen before allocating any run artifacts or clients.
PreparedRun executes its captured plan once through the existing RunEnvironment.
No tool accepts credentials, policy changes, output paths or catalog filenames.

One active worker and 32 retained runs bound each stdio session. Admission beyond
the retained-record limit evicts the oldest completed in-memory record; it never
removes operator artifacts or an active run. Evicted IDs become unknown rather
than restoring access from files. This is FIFO retention, not a lifetime run cap.
Run admission is
separate from completion; cancel_run sets the runtime's cooperative cancellation
flag. Interrupting the worker could instead abort an in-flight cleanup request.
An active connector attempt may finish within its configured timeout before
cancellation is observed. Terminal publication follows runtime cleanup/rollback, environment release
and journal/report finalization. EOF, transport failure and normal shutdown
request cancellation and join the worker under one absolute lifecycle limit.

An independent deadline guard starts at admission. The limit is effective run
timeout plus LocalEngine's cleanup budget plus 5000ms grace. The final portion
of grace (at most 1000ms) is reserved for marker I/O. On expiry the guard attempts
to create an owned termination.json with cleanupConfirmed=false and fixed reason
SHUTDOWN_DEADLINE_EXCEEDED, then calls Runtime.halt(3). Marker I/O is bounded
independently: an I/O failure cannot turn shutdown back into an unbounded wait.
A late callback completion cannot revoke a forced-termination decision, and the
marker overrides any late report. Successful completion disarms the guard.

ADR-024 places the final fault rollback sweep outside declared cleanup's
30000ms budget. The MCP limit above does not add a separate rollback allowance.
It relies on a closed fault profile: only LocalFaultProvider is instantiated,
and configured fault types must be supported by that in-process provider.
These faults intercept this JVM's outbound requests; their state disappears
with the JVM. Tests pin both the execution provider composition and refusal of
external fault types in startup configuration. Adding Toxiproxy or any other
provider with effects outside this process requires revisiting the lifecycle
and rollback budget before enabling it. Target-side data effects and declared
cleanup remain unconfirmed after forced exit even with local faults.

This deliberately sacrifices the whole MCP process when a callback will not
return. It cannot prove target-side cleanup or rollback. A timed join alone
would leave a live non-daemon worker; changing it to daemon would merely hide
that ownership loss. Supervised child execution is deferred to the common
lifecycle work with ADR-023's process boundary, rather than adding a second
termination mechanism now.

Exit 3 also represents ordinary CLI COMMAND_FAILED and MCP transport failure.
It does not uniquely identify forced termination. A valid owned termination.json
with the matching run ID and SHUTDOWN_DEADLINE_EXCEEDED identifies that decision;
if marker creation failed, the exit code alone cannot distinguish the causes or
confirm cleanup. Clients must not infer orderly completion from an absent marker.

Report availability is separate from execution outcome. If writing the optional
structural report fails after execution and mandatory journal finalization,
get_run retains the actual status and cleanup confirmation, reports
reportStatus=UNAVAILABLE with REPORT_WRITE_FAILED, and advertises no artifact.
This does not downgrade a mandatory journal failure or expose exception details.

Only an owned structural report is exportable by the fixed artifact ID report.
Its byte limit, regular-file/no-redirection checks, owner marker and captured
digest prevent altered local files from becoming an export channel. The journal
stays local. This is a deliberately smaller export surface than CLI reporting.
Evidence for assertions uses RunEvidence.defaultPolicy, keeping CLI semantics
without promoting body/header values into reports. Secret resolution is unavailable
in this profile. Existing preparation-only configurations still expose three tools.

## Alternatives

An unverified confirmation flag grants the requester its own authority. A random
capability stored in the same tool result has the same problem. A shared signing
secret in the MCP process enlarges credential exposure; public-key verification
keeps the private operator key out of the session. Reusing runner signed dispatch
would couple a local adapter to lease/dispatcher infrastructure without a need.
The JDK already supplies Ed25519, so no crypto dependency is added.

Synchronous start_run would block status/cancellation on the sequential transport.
An unbounded executor or detached background process would lose ownership and
resource limits. Exporting arbitrary paths or the raw journal would enlarge the
data boundary; the structural report suffices for outcome and cleanup inspection.

## Limits and evidence

An agent with unrestricted shell access under the key owner's OS account can
read the key and call mcp approve itself. In that configuration, the signature
does not establish independent operator approval. A hidden filename, another
directory or file permissions granting that same principal access do not change
this limitation.

Use a separate unprivileged OS identity for the agent and an independent operator
identity or device for signing. Protect the verification configuration, runtime,
classpath and MCP process against modification or process access by the agent;
moving only the private key is insufficient if verification can be replaced.
The operator must review the actual scenario, catalog, digest and run identity
before returning the signed grant through the client. This deployment separation
is an operator responsibility; the CLI does not create OS accounts or a sandbox.

A hardware-backed signer could keep the key non-exportable, but the current
mcp approve command accepts only a PKCS#8 DER private-key file. It does not
implement a hardware-token, PKCS#11 or remote-signing integration. Such a signer
would need to produce the same domain-separated Ed25519 approval payload and
provide independent operator authorization; possession of a token alone does
not prove that its owner reviewed this run.

Untrusted writers must also be excluded from artifacts. This is not a sandbox
against the same local OS principal or an uncatchable kill. Artifact checks do
not claim to close concurrent malicious filesystem races. Stdio does not establish
the locality of a client's model; offline use needs a local model and network policy.

Tests cover forged, wrong-key, expired and differently bound approval; scope
widening; replay and active admission; session ownership; cancel/close cleanup and
rollback; path, owner, size, regular-file and junction export refusal. Packaged
stdio approval/execution and saved-YAML CLI replay compare PASS, FAIL and
INDETERMINATE. Cancelled MCP is compared to an interrupted in-JVM CLI; this does
not certify Windows process-signal delivery or unrelated desktop clients.
