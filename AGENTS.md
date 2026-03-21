# AGENTS.md - Junction LLM Gateway

> **AI Agent Context**: This file provides comprehensive guidance for AI agents working on the Junction LLM Gateway project. Read this entire file before making any changes.

## Project Overview

Junction is an **OpenAI-compatible LLM Gateway** for the JVM, built with cutting-edge Java 25 features. It provides a unified API layer that routes requests to multiple LLM providers (e.g. Ollama, Gemini) with intelligent load balancing, rate limiting, and client compatibility features.

**Key Characteristics:**
- Multi-module Maven project (parent POM + 3 modules)
- Java 25 with preview features (Virtual Threads, Stream Gatherers, Scoped Values)
- Spring Boot 4.0.3 auto-configuration
- Jackson 3 for JSON processing
- OpenAI-compatible `/v1/chat/completions` endpoint
- API key authentication with tier-based rate limiting
- Client compatibility adapters for Cline, Roo Code, and other LLM clients

## Quick Start

```bash
# Build entire project
mvn clean install -DskipTests

# Run sample application
cd junction-samples
export GEMINI_API_KEY="your-key"  # Optional
mvn spring-boot:run

# Test the gateway
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama3.1",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": true
  }'
```

## Module Architecture

```
junction-gateway-parent/
├── junction-core/          # Core abstractions (no Spring dependencies)
│   ├── provider/           # LLM provider implementations
│   ├── router/             # Request routing strategies
│   ├── model/              # OpenAI-compatible DTOs
│   ├── context/            # Request context (ScopedValues)
│   ├── gatherer/           # Stream Gatherers for response transformation
│   └── security/           # API key auth and rate limiting
├── junction-starter/       # Spring Boot auto-configuration
│   ├── JunctionAutoConfiguration.java
│   ├── JunctionProperties.java
│   ├── GatewayController.java
│   ├── config/             # Jackson configuration
│   └── clientcompat/       # Client compatibility adapters
│       ├── ClientAdapter.java
│       ├── ClientAdapterConfig.java
│       ├── ClientAdapterLoader.java
│       ├── ClientCompatibilityService.java
│       └── ClientDetectionService.java
└── junction-samples/       # Demo application
    └── Application.java
```

### Module Dependencies
```
junction-samples → junction-starter → junction-core
```

## When to Modify Each Module

| Module | Modify When... | Key Files |
|--------|----------------|-----------|
| `junction-core` | Adding new LLM providers, changing routing logic, modifying request/response models | `LlmProvider.java`, `Router.java`, `ChatCompletionRequest.java` |
| `junction-starter` | Changing Spring configuration, adding properties, modifying controller behavior | `JunctionAutoConfiguration.java`, `JunctionProperties.java`, `GatewayController.java` |
| `junction-samples` | Adding examples, integration tests, demo configurations | `Application.java`, `application.yml` |

## Java 25 Features Used

This project leverages modern Java features extensively. Understand these before modifying code:

### 1. Virtual Threads (Project Loom)
**Location**: `application.yml` enables virtual threads
```yaml
spring:
  threads:
    virtual:
      enabled: true
```
**Impact**: All request handling uses virtual threads. Don't use `synchronized` blocks or `ThreadLocal`.

### 2. Scoped Values (JEP 487)
**Location**: `junction-core/src/main/java/io/junction/gateway/core/context/RequestContext.java`
```java
// Thread-local alternative for virtual threads
public final class RequestContext {
    private static final ScopedValue<RequestContext> CURRENT = ScopedValue.newInstance();
    // ...
}
```
**Usage**: Pass request-scoped data (requestId, timestamps) across async boundaries.

### 3. Stream Gatherers (JEP 485)
**Location**: `junction-core/src/main/java/io/junction/gateway/core/gatherer/OpenAIAdapterGatherer.java`
```java
// Transform provider-specific responses to OpenAI format
Gatherer<ProviderResponse, ?, ChatCompletionChunk> responseAdapter();
```
**Purpose**: Adapter pattern for converting different LLM provider formats to OpenAI-compatible streaming responses.

### 4. Sealed Interfaces + Pattern Matching
**Location**: `junction-core/src/main/java/io/junction/gateway/core/provider/LlmProvider.java`
```java
public sealed interface LlmProvider permits GeminiProvider, OllamaProvider {
    // ...
}
```
**Benefit**: Exhaustive pattern matching in `switch` expressions when handling providers.

## Core Abstractions

