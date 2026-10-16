# ADR-028: MCP inspection and preparation stay within operator authority

## Status

Accepted

## Context

Catalog descriptions and immutable preparation already have a shared runtime
boundary. A tool adapter must preserve that boundary: source documents, tool
arguments and a client's assertion of confirmation cannot grant filesystem,
target, extension or execution permissions.

## Decision

Provide the MCP revision `2025-11-25` tools subset on stdio. The client launches
`faultora mcp --config operator.json`; there is no listener, backend, model API
or implicit outbound connection. Standard output carries only newline-delimited
UTF-8 JSON-RPC; startup diagnostics go to stderr. Initialization, the initialized
notification, ping, tools/list and tools/call are implemented. Unsupported offers
receive the supported revision during initialization; the client must disconnect
if it cannot use it. No compatibility with the newer `2026-07-28` wire binding is
advertised.

The official specifications and Java SDK release were inspected on 2026-10-16:

- [2025-11-25 stdio transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [Initialization and negotiation](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)
- [Tool schemas, results and error handling](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
- [Newer specification](https://modelcontextprotocol.io/specification/2026-07-28)
- [Official Java SDK 2.0.1](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.1)

Use a small Java adapter over the existing Jackson dependency in faultora-mcp.
The official SDK is a viable alternative, including bounded stdio transport in
2.0.1, but brings a broader reactive client/server and schema-validation surface.
Three synchronous local tools need neither those transports nor client features.
This choice makes the supported subset explicit and keeps the build dependency
set unchanged. The fixtures and packaged subprocess test exercise the wire
boundary; replacing it with an SDK later must preserve those tests. This is not
a claim of implementing the complete MCP specification.

The tested client is `fixture-client/1`, including the packaged Java subprocess
driver. Other clients must support revision 2025-11-25, newline stdio, tool
discovery and content results. Desktop-client qualification and execution parity
remain future work; no named external application is claimed as verified.

## Authority and data

The operator supplies exact catalog IDs, source families and files at process
startup. Relative files resolve beside the operator configuration. Only regular
files with no redirected ancestor are accepted; catalogs are read into bounded
snapshots once. Tools can name declared IDs, never paths, URLs or importer
plugins. Only built-in extensions are admitted. Operation classes, target IDs
and local fault types come from operator configuration; tool arguments cannot
widen them. Default operation admission is READ_ONLY with no faults.

`list_capabilities` and `describe_catalog` use the shared RuntimeCapabilities
and CatalogDescription projections. `prepare_run` accepts ordinary scenario
YAML and ordinary declared input values, applies shared defaults/required-input
checks, validates through RunPreparation and seals through PreparedRun. Like the
CLI, it validates before sealing a second private compilation. Preparation does
not open connectors, construct fault providers, resolve secrets or allocate
artifact ownership. It exports a structural preview and identities, not source
text, endpoint URLs, input values or provider exception text.

A prepared ID belongs only to this stdio session. Its private plan is usable
for at most five minutes, subject to the runtime monotonic expiry check; expired
entries are discarded on the next preparation. EOF releases all entries. At
most 32 preparations are retained. The preparation digest binds the runtime's
captured inputs, run identity and effective policies, but neither it nor the ID
is an execution approval. There is no start_run tool in this slice. A future
execution adapter must verify operator authority over that exact preparation.

## Bounds and consequences

Configuration is at most 64 KiB; at most 16 catalog files, each 1 MiB, total
4 MiB. Input frames are at most 1 MiB; scenarios are at most 256 KiB UTF-8 and
input objects 64 KiB. JSON nesting is at most 64. Structured results are limited
to 96 KiB and complete response frames to 256 KiB. An oversized result is refused
whole; it is never exported as a partial catalog or successful preparation.
These are application bounds, not an OS memory or CPU sandbox.

Malformed JSON, duplicate keys, invalid UTF-8, batches and invalid IDs receive
sanitized protocol errors. An oversized or incomplete frame terminates the
connection without draining arbitrary input. Unexpected I/O failure is a
transport failure. Invalid tool arguments, unknown catalogs and preparation
refusals return bounded isError results. Notifications do not trigger tools.
Synchronous preparation has no background run to abandon; sent-request timeouts,
cancellable execution and durable run ownership are separate execution work.

Rejected alternatives: forwarding arbitrary file paths would delegate the
operator's filesystem authority; advertising execution before approval exists
would confuse a preview with permission; HTTP would add a listener and another
authentication boundary; silently claiming the newest revision would conceal
unimplemented lifecycle and transport changes. A preparation-only stdio subset
keeps each of these decisions explicit.
