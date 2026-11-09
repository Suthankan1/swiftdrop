# SwiftDrop AI delivery assistant

A small Spring Boot service that answers delivery questions with **Gemini function calling**. The model never
sees the database: it can only call four read-only tools, and each tool calls an existing SwiftDrop API the same
way the user could.

| Tool | Calls | Who |
| --- | --- | --- |
| `track_parcel` | Delivery `GET /api/v1/tracking/{trackingNumber}` | anyone |
| `list_zones` | Pricing `GET /api/v1/zones` | anyone |
| `get_quote` | Pricing `POST /api/v1/quotes` (Standard or Same Day) | anyone |
| `list_my_parcels` | Delivery `GET /api/v1/parcels` with the caller's own bearer token | signed-in users only |

Tool results are reduced before they reach the model: no street addresses, recipient names or phone numbers.
The assistant cannot book, cancel or change anything.

## API (through the gateway)

- `GET /api/v1/assistant/status` returns `{ "enabled": true|false }`. The frontend uses it to choose AI mode or
  its offline keyword mode.
- `POST /api/v1/assistant/chat` with `{ "messages": [{ "role": "user"|"assistant", "text": "..." }] }` (1 to 20
  messages, each up to 2000 characters, last one from the user) returns `{ "reply": "...", "toolsUsed": [...] }`.
  Anonymous calls are allowed; a bearer token, if sent, is validated and used for `list_my_parcels`.
  The gateway rate-limits these calls per IP because each one costs Gemini tokens.

## MCP (Model Context Protocol)

`POST /api/v1/assistant/mcp` speaks MCP over Streamable HTTP (JSON-RPC 2.0, JSON responses; protocol versions
2025-06-18, 2025-03-26 and 2024-11-05). It exposes the same four read-only tools, so an MCP client such as an IDE or
desktop AI app can use SwiftDrop data under the same rules: public tools anonymously, `list_my_parcels` only with the
user's own `Authorization: Bearer` token. It does not need a Gemini key. Supported methods: `initialize`, `ping`,
`tools/list`, `tools/call`; notifications get `202 Accepted`. `GET` returns `405` (no server-initiated stream).
The gateway applies the assistant rate limit to this path too.

Example client entry (URL-based MCP server):

```json
{ "mcpServers": { "swiftdrop": { "url": "http://localhost:8080/api/v1/assistant/mcp" } } }
```

## Configuration

| Variable | Default | Notes |
| --- | --- | --- |
| `GEMINI_API_KEY` | empty | Empty keeps the assistant disabled (`/status` reports `enabled: false`, `/chat` returns 503). Sent only in the `x-goog-api-key` header; never logged or returned. |
| `GEMINI_MODEL` | `gemini-2.5-flash` | Any Gemini model that supports function calling. |
| `PRICING_SERVICE_URL`, `DELIVERY_SERVICE_URL` | localhost | Internal service URLs. |
| `ASSISTANT_MAX_TOOL_ROUNDS` | `4` | Upper bound on tool calls per answer. |

Put the key in `platform/compose/.env` (git-ignored) as `GEMINI_API_KEY=...` and restart the `ai-assistant` container.

## Privacy

User messages and the reduced tool results are sent to Google's Gemini API. Do not enable a production key
without updating the privacy notice accordingly.

## Tests

`./mvnw verify` runs the service against a fake Gemini server and fake SwiftDrop APIs (no key or network needed).
