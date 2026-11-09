# CLI machine diagnostics, version 1

Put `--diagnostics=json` before the command:

```bash
java -jar faultora-cli/target/faultora-0.12.0.jar \
  --diagnostics=json test --scenario scenario.yaml --format json,junit
```

The command runs normally, including its requested filesystem writes and target
operations. The flag changes diagnostic output, not execution permission. Use
`test --preview` as well when execution is not intended.

On ordinary command completion, stdout contains exactly one JSON object and a
newline. Progress text, console reports, help/version prose and file notices
are suppressed in this mode. Technical logging stays on stderr. File reports
are still written when requested. Human mode remains the default.

```json
{
  "schemaVersion": 1,
  "command": "test",
  "exitCode": 2,
  "diagnostics": [
    {
      "code": "PLAN_REFUSED",
      "severity": "ERROR",
      "node": "read",
      "path": "execute",
      "hint": "Review the node, catalog references and effective execution policy."
    }
  ],
  "preview": null
}
```

`command` is a known command name, `help`, `version`, or null when no known
command was selected. `exitCode` matches the process exit code: 0 success,
1 test failure, 2 invalid configuration, 3 runner/infrastructure failure.
Existing command-specific classifications are retained: for example, a missing
file returns 2 for `validate` and 3 for `test`, `discover`, and `init`.

`diagnostics` is an array, possibly empty. Every entry has these fields:

| Field | Meaning |
| --- | --- |
| `code` | Stable category; branch on this rather than hint text. |
| `severity` | `ERROR`, `WARNING`, or `INFO`. Warnings alone do not change success. |
| `node` | Public plan node ID, or null when unavailable. |
| `path` | Document path or compilation phase, never an input filesystem path; null when unavailable. Scenario paths index lists, such as `execute[0].retry`. |
| `hint` | Fixed guidance without interpolated values or provider exception text. |
| `detailCode` | Optional refinement of a scenario `code`: `SCENARIO_UNKNOWN_FIELD` or `SCENARIO_TYPE_MISMATCH`. Absent otherwise; branch on `code` first. |

Locations are limited to 256 characters and identifier/dot/bracket/hyphen
syntax; other locations are null. Source text, input values, credential handles,
endpoint URLs, exception messages and provider details are not copied into the
diagnostics. Node diagnostics are ordered by node ID. Consumers must accept
unknown future codes and fields. Hint wording is informational.

| Codes | Meaning |
| --- | --- |
| `COMMAND_REQUIRED`, `UNKNOWN_COMMAND`, `INVALID_ARGUMENT` | Command selection or option syntax. |
| `FILE_READ_FAILED` | An input file could not be read. |
| `SCENARIO_INVALID`, `SCENARIO_WARNING` | Parsing or structural validation, with a document location when available. |
| `PLAN_REFUSED`, `PLAN_WARNING` | Compilation or policy diagnostics, with node/phase when available. |
| `CATALOG_IMPORT_FAILED`, `CAPABILITY_UNAVAILABLE` | Catalog import or unavailable/admission-restricted contract type. |
| `CATALOG_WARNING` | An importer accepted a catalog but reported something, such as a skipped server or a deprecated version; path names the catalog family (`openapi`, `asyncapi`, `observations`). The importer's message is printed only in human output. |
| `INPUT_UNKNOWN`, `INPUT_REQUIRED` | Scenario input binding; path names the input. |
| `ARTIFACT_WRITE_FAILED`, `REPORT_WRITE_FAILED` | Artifact setup or report writing failed. |
| `PREPARATION_FAILED` | Preparation stopped without a parser/compiler diagnostic. |
| `ASSERTION_FAILED`, `ASSERTION_INDETERMINATE` | A node's assertion failed or lacked usable evidence. |
| `DESTINATION_BLOCKED` | The node's target address is outside the destination policy — most often a local or private target run without `--allow-private`. |
| `PARAMETER_MISSING` | The node's operation requires a parameter the step gives no value — an HTTP path parameter or an observation's SQL bind variable. |
| `NODE_FAILED`, `NODE_ERROR` | Other node failures, including connector or policy errors. |
| `RUN_FAILED`, `RUN_ERROR`, `RUN_CANCELLED` | Run-level failure, infrastructure error or cancellation. |
| `COMMAND_INVALID`, `COMMAND_FAILED` | Fallback for command failures without a more specific diagnostic, including health checks. |

With `--diagnostics=json test --preview`, the structural preview is in `preview`
inside this envelope; a second JSON document is never emitted. Scenario and
compiler refusals also appear in the envelope's `diagnostics`. Errors before a
preview is built, including file, catalog and input failures, have `preview: null`.
Without the global flag, `test --preview` retains its original direct JSON form.

Each entry in the preview's `actions` is one node of the compiled plan, group
children included, so a preview usually lists more entries than `validate`
counts top-level entries. What the numbers mean depends on `kind`:

| `kind` | `deadlineMs` | `maxAttempts` | `repetitions` |
|---|---|---|---|
| `operation` | The step's own `timeout`; `0` when it has none and runs within its group's, the scenario's (`budgets.scenarioTimeoutMs`) and the run's duration budget | Attempts including the first: `1` is no retry | `1`, or the iterations of the `repeat` or the `maxPolls` of the `eventually` it belongs to |
| `cleanup` | The step's own `timeout`; `0` when it has none. Cleanup runs after the scenario under its own shared cleanup budget, not the scenario's or the run's | `1` | `1` |
| `parallel`, `repeat` | The group's `timeout`, `0` when none | `0` | `1`, or the iteration count of a `repeat` |
| `eventually` | The polling `timeout` | `0` | `maxPolls` |
| `wait` | How long it waits | `0` | `1` |
| `assertion`, `fault-stop` | `0` | `0` | `1` |

This is a terminal diagnostic result, not a streaming status protocol or the
complete command data model. For example, `discover` reports successful catalog
import with exit 0; the envelope does not contain the catalog. A running `runner`
emits its terminal result only when it stops. A process kill, JVM failure or
broken stdout can prevent delivery. Third-party plugins execute in-process and
must honor stdout ownership themselves; no stream interception or sandbox is
introduced. Runner dispatch verification, policy narrowing, scenario syntax and
artifact ownership are unchanged.
