# Faultora

**Break it here. Trust it everywhere.**

Faultora is a self-hosted reliability testing CLI for HTTP APIs. It imports
OpenAPI descriptions, runs repeatable scenarios, checks technical and business
invariants, and produces console, JSON, HTML, and JUnit reports. Execution stays
inside your infrastructure and does not require a hosted control plane or
telemetry.

Version 0.1.1 is a runnable technical preview. It targets local
development and CI use on Java 21. The unreleased 0.2.0 line on this branch
adds in-process fault injection.

## What 0.1.1 includes

- OpenAPI 3.x import and operation discovery;
- versioned YAML scenarios;
- HTTP GET, POST, PUT, PATCH, DELETE, HEAD, and OPTIONS operations;
- status, header, JSONPath, and duration assertions;
- sequential operation and wait steps with explicit dependencies;
- environment-backed bearer-token resolution;
- console, JSON, HTML, and JUnit reports;
- SSRF protection with DNS resolution and address pinning;
- bounded HTTP response streaming;
- policy-bounded in-memory evidence for assertions;
- header filtering, content-type allowlists, body limits, and JSON redaction;
- manual redirect handling with cross-origin credential stripping.

## What 0.2.0 adds (unreleased)

- in-process fault injection: `http-latency`, `http-error`, and
  `http-response-loss` fault steps with hard-expiry watchdog and guaranteed
  exactly-once rollback;
- `expectError` steps for requests that are supposed to fail under a fault;
- fault windows and fault-to-node attribution in console and HTML reports,
  plus `FAULT_INJECTED` / `FAULT_ROLLED_BACK` journal events;
- reference reliability scenarios: SLA under injected latency, and
  "duplicate payment is not created" under a lost response with an
  idempotency-key retry.

The built-in fault provider acts only on Faultora's own outbound requests. It
never touches the target system, its infrastructure, or other traffic, so no
extra privileges are needed.

Network-level faults, retries, parallel/repeat blocks, distributed workers,
Kafka, Kubernetes orchestration, and the web interface are not part of these
releases. Scenarios that request unsupported execution features are rejected
during validation or plan compilation rather than being silently accepted.

## Requirements

- Java 21 or newer;
- Maven is not required when using the release JAR.

## Install

Download the release JAR and its checksums:

```bash
FAULTORA_VERSION=0.1.1
RELEASE_URL="https://github.com/Malatiel/Faultora/releases/download/v${FAULTORA_VERSION}"

curl --fail --location --retry 3 \
  --output "faultora-${FAULTORA_VERSION}.jar" \
  "${RELEASE_URL}/faultora-${FAULTORA_VERSION}.jar"
curl --fail --location --retry 3 \
  --output SHA256SUMS \
  "${RELEASE_URL}/SHA256SUMS"

grep " faultora-${FAULTORA_VERSION}.jar$" SHA256SUMS \
  | sha256sum --check --strict -

java -jar "faultora-${FAULTORA_VERSION}.jar" --version
```

On macOS, use `shasum -a 256 -c` instead of `sha256sum --check --strict`.
Every release also includes a CycloneDX SBOM and the Apache 2.0 license.

## Build

```bash
./mvnw verify -B
```

The executable artifact is written to:

```text
faultora-cli/target/faultora-0.2.0-SNAPSHOT.jar
```

The regular CI build can run without repository secrets. Configure the
`NVD_API_KEY` repository secret to enable OWASP Dependency Check on every push.
Release publication always requires this secret and fails closed when it is
missing.

## Quick start

Check the executable and validate the example scenario:

```bash
java -jar faultora-cli/target/faultora-0.2.0-SNAPSHOT.jar --version

java -jar faultora-cli/target/faultora-0.2.0-SNAPSHOT.jar \
  validate \
  --scenario examples/payment-service/scenarios/passing.yaml
```

Generate a starter scenario from an OpenAPI document:

```bash
java -jar faultora-cli/target/faultora-0.2.0-SNAPSHOT.jar \
  init \
  --from-openapi examples/payment-service/openapi.yaml \
  --output ./generated
```

Run a scenario against an API:

```bash
java -jar faultora-cli/target/faultora-0.2.0-SNAPSHOT.jar \
  test \
  --scenario examples/payment-service/scenarios/passing.yaml \
  --openapi examples/payment-service/openapi.yaml \
  --target https://api.example.com \
  --format console,json,junit,html \
  --output faultora-results
```

Private, loopback, and link-local destinations are blocked by default. Use
`--allow-private` only for an explicitly trusted local test environment.

## Fault injection

The reference reliability scenarios run against the bundled payment example:

