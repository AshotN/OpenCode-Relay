#!/usr/bin/env bash
# Usage: ./scripts/bump-version.sh <new-version>
#        ./scripts/bump-version.sh --next-patch
#        ./scripts/bump-version.sh --next-minor
#        ./scripts/bump-version.sh --next-major
#
# This script:
#   1. Updates pluginVersion in gradle.properties
#   2. Promotes CHANGELOG.md [Unreleased] notes to the target version
#   3. Commits the change
#   4. Creates a git tag vX.Y.Z
#
# When called via `make release/patch|minor|major`, the push happens automatically.
# The tag push triggers the GitHub release workflow.

set -euo pipefail

PROPS_FILE="gradle.properties"
CHANGELOG_FILE="CHANGELOG.md"
CURRENT_VERSION=$(grep '^pluginVersion=' "$PROPS_FILE" | cut -d= -f2)
RELEASE_DATE=$(date +%F)

# Parse current version parts
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"

if [ $# -ne 1 ]; then
  echo "Usage: $0 <new-version> | --next-patch | --next-minor | --next-major"
  exit 1
fi

case "$1" in
  --next-patch)
    NEW_VERSION="${MAJOR}.${MINOR}.$((PATCH + 1))"
    ;;
  --next-minor)
    NEW_VERSION="${MAJOR}.$((MINOR + 1)).0"
    ;;
  --next-major)
    NEW_VERSION="$((MAJOR + 1)).0.0"
    ;;
  *)
    NEW_VERSION="$1"
    ;;
esac
TAG="v${NEW_VERSION}"

# Validate version format (semver: X.Y.Z)
if ! echo "$NEW_VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "Error: version must be in X.Y.Z format (e.g. 0.2.0)"
  exit 1
fi

if [ "$NEW_VERSION" = "$CURRENT_VERSION" ]; then
  echo "Error: version is already $CURRENT_VERSION"
  exit 1
fi

# Check for uncommitted changes
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "Error: you have uncommitted changes. Commit or stash them first."
  exit 1
fi

# Check the tag doesn't already exist
if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "Error: tag $TAG already exists."
  exit 1
fi

if [ ! -f "$CHANGELOG_FILE" ]; then
  echo "Error: $CHANGELOG_FILE not found."
  exit 1
fi

if ! grep -q '^## \[Unreleased\]$' "$CHANGELOG_FILE"; then
  echo "Error: $CHANGELOG_FILE is missing a [Unreleased] section."
  exit 1
fi

if grep -qE "^## \[${NEW_VERSION}\]( - .*)?$" "$CHANGELOG_FILE"; then
  echo "Error: $CHANGELOG_FILE already contains a section for $NEW_VERSION."
  exit 1
fi

echo "Bumping $CURRENT_VERSION → $NEW_VERSION"

# Update gradle.properties
if sed --version >/dev/null 2>&1; then
  # GNU sed (Linux)
  sed -i "s/^pluginVersion=.*/pluginVersion=${NEW_VERSION}/" "$PROPS_FILE"
else
  # BSD sed (macOS)
  sed -i '' "s/^pluginVersion=.*/pluginVersion=${NEW_VERSION}/" "$PROPS_FILE"
fi

TMP_CHANGELOG=$(mktemp "${TMPDIR:-/tmp}/opencode-relay-changelog.XXXXXX")
cleanup() {
  rm -f "$TMP_CHANGELOG"
}
trap cleanup EXIT

awk -v version="$NEW_VERSION" -v release_date="$RELEASE_DATE" '
function emit_release_section(    body) {
  body = unreleased_body
  gsub(/^[[:space:]]+/, "", body)
  gsub(/[[:space:]]+$/, "", body)
  if (body == "") {
    print "Error: CHANGELOG.md has an empty [Unreleased] section." > "/dev/stderr"
    exit 2
  }
  print "## [" version "] - " release_date
  print ""
  print body
}
BEGIN {
  found_unreleased = 0
  in_unreleased = 0
  emitted_release = 0
  unreleased_body = ""
}
{
  if (!found_unreleased && $0 == "## [Unreleased]") {
    found_unreleased = 1
    in_unreleased = 1
    print $0
    print ""
    next
  }

  if (in_unreleased) {
    if ($0 ~ /^## \[/) {
      emit_release_section()
      print ""
      print $0
      in_unreleased = 0
      emitted_release = 1
    } else {
      unreleased_body = unreleased_body $0 ORS
    }
    next
  }

  print
}
END {
  if (!found_unreleased) {
    print "Error: CHANGELOG.md is missing the [Unreleased] section." > "/dev/stderr"
    exit 3
  }
  if (in_unreleased) {
    emit_release_section()
    emitted_release = 1
  }
  if (!emitted_release) {
    print "Error: failed to promote [Unreleased] changelog entry." > "/dev/stderr"
    exit 4
  }
}
' "$CHANGELOG_FILE" > "$TMP_CHANGELOG"

mv "$TMP_CHANGELOG" "$CHANGELOG_FILE"
trap - EXIT

git add "$PROPS_FILE" "$CHANGELOG_FILE"
git commit -m "chore: bump version to ${NEW_VERSION}"
git tag "$TAG"

echo "Done. Tag $TAG created."