### LlmProvider (Sealed Interface)
```java
public sealed interface LlmProvider permits GeminiProvider, OllamaProvider {
    String providerId();
    boolean isHealthy();
    Gatherer<ProviderResponse, ?, ChatCompletionChunk> responseAdapter();
    Stream<ProviderResponse> execute(ChatCompletionRequest request);
}
```

**Adding a New Provider:**
1. Create class in `junction-core/src/main/java/io/junction/gateway/core/provider/`
2. Add to `LlmProvider` permits clause
3. Implement all methods
4. Add Spring bean in `JunctionAutoConfiguration.java`
5. Add properties in `JunctionProperties.java`

### Router Interface
```java
public interface Router {
    LlmProvider route(ChatCompletionRequest request);
}
```

**Current Implementation**: `RoundRobinRouter` - cycles through available providers.

**Adding New Routing Strategy:**
1. Implement `Router` interface in `junction-core`
2. Configure in `JunctionAutoConfiguration.java`
3. Add routing properties to `JunctionProperties.java`

## Configuration

### Application Properties (`application.yml`)

```yaml
junction:
  providers:
    ollama:
      enabled: true
      base-url: http://localhost:11434
      default-model: llama3.1
    gemini:
      enabled: false
      api-key: ${GEMINI_API_KEY:}
      model: gemini-1.5-flash
  security:
    api-key:
      required: true
      storage: memory
    ip-rate-limit:
      enabled: true
      requests-per-minute: 60
    ip-whitelist:
      enabled: false
      allowed-ips: ""
      allow-private-ips: true
  client-adapters:
    classpath-enabled: true
    external-directory: ""
    hot-reload: true
    external-priority: true
```

### Property Classes
- **Location**: `junction-starter/src/main/java/io/junction/gateway/starter/JunctionProperties.java`
- **Pattern**: Nested classes for provider-specific config
- **Binding**: Spring Boot `@ConfigurationProperties`

## Provider Routing Logic

### How the Gateway Decides When to Call a Provider

The gateway uses a **Round-Robin Router** to distribute requests among healthy LLM providers. Here's how the decision process works:

#### 1. Configuration Enablement (Startup Time)

A provider is only registered if enabled in `application.yml`:

```yaml
junction:
  providers:
    gemini:
      enabled: true      # Must be 'true'
      api-key: "your-key"  # Must be non-empty
      model: "gemini-1.5-flash"
```

In `JunctionAutoConfiguration.java`:
```java
@Bean
@ConditionalOnProperty(prefix = "junction.providers.gemini", name = "enabled", havingValue = "true")
public GeminiProvider geminiProvider(JunctionProperties props) {
    return new GeminiProvider(props.getGemini().getApiKey(), props.getGemini().getModel());
}
```

#### 2. Router Registration (Startup Time)

If enabled, the provider is added to the list of available providers:

```java
@Bean
public Router router(JunctionProperties props, ApplicationContext ctx) {
    var providers = new ArrayList<LlmProvider>();
    
    if (props.getOllama().isEnabled()) {
        providers.add(ctx.getBean(OllamaProvider.class));
    }
    if (props.getGemini().isEnabled()) {
        providers.add(ctx.getBean(GeminiProvider.class));
    }
    
    return new RoundRobinRouter(providers);
}
```

#### 3. Health Check (Request Time)

For each incoming request, the `RoundRobinRouter` performs parallel health checks:

```java
// In RoundRobinRouter.route():
var healthFutures = providers.stream()
    .map(provider -> CompletableFuture.supplyAsync(
        () -> new HealthResult(provider, provider.isHealthy()),
        executor
    ))
    .collect(Collectors.toList());
```

**GeminiProvider.isHealthy()** returns `true` only if:
```java
return apiKey != null && !apiKey.isEmpty();
```

#### 4. Round-Robin Selection (Request Time)

From the pool of **healthy** providers, one is selected using round-robin:

```java
var idx = counter.getAndIncrement() % healthy.size();
return healthy.get(idx);
```

### Example Scenarios

**Scenario 1: Both providers enabled and healthy**
- Request 1 → OllamaProvider
- Request 2 → GeminiProvider
- Request 3 → OllamaProvider
- Request 4 → GeminiProvider
- (Requests alternate between providers)

**Scenario 2: Only Gemini enabled**
- All requests → GeminiProvider

**Scenario 3: Gemini disabled**
- All requests → OllamaProvider (or other enabled providers)

**Scenario 4: Gemini unhealthy (missing API key)**
- All requests → OllamaProvider (or other healthy providers)

