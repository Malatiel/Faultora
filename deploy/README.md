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

Bring a dispatcher that implements the [runner protocol](../docs/RUNNER_PROTOCOL.md).
It receives registration and results and issues signed work over mutual TLS.
Use the local CLI or CI integration when you do not have a dispatcher. The
repository's qualification dispatcher is a test fixture. The bundled
controller is scheduled for 2.0.

| | What | Why it is separate |
|---|---|---|
| `--dispatcher` | An HTTPS origin with an optional port | Identifies the server whose certificate must match the configured host |
| `--keystore` | This runner's own identity, PKCS#12 | Proves which runner is speaking |
| `--truststore` | PKCS#12 store containing the trusted dispatcher certificates or issuers | Verifies the dispatcher's server certificate |
| `--policy-key <id>=<file>` | The dispatcher policy-signing certificate in X.509 format, with the key ID used in its signed policy | Keeps authority to sign a policy separate from authority to terminate TLS |
| `FAULTORA_SECRET_RUNNER_TLS` | The password shared by the two PKCS#12 stores and the runner's private-key entry | The runner resolves the local `runner-tls` handle from its environment |

Issue the runner's client identity through the PKI trusted by the dispatcher.
Keep its private key on the runner side and mount both stores and the policy
certificate read-only. Provision the password through the platform's secret
mechanism. Validate the dispatcher CA against your PKI before importing it in
`trusted.p12`. When performing an in-place file rotation, keep the same
store password and policy key ID. The next TLS connection reads the new
stores and the next verification reads the policy certificate. A password
change requires recreating the runner process with the new environment and
the corresponding stores.

## Start and verify a deployment

Choose an image from [Runner images](../docs/RUNNER_IMAGE.md), either built
locally or pulled using its verified digest. Before the first dispatch,
restrict the runner's outbound network to the dispatcher and the intended
test systems. For targets on private or loopback addresses, deliberately add
`--allow-private` to the runner arguments. This permits private destinations
at the connector layer. Narrow the reachable network, pass `--allow-target`
for each permitted target ID, and have the dispatcher sign the intended
targets and endpoints. Keep these controls in place even if the dispatcher is
inside the same private network.

For Compose, place `runner.p12` and `trusted.p12` under `deploy/tls/` and the
policy certificate under `deploy/keys/`. Update the policy key argument to
match the dispatcher. Set `FAULTORA_DISPATCHER` to its HTTPS origin and
provide `FAULTORA_SECRET_RUNNER_TLS` through the environment. Set
`FAULTORA_RUNNER_IMAGE` to use a verified digest in place of the locally
built tag.

```sh
docker compose -f deploy/docker-compose.yml config --quiet
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml exec -T runner \
  java -jar /opt/faultora/faultora.jar health \
  --file /var/faultora/health.json --require-registered
```

For Kubernetes, create the `faultora` namespace and provision the
`faultora-runner-tls` Secret with keys `runner.p12`, `trusted.p12` and
`password`. Provision the policy certificate in the
`faultora-policy-keys` ConfigMap. Set the image digest, dispatcher origin
and policy key in `kubernetes/runner.yaml`. Update and apply the egress
policy for your CNI and deployment before starting the pod. The sample
`emptyDir` storage is suitable for smoke tests. For persistent operation,
give each runner its own PersistentVolumeClaim and stable `--runner-id`,
and use a rollout such as `Recreate` that prevents two writers from
sharing one work directory.

```sh
kubectl apply -f deploy/kubernetes/network-policy.yaml
kubectl apply -f deploy/kubernetes/runner.yaml
kubectl -n faultora rollout status deployment/faultora-runner
```

Readiness confirms registration. For the first run, dispatch a read-only
scenario against an approved test target and check receipt of its journal
and final result. If the runner stays unready, inspect the container logs
for the registration refusal and see [Troubleshooting](../docs/TROUBLESHOOTING.md#a-runner-that-cannot-start).
The [work directory guide](../docs/RUNNER_SPOOL.md) covers retaining,
backing up and restoring the evidence and replay state.

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

A run longer than the grace is cut off. **Protocol v1 leaves an undelivered
journal on disk after restart.** An operator with shell access can collect it.
Set the platform's termination grace at or above `--shutdown-grace`, and
keep the working directory on storage that outlives the container.

See [Operating the work directory](../docs/RUNNER_SPOOL.md#operating-the-work-directory)
for capacity, retention, backup and restore procedures.

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

Matching checksums only show that the files agree with the list that came
with them: whoever could change a file could change the list, and the
verification script inside the bundle with it. So trust starts outside the
bundle. `offline-bundle.sh` prints a **SHA256SUMS fingerprint** when it writes
the bundle; the sender records it somewhere other than the medium the bundle
travels on, and the recipient starts from it.

On arrival, unpack the bundle anywhere. Before running anything in it, check
the fingerprint and every listed file, the verification script included, with
the system's own `sha256sum`:

```bash
tar xzf faultora-1.0.0-offline.tar.gz
cd faultora-1.0.0-offline
FINGERPRINT=the-recorded-64-hex-digits
echo "${FINGERPRINT}  SHA256SUMS" | sha256sum -c - && sha256sum -c --quiet SHA256SUMS
```

Only then is the bundled script worth running:

```bash
sh verify-offline-bundle.sh --fingerprint "${FINGERPRINT}" --release-sums ../SHA256SUMS
```

It compares the fingerprint first and runs nothing from the bundle when it
differs. It then refuses a file that does not match, a listed file that is
missing, a file the list does not name, a link, and a listed path that leaves
the bundle, and finally runs the bundled CLI on the bundled example, which
needs a local Java 21 and nothing from the network.

On the connected side, use a Faultora checkout to verify the downloaded
release JAR and its checksum manifest before building the bundle:

```bash
python3 deploy/verify-release.py faultora-1.0.0.jar --sums SHA256SUMS \
  --tag v1.0.0 --require-sums-attestation
```

This checks the release workflow, tag and hosted runner for both subjects.
The optional `--source-digest` pins both to the same source commit. Carry
the verified `SHA256SUMS` separately and pass it to `--release-sums` on
arrival. The recorded fingerprint identifies the transferred bundle and
the verified release checksums identify its `faultora.jar`. Without a fingerprint
the script refuses to run, unless asked for `--self-consistency-only`, which
checks the files against their own list, says that this is not a trust
check, and runs nothing.

A saved image is covered by the checksums only: they say it arrived as it was
saved, not where it came from. Save one pulled from GHCR and verified as
[Runner images](../docs/RUNNER_IMAGE.md) describes, or one built from the
checked jar.

`OfflineBundleE2ETest` builds a bundle, unpacks it in a new directory and makes
each of those alterations once, with a `java` on `PATH` that records whether it
was started: a refused bundle must never reach it.

`OfflineBundleNetworkE2ETest` transfers the JAR-only archive to a fresh
directory and runs the bundled verifier in a separate Linux network namespace
without routes to the host. It checks the CLI version and validates the
bundled scenario with a local Java runtime.

The separate [offline runner image check](../docs/QUALIFICATION.md#offline-runner-image-qualification)
transfers the image bundle to a Docker daemon with an empty image store and
no external network access.

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
