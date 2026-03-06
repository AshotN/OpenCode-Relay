# OpenCode Diff API — Research Findings

## Summary

The OpenCode server has **no ability to return per-message (per-turn) diffs**.
All diff endpoints always return the full cumulative session diff, regardless of
any query parameters passed.

## Endpoints Tested

### `GET /session/:id/diff`
Returns the cumulative diff for the entire session:
- `before` = file content at session start
- `after` = current file content on disk

### `GET /session/:id/diff?messageID=<id>`
**The `messageID` parameter is silently ignored.**
Returns identical data to the unparameterised endpoint regardless of which
messageID is passed. Verified by:
1. Querying the same session with different messageIDs from different turns
2. All returned identical `before`/`after` content
3. The `before` for "add item 7" and "remove all items" turns were identical

### `GET /session/:id/diff?snapshot=<hash>`
The patch part of `message.part.updated` SSE events carries a `hash` field.
We tested this as a `snapshot` query parameter.
**Also silently ignored** — returns identical cumulative data.

Also tested `snapshot=<prevHash>&snapshot2=<currentHash>` — same result.

## SSE Event Ordering (Confirmed)

Per tool call, events arrive in this order:

```
session.busy          ← first one = new turn; clear state
session.diff          ← STALE: after = previous turn's result
permission.asked      ← (if applicable)
permission.replied    ← (if applicable)
TurnPatch             ← correct file list + messageId, but diff still stale
session.busy          ← repeated, ignored
session.diff          ← FRESH: after = this turn's result  ← apply here
session.busy + session.idle
session.diff          ← final repeat, ignored
```

Key insight: `TurnPatch` arrives BEFORE the `session.diff` that has fresh content.
The `session.diff` immediately before `TurnPatch` is stale.

## What We Can Reliably Get

| Data Point | Source | Reliable? |
|---|---|---|
| Which files were touched | `TurnPatch.files` | ✓ Yes |
| messageID for revert | `TurnPatch.messageId` | ✓ Yes |
| Current file content (`after`) | Fresh `session.diff` after TurnPatch | ✓ Yes |
| Pre-turn file content (`before`) | Cached `after` from previous turn | ✓ Yes (with cache) |
| Per-message before/after | Any diff endpoint with messageID | ✗ No |

## Current Plugin Strategy

Since the server cannot scope diffs per message, the plugin uses:

1. **Stash-then-apply**: `TurnPatch` is stashed; the next `session.diff` (fresh)
   is consumed to build hunks.

2. **`lastKnownAfter` cache**: The `after` from each applied diff is cached by
   relative file path. The next turn uses this as `before`, so the diff shows
   only the current turn's delta rather than the full session history.

3. **First-turn behaviour**: Cache starts empty, so the first turn of a session
   uses `session.diff.before` (session-start content) as `before` — which is
   correct for turn 1.

4. **Cache reset**: `clearAll()` clears the cache so after Keep All / Revert All
   the next turn starts from the new baseline.

## Outstanding Problem

The stash-then-apply is consuming the wrong `session.diff`. The log shows
`stashing TurnPatch` immediately followed by `applying patch` with no gap,
meaning the stale `session.diff` (which arrived before `TurnPatch`) is being
consumed rather than the fresh one that follows. This needs to be fixed.
