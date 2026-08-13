# ADR-023: What an extension policy promises, and when

## Status

Accepted

## Context

`ExtensionPolicy` has five fields. One of them — `allowedExtensions` — is read,
and is compared against a class name. The other four have never been read by
anything:

| Field | What its javadoc says | What happens |
|---|---|---|
| `requireProcessIsolation` | "whether extensions must run in separate processes" | nothing |
| `maxResourceMemoryMb` | "memory limit per extension" | nothing |
| `maxNetworkDestinations` | "network destinations the extension may contact" | nothing |
| `secretCapabilities` | "secret handle IDs the extension may access" | nothing |

This is worse than four missing fields. An operator who set
`requireProcessIsolation` read a configuration that describes a control, and
got a run that proceeded in-process exactly as if they had not set it. The
project's standing gate — *a guarantee without a failing test is a wish* — was
written about sentences in ADRs drifting from code; this is the same defect in
a record's javadoc.

M6-02 is the milestone that implements them. It is large: an out-of-process
protocol, a manifest, capability and compatibility validation, an SDK, a
reference extension, isolation and resource limits.

## Decision

- **A policy asking for something this build does not enforce stops the run.**
  Not a warning, and not silence. The three limits below cannot be honestly
  enforced while an extension shares this JVM, so a run configured with one of
  them is a run whose operator believes something untrue, and continuing is the
  harmful option. The refusal names each request and points here.
- **It is refused at the composition root**, `RunEnvironment.open`, which is
  the one place both the CLI and the runner go through. Checking it in each
  caller is how one of them eventually stops checking.
- **Three of the four wait for the process boundary, on purpose.** A memory
  ceiling, a network allowlist and a secret allowlist all assume the extension
  is somewhere its heap, its sockets and its `SecretResolver` are not the run's.
  In-process approximations are available — a thread with a watchdog, a wrapped
  resolver — and every one of them is a control that reports success and
  prevents nothing, which is the thing this ADR exists to stop doing.
- **Each entry disappears as its enforcement lands**, rather than a warning
  somebody has to remember to delete.

## The slices, and what each may claim

M6-02 arrives in four pieces. They are listed because "extension isolation" is
not a thing that half-exists: a release that shipped a manifest and called it
isolation would be this ADR's own defect, one level up.

1. **This one.** A policy this build cannot keep is refused. Nothing new is
   enforced; what changes is that nothing is quietly unenforced.
2. **Manifest and identity.** A plugin declares what it implements, what it
   needs, and what it was built against; identity becomes the digest of the
   artifact rather than a class name — which closes the gap between
   `ExtensionRegistry`'s javadoc ("identity is checked by class name") and
   `ExtensionPolicy`'s ("extension identity digests or names"). Implementable
   without the process boundary, and the cheapest real narrowing available.
3. **The out-of-process protocol**, for the three contracts that are pure
   functions over data — `AssertionProvider`, `SourceImporter`,
   `ReportRenderer`. Connectors and fault providers stay in-process and stay
   constructed explicitly: they carry the destination policy, and ADR-004
   already says a jar on the classpath must never widen what a run may touch.
   A `Connector` hands back live evidence and a `SecretResolver` returns a
   handle wrapping a supplier; neither crosses a process boundary as data.
4. **Isolation and limits**, which is where three of the four fields above stop
   being refused: a child process with a heap ceiling it cannot exceed, sockets
   an allowlist can be applied to, and secrets it is handed rather than
   resolves. What that does *not* bound has to be stated when it lands —
   `-Xmx` is not a container, and a plugin that spawns something is outside
   what a JVM flag can say anything about.

## Rejected alternatives

- **Leaving the fields and fixing the javadoc.** The cheapest change and it
  keeps the failure: a policy that reads as configured and does nothing.
- **Removing the four fields until they are implemented.** Honest, and it
  churns the constructor at every call site twice — once to remove, once to
  restore — while losing the record of what the design intends. Refusing says
  the same thing without pretending the intent does not exist.
- **A warning rather than a refusal.** A warning on a security control is a
  line in a log nobody reads, on the one occasion it mattered.
- **Enforcing the limits in-process now.** Reviewed above: every available
  approximation is a control that cannot fail for the reason it exists.

## Consequences

- Nothing in this repository sets any of the four, so nothing changes for any
  run made today. That is the point: the refusal is for the operator who was
  going to configure one and be misled.
- The list in `ExtensionRegistry.notYetEnforced` has to shrink as slices land,
  and a slice that implements a control while leaving its entry there would
  refuse a policy it can now keep. The tests name each entry, so removing one
  is a deliberate edit rather than an oversight.
