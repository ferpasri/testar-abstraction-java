# TESTAR Abstraction for Java

Construct abstract states, actions, and transitions from recorded test execution evidence. Rules select which captured properties participate in comparison.

## Build

Requires Java 17 or later and Maven. From this repository:

~~~powershell
mvn package -DskipTests
~~~

The JAR is written to `target/`. Maven coordinates are `org.testar:testar-abstraction`; the package version is declared in `pom.xml`.

## Use

~~~java
import com.fasterxml.jackson.databind.JsonNode;
import org.testar.abstraction.Abstraction;

JsonNode rules = Abstraction.parseRules(rulesJson);
JsonNode preview = Abstraction.preview(sources, rules, selectedRuns);
JsonNode abstractModel = Abstraction.buildModel(preview, sources);
~~~

Inputs and results use Jackson JSON trees. `sources` contains reconstructed evidence collections. `selectedRuns` contains references such as `{"source": "source-id", "run": "run-id"}`. See the [API and input specification](docs/SPEC.md) for the collection structure and result fields.

To install the package into your local Maven repository after testing:

~~~powershell
mvn install
~~~

## Shared tests

Download the latest published shared cases, then run tests and package the library:

~~~powershell
mvn test-compile exec:java "-Dexec.mainClass=org.testar.abstraction.PrepareConformance" "-Dexec.classpathScope=test"
mvn verify
~~~

Case preparation records the release and archive digest in `.cache/specs/current.json`. Tests verify file hashes before reading the prepared cases. [Compatibility metadata](compatibility.json) identifies the supported evidence format, rule language, and interpretation behavior.

Shared rules and cases are maintained in [TESTAR Abstraction Specs](https://github.com/ferpasri/testar-abstraction-specs). Remaining work is in [TODO](TODO.md).
