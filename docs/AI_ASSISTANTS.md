# Using Faultora with an AI assistant

An AI assistant can write and run Faultora scenarios for you through the
[MCP server](MCP.md) built into the CLI. This page is the practical path: what
the assistant can and cannot do, how an operator connects one, how a run gets
approved, and how to read what comes back. [MCP.md](MCP.md) is the full
reference for every tool, field and limit.

## What the assistant can and cannot do

- It **can** inspect the API descriptions the operator declared, see which
  operations and fault types the operator permits, and prepare scenarios:
  Faultora compiles each one against the operator's policy and returns a
  structural preview or a refusal that names the field to fix.
- It **cannot run** anything by itself. Every run needs an approval token the
  operator signs for that exact preparation. Asking, insisting or claiming to
  be authorized changes nothing; a signature does.
- It **sees structure, not data**: assertion outcomes by node ID and type, run
  status and counts. It never receives response bodies, compared values,
  credentials, endpoint URLs or provider error text, and the server contacts
  no model or backend itself.

## Set it up (operator)

You need the CLI release, Java 21, the API description, and a test
environment the run may reach.

**1. A key for approving runs.** Generate an Ed25519 key pair; keep the
private key where the assistant cannot read it:

```bash
openssl genpkey -algorithm ed25519 -outform DER -out operator.der
openssl pkey -inform DER -in operator.der -pubout -outform DER | base64
```

The second command prints the public key for the configuration below. On
Windows, `openssl` comes with Git for Windows; there,
`[Convert]::ToBase64String([IO.File]::ReadAllBytes("operator.pub.der"))` in
PowerShell encodes a public key written with `-out operator.pub.der`.

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
java -jar /path/to/faultora-0.12.0.jar mcp --config /path/to/operator.json
```

For Claude Code, put this in `.mcp.json` in the folder the assistant works in:

```json
{
  "mcpServers": {
    "faultora": {
      "command": "java",
      "args": ["-jar", "/path/to/faultora-0.12.0.jar", "mcp", "--config", "/path/to/operator.json"]
    }
  }
}
```

For the Codex CLI:

```bash
codex mcp add faultora -- java -jar /path/to/faultora-0.12.0.jar mcp --config /path/to/operator.json
```

Other MCP clients take the same command and arguments. Use absolute paths, and
give `java` as a full path if the client does not inherit your `PATH`.

For approval to mean anything, the assistant must not be able to read
`operator.der`, rewrite `operator.json`, or replace the JAR: run it under a
separate account or keep the key on another machine. [MCP.md](MCP.md#operator-approval-and-execution)
explains why the same account is not enough.

## The approval loop

1. The assistant calls `prepare_run` with a scenario and gets back
   `preparedId`, `preparationDigest`, `runId` and a preview.
2. It asks you to approve, giving those three values.
3. You read the scenario and the preview — which operations, how many
   requests, which faults — and if you agree, sign:

   ```bash
   java -jar faultora-0.12.0.jar mcp approve --private-key operator.der \
     --prepared-id <preparedId> --digest <preparationDigest> --run-id <runId> --expires-in 300
   ```

4. You give the assistant the printed token, and it calls `start_run`, then
   `get_run` until the run is terminal.

A preparation expires five minutes after it was made, and a token after at
most 300 seconds, so approve promptly or let the assistant prepare again.

## Reading the results

A terminal `get_run` result, and the `report` artifact, contain:

- `status` — `PASSED`, `FAILED` or `ERROR` for the run;
- `assertions` — one entry per assertion with its `nodeId`, `assertionType`
  and `outcome`. Evaluated ones are `PASS`, `FAIL` or `INDETERMINATE`; the
  rest are `SKIPPED` (`reasonCode` `DEPENDENCY_NOT_PASSED`) or `NOT_EVALUATED`
  with a `reasonCode` such as `ASSERTION_REFUSED`, `INPUTS_UNRESOLVABLE`,
  `DEADLINE_EXCEEDED` or `RUN_STOPPED`;
- the counts of passed and failed assertions, and `omittedAssertionCount` when
  a very large run did not fit;
- `cleanupConfirmed` — whether cleanup and fault rollback are known to have
  finished.

The verdict carries no explanation text, so **name each assertion after the
promise it checks** — `seats-never-exceed-capacity`, not `check-3`. That name
is how you and the assistant tell what failed.

## What trips assistants up

From a trial in which an assistant with no prior knowledge of Faultora found
a planted defect in an unfamiliar service through this server:

- **An HTTP error status is not a failed step.** A `409` or `500` completes
  the step and its dependents run; check the status with a `status` assertion.
  Only a normalized error — a timeout, a refused connection, an injected fault
  — fails a step ([scenario reference](SCENARIO_REFERENCE.md)).
- **Races need `parallel`.** A rule like "never more registrations than
  seats" usually holds one request at a time and breaks only when requests
  overlap; put the competing requests in a `parallel` group and check the
  state afterwards.
- **Read the refusal's code and path.** A refused preparation names the
  field, such as `execute[0].steps[3].params`, with a code such as
  `SCENARIO_UNKNOWN_FIELD`; change that field rather than guessing.
- **Check `operatorPolicy` first.** `list_capabilities` says which operation
  classes and fault types this operator permits.
- **Keep the YAML.** A scenario that ran over MCP runs the same way with
  `faultora test`, so it can join CI.

## A brief to give the assistant

```text
Faultora is connected as the MCP server "faultora". Use it to check whether
<service> keeps <promise>. Start with list_capabilities and describe_catalog.
Write faultora.dev/v1 scenarios (reference: docs/SCENARIO_REFERENCE.md),
prepare them with prepare_run, and when one compiles, stop and ask me to
approve it, giving preparedId, preparationDigest and runId. Name each
assertion after the promise it checks. After the run, report which
assertions failed and whether you trust the result.
```