### Provider Health Criteria

| Provider | Health Check |
|----------|-------------|
| **GeminiProvider** | `apiKey != null && !apiKey.isEmpty()` |
| **OllamaProvider** | HTTP connectivity to Ollama server + model availability |

### Adding Custom Routing Logic

To implement provider-specific routing (e.g., route by model name), create a custom `Router` implementation:

```java
public class ModelBasedRouter implements Router {
    @Override
    public LlmProvider route(ChatCompletionRequest request) {
        // Route based on model name or other criteria
        if (request.model().contains("gemini")) {
            return ctx.getBean(GeminiProvider.class);
        }
        return ctx.getBean(OllamaProvider.class);
    }
}
```

Then wire it in `JunctionAutoConfiguration.java` instead of `RoundRobinRouter`.

## Security Features

### API Key Authentication

**Location**: `junction-core/src/main/java/io/junction/gateway/core/security/`

- **ApiKey.java**: Immutable API key entity with tier-based limits (FREE, PRO, ENTERPRISE)
- **ApiKeyValidator.java**: Validates API keys and checks IP restrictions
- **ApiKeyRepository.java**: Interface for API key storage (memory, file, H2, PostgreSQL)
- **InMemoryApiKeyRepository.java**: In-memory implementation for development

**Key Features:**
- Tier-based rate limiting (per-minute, per-day, per-month)
- IP whitelist support with CIDR range checking
- API key expiration and revocation
- Request counting and last-used tracking

### Rate Limiting

**Location**: `junction-core/src/main/java/io/junction/gateway/core/security/RateLimiter.java`

- **RateLimiter.java**: Interface with three time windows (minute, day, month)
- **IpRateLimiter.java**: IP-based rate limiting
- **InMemoryRateLimiter.java**: In-memory implementation

**Rate Limit Tiers:**
| Tier | Per Minute | Per Day | Per Month |
|------|------------|---------|-----------|
| FREE | 20 | 200 | 1,000 |
| PRO | 100 | 5,000 | 50,000 |
| ENTERPRISE | 1,000 | 50,000 | 500,000 |

### Client Compatibility Adapters

**Location**: `junction-starter/src/main/java/io/junction/gateway/starter/clientcompat/`

The client compatibility system detects LLM clients and applies patches to ensure proper tool calling behavior.

**Key Classes:**
- **ClientAdapterConfig.java**: YAML configuration for client adapters
- **ClientDetectionService.java**: Detects client via HTTP headers
- **ClientCompatibilityService.java**: Applies request/response patches
- **ClientAdapterLoader.java**: Loads adapter configs from classpath/external directory

**Built-in Adapters:**
| Adapter | ID | Detection |
|---------|-----|-----------|
| Cline Global | `cline-global` | User-Agent contains "Cline/" |
| Roo Code | `roo-code` | User-Agent contains "RooCode" |

**Adapter Configuration** (`junction-starter/src/main/resources/client-adapters/`):
```yaml
id: "cline-global"
name: "Cline Global"
description: "Fixes tool calling strictness for Cline 3.x"

detection:
  header-contains:
    User-Agent: "Cline/"

patches:
  system-prompt-injection:
    enabled: true
    position: "append"
    content: |
      # TOOL EXECUTION CRITICAL RULES...
  
  response-transforms:
    - type: "fix-missing-xml-tags"
      enabled: true
    - type: "convert-tool-args-to-object"
      enabled: true
```

**Response Transform Types:**
- `fix-missing-xml-tags`: Fixes missing XML tags in tool calls
- `validate-xml-structure`: Full XML validation (placeholder)
- `convert-tool-args-to-object`: Convert tool args from string to object

## Common Development Tasks

### Adding a New LLM Provider

1. **Create provider class** in `junction-core`:
```java
package io.junction.gateway.core.provider;

public final class NewProvider implements LlmProvider {
    // Implement all methods
}
```

2. **Update sealed interface** in `LlmProvider.java`:
```java
public sealed interface LlmProvider permits 
    GeminiProvider,
    OllamaProvider,
    NewProvider {  // Add here
```

3. **Add Spring bean** in `JunctionAutoConfiguration.java`:
```java
@Bean
@ConditionalOnProperty(prefix = "junction.providers.new", name = "enabled", havingValue = "true")
public NewProvider newProvider(JunctionProperties props) {
    return new NewProvider(props.getNew().getApiKey());
}
```

