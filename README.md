# Faultora

**Break it here. Trust it everywhere.**

Faultora is a self-hosted reliability testing CLI for HTTP APIs. It imports
OpenAPI descriptions, runs repeatable scenarios, checks technical and business
invariants, and produces console, JSON, HTML, and JUnit reports. Execution stays
inside your infrastructure and does not require a hosted control plane or
telemetry.

Version 0.1.0 is the first runnable release candidate. It targets local
development and CI use on Java 21.

## What 0.1.0 includes

- OpenAPI 3.x import and operation discovery;
- versioned YAML scenarios;
- HTTP GET, POST, PUT, PATCH, DELETE, HEAD, and OPTIONS operations;
- status, header, JSONPath, and duration assertions;
- sequential, parallel, repeat, and eventual execution blocks;
- environment-backed bearer-token resolution;
- console, JSON, HTML, and JUnit reports;
- SSRF protection with DNS resolution and address pinning;
- bounded HTTP response streaming;
- metadata-only evidence capture by default;
- header filtering, content-type allowlists, body limits, and JSON redaction;
- manual redirect handling with cross-origin credential stripping.

Distributed workers, Kafka, Kubernetes orchestration, and the web interface are
not part of this release.

## Requirements

- Java 21 or newer;
- Maven is not required when using the release JAR.

## Build

```bash
./mvnw verify -B
```

The executable artifact is written to:

```text
faultora-cli/target/faultora-0.1.0.jar
```

## Quick start

Check the executable and validate the example scenario:

```bash
java -jar faultora-cli/target/faultora-0.1.0.jar --version

java -jar faultora-cli/target/faultora-0.1.0.jar \
  validate \
  --scenario examples/payment-service/scenarios/passing.yaml
```

Generate a starter scenario from an OpenAPI document:

```bash
java -jar faultora-cli/target/faultora-0.1.0.jar \
  init \
  --from-openapi examples/payment-service/openapi.yaml \
  --output ./generated
```

Run a scenario against an API:

```bash
java -jar faultora-cli/target/faultora-0.1.0.jar \
  test \
  --scenario examples/payment-service/scenarios/passing.yaml \
  --openapi examples/payment-service/openapi.yaml \
  --target https://api.example.com \
  --format console,json,junit,html \
  --output faultora-results
```

Private, loopback, and link-local destinations are blocked by default. Use
`--allow-private` only for an explicitly trusted local test environment.

## Credentials

Faultora accepts a secret handle rather than a token on the command line. A
handle is mapped to an environment variable with the `FAULTORA_SECRET_` prefix:

```bash
export FAULTORA_SECRET_PAYMENTS_API='replace-with-a-real-token'

java -jar faultora-cli/target/faultora-0.1.0.jar \
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
- response bodies and headers are not captured unless enabled by policy;
- configured redaction fails closed when content cannot be safely processed.

See [Security architecture](docs/SECURITY.md) for the threat model and trust
boundaries. Please report vulnerabilities through GitHub's private security
advisory flow; do not open a public issue with exploit details.

## Project documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Security architecture](docs/SECURITY.md)
- [Delivery roadmap](docs/ROADMAP.md)
- [Changelog](CHANGELOG.md)

## License

Apache License 2.0. See [LICENSE](LICENSE).
