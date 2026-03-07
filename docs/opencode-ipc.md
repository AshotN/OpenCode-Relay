# OpenCode IDE <-> Process Communication (LLM Reference)

This document is a practical, LLM-oriented guide for calling a running OpenCode server from an IDE/plugin process.

Canonical source of truth: `http://localhost:4096/doc` (OpenAPI JSON).
This file is synchronized against live spec `openapi: 3.1.1`, `info.version: 0.0.3`.

## What `/doc` Returns

- `GET /doc` serves OpenAPI JSON (`Content-Type: application/json`), not HTML.
- The payload includes all paths, request bodies, query/path params, and schemas.
- When behavior here and `/doc` differ, trust `/doc`.

---

## Architecture

When `opencode` or `opencode serve` runs, an HTTP server is exposed on `127.0.0.1:4096` by default.

```text
JetBrains Plugin  -- HTTP JSON --> OpenCode Server
                 <-- SSE events --
```

The plugin can drive the TUI and/or use direct session APIs.

---

## Authentication

If server-side basic auth is enabled, include an `Authorization: Basic ...` header on API calls.

Environment variables used by the server:
- `OPENCODE_SERVER_PASSWORD`
- `OPENCODE_SERVER_USERNAME` (defaults to `opencode`)

---

## SSE Event Model

### Streams

- `GET /event`: standard event stream.
- `GET /global/event`: global event stream with wrapper object.

`/global/event` yields:

```json
{
  "directory": "...",
  "payload": { "type": "...", "properties": { ... } }
}
```

### Event types in current schema

- `server.connected`
- `global.disposed`
- `tui.prompt.append`
- `tui.command.execute`
- `tui.toast.show`
- `tui.session.select`
- `installation.updated`
- `installation.update-available`
- `project.updated`
- `workspace.ready`
- `workspace.failed`
- `server.instance.disposed`
- `file.edited`
- `worktree.ready`
- `worktree.failed`
- `lsp.client.diagnostics`
- `permission.asked`
- `permission.replied`
- `session.status`
- `session.idle`
- `question.asked`
- `question.replied`
- `question.rejected`
- `todo.updated`
- `pty.created`
- `pty.updated`
- `pty.exited`
- `pty.deleted`
- `file.watcher.updated`
- `mcp.tools.changed`
- `mcp.browser.open.failed`
- `lsp.updated`
- `vcs.branch.updated`
- `command.executed`
- `message.updated`
- `message.removed`
- `message.part.updated`
- `message.part.delta`
- `message.part.removed`
- `session.compacted`
- `session.created`
- `session.updated`
- `session.deleted`
- `session.diff`
- `session.error`

Operationally, expect `server.connected` as the initial event on new stream subscriptions.

---

## Complete Endpoint Inventory (Spec 0.0.3)

Use exact path parameter names as shown.

### Global

- `GET /global/health` (`global.health`)
- `GET /global/event` (`global.event`)
- `GET /global/config` (`global.config.get`)
- `PATCH /global/config` (`global.config.update`)
- `POST /global/dispose` (`global.dispose`)

### Auth

- `PUT /auth/{providerID}` (`auth.set`)
- `DELETE /auth/{providerID}` (`auth.remove`)

### Project

- `GET /project` (`project.list`)
- `GET /project/current` (`project.current`)
- `PATCH /project/{projectID}` (`project.update`)

### PTY

- `GET /pty` (`pty.list`)
- `POST /pty` (`pty.create`)
- `GET /pty/{ptyID}` (`pty.get`)
- `PUT /pty/{ptyID}` (`pty.update`)
- `DELETE /pty/{ptyID}` (`pty.remove`)
- `GET /pty/{ptyID}/connect` (`pty.connect`)

### Config

- `GET /config` (`config.get`)
- `PATCH /config` (`config.update`)
- `GET /config/providers` (`config.providers`)

### Providers

- `GET /provider` (`provider.list`)
- `GET /provider/auth` (`provider.auth`)
- `POST /provider/{providerID}/oauth/authorize` (`provider.oauth.authorize`)
- `POST /provider/{providerID}/oauth/callback` (`provider.oauth.callback`)

### Sessions and Messages

- `GET /session` (`session.list`)
- `POST /session` (`session.create`)
- `GET /session/status` (`session.status`)
- `GET /session/{sessionID}` (`session.get`)
- `DELETE /session/{sessionID}` (`session.delete`)
- `PATCH /session/{sessionID}` (`session.update`)
- `GET /session/{sessionID}/children` (`session.children`)
- `GET /session/{sessionID}/todo` (`session.todo`)
- `POST /session/{sessionID}/init` (`session.init`)
- `POST /session/{sessionID}/fork` (`session.fork`)
- `POST /session/{sessionID}/abort` (`session.abort`)
- `POST /session/{sessionID}/share` (`session.share`)
- `DELETE /session/{sessionID}/share` (`session.unshare`)
- `GET /session/{sessionID}/diff` (`session.diff`)
- `POST /session/{sessionID}/summarize` (`session.summarize`)
- `GET /session/{sessionID}/message` (`session.messages`)
- `POST /session/{sessionID}/message` (`session.prompt`)
- `GET /session/{sessionID}/message/{messageID}` (`session.message`)
- `DELETE /session/{sessionID}/message/{messageID}` (`session.deleteMessage`)
- `DELETE /session/{sessionID}/message/{messageID}/part/{partID}` (`part.delete`)
- `PATCH /session/{sessionID}/message/{messageID}/part/{partID}` (`part.update`)
- `POST /session/{sessionID}/prompt_async` (`session.prompt_async`)
- `POST /session/{sessionID}/command` (`session.command`)
- `POST /session/{sessionID}/shell` (`session.shell`)
- `POST /session/{sessionID}/revert` (`session.revert`)
- `POST /session/{sessionID}/unrevert` (`session.unrevert`)
- `POST /session/{sessionID}/permissions/{permissionID}` (`permission.respond`)