4. **Add properties** in `JunctionProperties.java`:
```java
private NewProviderProperties newProvider = new NewProviderProperties();

public static class NewProviderProperties {
    private boolean enabled;
    private String apiKey;
    // getters/setters
}
```

5. **Update router** to include new provider in `JunctionAutoConfiguration.java`:
```java
if (props.getNewProvider().isEnabled()) {
    providers.add(ctx.getBean(NewProvider.class));
}
```

### Modifying Request/Response Models

**Location**: `junction-core/src/main/java/io/junction/gateway/core/model/`

Key classes:
- `ChatCompletionRequest.java` - OpenAI-compatible request format
- `ChatCompletionResponse.java` - Non-streaming response
- `ChatCompletionChunk.java` - Streaming response chunk
- `ProviderResponse.java` - Internal provider-specific response

**Important**: Maintain OpenAI API compatibility. Test with:
```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model": "test", "messages": [{"role": "user", "content": "hi"}]}'
```

### Adding New Routing Logic

1. Create router implementation in `junction-core/src/main/java/io/junction/gateway/core/router/`
2. Implement `Router` interface
3. Wire in `JunctionAutoConfiguration.java` (replace or conditionally create `RoundRobinRouter`)

### Adding a New Client Adapter

1. Create YAML file in `junction-starter/src/main/resources/client-adapters/`
2. Define detection rules based on HTTP headers
3. Configure patches (system prompt injection, response transforms)
4. Restart Junction (or wait for hot-reload if enabled)

## Build & Test

### Maven Commands

```bash
# Full build (from root)
mvn clean install

# Skip tests for faster build
mvn clean install -DskipTests

# Run specific module tests
mvn test -pl junction-core

# Build single module
mvn install -pl junction-core

# Run samples
mvn spring-boot:run -pl junction-samples
```

### Testing Requirements

- **Unit tests**: Use JUnit 5 (Jupiter)
- **Integration tests**: Use Spring Boot Test in `junction-samples`
- **Manual testing**: Use provided curl commands

### Prerequisites for Running

1. **Java 25+** with preview features enabled (configured in `pom.xml`)
2. **Maven 3.9+**
3. **Ollama** running locally (for Ollama provider): `ollama run llama3.1`
4. **Gemini API key** (optional, for Gemini provider)

## Code Conventions

### Package Structure
- `io.junction.gateway.core.*` - Core module
- `io.junction.gateway.starter.*` - Spring Boot starter
- `io.junction.samples.*` - Sample application

### Naming Conventions
- Interfaces: No prefix (e.g., `Router`, `LlmProvider`)
- Implementations: Descriptive suffix (e.g., `RoundRobinRouter`, `GeminiProvider`)
- Configuration: `*Properties`, `*Configuration`, `*AutoConfiguration`

### Error Handling
- Core exceptions in `junction-core/src/main/java/io/junction/gateway/core/exception/`
- `NoProviderAvailableException` - When router can't find healthy provider
- `ProviderException` - Provider-specific errors
- `RouterException` - Routing failures
- `ApiKeyAuthenticationException` - API key validation failures
- `IpRateLimitExceededException` - IP rate limit exceeded

## Jackson Configuration

**Location**: `junction-starter/src/main/java/io/junction/gateway/starter/config/JacksonConfig.java`

- Uses **Jackson 3** (`tools.jackson.core`)
- Custom deserializers for OpenAI message format
- Compact JSON for SSE streaming (`INDENT_OUTPUT: false`)

### Jackson 3 Migration Notes

**Important**: Jackson 3 uses a hybrid package structure:

| Component | Jackson 2 | Jackson 3 |
|-----------|-----------|-----------|
| Core/Streaming | `com.fasterxml.jackson.core` | `tools.jackson.core` |
| Databind | `com.fasterxml.jackson.databind` | `tools.jackson.databind` |
| **Annotations** | `com.fasterxml.jackson.annotation` | **`com.fasterxml.jackson.annotation`** (unchanged) |

**Key Discovery**: Jackson 3 databind POM explicitly states: *"Annotations remain at Jackson 2.x group id"*. This means:
- Use `tools.jackson.databind.*` for databind classes (ObjectMapper, JsonNode, etc.)
- Use `tools.jackson.core.*` for streaming API (JsonParser, JsonGenerator)
- **Continue using `com.fasterxml.jackson.annotation.*` for annotations** (@JsonInclude, @JsonProperty, etc.)

**Example imports**:
```java
// Jackson 3 databind classes
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

// Jackson 3 streaming API
import tools.jackson.core.JsonParser;

// Annotations remain at Jackson 2 package (compatible with Jackson 3)
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
```

