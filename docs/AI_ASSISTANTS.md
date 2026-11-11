# Using Faultora with an AI assistant

An AI assistant can write and run Faultora scenarios for you through the
[MCP server](MCP.md) built into the CLI. [MCP.md](MCP.md) is the full
reference for every tool, field and limit.

## What the assistant can do over MCP

- Inspect declared API descriptions, see which operations and faults the
  operator permits, and prepare scenarios. Faultora compiles each scenario
  against the operator's policy and returns a preview or a refusal with
  diagnostic codes and paths.
- Start a run through the configured server with an approval token that the
  operator signed for that exact preparation.
- Read run status, counts and assertion outcomes. MCP withholds response
  bodies, compared values, credentials, endpoint URLs and provider error text.
  A `PASS` or `FAIL` can still reveal whether a response value met the
  assertion's condition.

## Set it up (operator)

You need the CLI release, Java 21, the API description, and a test
environment the run may reach.

**1. A key for approving runs.** Use an `openssl` executable with Ed25519
support. Generate the key in an operator-only location:

```bash
openssl genpkey -algorithm ed25519 -outform DER -out operator.der
openssl pkey -inform DER -in operator.der -pubout -outform DER | base64
```

The second command prints the public key for the configuration below. If
your shell lacks `base64`, replace `| base64` with `-out operator.pub.der`.
In PowerShell, encode the resulting file with the following commands.

```powershell
$publicKeyPath = (Resolve-Path -LiteralPath 'operator.pub.der').ProviderPath
[Convert]::ToBase64String([IO.File]::ReadAllBytes($publicKeyPath))
```

**2. An operator configuration** beside the API description:

```json
{
  "catalogs": [
    {"id": "orders", "family": "openapi", "path": "orders.openapi.yaml"}
  ],
  "allowedOperationClasses": ["READ_ONLY", "MUTATING"],
  "allowedTargets": [],
  "allowedFaultTypes": ["http-latency", "http-error", "http-response-loss"],
  "execution": {
    "workingDirectory": "runs",
    "approvalPublicKey": "<the base64 public key>",
    "allowPrivate": true
  }
}
```

- Paths resolve beside this file, and `runs` must already exist.
- An empty `allowedTargets` list permits every target in the declared catalogs,
  subject to the rest of the policy.
- Add `"DESTRUCTIVE"` to the operation classes only if scenarios may delete
  what they create; most cleanup steps need it.
- `allowedFaultTypes` lists what a scenario may inject; an empty list permits
  no faults. The assistant sees this list, so it knows faults are available.
- `allowPrivate` is needed for localhost and other private addresses.
- The MCP profile has no credential resolver: it suits a test environment
  that needs no secrets, or one reachable without them.

The run's target is the server URL in the API description; MCP cannot
redirect it. Point that URL at the test environment.

**3. Connect the assistant.** The server is a subprocess speaking MCP over
stdio:

```bash
java -jar /path/to/faultora-0.13.0.jar mcp --config /path/to/operator.json
```

For Claude Code, put this in `.mcp.json` in the folder the assistant works in:

```json
{
  "mcpServers": {
    "faultora": {
      "command": "java",
      "args": ["-jar", "/path/to/faultora-0.13.0.jar", "mcp", "--config", "/path/to/operator.json"]
    }
  }
}
```

For the Codex CLI:

```bash
codex mcp add faultora -- java -jar /path/to/faultora-0.13.0.jar mcp --config /path/to/operator.json
```

Use absolute paths, including the path to `java` if the client does not
inherit your `PATH`. The packaged compatibility tests use `fixture-client/1`.
Verify the connection with your chosen client.

