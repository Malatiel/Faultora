# Troubleshooting a first run

The failures a new scenario usually meets, by where they happen. Each one
names what you see, the code `--diagnostics=json` reports, and what to change.
Exit codes stay as documented in the README: 1 means the scenario ran and
something failed, 2 means the configuration was refused before anything ran,
3 means the command itself could not do its job.

A path that works as generated, for an API with an OpenAPI description
(`faultora` stands for `java -jar faultora-<version>.jar`):

```bash
faultora init --from-openapi openapi.yaml --output faultora
faultora validate --scenario faultora/scenario.yaml --openapi openapi.yaml
faultora test --preview --scenario faultora/scenario.yaml --openapi openapi.yaml
faultora test --scenario faultora/scenario.yaml --openapi openapi.yaml \
  --target http://localhost:8080 --allow-private --format console,html
```

`init` generates steps only for operations that can run with nothing filled
in. Operations that need a parameter or a request body, or that are destructive, are listed
at the top of the file as comments saying what they need; turn them into steps
once you have decided what to give them.

## Before anything runs

**The documents do not import** — `CATALOG_IMPORT_FAILED`, exit 2. The
message names the document family and what the importer refused: an unknown
`apiVersion`, a document that is not OpenAPI 3.x or AsyncAPI 3.0, or two
documents declaring the same operation, target or schema. `validate` with the
same `--openapi`, `--asyncapi` and `--observations` options reports this
without running anything.

**An importer accepted a document but warned** — `CATALOG_WARNING`, exit
unchanged. A server without a host, a channel without an address or the
deprecated observation version `faultora.dev/v1alpha1` is skipped or still
read. The human output prints the importer's own words; the machine output
names only the family.

**The plan is refused** — `PLAN_REFUSED`, exit 2, before any request:

- *"Operation … with safety DESTRUCTIVE is not allowed by execution policy"*:
  the step would delete or overwrite something. Add `--allow-destructive`, or
  remove the step.
- *"Fault type is not allowed by execution policy"*: network faults need
  `--toxiproxy-url`; in-process HTTP faults are available without it.
- *"Plan requires N requests (connector attempts), policy allows M"*: repeats,
  retries and polls multiply the requests a plan can make, and the total is
  bounded before the run starts. Lower the counts, attempts or polls.

`validate` checks the documents; `test --preview` also compiles the plan against
the same execution policy as a real run, without contacting the target. Run
both before the first real run, and in CI on every pull request.

## While it runs

**Every step fails against localhost** — `DESTINATION_BLOCKED`, exit 1. Private
and loopback addresses are refused unless the run permits them, so a local
service needs `--allow-private`. With it set, the message says the address is
outside the policy even so — for example a redirect to a disallowed scheme.

**A step that reads one item fails at once** — `PARAMETER_MISSING`, exit 1.
The operation requires a parameter the step does not supply: a path parameter
such as `{paymentId}`, or a bind variable such as `:paymentId` in an
observation's SQL. Nothing is sent. Give it under the step's inputs, usually
from an earlier step:

```yaml
  - id: read-payment
    operationId: get-payment
    inputs:
      paymentId: "{{steps.create-payment.body.id}}"
```

**An assertion is `INDETERMINATE`** — `ASSERTION_INDETERMINATE`, exit 1. The
assertion had no usable evidence: a body that was not captured or was
truncated, rows beyond the observation's limit. It is not a pass. Check the
evidence settings and limits rather than the system under test.

**An assertion fails** — `ASSERTION_FAILED`, exit 1. This is the result the
scenario exists to produce. The report names the assertion; the journal
(`events.ndjson`) holds what was observed.

Provider details — response bodies, exception text, addresses — are withheld
from reports and diagnostics by the export policy. The codes above are what is
safe to say; the journal in the output directory is where to look next.

## A runner that cannot start

`Could not register:` is followed by the reason — the connection was refused,
the host name does not resolve, a timeout, the TLS handshake failed, or the
answer was not the runner protocol — with what to check. See the README section
on running inside a private network for the options involved.

## In CI

[`examples/github-actions/faultora.yml`](../examples/github-actions/faultora.yml)
runs `validate` and `test --preview` on pull requests, with no credentials and
no request to the target, and the full run on the main branch. A refused plan
therefore fails the pull request rather than the first deployment that runs it.

## Reporting a problem

Attach a diagnostic bundle rather than the run directory:

```bash
java -jar faultora.jar diagnose --run results/run --output faultora-diagnostics.zip
```

It holds the run's journal with each event reduced to identifiers, counts,
durations, outcomes and digests, and a manifest naming what was read, its
SHA-256 and which fields were withheld. Assertion messages, skip reasons,
error messages, the resolved configuration, target scopes and channel names
are left out, since they can quote values or name things inside your network;
of an error only its category and code remain. Nothing else in the directory
is read, so a key, an environment file or a report beside the journal never
reaches the bundle. A journal that is a link or lies outside the directory,
or one over 16 MiB, is refused. `--health <file>` adds a runner's status file,
reduced to its state, identifier, registration and counters.