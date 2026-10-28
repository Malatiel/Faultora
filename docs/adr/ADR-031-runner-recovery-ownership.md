# ADR-031: Durable delivery does not replay execution

## Status

Accepted design; durable spool and recovery are not implemented.

## Context: the current boundary

[ADR-020](ADR-020-runner-dispatch-and-leases.md) guarantees bounded execution
and describes journal delivery during a live runner. It explicitly excludes
delivery after a process restart. The following call sites explain why:

| Call site | Current behavior |
|---|---|
| `RunnerAgent.takeOneDispatch` | Executes a received dispatch, creates a `JournalTail` and a heartbeat thread |
| `RunnerAgent.sendWhateverIsFinished` | Sends complete lines; advances the in-memory tail after a progress response |
| `JournalTail.next` / `delivered` | Tracks byte offset and acknowledged line position in memory; excludes partial final lines |
| `RunnerAgent.deliverWhatIsLeft` | Attempts the final journal tail and outcome once; does not enqueue a durable retry |
| `RunnerClient.sendProgress` | Reads a numeric response as an acknowledgement, without durable checkpoint validation |
| `RunnerClient.sendOutcome` | Sends the result but does not validate an explicit terminal acknowledgement |
| `QualificationDispatcher.progress` | Stores lines in memory, drops positional overlap and refuses gaps; does not compare overlapping bytes |
| `QualificationDispatcher.result` | Replaces an in-memory outcome; it is qualification scaffolding, not durable terminal ownership |

There is no startup scanner, persisted delivery cursor or recovered execution
ownership. A local journal file alone supplies none of those guarantees. The
decision below constrains future spool and recovery work; it changes neither current code nor the
frozen runner protocol.

## Decision

### Separate execution from delivery

The runner owns the facts it observed during execution. The receiver owns its
durable copy and acknowledgement. Connection loss changes delivery state, not
the execution verdict. A completed failed run whose terminal record cannot be
delivered remains failed with delivery pending; it is not replaced by a
transport error or by PASS.

Persisted work is keyed by a runner identity, a unique execution attempt and
the approved dispatch fingerprint, with the logical run ID as correlation.
The fingerprint binds all execution inputs, including runtime inputs, target
redirects, seed and effective policy, not only document digests. A retry of
delivery retains the attempt identity. A conflicting fingerprint for that
identity is refused, never interpreted as a new permission to execute.

A process restart does **not** resume the scenario or reissue connector calls.
A nonterminal attempt becomes `RECOVERY_REQUIRED`: target effects and cleanup
may be unknown. Neither an expired lease nor a new connection permits another
execution of the same attempt. An operator-requested new run needs a new
attempt and an explicit decision about possible previous effects. Business
idempotency keys can help reconciliation, but transport deduplication cannot
provide exactly-once business execution.

### Local spool commit order

1. Before an attempt can invoke target operations, commit its identity,
   fingerprint, original bounds and ownership record. A single local writer
   lock and receiver-side attempt fencing prevent competing spool owners.
   Fencing delivery cannot undo an already issued remote request.
2. Append only complete, sequenced, policy-projected journal records to bounded
   segments. Commit a record before offering it for delivery. Do not spool raw
   evidence, secret handles, credentials or exception prose beside the journal.
3. On engine completion and resource release, commit one immutable terminal
   record: execution status, final journal position, cleanup/rollback
   confirmation and fixed infrastructure reason codes. This includes the
   mandatory writer/rollback failures of ADR-024/025. An attempted rollback is
   not confirmation. An unavailable result is represented as unknown.
4. Send the committed journal prefix, then the terminal record, and checkpoint
   acknowledged positions durably. A checkpoint failure leaves delivery
   pending; retransmitting committed records is safe.
5. Reclaim only after a durable terminal ACK and completion of retention and
   cleanup obligations. An ACK does not authorize deletion of policy-required
   evidence or unresolved recovery obligations.

Spool format has its own version. Paths are generated local identifiers, not
unchecked run IDs supplied by a peer. Updates use flushed files and atomic
checkpoint replacement with directory durability where the platform supports
it. Durable mode must refuse an unsupported storage guarantee rather than
silently downgrade it. Process-crash recovery and power-loss durability are
different claims; the latter requires separately tested filesystem and device
semantics. This ADR is not that evidence.

