# ADR-014: Bounded, repeatable observation of event channels

## Status

Accepted

## Context

Roadmap M3-02 asks for consumption with bounded start and end positions,
isolated test consumers, and support for duplicate and delayed delivery.
Underneath that is a harder question than it looks: what does it mean for an
observation of a message channel to be *repeatable*?

An HTTP step is repeatable because a request produces its own response. A
channel has no such pairing. It holds whatever anyone put there, from before
the run started, and it keeps receiving while the observation is open. Three
things can go wrong, and they are different problems:

- an observation reports history that predates the run, so a scenario asserting
  "one event" fails on traffic it had nothing to do with;
- an observation never ends, because a channel never ends;
- an observation inside a repeat block sees the previous iterations, so
  "exactly one event per iteration" is unwritable.

The first two are about bounds. The third is not, and conflating them is the
mistake this record exists to avoid.

## Decision

- **The floor is a time, not a position: the moment the run began.** Every
  observation reads forward from the first message written at or after that
  time, resolved through the broker's own record timestamps. A channel with
  nothing that recent has nothing to look back at, and its floor is its current
  end. `from: beginning` reaches into history, explicitly.
- **The floor is resolved lazily, on a channel's first use, and cached for the
  run.** What is resolved is a time, so the answer does not depend on when the
  lookup happened — which is what lets the resolution stay lazy, and lazy
  resolution is what keeps the broker's vocabulary out of the composition root
  and protocol-specific keys out of the shared connector context. The cache
  belongs to the run rather than to a client, because a client lasts one
  operation and "where we started" does not.
- **A tolerance of two seconds is applied to the run's start.** The record's
  timestamp is set by whoever produced it, on their clock, which is a different
  machine whenever the application under test is not this process. Missing an
  event because of clock skew would be a silent failure of the tool; including
  two seconds of history is not, because selection decides what a step is about.
- **Bounds are three, and each answers a different failure.** The floor bounds
  how far back an observation reaches. A wait, capped by the run's request
  timeout, bounds how long it stays open. A message count and a byte budget for
  stored payloads bound what one observation can make a run hold. A scenario can
  tighten any of them and widen none.
- **Repeatability comes from selection, not from position.** A step declares
  which messages it is about — by key, by header, or by a payload field,
  usually a correlation value the step itself published — and the assertions
  run over those. Two iterations of a repeat block read the same window and
  still each see only their own messages.
- **Observing twice is defined and safe.** Every observation re-reads from the
  same floor, so a later one sees everything an earlier one did plus whatever
  arrived since. This is what makes "exactly one" assertable at all: the poll
  that first sees one event cannot distinguish one from the first of two.
- **An empty observation is a result, not an error.** Whether nothing arriving
  is a failure is an assertion's question. A connector that decided it would
  make absence unassertable, and absence is what several event assertions are
  about.
- **A wait is spent, not outlived.** The window closes at its deadline even
  while messages are still arriving. Draining until a channel goes quiet reads
  as reasonable and means a busy channel can hold a run open for as long as the
  target stays busy — the bound would exist only on paper. A zero wait still
  gets one poll, so a snapshot reads the batch already there.
- **A client's lifetime follows the guarantee the broker gives it.** A producer
  is thread-safe, so the run keeps one per target and a parallel group publishes
  through it. A consumer belongs to the one thread that polls it, so each
  operation opens its own and closes only that. Sharing one between concurrent
  steps is not slow but wrong twice over: the client refuses concurrent access,
  and releasing one step's handle would close a client another step is reading
  from.
- **The connector owns the settings that decide what a run may reach.** An
  operator may pass broker settings through — TLS, SASL, tuning — but not the
  bootstrap list, which the destination policy has just verified, nor the
  serializers the evidence path depends on. Those are refused by name: a
  pass-through that could replace the broker list is a policy bypass wearing
  configuration's clothes.
- **No consumer group is ever joined.** Partitions are assigned directly and no
  offset is committed, so a run creates no group state on the broker, cannot
  disturb the application's own consumers, and leaves nothing behind when it is
  interrupted. A binding that names a group id is recorded and not used.
