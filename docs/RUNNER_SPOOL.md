# Local runner spool

Each admitted execution owns a `spool.bin` beside its ordinary `events.ndjson`.
The spool is local evidence for [ADR-031](adr/ADR-031-runner-recovery-ownership.md),
not a new runner transport or a claim of durable remote delivery.

Before the engine starts, the runner commits a version-one identity: persistent
local runner UUID, fresh attempt UUID, logical run ID, dispatch fingerprint and
original local lease deadline. The fingerprint covers the complete dispatch
(including inputs, redirects, seed, policy and credential **names**) and the
prepared execution digest (including locally narrowed policies). Only this
digest is retained, not those inputs, names or credentials. The owner UUID in
`runner-identity` is reused; a partial owner file refuses admission.

Every journal event is first appended to the spool and forced with
`FileChannel.force(true)`. Only after that succeeds can it enter the compatible
NDJSON mirror and be offered by the live sender. Engine and connector resource
release precede an immutable terminal record containing status, final event
position, lease expiry, cleanup confirmation and a fixed infrastructure reason.
An ERROR never claims confirmed cleanup. Terminal publication requires both a
committed terminal and successful writer close. A disk failure that prevents
this leaves the dispatcher without a terminal; missing delivery is unknown,
never evidence of PASS or confirmed cleanup. Admission refusals remain ordinary
protocol control replies because no execution began.

An exception from execution, journal startup or resource release still produces
the existing `broke` diagnosis when the spool can commit an ERROR terminal with
unconfirmed cleanup. Failure to read/send the NDJSON mirror does not suppress
that committed diagnosis. If the spool itself cannot commit the terminal, no
terminal notification is sent; local failure and retained evidence remain.

An interrupt already observed on entry to terminal finalization is deferred
through commit and spool close, then restored on the caller thread. This does
not resume target work or discard cancellation. A new interrupt during channel
I/O can still close the channel: that storage failure is not retried and cannot
produce a confirmed terminal. This deferral also cannot repair a spool already
broken by earlier interrupted journal I/O. Live network delivery can itself be cancelled.
The runner's SIGTERM hook stops admission and waits for its shutdown grace;
it does not interrupt the execution thread. Lease expiry uses the existing
cancellation flag.

Only ordinary `Exception` failures enter the ERROR-commit path. `Error`, such
as a linkage or VM failure, propagates; resource release is best effort and no
terminal commit or notification is promised. A retained nonterminal spool is
`RECOVERY_REQUIRED`, with unconfirmed effects and cleanup. Inspection must not
invent a verdict or resume execution, even when the process failed before its
first target operation.

## Commit and verification

The file begins with `FTSP001\n`. Records contain a 32-bit payload byte length,
one-byte kind (identity/event/terminal), 64-bit sequence, UTF-8 JSON payload,
32-byte SHA-256 chain and a four-byte `CMIT` certificate. Integers are big-endian.
The chain hashes the previous digest, kind, sequence and payload. Payload and
checksum are forced **before** the certificate is written; the certificate is
then forced before append returns. Failure before the certificate leaves an
uncommitted tail even if all payload bytes reached the file. Failure during the
second force can leave a valid but unacknowledged commit; its payload was already
forced and it may safely be present in a later inspection.

One writer owns a newly created file and an exclusive OS file lock. Read-only
`DurableRunSpool.recover` refuses an active writer, validates the entire file
before exposing events, and returns the verified prefix, terminal (if present)
and whether a truncated tail remains. It neither truncates nor repairs files.
Bad lengths, schema, UTF-8, sequence, ownership or checksums refuse the spool;
operators should retain it for diagnosis. A verified nonterminal attempt is
`RECOVERY_REQUIRED`: inspection never resumes a scenario, reissues a connector
operation or asserts target recovery. Existing run ownership also prevents a
new process from executing the same dispatch ID over retained artifacts.

## Bounds and remaining delivery work

Per attempt the current hard limits are 64 MiB, 100,000 events and 1 MiB per
JSON record. Eight KiB is withheld from the event quota for the terminal record.
This is a logical reservation, not a guarantee of physical free disk space.
Quota or append failure cancels further business work through the existing
cancellation path while preserving cleanup and rollback obligations. A full
disk may also prevent recording that failure; no unacknowledged file is deleted
to manufacture space. Local directories must be operator-owned and writable
only by the runner/deployment; path checks and checksums are not a boundary
against a concurrent writer with the same filesystem authority.

This slice qualifies process-crash persistence with real killed JVMs. It does
not qualify power-loss durability of directory entries, filesystem or device
caches on every platform. Do not infer such a mode from `force(true)`.

Runner protocol v1 remains best effort: numeric progress replies and an empty
result reply do not prove remote persistence. There is no persisted ACK cursor,
startup delivery scanner, replay sender, receiver fencing, automatic recovery
or retention deletion. Aggregate pending-attempt quotas, admission management
and retention require the later durable-delivery slices. Retain the working
directory across process changes and size/monitor its volume accordingly;
unacknowledged evidence currently requires operator management. Restart never
extends a lease or authorizes repeating target actions.
