# Inline Diff Policy

Inline green/red editor highlights serve one purpose: **showing the user where the AI just made
changes in the current flow of work**. They are not a history viewer. They are not a review tool.
They are a real-time activity signal.

## Rules

### Rule 1 — Only the selected session may render inline highlights

No other session may paint green or red lines in the editor, regardless of whether it is busy,
idle, or has unreviewed changes. If a user is working with multiple LLMs simultaneously, each
doing edits, they will see highlights only for whichever session they have selected.

Concurrent sessions show their activity in the session list (busy badge, file count, timestamp)
— not in the editor.

### Rule 2 — Inline highlights show only the most recent turn

Within the selected session, only files touched in the **latest committed turn** are highlighted.
Files that were modified in earlier turns of the same session must not remain highlighted.

The OpenCode server always returns a cumulative `session.diff` across the entire session. The
plugin must scope inline rendering to the current turn's file set (`turn.patch` scope), ignoring
all prior turns.

### Rule 3 — Selecting a session swaps inline context immediately

When the user selects a different session, the previous session's inline highlights are cleared
immediately and the newly selected session's latest-turn highlights are painted. There is no
blending or merging across sessions.

### Rule 4 — Busy/idle status does not affect inline eligibility

A session that is briefly idle between turns is still a fully first-class selected session.
Its latest-turn highlights remain visible while it is idle and are replaced only when a new turn
completes. Busy/idle state drives activity indicators only (spinner, badge) — never inline
highlight eligibility.

### Rule 5 — Inline highlights do not survive IDE restart

Inline highlights are a runtime signal, not a persistent state. After an IDE restart, no inline
green/red highlights are shown — even for sessions that had active highlights before the restart.

When the plugin restarts and reloads historical session diffs from the server, that data is used
only to populate the session list (file counts, titles) and the diff preview panel. It never
restores inline highlights. Highlights only appear again once a new turn runs in the current
IDE session.

## What inline highlights are NOT for

- Reviewing the full cumulative diff of a session.
- Showing every file a session has ever touched.
- Comparing what multiple sessions changed across the same file.

Those use cases belong to the diff preview panel (double-click a file), the session list file
count badge, and future review tooling.

## Implementation notes

- `liveHunksBySessionAndFile` holds the latest-turn state for each session. This is what inline
  rendering must read from.
- `hunksBySessionAndFile` holds cumulative per-session state. This is used only for the diff
  preview panel and the session list file count badge.
- `fromHistory=true` applies must not write to `liveHunksBySessionAndFile` (already enforced).
- `EditorDiffRenderer` reads from `getHunks()` which sources from `liveHunksBySessionAndFile`
  via `inlineLiveHunks()`. Cumulative `hunksBySessionAndFile` is not used for inline rendering.
