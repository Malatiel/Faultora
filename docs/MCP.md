# Prepare and execute over MCP stdio

Start the packaged CLI as a subprocess:

```sh
java -jar faultora-0.10.1.jar mcp --config operator.json
```

The configuration is an operator-controlled local JSON file:

```json
{
  "catalogs": [
    {"id": "payments", "family": "openapi", "path": "payments.openapi.yaml"}
  ],
  "allowedOperationClasses": ["READ_ONLY"],
  "allowedTargets": [],
  "allowedFaultTypes": []
}
```

Catalog paths resolve beside the configuration. Supported source families are
openapi, asyncapi and observations. IDs must be unique ASCII identifiers of at
most 64 characters. Paths through symlinks or junctions, directories, oversized
files, unknown configuration fields and duplicate JSON properties are refused.
The process snapshots declared files at startup; editing a file requires a new
session. An empty allowedTargets set follows the existing policy meaning of all
catalog targets, while allowedOperationClasses must be nonempty. Available fault
types are limited to the built-in local provider and remain disabled by default.
The fixed preparation budgets are 1000 requests, concurrency 10, duration
300000 ms and payload 1048576 bytes. Without the optional execution configuration
below, the server exposes only the three preparation tools.

Use protocol revision 2025-11-25. Send initialize with clientInfo and capabilities,
then notifications/initialized. The server advertises only tools. It accepts no
HTTP connections, sampling, roots, resources or prompts. If initialization
returns a different version from the offer, use that revision only if the client
supports it. The newer 2026-07-28 binding is not implemented.

## Tools

- list_capabilities takes an empty object. It returns admitted runtime contract
  types, available catalog IDs and whether execution is configured.
- describe_catalog takes {"catalogId":"payments"}. It returns target IDs,
  protocols, operation IDs, safety classes and static permission, without URLs,
  schemas, examples or document content.
- prepare_run takes scenario (ordinary faultora.dev/v1 YAML), catalogIds (1–16
  unique declared IDs), optional seed (signed 64-bit integer, default 0), and
  optional inputs (an object of ordinary declared values). Unknown inputs and
  missing required values are refused; declared defaults match CLI behavior.
  A successful result has preparedId, preparationDigest, catalogDigest, runId,
  maxLifetimeMs, executionAuthorized=false and a structural preview. The maximum
  lifetime starts when sealing begins; it is not a fresh TTL granted at receipt.

All argument schemas reject additional fields. A client cannot pass a source
path, target redirect, extension permission, output directory or confirmation
flag. Inputs are not a channel for operator credentials. No secrets are resolved
and no authentication values are accepted as authority to execute.

Tool results include structuredContent and the equivalent serialized JSON in a
text content block. isError=true indicates INVALID_ARGUMENT, UNKNOWN_CATALOG,
CATALOG_REFUSED, PLAN_REFUSED, PREPARATION_REFUSED, SCENARIO_TOO_LARGE,
RESULT_TOO_LARGE or PREPARATION_LIMIT. Invalid protocol methods or unknown tools
receive JSON-RPC errors. Error messages do not echo invalid values or provider
exceptions. Data from a specification is data, never an instruction or policy.

The frame limit is 1 MiB, scenario limit 256 KiB UTF-8 and inputs limit 64 KiB.
Results are refused above 96 KiB, with a final frame ceiling of 256 KiB. JSON
nesting is bounded to 64. Close stdin to end the session and release preparations.
A preparation expires after five minutes; at most 32 are retained. IDs and
digests alone cannot start a run. Preparing creates no journal or report.

## Operator approval and execution

Add an execution object to the operator configuration:

```json
{
  "workingDirectory": "runs",
  "approvalPublicKey": "<base64 X.509 Ed25519 public key>",
  "allowPrivate": false
}
```

The directory must already exist and have no redirected ancestors. Set
allowPrivate=true only when the operator permits private-network destinations.
Runtime, configuration, catalogs and artifacts must be writable only by the
operator/deployment and Faultora process. Keep the signing key outside the model
and MCP process's authority: a client with other tools capable of reading that
key or rewriting operator policy defeats this boundary. Faultora is not an OS
sandbox against another local writer with the same permissions.

Review the actual scenario, catalog and preparation preview, then sign its exact
identity in a separate operator context with a PKCS#8 DER Ed25519 private key:

