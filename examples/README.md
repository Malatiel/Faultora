# Scenario examples

Five existing scenario families, with the observations each needs and the
limits of what a passing run establishes. These examples use the ordinary
operation, assertion, retry and fault APIs; no family-specific engine or MCP
tool is needed. See the [scenario reference](../docs/SCENARIO_REFERENCE.md)
for syntax and the [recovery system](payment-recovery/README.md) for its model.

| Family | Published scenario | Required observations | What PASS establishes |
|---|---|---|---|
| Contract | [passing.yaml](payment-service/scenarios/passing.yaml) | HTTP status and complete JSON response; OpenAPI response schema | This create response has status 201, an ID and the declared shape |
| Retry | [fault-retry.yaml](payment-service/scenarios/fault-retry.yaml) | Operation errors, retry journal and final HTTP status | A bounded retry recovers after a local pre-send outage |
| Eventual | [eventually-settlement.yaml](payment-service/scenarios/eventually-settlement.yaml) | Create response and repeated reads of that payment's status | Settlement becomes visible within the polling budget |
| Cross-component | [settlement-invariant.yaml](payment-recovery/scenarios/settlement-invariant.yaml) | HTTP response, correlated Kafka event and payment-scoped PostgreSQL rows | The observed event, booking and accepted amount agree |
| Fault lifecycle | [fault-lifecycle.yaml](payment-service/scenarios/fault-lifecycle.yaml) | Fault activation/rollback journal, HTTP read and cleanup result | The payment is readable during local latency; cleanup and rollback complete |

## Run the HTTP examples

Java 21 is required. From the repository root, build the executable and example
API with `./mvnw package -DskipTests` (`.\mvnw.cmd package -DskipTests` on
Windows). This builds the manual demo; the automated checks are listed below.
The examples' E2E tests start their own API; the manual commands below use a
separate, disposable API instance. Only the cross-component family needs Docker.

In one terminal, start JShell with the API and CLI JARs on its classpath:

```sh
jshell --class-path "examples/payment-service/target/payment-service-0.11.2.jar:faultora-cli/target/faultora-0.11.2.jar"
```

On Windows use `;` between the two JAR paths instead of `:`. In JShell:

```java
var api = new dev.faultora.examples.payment.PaymentApi();
api.start(8080);
```

The API stores payments in memory. Keep JShell open while running the examples;
call `api.stop()` when done. Restart it for an empty store. It exposes no
production credentials and is intended only for a trusted local environment.

In a second terminal, set `FAULTORA_JAR` to the executable
`faultora-cli/target/faultora-0.11.2.jar`. The following are POSIX shell commands;
in PowerShell use `$env:FAULTORA_JAR` and put each command on one line.

```sh
java -jar "$FAULTORA_JAR" test \
  --scenario examples/payment-service/scenarios/passing.yaml \
  --openapi examples/payment-service/openapi.yaml \
  --target http://localhost:8080 --allow-private \
  --format console,json --output results/contract

java -jar "$FAULTORA_JAR" test \
  --scenario examples/payment-service/scenarios/fault-retry.yaml \
  --openapi examples/payment-service/openapi.yaml \
  --target http://localhost:8080 --allow-private \
  --format console,json --output results/retry

java -jar "$FAULTORA_JAR" test \
  --scenario examples/payment-service/scenarios/eventually-settlement.yaml \
  --openapi examples/payment-service/openapi.yaml \
  --target http://localhost:8080 --allow-private \
  --format console,json --output results/eventual

java -jar "$FAULTORA_JAR" test \
  --scenario examples/payment-service/scenarios/fault-lifecycle.yaml \
  --openapi examples/payment-service/openapi.yaml \
  --target http://localhost:8080 --allow-private --allow-destructive \
  --format console,json --output results/lifecycle
```

`--allow-private` admits this local target. `--allow-destructive` is needed
only by the lifecycle example, whose cleanup deletes the payment it created.
Use a fresh output directory for each run. Exit 0 is a passed run, 1 is a
scenario failure, 2 an invalid configuration and 3 a command/runtime failure.
Inspect `events.ndjson` and `report.json`, including assertion outcomes:
`INDETERMINATE` means missing evidence, not a proven property.

### Contract: the response matches its description

The example imports [openapi.yaml](payment-service/openapi.yaml), calls
`create-payment`, asserts 201 and an ID, then validates the captured JSON
against the operation's response schema. OpenAPI alone is not an observation;
the run must capture the body. A truncated or unavailable body cannot prove
the schema assertion.

PASS covers this response and this schema subset. It does not prove every
operation, all possible request data or a business invariant. The packaged
CLI test also returns an amount as a string while keeping 201 and an ID:
the same published scenario fails its `schema` assertion.

### Retry: recover within a bounded attempt budget

The local `http-error` fault lasts 400 ms and rejects matching requests
**before sending them**. `create-payment` permits five attempts, with
200 ms exponential backoff capped at 1000 ms. The journal exposes
`OPERATION_RETRIED` and the final 201 assertion checks recovery. A passing
status by itself does not establish that any retry occurred; inspect the
retry events too.

