---
name: Write test based on trace
description: Read trace log based on user actions in chat, create a failing test that captures the issue
compatibility: opencode
---

# Core

The user has experienced an issue. They followed a series of steps with the plugin and something
went wrong. With their explanation in mind, read the latest trace log and create a test that
captures the issue. The test must fail before any fix is applied.

# Trace files

Use a explore sub-agent to find the most recent trace

Trace files are written under Java's temp directory, not necessarily literal `/tmp`. The code uses
`Path.of(System.getProperty("java.io.tmpdir"), "opencode-diff-traces")`.

On macOS this often resolves to a sandboxed path such as
`/var/folders/.../T/opencode-diff-traces`, even though users may describe it as `/tmp`. Do not
assume `/tmp/opencode-diff-traces` exists before checking how `java.io.tmpdir` was resolved for the
running IDE instance.

If the user ran the plugin from Gradle's `Run Plugin` configuration, check the sandbox IDE log
first to find the exact trace file path. In this repo that is typically
`build/idea-sandbox/IU-*/log/idea.log`. Search for:

- `JsonlDiffTracer: tracing enabled file=`
- `failed to create trace directory`
- `failed to open trace file`

Use that log line to locate the exact JSONL trace file before reading it. Once you know the
directory, list it to find the most recent file (sorted by modification time). Check its line count
with `wc -l` before reading. If it is small (under a few hundred lines), read it in full with
`cat`. If it is large, use `grep` to filter by the `kind` values relevant to the issue, or read the
tail with `tail -n N` to focus on the most recent events.

Each line is a JSON object. Read events in `seq` order. Each event has a `kind`, a `sessionId`
inside `fields`, and additional fields that vary by kind. Read the fields that are present and
reason from them — do not assume which fields will be relevant before reading.

# Identifying the root cause

Do not jump to conclusions. Read the trace in full first, then reason from what you observe.

Describe what the trace shows and compare it against what the user expected to happen. The gap
between those two things is the bug. Only after identifying the gap should you form a hypothesis
about the cause.

# Writing the test

## Before writing

Search for existing test harnesses and helpers under `src/test` to understand what infrastructure
is already available. Use what fits; build what doesn't exist yet.

## Test structure

Each test must have:

1. A block comment stating the **invariant** being enforced.
2. A **MANUAL VERIFICATION** section: numbered, concrete steps the user can follow in the running
   plugin to confirm the fix. Do not start with "Run the plugin". Example:
   ```
   // MANUAL VERIFICATION:
   //   1. Ask the AI to create a new file with some content.
   //   2. Open the file — only green highlighting should be visible, no red inlay block.
   ```
3. A descriptive test name.

## Writing the block comment

The comment must describe the **user-visible invariant** — what the user expects to see — not the
implementation mechanism that enforces it. Code changes; the user expectation does not.

**Wrong** (describes implementation):

```
// Child session liveHunks are keyed by child session ID; the family query must include active
// child sessions regardless of whether parentBySessionId has been populated yet.
```

**Right** (describes user expectation):

```
// When the AI delegates work to parallel sub-agents, every file touched by any sub-agent must
// show a green highlight in the editor. All files must be visible simultaneously.
```

Do not mention: internal class names, field names, async timing, or data structures. If someone
who only uses the plugin (not reads the code) could not understand a sentence, rewrite it.

## The test must fail before any fix

The assertion must state what the user expects to see, not what the broken code currently does.
For example, do not write `assertTrue(result.isEmpty(), "demonstrating the bug")` when the correct
behaviour is `assertEquals(expectedFiles, result)`.

## Run the test

After writing, run the test class via `jetbrains_execute_run_configuration`. The new test must
fail and all existing tests must still pass.

# Rules

- Ask the user if the issue is unclear before reading the trace.
- Do not fix the issue. Only write the test.
- Use `jetbrains_*` tools for all file reads and edits.
