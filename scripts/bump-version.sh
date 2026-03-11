#!/usr/bin/env bash
# Usage: ./scripts/bump-version.sh <new-version>
#        ./scripts/bump-version.sh --next-patch
#        ./scripts/bump-version.sh --next-minor
#        ./scripts/bump-version.sh --next-major
#
# This script:
#   1. Updates pluginVersion in gradle.properties
#   2. Commits the change
#   3. Creates a git tag vX.Y.Z
#
# When called via `make release/patch|minor|major`, the push happens automatically.
# The tag push triggers the GitHub release workflow.

set -euo pipefail

PROPS_FILE="gradle.properties"
CURRENT_VERSION=$(grep '^pluginVersion=' "$PROPS_FILE" | cut -d= -f2)

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

echo "Bumping $CURRENT_VERSION → $NEW_VERSION"

# Update gradle.properties
sed -i "s/^pluginVersion=.*/pluginVersion=${NEW_VERSION}/" "$PROPS_FILE"

git add "$PROPS_FILE"
git commit -m "chore: bump version to ${NEW_VERSION}"
git tag "$TAG"

echo "Done. Tag $TAG created."
