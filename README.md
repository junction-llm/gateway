# Junction LLM Gateway

[![CI](https://github.com/junction-llm/gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/junction-llm/gateway/actions/workflows/ci.yml)

Junction is an OpenAI-compatible LLM gateway for the JVM. It exposes `/v1/chat/completions`, routes requests to configured providers, and keeps the integration surface simple for local tools and custom clients.

## Status

`v0.0.4` is the current public release.

What is ready in `v0.0.3`:
- OpenAI-compatible `POST /v1/chat/completions`
- OpenAI-compatible `GET /v1/models`
- OpenAI-compatible `POST /v1/embeddings`
- Streaming and non-streaming responses
- Ollama provider support
- Gemini provider support
- Round-robin routing across healthy providers
- API key validation via `X-API-Key` or `Authorization: Bearer`
- Tier-based rate limiting (FREE, PRO, ENTERPRISE)
- Image-capable chat request routing and Ollama multimodal payload handling
- IP rate limiting and optional IP allowlisting with CIDR support
- Client compatibility adapters for Cline and Roo Code
- Optional `base64` embeddings output for `/v1/embeddings`
- Per-request log files
- Spring Boot starter auto-configuration
- Persistent API key storage backends for `file`, `h2`, and `postgresql`
- Actuator-based observability with protected `metrics`, `junction`, and opt-in `junctioncache` endpoints
- W3C trace propagation for inbound and outbound provider calls (`traceparent`, `tracestate`, `baggage`)
- Distributed tracing export through OTLP plus protected `/actuator/prometheus` support
- Stable gateway correlation via `X-Trace-ID`, with distributed trace/span IDs exposed separately in logs as `otelTraceId` and `otelSpanId`

What is not in `v0.0.4`:
- Advanced routing strategies beyond round-robin
- `dimensions` support for `/v1/embeddings`
- Full OpenAI API surface outside chat, models, and embeddings
- Admin APIs

## Requirements

| Component | Version |
|-----------|---------|
| Java | 25 |
| Maven | 3.9+ |
| Spring Boot | 4.0.3 |
| Jackson | 3.0.4 |

## Provider Support

| Provider | Status | Notes |
|----------|--------|-------|
| Ollama | Supported | Best-tested path for `0.0.4`|
| Gemini | Supported | Optional, requires `GEMINI_API_KEY` |

## Quick Start

```bash
# Build the project
mvn clean install

# Generate an API key for the sample application
export JUNCTION_API_KEY_1="$(openssl rand -hex 24 | tr 'a-f' 'A-F' | cut -c1-48 | xargs -I {} echo junc_{})"

# Optional: enable Gemini explicitly
# export GEMINI_API_KEY="your-key"
# export JUNCTION_PROVIDERS_GEMINI_ENABLED=true

# Optional: load external client adapters
# export JUNCTION_CLIENT_ADAPTERS_DIR="/absolute/path/to/adapters"

# Run the sample application
cd junction-samples
mvn spring-boot:run
```

## Example Requests

Junction accepts API keys through either `X-API-Key` or `Authorization: Bearer`.

Streaming:

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -H "X-API-Key: ${JUNCTION_API_KEY_1}" \
  -d '{
    "model": "llama3.1",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": true
  }'
```

Non-streaming:

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "X-API-Key: ${JUNCTION_API_KEY_1}" \
  -d '{
    "model": "llama3.1",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": false
  }'
```

Multimodal (image) request:

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "X-API-Key: ${JUNCTION_API_KEY_1}" \
  -d '{
    "model": "qwen3.5",
    "messages": [
      {
        "role": "user",
        "content": [
          {"type": "text", "text": "What do you see in this image?"},
          {"type": "image_url", "image_url": {"url": "https://example.com/example.png"}}
        ]
      }
    ],
    "stream": false
  }'
```

Model listing:

```bash
curl http://localhost:8080/v1/models \
  -H "Accept: application/json" \
  -H "Authorization: Bearer ${JUNCTION_API_KEY_1}"
