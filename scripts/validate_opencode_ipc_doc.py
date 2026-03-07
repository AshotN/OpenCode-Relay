#!/usr/bin/env python3
import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOC_PATH = ROOT / "docs" / "opencode-ipc.md"

INVENTORY_HEADER = "## Complete Endpoint Inventory (Spec 0.0.3)"
ENDPOINT_LINE = re.compile(r"^\s*-\s*`(GET|POST|PUT|PATCH|DELETE)\s+([^`]+)`")
EVENT_LINE = re.compile(r"^\s*-\s*`([^`]+)`\s*$")

SECTION_ORDER = [
    "Global",
    "Auth",
    "Project",
    "PTY",
    "Config",
    "Providers",
    "Sessions and Messages",
    "Permission and Questions",
    "Commands, Agents, Skills, Logging",
    "Files and Search",
    "MCP, LSP, Formatter",
    "Experimental",
    "TUI and Instance",
    "Other",
]


def normalize_endpoint(method: str, path: str) -> str:
    return f"{method} {path.split('?')[0].strip()}"


def fetch_openapi(spec_url: str) -> dict:
    with urllib.request.urlopen(spec_url, timeout=15) as response:
        payload = response.read().decode("utf-8")
    return json.loads(payload)


def extract_inventory_endpoints_and_counts(doc_text: str) -> tuple[set[str], dict[str, int]]:
    lines = doc_text.splitlines()
    in_inventory = False
    current_section = ""

    endpoints: set[str] = set()
    section_counts: dict[str, int] = {}

    for line in lines:
        if not in_inventory:
            if line.strip() == INVENTORY_HEADER:
                in_inventory = True
            continue

        if line.startswith("## "):
            break

        if line.startswith("### "):
            current_section = line[4:].strip()
            section_counts.setdefault(current_section, 0)
            continue

        match = ENDPOINT_LINE.match(line)
        if not match:
            continue

        if not current_section:
            continue

        endpoint = normalize_endpoint(match.group(1), match.group(2))
        endpoints.add(endpoint)
        section_counts[current_section] = section_counts.get(current_section, 0) + 1

    return endpoints, section_counts


def extract_events(doc_text: str) -> set[str]:
    marker = "### Event types in current schema"
    idx = doc_text.find(marker)
    if idx == -1:
        return set()

    section = doc_text[idx + len(marker) :]
    end_idx = section.find("\n---")
    if end_idx != -1:
        section = section[:end_idx]

    events: set[str] = set()
    for line in section.splitlines():
        match = EVENT_LINE.match(line)
        if not match:
            continue
        candidate = match.group(1)
        if "/" in candidate or candidate.startswith("GET "):
            continue
        events.add(candidate)
    return events


def classify_endpoint(path: str) -> str:
    if path.startswith("/global/"):
        return "Global"
    if path.startswith("/auth/"):
        return "Auth"
    if path.startswith("/project"):
        return "Project"
    if path.startswith("/pty"):
        return "PTY"
    if path.startswith("/config"):
        return "Config"
    if path.startswith("/provider"):
        return "Providers"
    if path.startswith("/session"):
        return "Sessions and Messages"
    if path.startswith("/permission") or path.startswith("/question"):
        return "Permission and Questions"
    if path in {"/command", "/agent", "/skill", "/log"}:
        return "Commands, Agents, Skills, Logging"
    if path.startswith("/find") or path.startswith("/file"):
        return "Files and Search"
    if path.startswith("/mcp") or path in {"/lsp", "/formatter"}:
        return "MCP, LSP, Formatter"
    if path.startswith("/experimental"):
        return "Experimental"
    if path.startswith("/tui") or path == "/instance/dispose":
        return "TUI and Instance"
    if path in {"/path", "/vcs", "/event"}:
        return "Other"
    raise ValueError(f"Unmapped endpoint path: {path}")


def expected_section_counts_from_spec(spec: dict) -> dict[str, int]:
    counts = {section: 0 for section in SECTION_ORDER}
    for path, operations in spec["paths"].items():
        section = classify_endpoint(path)
        counts[section] += len(operations)
    return counts


def endpoints_from_spec(spec: dict) -> set[str]:
    endpoints: set[str] = set()
    for path, operations in spec["paths"].items():
        for method in operations.keys():
            endpoints.add(f"{method.upper()} {path}")
    return endpoints


def events_from_spec(spec: dict) -> set[str]:
    refs = spec["components"]["schemas"]["Event"]["anyOf"]
    return {entry["$ref"].split("Event.", 1)[1] for entry in refs}


def print_diff(title: str, values: list[str]) -> None:
    if not values:
        return
    print(f"{title} ({len(values)}):")
    for value in values:
        print(f"  - {value}")


def print_count_diffs(expected: dict[str, int], found: dict[str, int]) -> None:
    all_sections = sorted(set(expected.keys()) | set(found.keys()))
    mismatches = []
    for section in all_sections:
        exp = expected.get(section)
        got = found.get(section)
        if exp != got:
            mismatches.append((section, exp, got))

    if not mismatches:
        return

    print(f"Section endpoint count mismatches ({len(mismatches)}):")
    for section, exp, got in mismatches:
        print(f"  - {section}: expected={exp}, found={got}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--spec-url", default="http://127.0.0.1:4096/doc")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    doc_text = DOC_PATH.read_text(encoding="utf-8")
    spec = fetch_openapi(args.spec_url)

    expected_endpoints = endpoints_from_spec(spec)
    expected_section_counts = expected_section_counts_from_spec(spec)
    found_endpoints, found_section_counts = extract_inventory_endpoints_and_counts(doc_text)

    missing_endpoints = sorted(expected_endpoints - found_endpoints)
    unexpected_endpoints = sorted(found_endpoints - expected_endpoints)

    expected_events = events_from_spec(spec)
    found_events = extract_events(doc_text)

    missing_events = sorted(expected_events - found_events)
    unexpected_events = sorted(found_events - expected_events)

    has_doc_json_note = "serves OpenAPI JSON" in doc_text

    print("OpenCode IPC doc validation")
    print(f"- spec url: {args.spec_url}")
    print(f"- endpoints expected: {len(expected_endpoints)}")
    print(f"- endpoints found: {len(found_endpoints)}")
    print(f"- endpoint sections expected: {len(expected_section_counts)}")
    print(f"- endpoint sections found: {len(found_section_counts)}")
    print(f"- events expected: {len(expected_events)}")
    print(f"- events found: {len(found_events)}")

    ok = True
    if missing_endpoints or unexpected_endpoints:
        ok = False
        print_diff("Missing endpoints", missing_endpoints)
        print_diff("Unexpected endpoints", unexpected_endpoints)

    if expected_section_counts != found_section_counts:
        ok = False
        print_count_diffs(expected_section_counts, found_section_counts)

    if missing_events or unexpected_events:
        ok = False
        print_diff("Missing events", missing_events)
        print_diff("Unexpected events", unexpected_events)

    if not has_doc_json_note:
        ok = False
        print("Missing required note: /doc serves OpenAPI JSON")

    if ok:
        print("Validation passed.")
        return 0

    print("Validation failed.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
