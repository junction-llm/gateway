# Client Adapter Configurations

This directory contains YAML configuration files for client compatibility adapters.

## What are Client Adapters?

Client adapters allow Junction Gateway to automatically detect different LLM clients (like Cline, Roo Code, Continue, etc.) and apply compatibility patches to ensure proper tool calling behavior.

## How It Works

1. **Detection**: When a request comes in, Junction checks HTTP headers to identify the client
2. **Matching**: The first matching adapter configuration is selected (First Match Wins)
3. **Patching**: Request and response patches are applied automatically

## Configuration Format

```yaml
id: "unique-adapter-id"
name: "Human Readable Name"
description: "What this adapter does"

detection:
  header-contains:             # Substring matches (case-insensitive)
    User-Agent: "Cline/"
  version-range: ">=3.0.0"     # Optional semver range

patches:
  system-prompt-injection:
    enabled: true
    position: "prepend"        # prepend, append, or replace
    content: |
      Your system prompt here...
  
  response-transforms:
    - type: "fix-missing-xml-tags"
      enabled: true
```

## Available Patch Types

### System Prompt Injection
- **prepend**: Add to beginning of existing system message (or create new one)
- **append**: Add to end of existing system message
- **replace**: Replace existing system message entirely

### Response Transforms
- **fix-missing-xml-tags**: Regex-based fixes for common XML tag omissions
- **validate-xml-structure**: (Placeholder) Full XML validation
- **convert-tool-args-to-object**: Convert tool arguments to object format

## Detection Headers

Common headers to detect clients:
- `User-Agent`: Standard user agent string (used by Cline, Roo Code)
- `x-client-name`: Client identifier (cline, roo-code, continue, etc.)
- `x-client-version`: Client version (3.68.0, etc.)
- `x-llm-model`: Model being used (kimi-k2.5, claude-3.5-sonnet, etc.)

## Built-in Adapters

| Adapter | ID | Detection |
|---------|-----|-----------|
| Cline Global | `cline-global` | User-Agent contains "Cline/" |
| Roo Code | `roo-code` | User-Agent contains "RooCode" |

## Creating Custom Adapters

1. Create a new `.yaml` file in this directory (or external directory)
2. Define detection rules based on headers
3. Configure patches to apply
4. Restart Junction (or wait for hot-reload if enabled)

## External Directory

You can also place adapter configs in an external directory specified by the environment variable:
```bash
export JUNCTION_CLIENT_ADAPTERS_DIR="/opt/junction/adapters"
```

External adapters take priority over built-in ones if `external-priority` is enabled.

## Examples

See `cline.global.yaml` for a complete example of a Cline adapter.