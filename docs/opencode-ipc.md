# OpenCode IDE ↔ Process Communication

Reference document for how a JetBrains plugin communicates with a running OpenCode process.
Sources: https://opencode.ai/docs/server/, https://opencode.ai/docs/sdk/, https://opencode.ai/docs/ide/, https://opencode.ai/docs/cli/, https://opencode.ai/docs/plugins/

---

## Architecture Overview

When `opencode` (or `opencode serve`) runs, it starts an **HTTP server** on `127.0.0.1:4096` by default.
The TUI is just a client to this server — meaning any external process (like a JetBrains plugin) can
interact with OpenCode entirely over HTTP.

```
JetBrains Plugin  ──HTTP REST──►  OpenCode HTTP Server (localhost:4096)
                  ◄──SSE events──
```

The full OpenAPI 3.1 spec is published at:
```
http://<hostname>:<port>/doc
```

---

## Starting the Server

### `opencode serve` (headless, no TUI)

```
opencode serve [--port <number>] [--hostname <string>] [--cors <origin>]
```

| Flag | Default | Description |
|---|---|---|
| `--port` | `4096` | Port to listen on |
| `--hostname` | `127.0.0.1` | Hostname to listen on |
| `--mdns` | `false` | Enable mDNS discovery |
| `--cors` | `[]` | Additional browser origins to allow |

### `opencode` (TUI + server)

When you run the plain TUI it also starts a server. Use `--port` and `--hostname` flags to pin the port:

```
opencode --port 4096 --hostname 127.0.0.1
```

You can then connect to its server from the plugin using the known port.

### Authentication (optional)

Set `OPENCODE_SERVER_PASSWORD` to protect the server with HTTP basic auth (username defaults to `opencode`,
or override with `OPENCODE_SERVER_USERNAME`):

```
OPENCODE_SERVER_PASSWORD=your-password opencode serve
```

---

## IDE Integration Pattern

The docs explicitly describe this pattern for IDE plugins:

> "The `/tui` endpoint can be used to drive the TUI through the server. For example, you can prefill or
> run a prompt. **This setup is used by the OpenCode IDE plugins.**"

Recommended flow:
1. Plugin starts `opencode serve --port <port>` as a child process (or detects a running instance)
2. Plugin polls `GET /global/health` to confirm the server is up
3. Plugin subscribes to `GET /event` (SSE) for real-time updates
4. Plugin uses `POST /tui/append-prompt` + `POST /tui/submit-prompt` to send prompts from IDE actions
5. Plugin uses `POST /session/:id/message` for direct session-level messaging

---

## HTTP API Reference

### Global

| Method | Path | Description | Response |
|---|---|---|---|
| `GET` | `/global/health` | Server health and version | `{ healthy: true, version: string }` |
| `GET` | `/global/event` | Global SSE event stream | Event stream |

### Project

| Method | Path | Description | Response |
|---|---|---|---|
| `GET` | `/project` | List all projects | `Project[]` |
| `GET` | `/project/current` | Get the current project | `Project` |

### Path & VCS

| Method | Path | Description | Response |
|---|---|---|---|
| `GET` | `/path` | Get the current path | `Path` |
| `GET` | `/vcs` | Get VCS info for current project | `VcsInfo` |

### Instance

| Method | Path | Description | Response |
|---|---|---|---|
| `POST` | `/instance/dispose` | Dispose the current instance | `boolean` |

### Config

| Method | Path | Description | Response |
|---|---|---|---|
| `GET` | `/config` | Get config info | `Config` |
| `PATCH` | `/config` | Update config | `Config` |
| `GET` | `/config/providers` | List providers and default models | `{ providers: Provider[], default: { [key: string]: string } }` |

### Provider

| Method | Path | Description | Response |
|---|---|---|---|
| `GET` | `/provider` | List all providers | `{ all: Provider[], default: {...}, connected: string[] }` |
| `GET` | `/provider/auth` | Get provider auth methods | `{ [providerID: string]: ProviderAuthMethod[] }` |
| `POST` | `/provider/{id}/oauth/authorize` | Authorize a provider via OAuth | `ProviderAuthAuthorization` |
| `POST` | `/provider/{id}/oauth/callback` | Handle OAuth callback | `boolean` |

