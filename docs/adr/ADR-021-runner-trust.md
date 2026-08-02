# ADR-021: How a runner and a control plane come to trust each other

## Status

Accepted

## Context

The companion to ADR-020, and the second decision architecture §15 requires
before implementation: the runner-controller authentication and trust model.

A runner executes arbitrary scenarios against systems inside a private network,
under an execution policy that says which targets it may reach and which faults
it may inject. Everything about that is a security boundary: who may dispatch
to it, what it will accept as a policy, and what it does when the answer to
either is "cannot tell".

M4-01 and M4-02 name the pieces — mutually authenticated identity, certificate
rotation, replay protection, signed effective policy, local refusal independent
of controller behaviour. What is undecided is how they fit together, and what
happens at each edge.

## Decision

- **Both sides authenticate with certificates, and the runner verifies the
  control plane as strictly as the control plane verifies it.** A runner that
  authenticated itself to whoever answered would accept work from anyone who
  could reach the address in its configuration.
- **Trust is a file, and rotation is replacing it.** The runner reads its key
  material and the trust anchor from paths in its configuration, and re-reads
  them when it opens a connection. Rotating a certificate is writing new files;
  nothing has to be restarted, and nothing has to be told. This shape is chosen
  because it is what a Kubernetes secret mount does, and because it is testable
  — a test can swap the files mid-life and assert the next connection uses the
  new identity and that the retired one is refused.
- **Test certificates are generated with `keytool` at test time.** There is no
  public JDK API for issuing an X.509 certificate — `sun.security.x509` is
  internal and not exported — so the choices were a committed fixture, a new
  dependency, or the tool that ships with the JDK. Committed fixtures expire
  and then fail a build for a reason unrelated to the change that broke it; a
  dependency for a test is a dependency. `keytool` is the tool an operator
  actually uses, which makes the rotation test a rehearsal of the documented
  procedure rather than a simulation of it. It is invoked at
  `java.home/bin/keytool` rather than from the path: the JDK running the tests
  is the one that has it, and a machine with a different `keytool` earlier on
  its path would otherwise decide what the test proves.
- **The effective policy is signed, and an unsigned one is refused.** A
  dispatch carries the execution policy the run is permitted under, signed by
  the control plane. A runner that took a policy on the word of whoever sent it
  would let a compromised transport widen what a run may do.
- **The verifying key is its own file, named in the runner's configuration.**
  Not folded into the TLS trust anchor: the two answer different questions and
  rotate on different schedules. Terminating TLS is something an infrastructure
  component may legitimately be given the ability to do; issuing a policy that
  says which faults may be injected is not, and the file that grants the second
  should not arrive with the first. It is read the same way as the rest of the
  key material — from a path, on connection — so rotating it is the same
  operation.
- **A dispatch names secret handles, never secret values.** The policy and the
  scenario refer to `authSecretId` and `databaseSecretId`; the runner resolves
  them from its own environment, as a local run does. Nothing that has ever
  been a credential crosses this wire. The alternative — resolving centrally
  and shipping what came back — would put every target's credentials into a
  message, a buffer, and eventually a log, in exchange for one less thing to
  configure on the runner.
- **The runner's own limits are a floor the signed policy cannot lower.** Its
  configuration states what this deployment permits — which hosts, which fault
  kinds, what concurrency, what duration. A signed policy narrows that and can
  never widen it. This is what "local refusal independent of controller
  behaviour" means: the runner is not enforcing the controller's decision, it
  is enforcing its own and honouring the controller's on top.
- **A dispatch is accepted once.** It carries a run id, a nonce and an issue
  time; the runner refuses a run id it has already seen and one issued more
  than **five minutes** ago or more than five minutes ahead. Replaying a
  dispatch is otherwise a way to run a destructive scenario twice with one
  authorization. The window is a clock-skew allowance between two hosts nobody
  promised to synchronize — the same reason the Kafka observation floor carries
  a grace, at the scale a dispatch needs rather than a broker timestamp.
  **The set of accepted run ids lives in memory**, so a restarted runner will
  accept a replay of a dispatch it saw before it restarted. That is stated
  rather than hidden: closing it needs durable state whose own failure modes
  are worse than the window it closes, and a restart is not something an
  attacker can cause from outside the private network.
- **A refusal is named, and it is not a failure of the run.** Every refusal
  above — untrusted peer, bad signature, replayed dispatch, digest mismatch,
  a policy exceeding the local floor, an unsupported protocol version — has its
  own reason on the wire. "The runner said no, and here is which rule" is
  actionable; a connection that drops is not.

## Rejected alternatives

- **A shared bearer token.** One secret, no identity, no rotation story, and
  nothing to revoke when a runner is decommissioned. It is what "just get it
  working" looks like and it is the thing a private-network deployment is least
  able to tolerate.
- **TLS in one direction, with the runner authenticated by a token inside it.**
  Half the property for most of the work: the runner still could not tell which
  control plane it was talking to.
- **Trusting the policy because the connection was mutually authenticated.**
  Authentication says who is speaking. A signature says what they said and that
  it has not been altered since — including by anything terminating TLS in the
  middle, which in a private network is a proxy somebody operates.
- **Refusing to start without a signed policy from a reachable control
  plane.** Tempting, and it makes the runner useless exactly when the network
  is having a bad day. The runner's local floor is what it enforces when it has
  nothing else; a run simply does not start without a dispatch, and a dispatch
  is what carries the policy.
- **Committing test certificates.** Reviewed above: an expiry date that fails a
  future build for an unrelated reason.

## Consequences

- A deployment has to issue certificates. That is real operational work and it
  is the cost of the property; `docs/` will carry the procedure, and the
  rotation test exercises the same steps.
- The runner refuses more than it accepts, and each refusal needs a reason a
  reader can act on. That is more code than a boolean, and it is the part that
  makes an isolated deployment diagnosable at all.
- Signing the policy means the control plane holds a signing key beside its TLS
  identity. They are separate on purpose: terminating TLS is something an
  infrastructure component may legitimately do, and issuing a policy is not.
