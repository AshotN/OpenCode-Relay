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

- Build with **JDK 21** (project targets JVM 21, Kotlin 2.1.0). The Kotlin 2.1.0 Gradle daemon crashes on JDK 27
  (the system default on the dev machine) with `IllegalArgumentException: 27` from `JavaVersion.parse`.
  If a build fails that way: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && ./gradlew ...`, then
  `./gradlew --stop` to kill a poisoned daemon.
- Unit tests: `./gradlew test`.
- Live integration tests live in `src/liveTest` and only run when the `OPENCODE_TEST_VERSIONS` environment
  variable lists OpenCode **2.x** versions to test against.

## Kotlin Style

New and changed Kotlin follows the `best-practice-kotlin` skill, adapted to this project:

- Kotlin 2.1.0, JVM 21, 4-space indentation
- No `!!`; sealed types for result/event models (`ApiResult`, `OpenCodeEvent`)
- Read-only collection types (`List`, `Map`, `Set`) at API boundaries
- No new lint tooling (the project does not run ktlint/detekt)