```bash
java -jar faultora-cli/target/faultora-0.2.0-SNAPSHOT.jar \
  test \
  --scenario examples/payment-service/scenarios/fault-duplicate-payment.yaml \
  --openapi examples/payment-service/openapi.yaml \
  --target http://localhost:8080 \
  --allow-private
```

The scenario delivers a `create-payment` request whose response is lost,
retries it with the same `Idempotency-Key`, and asserts the business invariant
that exactly one payment exists. The report shows the fault window and every
node that ran while the fault was active:

```text
--- Nodes ---
  [PASSED] lose-response (0ms)
  [PASSED] first-attempt (2ms)
  [PASSED] wait-for-fault-expiry (1502ms)
  [PASSED] retry-with-same-key (7ms)
  [PASSED] list-payments (3ms)
  [PASSED] retry-is-replayed (1ms)
         Assertion: PASS — Status 200 matches expected 200
  [PASSED] no-duplicate-payment (43ms)
         Assertion: PASS — Path '@' has 1 elements

--- Faults ---
  [http-response-loss] target * — active 1003ms, rollback: hard-expiry
         During fault: first-attempt, wait-for-fault-expiry
```

See the [scenario reference](docs/SCENARIO_REFERENCE.md#faults) for fault
types, parameters, and rollback guarantees.

## Reports

Faultora can write console, JSON, JUnit XML, and self-contained offline HTML
reports in one run:

```text
=== Faultora Run Report ===
--- Nodes ---
  [PASSED] create-payment (31ms)
  [PASSED] create-status (1ms)
         Assertion: PASS — Status 201 matches expected 201
  [PASSED] response-has-id (1ms)
         Assertion: PASS — Path 'id' exists: true
  [PASSED] list-payments (2ms)

Result: PASSED — 4 nodes, 2 passed assertions, 0 failed assertions (35ms)
```

![Faultora HTML report](docs/assets/html-report.png)

The HTML report has no CDN, remote fonts, scripts, or telemetry and can be
opened directly from a CI artifact.

## CI integration

A copy-ready GitHub Actions workflow is available at
[`examples/github-actions/faultora.yml`](examples/github-actions/faultora.yml).
Copy it to `.github/workflows/faultora.yml` in the project being tested, then:

1. place the scenario at `faultora/scenario.yaml`;
2. place the OpenAPI document at `faultora/openapi.yaml`;
3. create the repository variable `FAULTORA_TARGET_URL`;
4. create the repository secret `FAULTORA_API_TOKEN`.

The example downloads the pinned release and verifies its SHA-256 checksum.
Pull requests validate the scenario without receiving credentials or contacting
the target. Main-branch pushes and manual runs execute all report formats and
upload the results even when an assertion fails. For an unauthenticated API,
remove `--auth-secret-id api` and `FAULTORA_SECRET_API` from the workflow.

## Credentials

Faultora accepts a secret handle rather than a token on the command line. A
handle is mapped to an environment variable with the `FAULTORA_SECRET_` prefix:

```bash
export FAULTORA_SECRET_PAYMENTS_API='replace-with-a-real-token'

java -jar faultora-cli/target/faultora-0.2.0-SNAPSHOT.jar \
  test \
  --scenario scenario.yaml \
  --openapi openapi.yaml \
  --target https://api.example.com \
  --auth-secret-id payments-api
```

For `payments-api`, Faultora reads `FAULTORA_SECRET_PAYMENTS_API`. Secret values
are never written to scenario files or normal diagnostic output. Apache
HttpClient header and wire logging is disabled in the release configuration.

## Exit codes

| Code | Meaning |
|---:|---|
| `0` | All tests passed |
| `1` | A scenario assertion failed |
| `2` | Invalid scenario or CLI configuration |
| `3` | Runner or infrastructure failure |

## Security model

The default policy is intentionally restrictive:

- private and special-purpose network ranges are rejected;
- every redirect hop is checked and pinned independently;
- HTTPS-to-HTTP redirects are rejected;
- credentials are removed on cross-origin redirects;
- HTTP responses are read with a hard byte limit;
- response bodies and headers are held in memory only when required by the
  active evidence policy; the CLI run journal stores evidence digests, not raw
  response bodies;
- authentication and cookie headers are filtered from captured evidence;
- configured redaction fails closed when content cannot be safely processed.

See [Security architecture](docs/SECURITY.md) for the threat model and trust
boundaries. Please report vulnerabilities through GitHub's private security
advisory flow; do not open a public issue with exploit details.

## Project documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Security architecture](docs/SECURITY.md)
- [Scenario reference](docs/SCENARIO_REFERENCE.md)
- [Delivery roadmap](docs/ROADMAP.md)
- [Changelog](CHANGELOG.md)

## License

Apache License 2.0. See [LICENSE](LICENSE).