### Sessions

| Method | Path | Description | Notes |
|---|---|---|---|
| `GET` | `/session` | List all sessions | Returns `Session[]` |
| `POST` | `/session` | Create a new session | body: `{ parentID?, title? }` |
| `GET` | `/session/status` | Get status for all sessions | Returns `{ [sessionID]: SessionStatus }` |
| `GET` | `/session/:id` | Get session details | Returns `Session` |
| `DELETE` | `/session/:id` | Delete a session | Returns `boolean` |
| `PATCH` | `/session/:id` | Update session properties | body: `{ title? }` |
| `GET` | `/session/:id/children` | Get child sessions | Returns `Session[]` |
| `GET` | `/session/:id/todo` | Get todo list for a session | Returns `Todo[]` |
| `POST` | `/session/:id/init` | Analyze app, create `AGENTS.md` | body: `{ messageID, providerID, modelID }` |
| `POST` | `/session/:id/fork` | Fork a session at a message | body: `{ messageID? }` |
| `POST` | `/session/:id/abort` | Abort a running session | Returns `boolean` |
| `POST` | `/session/:id/share` | Share a session | Returns `Session` |
| `DELETE` | `/session/:id/share` | Unshare a session | Returns `Session` |
| `GET` | `/session/:id/diff` | Get the diff for this session | query: `messageID?` |
| `POST` | `/session/:id/summarize` | Summarize the session | body: `{ providerID, modelID }` |
| `POST` | `/session/:id/revert` | Revert a message | body: `{ messageID, partID? }` |
| `POST` | `/session/:id/unrevert` | Restore reverted messages | Returns `boolean` |
| `POST` | `/session/:id/permissions/:permissionID` | Respond to a permission request | body: `{ response, remember? }` |

### Messages

| Method | Path | Description | Notes |
|---|---|---|---|
| `GET` | `/session/:id/message` | List messages | query: `limit?` |
| `POST` | `/session/:id/message` | Send a message and **wait** for response | body: `{ messageID?, model?, agent?, noReply?, system?, tools?, parts }` |
| `GET` | `/session/:id/message/:messageID` | Get message details | |
| `POST` | `/session/:id/prompt_async` | Send a message **asynchronously** (no wait) | Returns `204 No Content` |
| `POST` | `/session/:id/command` | Execute a slash command | body: `{ messageID?, agent?, model?, command, arguments }` |
| `POST` | `/session/:id/shell` | Run a shell command | body: `{ agent, model?, command }` |

#### Sending a message (body parts)

The `parts` array supports:
- `{ type: "text", text: "..." }` — plain text
- File attachments and other part types per OpenAPI spec

Setting `noReply: true` injects context into the session without triggering an AI response — useful for providing editor context.

### Commands

| Method | Path | Description | Response |
|---|---|---|---|
| `GET` | `/command` | List all available commands | `Command[]` |

### Files

| Method | Path | Description | Response |
|---|---|---|---|
| `GET` | `/find?pattern=<pat>` | Search for text in files | Array of match objects |
| `GET` | `/find/file?query=<q>` | Find files by name (fuzzy) | `string[]` |
| `GET` | `/find/symbol?query=<q>` | Find workspace symbols | `Symbol[]` |
| `GET` | `/file?path=<path>` | List files and directories | `FileNode[]` |
| `GET` | `/file/content?path=<p>` | Read a file | `FileContent` |
| `GET` | `/file/status` | Get status for tracked files | `File[]` |

### Tools (Experimental)

| Method | Path | Description | Response |
|---|---|---|---|
| `GET` | `/experimental/tool/ids` | List all tool IDs | `ToolIDs` |
| `GET` | `/experimental/tool?provider=<p>&model=<m>` | List tools with JSON schemas | `ToolList` |

### LSP, Formatters & MCP

| Method | Path | Description | Response |
|---|---|---|---|
| `GET` | `/lsp` | Get LSP server status | `LSPStatus[]` |
| `GET` | `/formatter` | Get formatter status | `FormatterStatus[]` |
| `GET` | `/mcp` | Get MCP server status | `{ [name: string]: MCPStatus }` |
| `POST` | `/mcp` | Add MCP server dynamically | body: `{ name, config }` |

