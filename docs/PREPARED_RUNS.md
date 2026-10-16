# Prepared executions and artifact ownership

A replay seed reproduces generated values. A run ID identifies one execution;
the CLI generates a fresh UUID-based ID independently of the seed. Run IDs do
not promise byte-identical journals: timestamps, duration and identity differ.

Without `--output`, the CLI writes to `faultora-results/<run-id>`. An explicit
`--output` is still the exact artifact directory. Existing `events.ndjson`,
`report.*` or `run-owner` (case-insensitive) refuses the run before connectors
open. Use another directory for a replay. Unrelated scenario/input files can
share the directory.
An exclusive CREATE_NEW ownership marker selects one concurrent claimant. It
is retained after failure so a later process cannot silently overwrite partial
evidence. This is an intentional change from truncating an existing journal.

Runner journals live under `run-<sha256(run-id)>/events.ndjson` in the configured
working directory. The dispatch ID never becomes a relative filesystem path.
Existing ownership also prevents accidental overwrites after runner restart,
independently of the in-memory replay cache. Every engine invocation owns a
FaultSession; provider inject/rollback contexts carry `FaultContext.RUN_ID`.
Providers sharing external resources must respect that ownership themselves.

`PreparedRun` is an in-memory, single-use object with a monotonic lifetime of at
most one hour (CLI and runner use five minutes). Preparation captures document
bytes, catalog identity, seed, run identity, target and extension policies,
expression bindings and secret-origin labels, connector configuration and
timeouts, evidence policy, network permission and offered fault types. Maps are
canonicalized, policy sets are sorted, and authored list order is preserved.
Changed caller state returns CHANGED; exact expiry returns EXPIRED; reuse
returns ALREADY_EXECUTED. These checks precede connector opening and fault
activation. The private plan compiled during preparation is the one executed;
execution does not compile or read documents again. Preview exposes only the
existing sanitized structural projection.

CLI and runner retain their existing diagnostic preparation before sealing a
successful run. Sealing compiles a detached private copy so callers holding
diagnostic models cannot mutate what executes. This additional compilation is
local and side-effect-free with respect to target execution. A larger redesign
of the diagnostic API was rejected to preserve its existing consumers.

Resolved secret values and resolver implementation are not hashed or persisted:
configuration binds secret handle IDs and the captured resolver remains the
authority for resolving current values. This supports secret rotation without
putting credentials in preparation metadata. Preparation is not a portable
authorization token, does not add a CLI persistent preview command, and does not
claim process isolation or protection against a writer controlling the runtime.
Fresh one-shot CLI runs prepare immediately; future interactive consumers can
hold this same object between preview and execution.

Rejected alternatives: deriving IDs from the seed confuses reproducibility with
ownership; appending a second run to an old journal mixes terminal states;
overwriting reports loses evidence; recompiling at execution could run something
different from the reviewed plan. Exclusive ownership and private captured plans
keep these concerns separate with no storage service or new transport protocol.
