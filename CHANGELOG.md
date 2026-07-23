# Changelog

All notable changes to Faultora are documented in this file.

## Unreleased

### Added

- Copy-ready GitHub Actions integration example.
- Complete `faultora.dev/v1alpha1` scenario and assertion reference.
- Verified console and HTML report examples in the project README.
- End-to-end coverage for all report formats and JSON response assertions.

### Fixed

- Duration assertions now enforce both bounds when `min` and `max` are set.
- Console and HTML reports preserve assertion outcomes and messages emitted
  before node completion.
- Reusing an output directory no longer mixes the new run with an existing
  event journal.
- Evidence-capture documentation now reflects the CLI's active policy.

## 0.1.0 — 2026-07-23

First runnable release candidate.

### Added

- Java 21 CLI with `init`, `discover`, `validate`, and `test` commands.
- OpenAPI 3.x import and versioned YAML scenarios.
- HTTP connector, core assertions, execution engine, and report renderers.
- Environment-backed bearer-token resolution.
- Executable release JAR with merged service-provider metadata.
- End-to-end payment-service fixture and CI test suite.

### Security

- DNS-aware SSRF policy and per-request address pinning.
- Redirect-hop validation, downgrade rejection, and cross-origin secret removal.
- Bounded response streaming with a hard payload limit.
- Fail-closed credential resolution and reusable request-scoped secret copies.
- Policy-bounded evidence capture, sensitive-header filtering, content-type
  allowlists, JSON-path redaction, and bounded evidence storage.
- Apache HttpClient wire and header logging disabled.

### Known scope limits

- HTTP APIs only.
- Local single-process execution.
- Environment variables are the only built-in secret provider.