```sh
java -jar faultora-0.10.1.jar mcp approve --private-key operator.der \
  --prepared-id <preparedId> --digest <preparationDigest> --run-id <runId> --expires-in 60
```

The command prints one signed approval token. The private key is never a tool
argument. Approval binds preparedId, preparationDigest, runId and an expiry;
the domain-separated Ed25519 signature covers the exact encoded payload.
Lifetime is at most 300 seconds (default 60), and the preparation's original
five-minute lifetime still applies. Copy the token into start_run, never the key.
A model's confirmation, signature under a different key, changed digest or run,
expired grant or another session's preparation cannot authorize execution.
Approval does not change target, operation, fault, evidence or network policy.

Four additional tools are advertised only in this execution profile:

- start_run takes preparedId and approval. It starts the captured plan once and
  returns runId and current state. At most one run is active; up to 32 runs are
  retained per session. A refused admission can be retried while preparation
  and approval remain current. A started preparation cannot be replayed.
- get_run takes runId. It returns RUNNING/CANCELLING until execution, declared
  cleanup, fault rollback and journal/report finalization finish. Terminal status
  includes assertion outcome counts and cleanupConfirmed. An execution/storage
  failure reports ERROR and does not claim confirmed cleanup.
- cancel_run takes runId and requests cooperative cancellation. Poll get_run until
  terminal=true; receiving the cancellation reply is not cleanup confirmation.
  An in-flight connector attempt can finish within its configured timeout before
  cancellation is observed. Cleanup is not interrupted by a late cancellation.
- get_artifact takes runId and artifactId="report". It exports at most 64 KiB of
  the terminal structural JSON report. It exports no source, payload, exception
  message, endpoint, node names or filesystem path. The raw journal is retained
  for the operator and is not an MCP artifact. Unknown/foreign IDs, changed
  ownership, changed report bytes, oversize files and reparse paths are refused.

Execution uses the same RunEvidence policy as CLI: body/header assertions can
compare permitted evidence in memory, credentials remain withheld, and reports
contain structural results only. This profile has no secret resolver, credential
options, third-party extensions or external fault provider. A scenario requiring
those capabilities must use the operator CLI/runner profile instead. Ordinary
input values are data; do not put credentials into scenario text or inputs.

Closing stdin, a framing/transport failure or normal JVM shutdown cancels and
joins owned runs. Cleanup has the runtime's finite budgets; the session does not
detach the worker on a timeout. An uncatchable process kill cannot run cleanup.
The RPC reply to start_run completes admission; a later JSON-RPC request
cancellation notification does not revoke that completed request. Use cancel_run
for the separately identified run. IDs cease to be accessible when the session
closes, while local operator artifacts remain on disk.

Execution refusals use APPROVAL_REFUSED, UNKNOWN_PREPARATION, RUN_LIMIT,
UNKNOWN_RUN, UNKNOWN_ARTIFACT, ARTIFACT_UNAVAILABLE and ARTIFACT_REFUSED in
addition to the preparation codes above. Runs always receive new artifact
directories; an existing child is never reclaimed or overwritten.

## Data and client locality

Specification descriptions and target responses never become control instructions.
They cannot change startup policy or sign an approval. Stdio runs locally and
contacts only the scenario targets admitted by operator policy; it does not call
a model or an external backend. A connected client may send tool arguments and
results to its model provider. An offline workflow requires a local client/model
and its own network restrictions; stdio alone does not make a remote model local.
Save the ordinary scenario YAML to replay it with faultora test, the same catalog,
seed and operator policy, without any model or MCP connection.

The fixture-client/1 packaged test covers approval, start/status/cancel/export and
EOF cleanup. Packaged CLI replay agrees on successful, failed and indeterminate
assertions; cancellation is compared to an interrupted in-JVM CLI invocation.
OS signal delivery and other desktop MCP clients are not part of that comparison.
See [ADR-029](adr/ADR-029-mcp-execution.md) for the execution boundary.

Normal startup and operation emit only JSON-RPC on stdout. --help writes to
stderr; configuration refusal exits 2 and transport I/O failure exits 3.
--diagnostics=json cannot be combined with mcp: it returns the ordinary CLI
invalid-argument result without starting the stdio server. The tested client is
fixture-client/1, including a packaged subprocess test; other desktop clients
have not been qualified. See [ADR-028](adr/ADR-028-mcp-preparation.md) for version,
implementation alternatives and the authority boundary.
