#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TRACE_DIR="$ROOT_DIR/build/diff-traces"
NOTE_FILE="$ROOT_DIR/build/sandbox-project/notes/note1.md"

mkdir -p "$TRACE_DIR"
mkdir -p "$(dirname "$NOTE_FILE")"

rm -f "$TRACE_DIR"/*.jsonl || true

cat > "$NOTE_FILE" <<'EOF'
1
2
3
4
5
6
7
8
9
10
EOF

echo "Diff trace capture prep complete."
echo "Trace output dir: $TRACE_DIR"
echo "Reset file: $NOTE_FILE"
echo
echo "IMPORTANT: Start IntelliJ/plugin with OPENCODE_DIFF_TRACE=1 to enable tracing."
echo
echo "Next steps:"
echo "1) Restart the OpenCode server/plugin listener if it is already running."
echo "2) In OpenCode, run these prompts in order:"
echo "   - add number 11 to @notes/note1.md"
echo "   - add number 12 to @notes/note1.md"
echo "   - remove the last two numbers from @notes/note1.md"
echo "   - add number 11 to @notes/note1.md"
echo "3) Run: scripts/collect_diff_trace_capture.sh"