### Agents

| Method | Path | Description | Response |
|---|---|---|---|
| `GET` | `/agent` | List all available agents | `Agent[]` |

### Logging

| Method | Path | Description | Response |
|---|---|---|---|
| `POST` | `/log` | Write log entry | body: `{ service, level, message, extra? }` |

### TUI Control (Key endpoints for IDE plugins)

These endpoints drive the TUI from outside — the primary mechanism used by IDE plugins.

| Method | Path | Description | Response |
|---|---|---|---|
| `POST` | `/tui/append-prompt` | Append text to the prompt input | `boolean` |
| `POST` | `/tui/submit-prompt` | Submit the current prompt | `boolean` |
| `POST` | `/tui/clear-prompt` | Clear the prompt | `boolean` |
| `POST` | `/tui/execute-command` | Execute a command (`{ command }`) | `boolean` |
| `POST` | `/tui/show-toast` | Show toast (`{ title?, message, variant }`) | `boolean` |
| `POST` | `/tui/open-help` | Open the help dialog | `boolean` |
| `POST` | `/tui/open-sessions` | Open the session selector | `boolean` |
| `POST` | `/tui/open-themes` | Open the theme selector | `boolean` |
| `POST` | `/tui/open-models` | Open the model selector | `boolean` |
| `GET` | `/tui/control/next` | Wait for next control request | Control request object |
| `POST` | `/tui/control/response` | Respond to a control request (`{ body }`) | `boolean` |

### Auth

| Method | Path | Description | Response |
|---|---|---|---|
| `PUT` | `/auth/:id` | Set authentication credentials | `boolean` |

### Events (SSE)

| Method | Path | Description | Response |
|---|---|---|---|
| `GET` | `/event` | Subscribe to server-sent events | SSE stream |

The first event is always `server.connected`, then bus events follow.

### Docs

| Method | Path | Description | Response |
|---|---|---|---|
| `GET` | `/doc` | OpenAPI 3.1 specification | HTML with spec |

---

## Server-Sent Events (SSE)

Subscribe to `GET /event` (or `GET /global/event`) to receive real-time events.

### Available Event Types

#### Command Events
- `command.executed`

#### File Events
- `file.edited`
- `file.watcher.updated`

#### Installation Events
- `installation.updated`

#### LSP Events
- `lsp.client.diagnostics`
- `lsp.updated`

#### Message Events
- `message.part.removed`
- `message.part.updated`
- `message.removed`
- `message.updated`

#### Permission Events
- `permission.asked`
- `permission.replied`

#### Server Events
- `server.connected` ← always first event on connection

#### Session Events
- `session.created`
- `session.compacted`
- `session.deleted`
- `session.diff`
- `session.error`
- `session.idle` ← AI finished responding
- `session.status`
- `session.updated`

#### Todo Events
- `todo.updated`

#### Shell Events
- `shell.env`

#### Tool Events
- `tool.execute.after`
- `tool.execute.before`

#### TUI Events
- `tui.prompt.append`
- `tui.command.execute`
- `tui.toast.show`

---

## CLI Commands Relevant to Plugin Lifecycle

```
# Start headless server (no TUI)
opencode serve --port 4096

# Start TUI with pinned port (server accessible at same port)
opencode --port 4096

# Run a single prompt non-interactively (scripting/automation)
opencode run "Explain closures in JavaScript"

# Attach to a running serve instance (avoids MCP cold boot)
opencode run --attach http://localhost:4096 "Refactor this function"

# Attach TUI to a remote/already-running backend
opencode attach http://localhost:4096
```

### Global Flags

| Flag | Description |
|---|---|
| `--port` | Port to listen on |
| `--hostname` | Hostname to listen on |
| `--continue` / `-c` | Continue the last session |
| `--session` / `-s` | Session ID to continue |
| `--model` / `-m` | Model to use (`provider/model`) |
| `--agent` | Agent to use |
| `--prompt` | Prompt to use on startup |

### Relevant Environment Variables

