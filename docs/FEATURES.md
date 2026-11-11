# What Faultora includes

Everything below is in the 0.13.0 release. The [README](../README.md) shows how
to start; this page lists what is there once you have.

Scenario execution:

- a frozen document format, `faultora.dev/v1`: within 1.x it may only gain
  something an earlier 1.x would have ignored, and the rule is enforced by a
  committed snapshot rather than promised in a document;
- `faultora migrate` moves documents written against the preview version, which
  is still read — with a warning, never an error — until 2.0;
- OpenAPI 3.x and AsyncAPI 3.0 import, together in one run;
- versioned YAML scenarios with runtime inputs (`--input key=value`);
- HTTP GET, POST, PUT, PATCH, DELETE, HEAD, and OPTIONS operations;
- step output binding: later steps reference earlier responses through
  `{{steps.<name>.body...}}` templates;
- bounded parallel groups for genuinely concurrent requests;
- repeat groups: a fixed count or a literal item list, with `{{repeat.index}}`
  and `{{repeat.item}}` bound per iteration;
- eventually (poll-until) groups that converge on asynchronous state and fail
  with a spent budget instead of hanging;
- request values generated from the operation's schema, deterministically from
  the run seed, with explicit inputs applied over them;
- retry policies with exponential backoff and deterministic seed-derived
  jitter;
- sequential operation and wait steps with explicit dependencies;
- per-step, per-group, and scenario-wide deadlines, bounded by the
  execution policy's wall-clock budget;
- Kafka operations: publish a command, observe the events it caused, within a
  window bounded below by the run's own start and above by a wait the execution
  policy caps;
- database observations: read-only queries an operator declares in an
  observation catalog, parameterized, bounded at the driver, and named by a
  scenario the way any other operation is;
- status, header, response-schema, JSONPath, and duration assertions;
- event assertions: count, uniqueness, correlation continuity, and ordered or
  unordered sequences;
- tabular assertions: row count, row value, numeric balance, and uniqueness —
  the ledger invariant among them;
- assertion parameters are expressions, so an assertion compares a value one
  step produced against a value another one did. That is what makes a
  cross-component invariant expressible without a compound assertion type;
- console, JSON, HTML, and JUnit reports; the HTML report can be filtered by
  node, fault and status offline, and loads or runs nothing but its own code;
- per-run budgets that hold while the run is running: one deadline across
  retries, polls and redirects, a finite cleanup budget, outgoing HTTP and
  Kafka payload limits, and connector attempts counted against `maxRequests`;
- `test --preview` shows the compiled plan without contacting a target, and
  `--diagnostics=json` gives scripts one versioned result per command;
- `validate` imports the catalogs a run would use, so a catalog `test` would
  refuse is refused before anything runs, and importer warnings — a skipped
  server, a deprecated version — are reported instead of dropped;
- `init` writes a starter scenario that runs as generated, and a first run
  against localhost, or with an ID left out, says which flag or input to add;
  [troubleshooting](TROUBLESHOOTING.md) walks through each failure class;
- ZIP and tar.gz archives with Windows and Linux launchers, using an existing
  Java; see [Installing the CLI](INSTALLATION.md);
- a [paired qualification](../examples/payment-service/qualification/README.md)
  example: one frozen scenario run against a healthy and a broken payment
  service, telling them apart without revealing which is which.

Fault injection:

- in-process faults with no external dependencies: `http-latency`,
  `http-error`, and `http-response-loss`, with a hard-expiry watchdog and
  guaranteed exactly-once rollback;
- real network faults through a [Toxiproxy](https://github.com/Shopify/toxiproxy)
  instance (`--toxiproxy-url`): `network-latency`, `network-timeout`,
  `network-reset`, and `network-bandwidth`;
- `expectError` steps for requests that are supposed to fail under a fault;
- fault windows and fault-to-node attribution in console and HTML reports.

Local tool integration:

- [MCP stdio](MCP.md) for capabilities, operator-declared catalog inspection
  and scenario preparation. Optional execution requires a separate operator
  signature on the exact preparation; owned runs support cancellation and
  bounded structural report export. Results name each assertion by node ID,
  type and outcome, including checks that were skipped or never evaluated,
  and a refused preparation names the field to fix.

Operations and support:

- `faultora diagnose` writes a bundle to attach to a bug report: the run's
  journal reduced to identifiers, counts, outcomes and digests, and nothing
  else from the run directory ([troubleshooting](TROUBLESHOOTING.md));
- `FAULTORA_LOG_FORMAT=json` writes structured logs a collector can read,
  naming the run and node and holding no payload;
- an [upgrade and rollback runbook](UPGRADE_ROLLBACK.md) and a measured
  [capacity baseline](CAPACITY.md).

Private-network execution:

- `faultora runner` — the same binary serving a control plane from inside a
  network nothing can reach into, dialling out for work over mutually
  authenticated TLS;
- runs bounded by a lease the runner renews by asking, so losing contact stops
  a run and rolls back its faults rather than extending either;
- every event and the final result committed to a local spool before they are
  sent, so a disk failure cannot report a passing run
  ([runner spool](RUNNER_SPOOL.md));
- opt-in [protocol 2 delivery](RUNNER_PROTOCOL.md#version-2-durable-delivery)
  resumes from a verified checkpoint after restart. Operators can retire
  acknowledged journals while preserving the terminal receipt;
- a signed execution and evidence policy, verified against a key the operator
  configures, narrowed by limits the deployment states and a dispatch can never
  widen;
- `faultora health` — a status file and a command to read it, for a platform
  probing a process that has no port;
- a distroless image, Compose and Kubernetes examples, and an offline
  installation bundle in [deploy/](../deploy/README.md).

Security posture:

- SSRF protection with DNS resolution and address pinning;
- bounded HTTP response streaming;
- policy-bounded in-memory evidence for assertions;
- header filtering, content-type allowlists, body limits, and JSON redaction;
- manual redirect handling with cross-origin credential stripping;
- environment-backed bearer-token resolution;
- extension trust decided before any plugin code runs: built-ins are an exact
  class inventory, and a third-party plugin needs a valid manifest;
- results, journals, reports and diagnostics export fixed error text and
  structural facts, never values the evidence policy withholds;
- release files and runner images carry a GitHub attestation, and the
  installation commands check who built a download, not only that it arrived
  intact; a dependency finding at CVSS 7.0 or above blocks a release.

The in-process fault provider acts only on Faultora's own outbound requests.
It never touches the target system, its infrastructure, or other traffic, so
no extra privileges are needed. Network faults require a Toxiproxy you already
operate on the traffic path.

Distributed workers, Kubernetes orchestration, and the web interface are not
part of this release. Scenarios that request unsupported execution features are
rejected during validation or plan compilation rather than being silently
accepted.
