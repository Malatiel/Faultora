# Authoring an isolated assertion extension

An assertion provider reads scenario parameters and policy-filtered evidence,
then returns a verdict. This example checks a synthetic parameter on a wait
step, so you can verify the packaged extension without a target service.

## Build against a packaged CLI

Use a Java 21 JDK and a packaged Faultora JAR that supports
`test --isolated-assertion`. From a source checkout, build it with
`./mvnw -B package -pl faultora-cli -am -DskipTests`. The packaged JAR in
`faultora-cli/target/` includes the SPI contracts used at compile time.
Use the same Faultora version to compile and test the provider. The commands
below use a Linux shell with the JDK tools and `sha256sum` on `PATH`.

Work in a fresh directory and save this file as `SyntheticAssertion.java`.

```java
package example.faultora;

import dev.faultora.spi.context.AssertionContext;
import dev.faultora.spi.context.EvidenceView;
import dev.faultora.spi.contract.AssertionProvider;
import dev.faultora.spi.result.AssertionResult;

import java.util.Map;

public final class SyntheticAssertion implements AssertionProvider {
    @Override
    public String type() {
        return "synthetic-check";
    }

    @Override
    public AssertionResult evaluate(String assertionType, Map<String, Object> params,
                                    EvidenceView evidence, AssertionContext context) {
        if (!(params.get("expected") instanceof String expected)) {
            return AssertionResult.indeterminate("Expected a string parameter");
        }
        return "synthetic".equals(expected)
                ? AssertionResult.pass("Synthetic parameter matched")
                : AssertionResult.fail("Synthetic parameter differed", Map.of());
    }
}
```

Set `faultora_jar` to the absolute path of your packaged CLI JAR. Compile the
provider and add its service descriptor and plugin manifest at the paths below.
The manifest's `version` identifies the plugin. The inclusive `requiresApi`
bounds identify the Faultora versions the author has checked. Set those bounds
to the version reported by your packaged CLI.

```sh
mkdir -p classes/META-INF/services
javac --release 21 -cp "$faultora_jar" -d classes SyntheticAssertion.java
printf '%s\n' example.faultora.SyntheticAssertion \
  > classes/META-INF/services/dev.faultora.spi.contract.AssertionProvider
cat > classes/META-INF/faultora-plugin.yaml <<'EOF'
id: synthetic-assertion
version: "1.0"
requiresApi:
  from: "0.13.0"
  to: "0.13.0"
EOF
jar --create --file synthetic-assertion.jar -C classes .
jar --list --file synthetic-assertion.jar
```

The JAR contains the provider class and both `META-INF` resources. The service
descriptor names the Java class. The scenario and operator approval use the
value returned by `type()`. Choose a type distinct from the built-ins. This
manifest omits `capabilities`, which declares no network or secret capability
requests. Keep the Faultora SPI classes on the compile classpath. The runtime
provides them when it loads the finished extension.

## Run the example

Save the following as `scenario.yaml`.

```yaml
apiVersion: faultora.dev/v1
kind: Scenario
metadata:
  name: synthetic-extension
execute:
  - id: pause
    type: wait
    timeout: 1ms
assertions:
  - id: check-synthetic
    assertionType: synthetic-check
    targetStep: pause
    params:
      expected: synthetic
```

Compute the SHA-256 of the finished JAR after review and use it to approve this
one artifact for the run.

```sh
digest=$(sha256sum synthetic-assertion.jar | cut -d ' ' -f 1)
java -jar "$faultora_jar" test --scenario scenario.yaml \
  --isolated-assertion synthetic-check "sha256:$digest" synthetic-assertion.jar \
  --output result
```

The scenario exits with code 0 and reports one passed assertion. Change
`expected` to another string to exercise the failed verdict. When a parameter or
policy-filtered evidence value needed for evaluation is missing, return
`INDETERMINATE`. Keep diagnostics and details free of raw evidence and secrets.

The packaged CLI approves one isolated assertion per run. Its option conflicts
with `--allow-extension` and with local `--preview`. The [extension wire
protocol](EXTENSION_PROTOCOL.md) defines the child exchange. The Faultora
bootstrap implements the exchange for this provider.

## Approve the artifact on a runner

The operator reviews the plugin JAR, records its SHA-256 independently of
dispatch input, and supplies the same `--isolated-assertion <type> <sha256:digest>
<jar>` option in the runner's local configuration. Use an operator-controlled
artifact directory. The dispatcher can request the type in a scenario, while
approval of the artifact stays with the runner operator. Review and record a
fresh digest after every rebuild.

On a Linux runner, `--require-extension-sandbox` places the child in filesystem
and network isolation. An ordinary child JVM runs with the parent account's
filesystem and network permissions. See [separate JVM assertions](SECURITY.md#separate-jvm-assertions)
for host prerequisites and optional CPU, memory and task limits.
