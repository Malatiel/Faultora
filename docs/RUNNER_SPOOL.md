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

## Operating the work directory

Give each runner its own persistent, operator-controlled `--work-dir`. Keep
`runner-identity`, `dispatch-replay/` and every `run-<digest>` directory together.
A run directory can contain `run-owner`, `spool.bin`, `events.ndjson` and reports. These files
preserve evidence and prevent the same dispatch ID from claiming the
directory again. `health.json` defaults to this volume and describes the
current process. Its presence alone says nothing about readiness.

`dispatch-replay/` holds the bounded ledger for run IDs and nonces. The runner
forces each new ledger snapshot before atomically replacing the previous one.
Keep the ledger across restarts, and pause dispatch if the runner refuses work
because the ledger is damaged or inaccessible. Reconcile the dispatcher and
the work directory before restoring ledger data from backup.

### Fault obligations after a crash

Before invoking an external fault provider, the runner forces an activation record under
`fault-obligations/` in the run directory. The record binds an opaque activation
ID and hard deadline to the spool attempt and a hash of the configured provider
key. Provider parameters and endpoints stay out of this record. Successful
rollback adds a completion record. Keep both records with the run artifacts.
The ledger allows up to 1,024 activations per run and reserves room for their
rollback confirmations.

The runner library can inspect a closed spool with
`DispatchedRun.inspectFaultRecovery`. Use the same provider configuration that
owned the activation. The inspection asks providers implementing
`ExpiringFaultProvider` for read-only proof that the exact activation expired
and its dependency was restored. Missing providers, failed queries and
unconfirmed responses leave the obligation outstanding. Toxiproxy toxics
require operator reconciliation after runner loss because Toxiproxy does not
provide independent expiry.

External expiry confirms that fault obligation alone. An empty inspection
reports no surviving outstanding entries. The original spool and its
recovery state remain intact. Scenario cleanup and target effects require
their own reconciliation.

### Capacity and retention

Budget up to 64 MiB per retained attempt for its spool, plus space for its
journal mirror and reports. Measure their sizes on representative runs and
leave headroom for new attempts and terminal writes. The spool limit is per
attempt. Monitor both free bytes and inodes on the whole volume.

Before claiming a run directory, the runner checks its work directory under
a short exclusive lock. The default admission limits are 8 GiB of reserved
space, 64 attempts with journal payloads and 512 owned run directories.
Each unretired attempt reserves at least 129 MiB for its spool, mirror and
metadata. The budget also accounts for
the runner's shared files and replay ledger. Larger existing run artifacts
count at their current size. The runner checks the volume's usable space
for the unwritten reservations and filesystem headroom. At the default
reservation size, the byte budget can block admission after 63 retained
attempts. A capacity refusal leaves the dispatch ID and nonce unreserved
locally. Delivery and inspection of previously committed results remain
available.

For a read-only inventory on Linux, use the configured work directory.

```bash
df -h -- "$work"
df -i -- "$work"
du -sk -- "$work"
find "$work" -mindepth 1 -maxdepth 1 -type d -name 'run-*' -print
```

Treat the inventory as a dry run for retention review. Age, an apparently
idle health file and a dispatcher reply are insufficient evidence that an
attempt can be removed. Retain active, interrupted and unconfirmed attempts.
In durable mode, `RunnerAgent.inspectRetention` checks a specific run against
an operator-supplied acknowledgement cutoff. It verifies the closed spool,
owner, dispatcher route, exact terminal acknowledgement and cleanup
obligations. A candidate includes the verified terminal and the local
checkpoint timestamp. Use the read-only result as evidence when reviewing
retention.

### Retire an acknowledged journal

Drain the runner and verify an offline backup under your evidence retention
policy. Use the same runner arguments with `--durable-delivery`. Replace
`--once` with `--retire-acknowledged <run-id>` and
`--acknowledged-before <ISO-8601 timestamp>`. The command operates on local
files. The dispatcher URL and runner ID identify the route in the
acknowledgement, and the checkpoint timestamp must precede the cutoff.

The command verifies the spool, its owner, the exact terminal ACK and cleanup
before writing and forcing `retention-receipt.json`. It then removes the
spool and its NDJSON mirror. The receipt, checkpoint and owner marker remain
available for restart, result inspection and duplicate-dispatch checks. After
an interruption, rerun the same retirement command. Ordinary restart
validates receipts and leaves interrupted removals for an explicit retry.

A verified retired attempt uses its actual file sizes in the byte budget and
frees a journal slot. Its owner marker still uses one of the 512 owned
directory slots. Keep active, interrupted and unacknowledged attempts for
delivery or reconciliation. If the directory cap approaches, plan an
operator-controlled work directory transition with the dispatcher.

### Back up and rehearse a restore

Follow the [runner drain procedure](UPGRADE_ROLLBACK.md#a-runner) and wait
for the process to exit. Confirm no other runner can write the volume before
copying it. Back up the entire tree, including identity, replay ledger and
ownership files.
Store the backup outside the runner volume with access restricted to the
operator. Back up deployment configuration and keys separately.

The following Linux Bash example uses GNU tools. Set `work` to the stopped
runner's resolved directory and `backup` and `restore` to new, disjoint
directories outside it. Put both under operator-only parent directories,
since the copy preserves the source directory's modes. Use directories
without nested mounts. Rehearse with a scratch restore before replacing
any volume.

```bash
(
  set -euo pipefail
  umask 077
  unexpected=$(find "$work" -xdev ! -type d ! -type f -print -quit)
  test -z "$unexpected"
  mkdir -m 700 -- "$backup"
  cp -ax -- "$work/." "$backup/"
  diff -qr -- "$work" "$backup"
  mkdir -m 700 -- "$restore"
  cp -ax -- "$backup/." "$restore/"
  diff -qr -- "$backup" "$restore"
)
```

Check ownership and permissions separately before using the restored volume.
Keep the original stopped and dispatch paused. Restored ownership covers only
attempts in the backup. Reconcile attempts admitted since the backup and
ensure the dispatcher cannot offer their run IDs or nonces again. If you cannot
establish that, keep the restored volume for inspection. Once those attempts
are fenced off, start a single runner and check fresh readiness with
`faultora health --file <status file> --require-registered` before resuming
new dispatches.

A restore preserves evidence and local ownership. A verified spool without
a terminal is `RECOVERY_REQUIRED`. Reconcile target-side effects and fault
cleanup before scheduling new work. In default protocol v1, collect
undelivered results manually. For a support report, review the reduced bundle
produced by `faultora diagnose --run <run directory> --output <zip>` before
sharing it.
