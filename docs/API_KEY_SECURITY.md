# API Key Security

> Version: `0.0.2`  
> Status: Available in the current public release

## Overview

Junction Gateway includes API key validation for the protected chat completions and model listing endpoints.

Supported in `0.0.2`:
- In-memory API key storage
- SHA-256 hashed key storage
- Authentication via `X-API-Key` or `Authorization: Bearer`
- Tier-based rate limiting
- Optional IP allowlisting
- Optional per-key model restrictions
- OpenAI-style error responses

Not yet implemented in `0.0.2`:
- File-backed API key storage
- H2-backed API key storage
- PostgreSQL-backed API key storage
- Rate limit response headers

## Quick Start

```yaml
junction:
  security:
    api-key:
      required: true
      storage: memory
      preconfigured:
        - key: ${JUNCTION_API_KEY_1:}
          name: "Primary Key"
          tier: ENTERPRISE
```

## Using the API

Both protected endpoints accept API keys via `X-API-Key` or `Authorization: Bearer`.

Chat completions:

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${JUNCTION_API_KEY_1}" \
  -d '{
    "model": "llama3.1",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": false
  }'
```

Model listing:

```bash
curl http://localhost:8080/v1/models \
  -H "Authorization: Bearer ${JUNCTION_API_KEY_1}"
```

## Key Format

Keys must start with `junc_` and contain a sufficiently long alphanumeric suffix.

Example:

```text
junc_aB3dE5fG7hI9jK1lM2nO3pQ4rS5tU6vW7xY8zA0
```

## Tiers

| Tier | Per Minute | Per Day | Per Month |
|------|------------|---------|-----------|
| FREE | 20 | 200 | 1,000 |
| PRO | 100 | 5,000 | 50,000 |
| ENTERPRISE | 1,000 | 50,000 | 500,000 |

## Current Storage Model

`0.0.2` uses the in-memory repository by default.

Implications:
- keys are lost on restart
- this is acceptable for local development and simple single-node deployments
- persistent backends should be treated as future work until implemented

## Error Format

Authentication and authorization failures use an OpenAI-style wrapper:

```json
{
  "error": {
    "message": "Invalid API key.",
    "type": "invalid_key",
    "code": "401"
  }
}
```