Keep `operator.der` under a separate operator identity or on another device.
Protect the MCP client launch settings, `operator.json`, catalog files and the
server runtime from changes by the assistant. If the test environment must be
reachable only through approved runs, restrict the assistant's other tools
and processes, including shell commands, from reaching the targets. See
[operator approval and execution](MCP.md#operator-approval-and-execution).

## The approval loop

1. The assistant calls `prepare_run` with a scenario and gets back
   `preparedId`, `preparationDigest`, `runId` and a preview.
2. It asks you to approve, giving those three values.
3. Review the scenario in the actual `prepare_run` request and the preview in
   the server response, including operations, request count and faults. If you
   agree, sign with the IDs and digest from that response.

   ```bash
   java -jar faultora-0.13.0.jar mcp approve --private-key operator.der \
     --prepared-id <preparedId> --digest <preparationDigest> --run-id <runId> --expires-in 300
   ```

4. You give the assistant the printed token, and it calls `start_run`, then
   `get_run` until the run is terminal.

A preparation expires five minutes after it was made, and a token after at
most 300 seconds, so approve promptly or let the assistant prepare again.

## Reading the results

When Faultora produces a run summary, the terminal `get_run` response and
the `report` artifact, when available, carry these fields.

- `status` is `PASSED`, `FAILED`, `ERROR` or `CANCELLED`.
- `assertions` entries have a `nodeId`, an `outcome` and, when known, an
  `assertionType`. Evaluated outcomes are `PASS`, `FAIL` and `INDETERMINATE`.
  A `SKIPPED` entry has `reasonCode: DEPENDENCY_NOT_PASSED`. A `NOT_EVALUATED`
  entry has a reason code such as `ASSERTION_REFUSED`, `INPUTS_UNRESOLVABLE`,
  `DEADLINE_EXCEEDED` or `RUN_STOPPED`.
- Assertion counts cover evaluated checks across the entire run.
  `omittedAssertionCount` is the number of checks beyond the size limit of
  the `assertions` list, usually zero.
- `cleanupConfirmed` records whether cleanup and fault rollback are known to
  have finished.

If execution fails before Faultora can produce a summary, the terminal
response carries `status: ERROR`, a code such as `EXECUTION_FAILED` and
`cleanupConfirmed: false`.

To request cancellation, call `cancel_run` with the `runId` and keep polling
`get_run` until `terminal: true`, then read `cleanupConfirmed`.

The verdict carries no explanation text, so **name each assertion after the
promise it checks** — `seats-never-exceed-capacity`, not `check-3`. That name
is how you and the assistant tell what failed.

## What trips assistants up

From a trial in which an assistant with no prior knowledge of Faultora found
a planted defect in an unfamiliar service through this server:

- **Check HTTP status with an assertion.** By default, any response the
  connector accepts completes the step, including `409` and `500`. With
  `expectError: true`, the step needs a normalized error, such as a timeout
  or an injected fault. [Scenario reference](SCENARIO_REFERENCE.md#operation-steps).
- **Races need `parallel`.** A rule like "never more registrations than
  seats" usually holds one request at a time and breaks only when requests
  overlap; put the competing requests in a `parallel` group and check the
  state afterwards.
- **Read the refusal's code and path.** A refused preparation names the
  field, such as `execute[0].steps[3].params`, with a code such as
  `SCENARIO_UNKNOWN_FIELD`; change that field rather than guessing.
- **Check `operatorPolicy` first.** `list_capabilities` says which operation
  classes and fault types this operator permits.
- **Check the shape before preparing.** The trial assistant read the
  documentation through a web fetch that paraphrased its YAML examples; the
  [JSON Schema](schema/scenario-v1.schema.json) of the format gives exact
  field names and types to check a draft against.
- **Keep the YAML.** To replay a scenario with `faultora test` in CI, use the
  same catalog, target and seed, and match the MCP run's execution options,
  including permissions for private targets or destructive operations.

## A brief to give the assistant

```text
Faultora is connected as the MCP server "faultora". Use it to check whether
<service> keeps <promise>. Start with list_capabilities and describe_catalog.
Write faultora.dev/v1 scenarios (reference: docs/SCENARIO_REFERENCE.md;
shape: docs/schema/scenario-v1.schema.json). Use prepare_run. When one
compiles, stop and ask me to approve it, giving preparedId,
preparationDigest and runId. Name each assertion after the promise it
checks. After the run, report which assertions failed and whether you
trust the result.
```
