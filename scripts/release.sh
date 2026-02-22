#!/usr/bin/env bash
set -euo pipefail

# Guard: must be on main branch
CURRENT_BRANCH=$(git branch --show-current)
if [ "$CURRENT_BRANCH" != "main" ]; then
  echo "Error: release must be run from the main branch (currently on '$CURRENT_BRANCH')"
  exit 1
fi

# Determine last release tag
LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")

if [ -z "$LAST_TAG" ]; then
  echo "No previous release found. Starting at v0.0.0"
  MAJOR=0; MINOR=0
  LOG_RANGE=""
else
  echo "Last release: $LAST_TAG"
  MAJOR=$(echo "$LAST_TAG" | sed 's/^v//' | cut -d. -f2)
  MINOR=$(echo "$LAST_TAG" | sed 's/^v//' | cut -d. -f3)
  LOG_RANGE="$LAST_TAG..HEAD"
fi

# Bump version: v0.MAJOR.MINOR (beta)
# feat commits bump major, otherwise bump minor
HAS_FEAT=$(git log $LOG_RANGE --pretty=format:"%s" | grep -ciE '^feat(\(|:| )' || true)
if [ "$HAS_FEAT" -gt 0 ]; then
  MAJOR=$((MAJOR + 1))
  MINOR=0
else
  MINOR=$((MINOR + 1))
fi

NEW_TAG="v0.${MAJOR}.${MINOR}"
echo "New release: $NEW_TAG"
echo ""

# Build changelog entry
TMPFILE=$(mktemp)

echo "## $NEW_TAG ($(date +%Y-%m-%d))" > "$TMPFILE"
echo "" >> "$TMPFILE"

FEATS=$(git log $LOG_RANGE --pretty=format:"- %s" | grep -iE '^- feat(\(|:| )' || true)
if [ -n "$FEATS" ]; then
  echo "### Features" >> "$TMPFILE"
  echo "$FEATS" >> "$TMPFILE"
  echo "" >> "$TMPFILE"
fi

FIXES=$(git log $LOG_RANGE --pretty=format:"- %s" | grep -iE '^- fix(\(|:| )' || true)
if [ -n "$FIXES" ]; then
  echo "### Fixes" >> "$TMPFILE"
  echo "$FIXES" >> "$TMPFILE"
  echo "" >> "$TMPFILE"
fi

OTHER=$(git log $LOG_RANGE --pretty=format:"- %s" | grep -viE '^- (feat|fix)(\(|:| )' || true)
if [ -n "$OTHER" ]; then
  echo "### Other" >> "$TMPFILE"
  echo "$OTHER" >> "$TMPFILE"
  echo "" >> "$TMPFILE"
fi

# Prepend to existing changelog or create new one
if [ -f CHANGELOG.md ]; then
  cat CHANGELOG.md >> "$TMPFILE"
else
  HEADER_FILE=$(mktemp)
  echo "# Changelog" | cat - "$TMPFILE" > "$HEADER_FILE"
  mv "$HEADER_FILE" "$TMPFILE"
fi

mv "$TMPFILE" CHANGELOG.md

# Update version in tauri.conf.json
NEW_VERSION="0.${MAJOR}.${MINOR}"
python3 -c "
import json, sys
p = 'macos/app/tauri.conf.json'
c = json.loads(open(p).read())
c['version'] = sys.argv[1]
open(p, 'w').write(json.dumps(c, indent=2) + '\n')
" "$NEW_VERSION"

# Commit, tag, and push
git add CHANGELOG.md macos/app/tauri.conf.json
git commit -m "chore: release $NEW_TAG"
git tag "$NEW_TAG"
git push origin main "$NEW_TAG"
echo "Pushed $NEW_TAG — release pipeline will build artifacts."