| Variable | Type | Description |
|---|---|---|
| `OPENCODE_SERVER_PASSWORD` | string | Enable basic auth for `serve`/`web` |
| `OPENCODE_SERVER_USERNAME` | string | Override basic auth username (default `opencode`) |
| `OPENCODE_CLIENT` | string | Client identifier (defaults to `cli`) |
| `OPENCODE_CONFIG` | string | Path to config file |
| `OPENCODE_CONFIG_DIR` | string | Path to config directory |

---

## MCP: OpenCode Calling Back into the IDE

The reverse direction — OpenCode calling tools exposed by the JetBrains IDE — is handled via MCP.

The JetBrains IDE exposes an MCP server (via the JetBrains MCP plugin) on `localhost:64342/sse`.
This is configured in `opencode.json` so OpenCode can call IDE tools as part of its tool execution:

```json
{
  "mcp": {
    "jetbrains": {
      "type": "remote",
      "url": "http://localhost:64342/sse",
      "enabled": true
    }
  }
}
```

This is already set up in this project's `opencode.json`.

### MCP Config Options

#### Local MCP Server
```json
{
  "mcp": {
    "my-local-mcp": {
      "type": "local",
      "command": ["npx", "-y", "my-mcp-command"],
      "enabled": true,
      "environment": { "MY_ENV_VAR": "value" }
    }
  }
}
```

#### Remote MCP Server (SSE)
```json
{
  "mcp": {
    "my-remote-mcp": {
      "type": "remote",
      "url": "https://my-mcp-server.com",
      "enabled": true,
      "headers": { "Authorization": "Bearer MY_API_KEY" }
    }
  }
}
```

---

## JavaScript/TypeScript SDK (Reference — not for JVM)

The `@opencode-ai/sdk` package provides a typed client for JS/TS environments. The JetBrains plugin
cannot use this directly (JVM), but the API shapes are instructive for making equivalent HTTP calls from Kotlin.

### Key SDK Methods (maps to HTTP endpoints above)

```typescript
import { createOpencodeClient } from "@opencode-ai/sdk"

const client = createOpencodeClient({ baseUrl: "http://localhost:4096" })

// Health
await client.global.health()

// Sessions
const session = await client.session.create({ body: { title: "My session" } })
await client.session.list()

// Send a prompt and wait for AI response
const result = await client.session.prompt({
  path: { id: session.id },
  body: {
    parts: [{ type: "text", text: "Hello!" }],
    model: { providerID: "anthropic", modelID: "claude-3-5-sonnet-20241022" },
  },
})

// Inject context without triggering AI response
await client.session.prompt({
  path: { id: session.id },
  body: { noReply: true, parts: [{ type: "text", text: "Current file: Foo.kt" }] },
})

// TUI control
await client.tui.appendPrompt({ body: { text: "Add this to prompt" } })
await client.tui.submitPrompt()
await client.tui.showToast({ body: { message: "Done", variant: "success" } })

// Real-time events
const events = await client.event.subscribe()
for await (const event of events.stream) {
  console.log(event.type, event.properties)
}
```

---

## Kotlin/JVM Implementation Notes

Since `@opencode-ai/sdk` is JS-only, HTTP calls from the plugin must be made with a JVM HTTP client.

### Recommended approach

- **HTTP client**: Java 11+ built-in `java.net.http.HttpClient` (no extra dependencies), or OkHttp
- **JSON**: Gson or kotlinx.serialization (already available in IntelliJ plugin SDK)
- **SSE**: Use `java.net.http.HttpClient` with a streaming body handler, or OkHttp's `EventSource`

### Example: Health check (replaces raw TCP probe in `InstalledPanel.kt`)

```kotlin
val client = HttpClient.newHttpClient()
val request = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:$port/global/health"))
    .GET()
    .build()
val response = client.send(request, HttpResponse.BodyHandlers.ofString())
val healthy = response.statusCode() == 200
```

### Example: Append prompt from current editor selection

```kotlin
val body = """{"text": "$selectedText"}"""
val request = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:$port/tui/append-prompt"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(body))
    .build()
client.send(request, HttpResponse.BodyHandlers.ofString())
```

### Example: Subscribe to SSE events

```kotlin
// Use OkHttp EventSource or manually read the chunked stream
val request = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:$port/event"))
    .header("Accept", "text/event-stream")
    .GET()
    .build()
// Stream with a Flow or coroutine-based line reader
```
