# Running a runner

For a local CLI installation with Windows and Linux launchers, see
[Installing the CLI](../docs/INSTALLATION.md). The CLI archive includes the same
executable used by the runner; it does not include a Java runtime or container
image.

For local amd64/arm64 builds and the separate image publication workflow, see
[Runner images](../docs/RUNNER_IMAGE.md).

A Faultora runner sits inside the network it tests and dials out for work.
Nothing connects to it: not the control plane, which it calls; not a health
probe, which runs a command inside the container; not a metrics scrape. That
absence is the property this deployment shape exists for, and everything here
is arranged to keep it.

## What an operator has to provide

Four things, and three of them are files.

| | What | Why it is separate |
|---|---|---|
| `--keystore` | This runner's own identity, PKCS#12 | Proves which runner is speaking |
| `--truststore` | The control planes it will speak to | Proves which control plane it reached |
| `--policy-key <id>=<file>` | The certificate a signed policy is verified against | Terminating TLS is something an infrastructure component may legitimately be given; issuing a policy that says which faults may be injected is not, so the file that grants the second does not arrive with the first |
| `FAULTORA_SECRET_RUNNER_TLS` | The key material's password | Resolved from the runner's own environment. A dispatch names handles and never values |

All of them are read when they are used, so **rotating any of them is replacing
a file**. Nothing needs restarting, which matters for the thing least
convenient to reach.

Issue them the way the tests do:

```bash
keytool -genkeypair -alias runner -keyalg RSA -keysize 2048 \
        -dname CN=runner -validity 365 -ext SAN=dns:runner.internal \
        -keystore runner.p12 -storetype PKCS12
```

## What this deployment permits

The runner's configuration is a **floor**. A dispatched policy narrows it and
can never widen it, which is what makes a refusal independent of whatever is
dispatching. The runner prints its floor on startup, and it is worth reading
once:

```
faultora runner runner-3
  dispatcher    https://control.example.internal:8443
  work dir      /var/faultora
  protocols     http, jdbc, kafka
  policy keys   control-2026
  operations    MUTATING, READ_ONLY
  keeps         bodies up to 10485760 bytes, headers, up to 1000 rows
  faults        none
  targets       any
```

Two of those lines mean the opposite of what a reader might assume, and the
asymmetry is deliberate:

- **`targets any`** — naming no target ids means no restriction. A deployment
  that has not thought about which targets exist should not thereby refuse
  every dispatch.
- **`faults none`** — naming no fault types means none at all. Breaking
  something is a capability that is granted deliberately.

## Health, without a port

The runner writes a status file and `faultora health` reads it. A container
platform runs that as an exec probe; it needs no shell and no network.

```bash
faultora health --file /var/faultora/health.json                      # live
faultora health --file /var/faultora/health.json --require-registered # ready
```

**Live** means the runner's own timer is still running — a wedged process fails
it and should be restarted. **Ready** means a dispatcher has accepted this
runner.

A runner that cannot reach its control plane fails readiness and **must not**
fail liveness. Restarting does not make a control plane reachable, and a
liveness probe that asked about it would restart every runner in a fleet at the
same moment, during the outage.

## Shutting one down

SIGTERM stops the runner asking for new work and then waits up to
`--shutdown-grace` (30s by default) for the run in flight to finish and report
what it found. A run that ends tidily and tells nobody has failed just as
surely as one that ran too long.

It is a bound and not a guarantee. A run longer than the grace is cut off, and
**a journal the runner still holds when the process stops is not re-delivered
when it starts again** — it is on disk, and collecting it is a person with
shell access rather than the protocol. Set the platform's termination grace at
or above `--shutdown-grace`, and keep the working directory on something that
outlives the container.

## The examples here

| File | What it shows |
|---|---|
| `../Dockerfile` | Distroless image, non-root, no shell |
| `docker-compose.yml` | A runner beside the system it tests, publishing no port |
| `kubernetes/runner.yaml` | Deployment with every capability dropped, a read-only root, and exec probes |
| `kubernetes/network-policy.yaml` | Deny-all first, then the two things a runner does |
| `offline-bundle.sh` | Jar, saved image, examples and checksums in one file |
| `verify-offline-bundle.sh` | Checks a bundle on arrival; travels inside it |
| `runner-image.hcl` | Local multi-platform OCI archive and single-platform runtime builds |

## Carrying a bundle across an air gap

`offline-bundle.sh` prints a **SHA256SUMS fingerprint** when it writes the
bundle. Record it somewhere other than the medium the bundle travels on. On
arrival, unpack it anywhere and run the script it carries:

```bash
tar xzf faultora-0.11.3-offline.tar.gz
sh faultora-0.11.3-offline/verify-offline-bundle.sh --release-sums SHA256SUMS
```

It refuses a file that does not match `SHA256SUMS`, a listed file that is
missing, a file the list does not name, a link, and a listed path that leaves
the bundle. It then runs the bundled CLI against the bundled example, which
needs a local Java 21 and nothing from the network.

Matching checksums only show the files agree with the list that came with
them; whoever could change a file could change the list. Two things close
that gap. `--release-sums` takes the release's own `SHA256SUMS`, checked with
`gh attestation verify` on the connected side (see
[Verifying a download](../docs/INSTALLATION.md#verifying-a-download)) and
carried separately, and refuses a `faultora.jar` that is not the released one.
The fingerprint the script prints at the end must equal the one recorded when
the bundle was made. A saved image is covered by the checksums only: they say
it arrived as it was saved, not where it came from. Save one pulled from GHCR
and verified as [Runner images](../docs/RUNNER_IMAGE.md) describes, or one
built from the checked jar.

`OfflineBundleE2ETest` builds a bundle, unpacks it in a new directory and makes
each of those alterations once, including a replaced jar with a rewritten
`SHA256SUMS`, which only the release checksums catch.

**"Without infrastructure fault permissions" is two statements that have to
agree.** The pod drops every capability and is not privileged, *and* the runner
is started without `--toxiproxy-url` and without any `--allow-fault`. Granting
the capability while forbidding the fault type, or the reverse, is one fact
written in two places that will eventually disagree.

**What is exercised and what is not.**

- The image is built and run by `RunnerImageE2ETest`, which serves a real
  dispatch through it over mutual TLS.
- That **nothing listens** is checked by `RunnerIsolationTest`, which asks the
  operating system for the listening sockets of a running runner and requires
  there to be none — while idle and while a run is in flight. It puts the same
  question to a process that is listening, so a check that finds nothing means
  something.
- Rotating key material is checked by `TlsMaterialTest` and `PolicyKeysTest`:
  the file is replaced, the new material works, and the old stops working,
  with nothing restarted.
- The Compose file and the Kubernetes manifests are **documentation**. No test
  applies them, the deny-by-default network policy is not enforced by anything
  in this repository, and the addresses in them are examples that have to be
  replaced.
