# Synthetic document catalog

A second reference system for the same Faultora operation and assertion APIs.
It replaces a whole collection with a JSON array, queries by a label containing
a space, returns an ordered array with nested attributes, and deletes the
collection in cleanup. It needs Java 21 and no external service or Docker.

The [OpenAPI contract](openapi.yaml) and [scenario](scenarios/contract.yaml)
exercise bulk request bodies, path/query binding, schema checks, array counts,
key uniqueness and nested-value comparisons. The API sorts by document key;
the scenario does not depend on map or request insertion order. No catalog
operation is implemented as a special Faultora command or engine branch.

## Run

From the source repository root, build with `./mvnw package -DskipTests`
(`.\mvnw.cmd package -DskipTests` on Windows). In one terminal:

```sh
jshell --class-path "examples/catalog-service/target/catalog-service-0.11.3.jar:faultora-cli/target/faultora-0.11.3.jar"
```

On Windows, use `;` between the two JAR paths. In JShell:

```java
var api = new dev.faultora.examples.catalog.CatalogApi();
api.start(8081);
```

Keep JShell open. In another terminal, from the same repository root:

```sh
java -jar faultora-cli/target/faultora-0.11.3.jar validate \
  --scenario examples/catalog-service/scenarios/contract.yaml \
  --openapi examples/catalog-service/openapi.yaml

java -jar faultora-cli/target/faultora-0.11.3.jar test --preview \
  --scenario examples/catalog-service/scenarios/contract.yaml \
  --openapi examples/catalog-service/openapi.yaml \
  --target http://127.0.0.1:8081 --allow-private --allow-destructive

java -jar faultora-cli/target/faultora-0.11.3.jar test \
  --scenario examples/catalog-service/scenarios/contract.yaml \
  --openapi examples/catalog-service/openapi.yaml \
  --target http://127.0.0.1:8081 --allow-private --allow-destructive \
  --format console,json --output results/catalog
```

These are POSIX continuations; use single-line commands in PowerShell.
`--allow-private` permits this loopback target. `--allow-destructive` admits
the scenario's delete cleanup. Preview prepares the plan without calling the
API. Use a fresh output directory per run. Call `api.close()` in JShell when
done; a new instance starts empty.

## Qualification and limits

`CatalogApiTest` verifies replacement, collection isolation, filtering, invalid
batch rejection without a partial write, and input size bounds.
`CatalogReferenceE2ETest` invokes the packaged CLI: validate/preview make zero
API requests; the scenario passes against the normal API and fails its exact
count/key/value assertions against an operator-selected filter defect. Both
runs must still delete the collection. A matching response schema alone does
not establish correct filtering.

The fixture binds only to loopback and keeps data in memory. It accepts at
most 32 documents per replacement and 65536 body bytes, with at most 16 labels
per document. It has no authentication, persistence or external-reference
loading. Use it only as a disposable local demo with synthetic data. It does
not model storage durability, asynchronous indexing, Kafka or a database.
