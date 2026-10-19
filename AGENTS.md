# OpenCode-Plugin Project Rules

## About

This repo is a JetBrains IDE plugin that integrates the OpenCode AI coding assistant into IntelliJ-based IDEs. It
provides a tool window, settings panel, and lifecycle management for launching and interacting with the OpenCode CLI
from within the IDE.

## OpenCode 2.x Only

The plugin speaks the OpenCode 2.x HTTP/SSE API exclusively:

- OpenCode 1.x executables are detected at startup and reported with a dedicated "OpenCode 2.x required" message.
  Do not reintroduce v1 code paths, endpoints (`/session`, `/tui/*`, `prompt_async`, ...), or dual-version logic.
- All `/api/*` requests carry Basic auth (`opencode:<password>`). Servers the plugin launches receive their
  password via the `OPENCODE_PASSWORD` environment variable.
- v2 API facts were verified against a live v2 server (OpenAPI spec with 119 paths) and the official v2 client
  types. When in doubt, trust the live server over memory.

## Prefer JetBrains Tools

When searching the code base or attempting to run a build, take a look at the available run configurations
(`idea.get_run_configurations`). Always prefer `idea.*` tools over generic alternatives when both are available:

| Task                  | Prefer                                      | Over                       |
|-----------------------|---------------------------------------------|----------------------------|
| Read a file           | `idea.read_file`                            | `Read` / `cat`             |
| Create a file         | `idea.create_new_file`                      | `Write` / `echo`           |
| Find files by pattern | `idea.search_file` (glob)                   | `Glob` / `find`            |
| Search content        | `idea.search_text` or `idea.search_regex`   | `Grep` / `rg` / `grep`     |
| Browse directory      | `idea.list_directory_tree`                  | `ls` / `find`              |
| Rename symbols        | `idea.rename_refactoring`                   | text search-replace        |
| Check file errors     | `idea.get_file_problems`                    | manual inspection          |
| Build project         | `idea.build_project`                        | `./gradlew build` via Bash |

The tools are exposed under the `idea` MCP namespace (catalog paths look like `tools.idea.read_file`). For IDE
capabilities not listed above, use `idea.execute_tool` to invoke the underlying IDE MCP tool dynamically.

## Build

- Build with **JDK 21** (`/usr/lib/jvm/java-21-openjdk-amd64`; project targets JVM 21, Kotlin 2.1.0).
  JDK 21 is required: the Kotlin 2.1.0 compiler crashes on JDK 25+ with `IllegalArgumentException: 25...`
  from `JavaVersion.parse` ("Internal compiler error") — the release predates those JDKs.
- The crash follows the **Gradle daemon's** JVM (the Kotlin compiler runs inside the daemon). If a
  JDK 25 daemon is already running, setting `JAVA_HOME` alone does not fix the build — run
  `./gradlew --stop` first, then `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && ./gradlew ...`.
- Unit tests: `./gradlew test`.
- Live integration tests live in `src/liveTest` and only run when the `OPENCODE_TEST_VERSIONS` environment
  variable lists OpenCode **2.x** versions to test against. They also require `OPENCODE_TEST_AUTH_JSON`
  (OpenCode provider `auth.json` content) and, by default, `npm` to install each version. On machines
  without node/npm (the dev box has none — OpenCode is a standalone native binary there), set
  `OPENCODE_TEST_BIN` to a pre-installed `opencode` binary; its `--version` must match the test list.
  Optionally set `OPENCODE_TEST_MODEL=providerID:modelID` to pin the session model (default model
  resolution can flake on small local deployments; the model ID must be the provider-registered
  one, e.g. `qwen3.8-27b`, not the Ollama-style tag `qwen3.8:27b`).
  Live scenarios start with an empty config home, so a provider defined only in the user's own
  `~/.config/opencode/opencode.json` (e.g. a local llama.cpp endpoint with a custom model catalog)
  is invisible to the test server — turns then fail with `provider.no-route`/deprecated-catalog
  errors. Set `OPENCODE_TEST_CONFIG_JSON` to the JSON written as the global config
  (`opencode/opencode.json` under the isolated config home; the `providers` definition plus the
  default `model` is sufficient, and the API key still comes from `OPENCODE_TEST_AUTH_JSON`).
  Live servers are started with a
  client-generated `OPENCODE_PASSWORD` (v2 servers always require Basic auth), and sessions are
  created with explicit permission rules (v2 removed the v1 `OPENCODE_PERMISSION` env var).
  Note: these variables are read inside the **Gradle daemon**, whose environment is fixed at
  daemon start — after changing any of them, run `./gradlew --stop` first or the stale value
  from the daemon that started stays in effect.

## Kotlin Style

New and changed Kotlin follows the `best-practice-kotlin` skill, adapted to this project:

- Kotlin 2.1.0, JVM 21, 4-space indentation
- No `!!`; sealed types for result/event models (`ApiResult`, `OpenCodeEvent`)
- Read-only collection types (`List`, `Map`, `Set`) at API boundaries
- No new lint tooling (the project does not run ktlint/detekt)