### Permission and Questions

- `GET /permission` (`permission.list`)
- `POST /permission/{requestID}/reply` (`permission.reply`)
- `GET /question` (`question.list`)
- `POST /question/{requestID}/reply` (`question.reply`)
- `POST /question/{requestID}/reject` (`question.reject`)

### Commands, Agents, Skills, Logging

- `GET /command` (`command.list`)
- `GET /agent` (`app.agents`)
- `GET /skill` (`app.skills`)
- `POST /log` (`app.log`)

### Files and Search

- `GET /find` (`find.text`)
- `GET /find/file` (`find.files`)
- `GET /find/symbol` (`find.symbols`)
- `GET /file` (`file.list`)
- `GET /file/content` (`file.read`)
- `GET /file/status` (`file.status`)

### MCP, LSP, Formatter

- `GET /mcp` (`mcp.status`)
- `POST /mcp` (`mcp.add`)
- `POST /mcp/{name}/auth` (`mcp.auth.start`)
- `DELETE /mcp/{name}/auth` (`mcp.auth.remove`)
- `POST /mcp/{name}/auth/callback` (`mcp.auth.callback`)
- `POST /mcp/{name}/auth/authenticate` (`mcp.auth.authenticate`)
- `POST /mcp/{name}/connect` (`mcp.connect`)
- `POST /mcp/{name}/disconnect` (`mcp.disconnect`)
- `GET /lsp` (`lsp.status`)
- `GET /formatter` (`formatter.status`)

### Experimental

- `GET /experimental/tool/ids` (`tool.ids`)
- `GET /experimental/tool` (`tool.list`) - requires query `provider` and `model`; optional `directory`, `workspace`
- `POST /experimental/workspace` (`experimental.workspace.create`)
- `GET /experimental/workspace` (`experimental.workspace.list`)
- `DELETE /experimental/workspace/{id}` (`experimental.workspace.remove`)
- `POST /experimental/worktree` (`worktree.create`)
- `GET /experimental/worktree` (`worktree.list`)
- `DELETE /experimental/worktree` (`worktree.remove`)
- `POST /experimental/worktree/reset` (`worktree.reset`)
- `GET /experimental/session` (`experimental.session.list`)
- `GET /experimental/resource` (`experimental.resource.list`)

### TUI and Instance

- `POST /tui/append-prompt` (`tui.appendPrompt`)
- `POST /tui/open-help` (`tui.openHelp`)
- `POST /tui/open-sessions` (`tui.openSessions`)
- `POST /tui/open-themes` (`tui.openThemes`)
- `POST /tui/open-models` (`tui.openModels`)
- `POST /tui/submit-prompt` (`tui.submitPrompt`)
- `POST /tui/clear-prompt` (`tui.clearPrompt`)
- `POST /tui/execute-command` (`tui.executeCommand`)
- `POST /tui/show-toast` (`tui.showToast`)
- `POST /tui/publish` (`tui.publish`)
- `POST /tui/select-session` (`tui.selectSession`)
- `GET /tui/control/next` (`tui.control.next`)
- `POST /tui/control/response` (`tui.control.response`)
- `POST /instance/dispose` (`instance.dispose`) - Does not shutdown the server

### Other

- `GET /path` (`path.get`)
- `GET /vcs` (`vcs.get`)
- `GET /event` (`event.subscribe`)

---

## Query/Path Parameter Notes for LLMs

- Use exact path params from spec (`{sessionID}`, `{messageID}`, `{providerID}`, `{projectID}`, `{ptyID}`, `{requestID}`, `{permissionID}`, `{name}`).
- Do not rewrite params as `:id` in generated code unless your HTTP client/router requires conversion.
- Common query params to support explicitly:
  - `/session/{sessionID}/message?limit=...`
  - `/session/{sessionID}/diff?messageID=...`
  - `/experimental/tool?provider=...&model=...&directory=...&workspace=...`
  - `/file?path=...`, `/file/content?path=...`
  - `/find?pattern=...`, `/find/file?query=...`, `/find/symbol?query=...`

---

## JVM/Kotlin Integration Notes

- HTTP: `java.net.http.HttpClient` or OkHttp.
- JSON: kotlinx.serialization or Gson.
- SSE: streaming HTTP response (manual parser) or OkHttp EventSource.

Minimal health check example:

```kotlin
val client = HttpClient.newHttpClient()
val request = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:4096/global/health"))
    .GET()
    .build()
val response = client.send(request, HttpResponse.BodyHandlers.ofString())
val ok = response.statusCode() == 200
```

Minimal prompt example:

```kotlin
val body = """
  {
    "parts": [{ "type": "text", "text": "Hello from IDE" }]
  }
""".trimIndent()

val request = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:4096/session/$sessionID/message"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(body))
    .build()
```

---

## Maintenance Checklist

When updating this file:

1. Pull fresh spec from `/doc`.
2. Verify endpoint inventory count and path/method exactness.
3. Refresh SSE event names from `components.schemas.Event.anyOf`.
4. Re-check high-value request body notes for session and TUI calls.