- **Evidence is protocol-neutral.** A connector produces messages in a shared
  shape; the engine journals them and the event assertions read them without
  either knowing which broker they came from.

## Rejected alternatives

- **A `subscribe` or `observe` step type.** It would enlarge the scenario
  contract for nothing: an observation is an operation with inputs, and the
  polling that "eventually appears" needs is the `eventually` block that
  already exists. `event-count min: 1` inside one says it exactly.
- **Bounding an observation by end offsets alone**, stopping as soon as the
  window is drained. It terminates, but it makes every asynchronous scenario
  require an `eventually` wrapper even to see an effect that takes 50ms — and
  it would still not make a repeat block writable, because the third problem is
  not about bounds.
- **An offset floor taken when the run first touches a channel.** This was
  built first and is wrong in the case that matters most. A run does not write
  to the channel it observes; the application under test does. By the time the
  run first reads that channel, the event it is waiting for has usually already
  been written, so an anchor taken then sits above it and the observation
  reports nothing — while the report shows the publishes succeeding, which
  makes it look like the target never reacted. It passed every unit test with
  in-memory clients and failed on the first run against a real broker.
- **A run-wide watermark of end offsets taken at run start.** It has the right
  timing but cannot be taken without knowing every channel in advance, which
  the connector does not and should not.
- **Committing offsets so an observation resumes where it left off.** It makes
  an observation stateful and non-repeatable, and it leaves group state on a
  broker the run does not own.
- **A dedicated topic per run.** It removes the selection problem by removing
  the sharing, but the application under test publishes where it publishes; a
  test that requires its own topics is testing a different deployment.

## Consequences

- A scenario on a shared channel without a `match` clause counts whatever else
  was happening. The journal records both the number of messages the window
  contained and the number the step claimed, so the difference is visible.
- A wait longer than the run's request timeout is shortened by the connector,
  which records both what was asked for and what it waited; the report states
  the difference rather than letting the scenario's stated wait read as fact.
  The compiler can only apply the coarser bound — a literal wait longer than the
  whole run's budget fails compilation — because the per-request timeout is
  connector configuration and is not known when a plan is built.
- Every poll of a polling block opens a consumer, which is the cost of a client
  that belongs to one thread. The metadata a poll would otherwise repeat is
  cached for the run, so what remains is the connection.
- An observation reaches back two seconds further than the run's start, so a
  message written just before the run on the same channel can be selected by a
  scenario that does not narrow its window. Naming a correlation value the run
  itself produced removes the possibility.
- **The fallback when a timestamp finds no offset is ambiguous, and the
  ambiguity is recorded rather than resolved.** `offsetsForTimes` returning
  nothing for a partition is read as "nothing that recent exists", and the
  floor becomes the partition's current end. That is right for a channel whose
  traffic is all older than the run. It is wrong if the broker's time index has
  not yet caught up with a record that *is* recent — the floor would then sit
  above an event the run caused, and every later poll of that channel would
  observe nothing at all, for the whole run.

  Whether that happens in practice is unproven. It is written down because the
  symptom is specific and cheap to recognise — `MESSAGES_OBSERVED` with
  `observed: 0` repeating while the effect demonstrably occurred — and because
  this is the same shape as the defect this ADR was written about: a floor
  above the event, invisible to every in-memory test. The events gate now
  reports the producing side's own count beside the observation, which is what
  tells the two apart the next time it is seen.
- The evidence budget is per observation, not per run. A polling block that
  observes twenty times can hold twenty windows' worth of payloads, because each
  poll's evidence belongs to its own node. That is the same accumulation any
  polled HTTP step has, and it is what the performance baselines of M6-04 have
  to state a scale for.
- Selection is exact-match only. A scenario needing a range or a predicate
  observes more broadly and asserts on what it observed.
- The payload a selector reads is the message as it arrived, not as the
  evidence policy left it: a policy that withholds payloads must not change
  which messages a scenario sees.