## Logging

**Configuration**: `junction-starter/src/main/resources/logback-spring.xml`

- Per-request log files in `junction-samples/logs/`
- Custom appender: `PerRequestFileAppender.java`
- Log levels: DEBUG for `io.junction.gateway`, INFO for Spring Web

## API Compatibility

The gateway exposes an **OpenAI-compatible** endpoint:

```
POST /v1/chat/completions
```

**Supported parameters:**
- `model`: Model name (mapped to provider)
- `messages`: Array of `{role, content}` objects
- `stream`: Boolean for streaming responses

**Response format**: OpenAI-compatible JSON/Server-Sent Events

## Troubleshooting

### Common Issues

1. **Java version errors**: Ensure Java 25+ is installed and `JAVA_HOME` is set
2. **Maven build failures**: Run `mvn clean` before `mvn install`
3. **Ollama connection refused**: Verify Ollama is running: `ollama list`
4. **Provider not found**: Check `application.yml` provider `enabled` flags
5. **API key authentication failed**: Verify `junction.security.api-key.required` setting
6. **IP not allowed**: Check `junction.security.ip-whitelist.allowed-ips` configuration

### Debug Logging

Enable debug logging in `application.yml`:
```yaml
logging:
  level:
    io.junction.gateway: DEBUG
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Java | 25 | Language runtime |
| Spring Boot | 4.0.3 | Web framework, DI |
| Jackson | 3.0.4 | JSON processing |
| SLF4J | (managed) | Logging facade |

## Security Considerations

- API keys stored in environment variables or `application.yml`
- Never commit real API keys to version control
- Use `${ENV_VAR:default}` pattern for sensitive config
- IP whitelist with CIDR range support
- Rate limiting per API key tier

## Performance Notes

- Virtual threads handle concurrent requests efficiently
- Stream Gatherers process responses with minimal memory overhead
- Scoped Values avoid ThreadLocal contention
- Consider connection pooling for HTTP clients in providers

## Future Enhancements (Agent Guidance)

When extending this project, consider:

1. **Additional Providers**: Anthropic Claude, OpenAI, Azure OpenAI
2. **Advanced Routing**: Latency-based, cost-based, model-capability-based
3. **Caching**: Response caching for identical requests
4. **Rate Limiting**: Per-client rate limiting
5. **Observability**: Additional exporters, dashboards, and backend-specific integrations beyond OTLP/Prometheus
6. **Authentication**: API key management, client authentication

## File Reference Quick Links

### Core Module
- `junction-core/src/main/java/io/junction/gateway/core/provider/LlmProvider.java`
- `junction-core/src/main/java/io/junction/gateway/core/router/Router.java`
- `junction-core/src/main/java/io/junction/gateway/core/router/RoundRobinRouter.java`
- `junction-core/src/main/java/io/junction/gateway/core/model/ChatCompletionRequest.java`
- `junction-core/src/main/java/io/junction/gateway/core/context/RequestContext.java`
- `junction-core/src/main/java/io/junction/gateway/core/gatherer/OpenAIAdapterGatherer.java`

### Security Module
- `junction-core/src/main/java/io/junction/gateway/core/security/ApiKey.java`
- `junction-core/src/main/java/io/junction/gateway/core/security/ApiKeyValidator.java`
- `junction-core/src/main/java/io/junction/gateway/core/security/RateLimiter.java`
- `junction-core/src/main/java/io/junction/gateway/core/security/IpRateLimiter.java`

### Starter Module
- `junction-starter/src/main/java/io/junction/gateway/starter/JunctionAutoConfiguration.java`
- `junction-starter/src/main/java/io/junction/gateway/starter/JunctionProperties.java`
- `junction-starter/src/main/java/io/junction/gateway/starter/GatewayController.java`
- `junction-starter/src/main/java/io/junction/gateway/starter/config/JacksonConfig.java`

### Client Compatibility
- `junction-starter/src/main/java/io/junction/gateway/starter/clientcompat/ClientCompatibilityService.java`
- `junction-starter/src/main/java/io/junction/gateway/starter/clientcompat/ClientDetectionService.java`
- `junction-starter/src/main/resources/client-adapters/README.md`

### Configuration
- `junction-samples/src/main/resources/application.yml`
- `junction-starter/src/main/resources/logback-spring.xml`

---

**Last Updated**: 2026-03-21
**Project Version**: 0.0.4
**Java Version**: 25
