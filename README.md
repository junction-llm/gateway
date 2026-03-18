# Junction LLM Gateway

[![CI](https://github.com/junction-llm/gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/junction-llm/gateway/actions/workflows/ci.yml)

Junction is an OpenAI-compatible LLM gateway for the JVM. It exposes `/v1/chat/completions`, routes requests to configured providers, and keeps the integration surface simple for local tools and custom clients.

## Status

`v0.0.3` is the current public release.

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

What is not in `v0.0.3`:
- Persistent API key storage backends beyond in-memory
- Advanced routing strategies beyond round-robin
- `dimensions` support for `/v1/embeddings`
- Full OpenAI API surface outside chat, models, and embeddings
- Metrics, tracing, and admin APIs

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
| Ollama | Supported | Best-tested path for `0.0.3` |
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
- `junction.security.api-key.preconfigured`: in-memory API keys for startup
- `junction.security.api-key.storage`: storage type (memory, file, h2, postgresql)
- `junction.security.ip-rate-limit.*`: per-IP throttling
- `junction.security.ip-whitelist.*`: optional IP allowlisting with CIDR support
- `junction.client-adapters.*`: client compatibility adapter settings

## Production Notes

- The sample application is a demo and reference app, not a managed production distribution.
- API keys are stored in-memory in `0.0.3`.
- The deployment files in [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md), [`Caddyfile`](Caddyfile), and [`docker-compose.caddy.yml`](docker-compose.caddy.yml) are example assets and should be customized before public deployment.
- Per-request logs are written under `junction-samples/logs/`.

## Documentation

- API key security: [`docs/API_KEY_SECURITY.md`](docs/API_KEY_SECURITY.md)
- Example deployment guide: [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md)
- Client adapter configuration: [`junction-starter/src/main/resources/client-adapters/README.md`](junction-starter/src/main/resources/client-adapters/README.md)
- Agent guidance for contributors: [`AGENTS.md`](AGENTS.md)

## Compatibility Matrix

| Area | `0.0.3` |
|------|---------|
| Java runtime | Java 25 |
| Build tool | Maven reactor |
| Web stack | Spring MVC on Spring Boot 4 |
| Streaming | SSE via `SseEmitter` |
| Request formats | OpenAI-compatible chat completion payloads with multimodal parsing and `/v1/embeddings` requests |

## License

Apache License 2.0