```

Embeddings:

```bash
curl -X POST http://localhost:8080/v1/embeddings \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "X-API-Key: ${JUNCTION_API_KEY_1}" \
  -d '{
    "model": "embeddinggemma",
    "input": ["Hello world", "OpenAI-compatible embeddings"]
  }'
```

```bash
curl -X POST http://localhost:8080/v1/embeddings \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "X-API-Key: ${JUNCTION_API_KEY_1}" \
  -d '{
    "model": "embeddinggemma",
    "input": "Hello world",
    "encoding_format": "base64"
  }'
```

Expected JSON shape (float):

```json
{
  "object": "list",
  "data": [{"object":"embedding","embedding":[0.1,0.2,0.3],"index":0}],
  "model": "embeddinggemma",
  "usage": {"prompt_tokens": 12, "total_tokens": 12}
}
```

## Request Formats

Chat completions:

- `messages` may be a string per message (legacy style) or an array of parts.
- Multimodal inputs use `type: "image_url"` entries with an `image_url.url` string.
- `image_url` sources can be `data:` URLs (Base64 payload required) or `http(s)` URLs.

Embeddings:

- `POST /v1/embeddings` requires `model` and `input`.
- `input` accepts a string or an array of strings.
- Optional `encoding_format` supports `float` (default) and `base64`.
- `dimensions` is currently unsupported and will return an `invalid_request`.

## Configuration

The sample configuration lives in `junction-samples/src/main/resources/application.yml`.

Important settings:
- `junction.providers.ollama.enabled`: enable Ollama
- `junction.providers.gemini.enabled`: enable Gemini
- `junction.security.api-key.required`: require API keys
- `junction.security.api-key.preconfigured`: startup seed keys; additive across all storage backends
- `junction.security.api-key.storage`: storage type (`memory`, `file`, `h2`, `postgresql`)
- `junction.security.ip-rate-limit.*`: per-IP throttling
- `junction.security.ip-whitelist.*`: optional IP allowlisting with CIDR support
- `junction.client-adapters.*`: client compatibility adapter settings
- `junction.logging.chat-response.enabled`: optional chat response-body logging to per-request files
- `junction.observability.security.*`: built-in Actuator authentication and public health behavior
- `junction.observability.admin.cache-write-enabled`: opt-in authenticated cache eviction endpoint
- `management.endpoints.web.exposure.include`: defaults to `health`; exposing `info`, `metrics`, `junction`, or `junctioncache` requires `JUNCTION_OBSERVABILITY_SECURITY_PASSWORD`

### Persistent API-key storage on `main`

Example file-backed storage:

```yaml
junction:
  security:
    api-key:
      storage: file
      file-path: ${JUNCTION_API_KEYS_FILE:./api-keys.yml}
```

Example H2-backed storage:

```yaml
junction:
  security:
    api-key:
      storage: h2
      h2-url: jdbc:h2:file:${JUNCTION_H2_PATH:./data/junction};DB_CLOSE_DELAY=-1
      h2-username: sa
      h2-password: ""
