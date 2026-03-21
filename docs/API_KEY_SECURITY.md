# API Key Security

> Version: `0.0.4`
> Main branch status: `memory`, `file`, `h2`, and `postgresql` backends are available.  

## Overview

Junction Gateway includes API key validation for the protected chat completions, embeddings, and model listing endpoints.

Supported in `0.0.4`:
- In-memory API key storage
- File-backed API key storage
- H2-backed API key storage
- PostgreSQL-backed API key storage
- SHA-256 hashed key storage
- Authentication via `X-API-Key` or `Authorization: Bearer`
- Tier-based rate limiting
- Optional IP allowlisting
- Optional per-key model restrictions
- OpenAI-style error responses

Not yet implemented in `0.0.4`:
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

Persistent backends in `0.0.4`:

```yaml
junction:
  security:
    api-key:
      storage: file
      file-path: ${JUNCTION_API_KEYS_FILE:./api-keys.yml}
```

```yaml
junction:
  security:
    api-key:
      storage: h2
      h2-url: jdbc:h2:file:${JUNCTION_H2_PATH:./data/junction};DB_CLOSE_DELAY=-1
      h2-username: sa
      h2-password: ""
```

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
- `preconfigured` keys are additive seed data across all storage backends.
- The file backend rewrites the YAML file on key mutations and usage updates and is best suited to single-node, lower-throughput deployments.
- PostgreSQL storage requires the PostgreSQL JDBC driver on the application classpath.
- Rate-limit windows remain in-memory in this iteration.

## Local PostgreSQL Development

Start the bundled PostgreSQL dev database:

```bash
docker compose -f docker-compose.postgresql.yml up -d
```

Run the sample app with explicit PostgreSQL overrides:

```bash
export JUNCTION_SECURITY_API_KEY_STORAGE=postgresql
export JUNCTION_SECURITY_API_KEY_POSTGRESQL_URL=jdbc:postgresql://localhost:5432/junction
export JUNCTION_SECURITY_API_KEY_POSTGRESQL_USERNAME=junction
export JUNCTION_SECURITY_API_KEY_POSTGRESQL_PASSWORD=junction

mvn spring-boot:run -pl junction-samples
```

Notes:
- The Compose file uses PostgreSQL 17 with database `junction`, user `junction`, and password `junction` by default.
- Fresh Docker volumes import `junction-starter/src/main/resources/sql/junction-api-key-schema.sql` automatically through `/docker-entrypoint-initdb.d/`.
- The application runs the same schema at startup, so existing databases still self-check and create missing objects.
- Stored rows live in `junction_api_keys` and contain hashed key material, metadata, and usage counters; raw API keys are never persisted.

## Using the API

All protected endpoints accept API keys via `X-API-Key` or `Authorization: Bearer`.

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

Embeddings:

```bash
curl -X POST http://localhost:8080/v1/embeddings \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${JUNCTION_API_KEY_1}" \
  -d '{
    "model": "embeddinggemma",
    "input": ["Hello", "How are you?"],
    "encoding_format": "base64"
  }'
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

Available backends on ``0.0.4`:
- `memory`: keys are lost on restart
- `file`: YAML-backed storage with write-through updates for key changes and usage counts
- `h2`: JDBC-backed persistent storage
- `postgresql`: JDBC-backed persistent storage

Operational notes:
- `preconfigured` keys are added when missing and never delete existing stored keys automatically
- rate-limit windows remain in-memory even when API-key storage is persistent

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
