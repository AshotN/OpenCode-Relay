#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TRACE_DIR="$ROOT_DIR/build/diff-traces"
OUT_FILE="$ROOT_DIR/build/diff-traces-package.tgz"

if [ ! -d "$TRACE_DIR" ]; then
  echo "No trace directory found: $TRACE_DIR"
  exit 1
fi

LATEST_TRACE="$(ls -1t "$TRACE_DIR"/*.jsonl 2>/dev/null | head -n 1 || true)"
if [ -z "$LATEST_TRACE" ]; then
  echo "No trace files found in $TRACE_DIR"
  exit 1
fi

tar -czf "$OUT_FILE" -C "$ROOT_DIR" "build/diff-traces"

echo "Trace capture complete."
echo "Latest trace: $LATEST_TRACE"
echo "Archive: $OUT_FILE"