This proves recovery from a pre-send failure in this run. It does not prove
idempotency after an ambiguous outcome or delivery to the server. For that
different claim see [fault-duplicate-payment.yaml](payment-service/scenarios/fault-duplicate-payment.yaml),
which loses a delivered response, reuses an idempotency key and counts effects.
Real service errors are retried only when the connector marks them retryable;
an arbitrary 500 response is not an instruction to retry.

### Eventual: a bounded observation of convergence

The create response supplies the payment ID and starts as `pending`. The
example polls `get-payment` every 200 ms for at most 10 s, requiring both 200
and `status: settled`. The final assertion reads the last poll. The example
API derives this transition from elapsed time; it has no broker or ledger.
The packaged CLI negative control leaves the payment pending and must return
`EVENTUALLY_TIMEOUT` with exit 1.

PASS means that state was observed within the window. It does not prove that
it remains settled, persisted across restart, or produced a downstream effect.
Missing the window is a failure to observe convergence within that budget,
not proof that convergence can never happen.

## Cross-component: HTTP, event and ledger agree

The [recovery reference system](payment-recovery/README.md) needs PostgreSQL,
Kafka, its API, relay and consumer. Import its three published documents:
[OpenAPI](payment-recovery/openapi.yaml), [AsyncAPI](payment-recovery/asyncapi.yaml)
and [Observations](payment-recovery/observations.yaml). The last file is an
operator-owned SQL allowlist; scenarios supply a payment ID, not SQL. Connect
as a role with SELECT only and resolve its password using
`FAULTORA_SECRET_LEDGER_PASSWORD` in the CLI environment.

For an already deployed instance of that reference system, set `API_URL`,
`KAFKA_BOOTSTRAP` and `LEDGER_JDBC_URL` to its test endpoints, then run:

```sh
java -jar "$FAULTORA_JAR" test \
  --scenario examples/payment-recovery/scenarios/settlement-invariant.yaml \
  --openapi examples/payment-recovery/openapi.yaml \
  --asyncapi examples/payment-recovery/asyncapi.yaml \
  --observations examples/payment-recovery/observations.yaml \
  --target "$API_URL" --target "broker=kafka://$KAFKA_BOOTSTRAP" \
  --target "ledger=$LEDGER_JDBC_URL" \
  --db-user faultora_readonly --db-secret-id ledger-password \
  --allow-private --seed 42001 --format console,json --output results/cross-component
```

Use a new seed for each payment in a shared database. The scenario correlates
the event and both database reads with `pay-<seed>`. The observations return
complete one/two-row results within the evidence limits; truncated rows cannot
establish counts or balance. The event shows arrival on the broker; the ledger
read is what checks two entries, zero sum and the accepted amount. Balance by
itself would also accept two balanced duplicate bookings.

PASS is evidence about this payment in these reads. It is not a global audit,
a transactional snapshot across protocols or proof of exactly-once delivery.
The reference system commits the booking before publishing its settlement
event; another system may need a bounded ledger poll as well.

To reproduce without deploying anything yourself, the suite starts the exact
reference system with disposable Kafka/PostgreSQL and SELECT-only credentials:

```sh
./mvnw verify -pl integration-tests -am -Dtest=CrossComponentE2ETest -Dsurefire.failIfNoSpecifiedTests=false
```

It runs the real CLI JAR on the published scenarios. The healthy ledger must
pass with evaluated event/row assertions; the single-entry variant must fail
the same settlement scenario. Docker absence is reported as skips locally
and is a failure under [strict Linux qualification](../docs/QUALIFICATION.md).

## Fault lifecycle: cleanup and rollback are distinct

The new scenario creates a payment in setup, activates 20 ms of local HTTP
latency for 30 s, and reads that payment under the fault. Cleanup deletes
only its captured ID. In an ordinary short run, the fault remains active until
the end-of-run sweep: expect one `FAULT_INJECTED`, a completed
`delete-created-payment`, `CLEANUP_COMPLETED` and one `FAULT_ROLLED_BACK` with
`rollbackStatus: run-end`. A sufficiently slow run may reach timed expiry
instead. Journal order and status say which path actually occurred.

The packaged CLI tests check cleanup and rollback both when the read assertion
passes and when a target returns 404. Cleanup does not turn the latter into a
passed scenario. Removing application data is cleanup; removing an injected
fault is rollback. One does not establish the other.

The local fault changes only this Faultora process's HTTP calls. It does not
degrade the service or other clients and is not evidence of a network outage.
The journal records lifecycle, not guaranteed recovery after killing the JVM.
For external network faults see the [Toxiproxy limits](../docs/SCENARIO_REFERENCE.md#faults).

## Automated coverage

`ExampleFamiliesE2ETest` launches the packaged JAR for contract, retry,
eventual and lifecycle examples, including schema/convergence/lifecycle
negative controls. `CrossComponentE2ETest` covers the fifth family through
the same JAR with real infrastructure. These tests read the files linked
above directly, rather than maintaining copies in test resources.

The full `./mvnw verify` includes both suites. For the HTTP examples alone:

```sh
./mvnw verify -pl integration-tests -am -Dtest=ExampleFamiliesE2ETest -Dsurefire.failIfNoSpecifiedTests=false
```
