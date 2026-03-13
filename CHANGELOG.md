# Changelog

All notable changes to this project will be documented in this file.

## 0.0.2 - 2026-03-13

Expanded the public gateway surface and authentication compatibility.

### Added

- OpenAI-compatible `GET /v1/models` endpoint
- Provider model discovery for Ollama and Gemini
- Model list caching to reduce repeated provider lookups
- Integration coverage for Bearer auth, dual-header validation, and model listing

### Changed

- Accepted API keys from `Authorization: Bearer` in addition to `X-API-Key`
- Clarified API key validation errors to mention both supported auth headers

### Known Limitations

- API key storage is still in-memory only in `0.0.2`
- Routing strategy is still limited to round-robin
- The public API surface is still focused on chat completions and model listing
- Deployment assets are examples and require environment-specific customization

## 0.0.1 - 2026-03-04

First public release.

### Added

- OpenAI-compatible `POST /v1/chat/completions` endpoint
- Streaming and non-streaming response support
- Ollama and Gemini provider integrations
- Round-robin provider routing with health checks
- API key validation with in-memory key storage
- IP rate limiting and optional IP allowlisting
- Client compatibility adapter loading and patching
- Per-request log files
- Spring Boot starter auto-configuration

### Changed

- Fixed SSE delivery to stream chunks incrementally instead of buffering the full response
- Aligned JSON and SSE request handling so both paths apply client compatibility patches
- Hardened sample configuration by removing insecure default API keys
- Added release metadata and Java preview compiler/test flags to Maven build
- Improved per-request logging so log files are written to the correct trace ID

### Known Limitations

- API key storage is in-memory only in `0.0.1`
- Routing strategy is limited to round-robin
- The public API surface is limited to chat completions
- Deployment assets are examples and require environment-specific customization