Reserve bounded space for the terminal/cleanup record before execution.
Bound total bytes, records, pending attempts, batch size and retention time.
Quota exhaustion stops admission or cancels further business work while
preserving cleanup obligations. A full or broken disk can also prevent the
failure marker from being written: report infrastructure failure where still
possible and never claim a durable terminal record that was not committed.
Do not delete unacknowledged data to manufacture free space or success.

### Receiver ACK and terminal ownership

The future durable mode requires a **new negotiated protocol version**. A v1
numeric progress response or empty result response cannot be reinterpreted as
proof of persistence. Unsupported peers must be refused when durable delivery
is requested; ordinary v1 behavior remains explicitly best effort.

A progress ACK names the attempt, bound fingerprint and cumulative exclusive
position: position N means records `[0,N)` are durably committed. Validate
positions, overflow and identity before advancing a local checkpoint. An ACK
must be within the submitted batch's end; when the receiver is further ahead,
it can acknowledge that submitted end and the sender catches up in batches.
An overlap is acknowledged only after its bytes or committed record hashes
match. A gap, a conflicting overlap or a foreign attempt is refused without
advancing the cursor. Authentication and run authorization apply to replay
and ACKs as they do to initial dispatch; possession of a spool path is not
authority to submit its contents.

Terminal acceptance is a compare-and-set for the immutable attempt and
terminal digest, after the full prefix through its final position is durable.
The terminal ACK includes that identity, digest and final position. An exact
duplicate returns the same ACK; a different terminal is a conflict. A timeout
at the receiver is an observation of missing delivery, not permission to
overwrite the runner's execution result. Recovery can add an explicit unknown
or reconciliation record, but cannot invent confirmed cleanup or PASS.

### Recovery states and failure cases

Execution and delivery are separate state axes. Execution is `ADMITTED`,
`RUNNING`, `TERMINAL` or `RECOVERY_REQUIRED`. Delivery is `PENDING`,
`SENDING` or `ACKNOWLEDGED`; a broken spool is `QUARANTINED`.

| Failure point | Recovery action |
|---|---|
| Connection lost while running | Continue only within the existing lease/budgets; journal locally; cancel and attempt cleanup when permission expires |
| Receiver committed a batch, ACK lost | Resend that committed batch; compare overlap and return the same acknowledged prefix; no connector invocation |
| Sender received ACK but crashed before checkpoint | Resend from the last local checkpoint; receiver deduplicates the same records |
| Receiver restarts | Recover its committed prefix and immutable terminal before accepting replay; an absent durable prefix cannot be acknowledged |
| Runner restarts with committed terminal but no ACK | Resume delivery only, including the same terminal; never rerun the scenario |
| Runner restarts with a nonterminal attempt | Mark recovery required; preserve evidence and fault/cleanup obligations; do not resume business operations |
| Partial trailing record after crash | Recover only the verified committed prefix; distinguish an uncommitted tail from corruption; never acknowledge guessed data |
| Corrupt record, impossible cursor or conflicting terminal | Quarantine that attempt with an infrastructure diagnosis; do not silently skip it or replay execution |
| Terminal accepted, terminal ACK lost | Resend the identical terminal and receive its existing ACK |
| Shutdown grace expires before confirmation | Preserve pending/unknown state; process exit cannot prove remote effects or rollback |

Persist external fault obligations before activation. Startup recovery can
attempt authorized, idempotent rollback and verify provider-enforced hard
expiry, but cannot claim that killing a runner recovered an external fault.
In-process state lost with the process is not evidence of target recovery.
Lease expiry is never extended or reset by restart. Recovery qualification must prove these
properties with kill/restart tests before enabling automatic recovery.

## Alternatives and required implementation evidence

- Replaying the dispatch is simpler than a delivery spool, but can repeat a
  mutation whose reply was lost. It is excluded.
- Treating a successful HTTP response as durable ACK hides receiver restart
  loss. Persist-before-ACK and explicit protocol negotiation are required.
- Last-writer-wins terminal storage lets retries change a verdict. Immutable
  attempt-scoped compare-and-set preserves ownership.
- Keeping every journal forever avoids eviction choices but creates unbounded
  disk usage. Quotas and admission control must be observable, not silent loss.

The implementation must test crashes on both sides of each commit/send/ACK/checkpoint
boundary, identical and conflicting replay, bounded quota failure, corrupt
tails, lost terminal ACK and external fault recovery. Count target mutations
in those tests: replay must leave that count unchanged. No durability entry
may be removed from documented limitations merely because this ADR exists.