```

Example PostgreSQL-backed storage:

```yaml
junction:
  security:
    api-key:
      storage: postgresql
      postgresql-url: ${JUNCTION_POSTGRES_URL:jdbc:postgresql://localhost:5432/junction}
      postgresql-username: ${JUNCTION_POSTGRES_USER:junction}
      postgresql-password: ${JUNCTION_POSTGRES_PASSWORD:}
```

Notes:
- `memory` remains the default backend.
- `preconfigured` keys are additive seed data for all backends; missing keys are inserted on startup and existing stored keys are left in place.
- The file backend rewrites the YAML store on key mutations and usage updates, so it is best suited to single-node, lower-throughput deployments.
- PostgreSQL storage requires the PostgreSQL JDBC driver on the application classpath.
- Rate-limit windows remain in-memory in this iteration; persistent storage covers API keys and per-key usage stats.

Local PostgreSQL development:

```bash
docker compose -f docker-compose.postgresql.yml up -d

export JUNCTION_SECURITY_API_KEY_STORAGE=postgresql
export JUNCTION_SECURITY_API_KEY_POSTGRESQL_URL=jdbc:postgresql://localhost:5432/junction
export JUNCTION_SECURITY_API_KEY_POSTGRESQL_USERNAME=junction
export JUNCTION_SECURITY_API_KEY_POSTGRESQL_PASSWORD=junction

mvn spring-boot:run -pl junction-samples
```

Notes:
- The provided Compose file bootstraps PostgreSQL 18 with database `junction`, user `junction`, and password `junction` by default.
- On a fresh Docker volume, PostgreSQL imports the schema from `junction-starter/src/main/resources/sql/junction-api-key-schema.sql` automatically.
- The application also runs the same schema on startup, so reused volumes and existing databases stay aligned.
- API keys are stored in the `junction_api_keys` table as hashed key material plus metadata and usage stats; raw API keys are never written to PostgreSQL.

### Management endpoints on the main branch

The sample application now defaults to a public-safe Actuator surface:

- `GET /actuator/health` is the only web-exposed management endpoint by default
- health details remain minimal for anonymous callers when `management.endpoint.health.show-details=when_authorized`
- protected endpoints such as `info`, `metrics`, `junction`, and `junctioncache` require HTTP Basic authentication through `junction.observability.security.*`

When you expose protected management endpoints, set a password first:

```bash
export JUNCTION_OBSERVABILITY_SECURITY_PASSWORD='replace-me'
```

For example:

```yaml
junction:
  observability:
    security:
      enabled: true
      public-health-enabled: true
      username: ${JUNCTION_OBSERVABILITY_SECURITY_USERNAME:actuator}
      password: ${JUNCTION_OBSERVABILITY_SECURITY_PASSWORD:}

management:
  endpoint:
    health:
      show-details: when_authorized
  endpoints:
    web:
      exposure:
        include: health,info,metrics,junction
```

### Response-body logging

Enable chat response-body logging with either `application.yml`:

```yaml
junction:
  logging:
    chat-response:
      enabled: true
```

or an environment variable:

```bash
export JUNCTION_LOGGING_CHAT_RESPONSE_ENABLED=true
```

When enabled, Junction logs the normalized OpenAI-compatible response body returned by `POST /v1/chat/completions`. This applies to both non-streaming JSON responses and streaming SSE responses. For streaming requests, Junction writes one final assembled response-body entry after the stream completes successfully.

These entries are written only to the per-request log file at `logs/YYYY-MM-DD/<traceId>.log`, relative to the app working directory. The `YYYY-MM-DD` folder follows the JVM/system default timezone, while each log line timestamp remains in UTC (`Z`). For the sample app, that means logs are typically written under `junction-samples/logs/`.

If you override the default Logback configuration, keep the dedicated `io.junction.gateway.payload.chat.response` logger wired to the `PER_REQUEST` appender. The property alone does not route response bodies into the per-request log files when that logger wiring is removed.

## Production Notes

- The sample application is a demo and reference app, not a managed production distribution.
- The public `0.0.4` release stores API keys `in-memory`, `file`, `h2`, and `postgresql`.
- The deployment files in [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md), [`Caddyfile`](Caddyfile), and [`docker-compose.caddy.yml`](docker-compose.caddy.yml) are example assets and should be customized before public deployment.
- Per-request logs are written under `logs/` relative to the app working directory. The dated folder name follows the JVM/system default timezone, and each log line timestamp remains UTC. For the sample app, that is typically `junction-samples/logs/`.

## Documentation

- API key security: [`docs/API_KEY_SECURITY.md`](docs/API_KEY_SECURITY.md)
- Example deployment guide: [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md)
- Client adapter configuration: [`junction-starter/src/main/resources/client-adapters/README.md`](junction-starter/src/main/resources/client-adapters/README.md)
- Agent guidance for contributors: [`AGENTS.md`](AGENTS.md)

## Compatibility Matrix

| Area | `0.0.4` |
|------|---------|
| Java runtime | Java 25 |
| Build tool | Maven reactor |
| Web stack | Spring MVC on Spring Boot 4 |
| Streaming | SSE via `SseEmitter` |
| Request formats | OpenAI-compatible chat completion payloads with multimodal parsing and `/v1/embeddings` requests |

## License

Apache License 2.0
