# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

The build must be done in two phases: first the `support/` directory (Maven Tiles + composite POMs), then the root reactor.

```bash
# Build without tests (fast)
./build_only.sh
# Equivalent: cd support && mvn -DskipTests=true -f pom-tiles.xml install && mvn install && cd .. && mvn -T4C -DskipTests=true clean install

# Build with tests
./build_alL_and_test.sh
# Equivalent: cd support && mvn -f pom-tiles.xml install && mvn install && cd .. && mvn clean install

# Build Java 17+ modules (requires JDK 17+)
cd v17-and-above && mvn install
```

### Running tests

```bash
# All tests in a module
cd core/client-java-core && mvn test

# Single test class
mvn -Dtest=ClassName test

# Single test method
mvn -Dtest=ClassName#methodName test
```

Tests are written in **Spock** (Groovy) in most modules. Spock is preferred for ALL tests and should be used when attempting to write tests. It relies on the composite-testing dependency which needs to be installed as a scope: `test`
for each project. It is located at `support/composite-logging/pom.xml`. 

## Architecture

This is a Maven multi-module SDK for integrating with the FeatureHub feature flag service. The modules form a layered dependency chain:

```
Usage Adapters (OpenTelemetry, Segment)
        ↑
Client Implementations (OKHttp, Jersey2, Jersey3)
        ↑
client-java-core  (domain logic, interfaces)
        ↑
client-java-api   (OpenAPI-generated models)
```

### Module Groups

- **`core/client-java-api`** — OpenAPI-generated models: `FeatureRolloutStrategy`, `FeatureState`, SSE event models, strategy matcher types.
- **`core/client-java-core`** — All domain logic: `ClientFeatureRepository` (in-memory cache), `EdgeService` interface, `ClientContext` (per-user evaluation context), `ApplyFeature` (rollout strategy evaluation engine), `FeatureValueInterceptor`.
- **`client-implementations/java-client-okhttp`** — OKHttp-based `EdgeService` implementation; the recommended client.
- **`client-implementations/java-client-jersey2`** and **`jersey3`** — JAX-RS 2.x / Jakarta REST alternatives.
- **`support/`** — Maven infrastructure: Tiles (shared plugin config for Java 8/11/21), composite POMs (dependency version management for OKHttp, Jersey, Jackson, logging, test utilities). Must be built before root modules.
- **`support/featurehub-okhttp3-jackson2`** — Pre-configured convenience artifact: OKHttp + Jackson 2, the recommended starting point for most users.
- **`usage-adapters/`** — Analytics plugins: OpenTelemetry tracing, Segment analytics — both implement `UsagePlugin`.
- **`v17-and-above/`** — Java 17+ specific modules, built separately with a different JDK.

### Key Interfaces and Extension Points

- **`EdgeService`** — implemented by each HTTP client; abstracts SSE/REST transport.
- **`FeatureHubClientFactory`** — discovered via `ServiceLoader`; each client registers itself.
- **`UsagePlugin`** — analytics/observability hooks called on feature evaluation.
- **`FeatureValueInterceptor`** — allows overriding feature values at runtime (e.g., dev overrides via system properties).
- **`RepositoryEventHandler`** / **`FeatureListener`** / **`ReadinessListener`** — observer callbacks for repository and feature state changes.

### Typical Initialization Pattern

```java
FeatureHubConfig fhConfig = new EdgeFeatureHubConfig(edgeUrl, apiKey);
fhConfig.streaming();          // or .restActive() / .restPassive()
fhConfig.init();               // blocks until first feature fetch

ClientContext ctx = fhConfig.newContext()
  .userKey("user-123")
  .country(StrategyAttributeCountryName.NewZealand)
  .build().get();

boolean enabled = ctx.isEnabled("MY_FEATURE");
```

### Jackson Abstraction Pattern

This repository deliberately abstracts all Jackson JSON functionality behind an interface so that
modules remain independent of the Jackson major version in use at runtime.

**Three libraries form the pattern:**

- **`support/common-jackson`** (`io.featurehub.sdk.common:common-jackson`) — the API-only module.
  Contains the `JavascriptObjectMapper` interface and nothing else. Has no dependency on any Jackson
  library itself. This is the only Jackson-related artifact that production code should depend on.

- **`support/common-jacksonv2`** (`io.featurehub.sdk.common:common-jacksonv2`) — implements
  `JavascriptObjectMapper` using Jackson 2.x (`com.fasterxml.jackson`). Registered via Java
  `ServiceLoader` so it is discovered automatically when on the classpath.

- **`v17-and-above/support/common-jacksonv3`** (`io.featurehub.sdk.common:common-jacksonv3`) —
  implements `JavascriptObjectMapper` using Jackson 3.x (`tools.jackson`). Java 17+ only.
  Also registered via `ServiceLoader`.

**Rules when writing or modifying code:**

1. **Never add `jackson-databind`, `jackson-core`, or any `com.fasterxml.jackson` / `tools.jackson`
   dependency directly to a production module's `pom.xml`.** If JSON functionality is needed,
   depend on `common-jackson` instead and use the `JavascriptObjectMapper` interface.

2. **If the required functionality is not available on the `JavascriptObjectMapper` interface,
   stop and ask for direction** — do not reach for Jackson directly or widen the interface
   without discussion.

3. **For tests that need real Jackson behaviour** (e.g. to back a mock or verify serialisation),
   add `common-jacksonv2` as a `<scope>test</scope>` dependency. This provides a concrete
   implementation without polluting production code with a Jackson version choice.

**Example test pom.xml entry:**

```xml
<dependency>
  <groupId>io.featurehub.sdk.common</groupId>
  <artifactId>common-jacksonv2</artifactId>
  <version>[1.1, 2)</version>
  <scope>test</scope>
</dependency>
```

### Build Infrastructure Notes

- **Maven Tiles** (`support/tile-java8`, `tile-java11`, `tile-java21`, `tile-sdk`, `tile-release`) provide shared plugin/compiler configuration. The `pom-tiles.xml` in `support/` must be installed before any other module.
- **Composite POMs** (`composite-okhttp`, `composite-jersey2`, etc.) centralise dependency versions — edit these when upgrading transitive dependencies.
- `.mvn/jvm.config` contains `--add-exports` flags required by the compiler for Java module system compatibility.
- The root `pom.xml` reactor version is independent of individual module versions; modules are versioned separately.
