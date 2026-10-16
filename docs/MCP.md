# Inspect and prepare over MCP stdio

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
300000 ms and payload 1048576 bytes. These describe possible execution bounds;
this interface cannot execute anything.

Use protocol revision 2025-11-25. Send initialize with clientInfo and capabilities,
then notifications/initialized. The server advertises only tools. It accepts no
HTTP connections, sampling, roots, resources or prompts. If initialization
returns a different version from the offer, use that revision only if the client
supports it. The newer 2026-07-28 binding is not implemented.

## Tools

- list_capabilities takes an empty object. It returns admitted runtime contract
  types, available catalog IDs and executionAvailable=false.
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
digests are not portable authorization tokens and cannot start a run. No journal
or report is created by these tools.

Normal startup and operation emit only JSON-RPC on stdout. --help writes to
stderr; configuration refusal exits 2 and transport I/O failure exits 3.
--diagnostics=json cannot be combined with mcp: it returns the ordinary CLI
invalid-argument result without starting the stdio server. The tested client is
fixture-client/1, including a packaged subprocess test; other desktop clients
have not been qualified. See [ADR-028](adr/ADR-028-mcp-preparation.md) for version,
implementation alternatives and the authority boundary.
