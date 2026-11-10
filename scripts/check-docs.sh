#!/usr/bin/env bash
# Mechanical checks for the knowledge base: the map stays short, links and paths resolve,
# and every design doc and spec is listed in its index. Each failure says how to fix it.
set -u
cd "$(dirname "$0")/.."
fail=0
error() { echo "::error::$1"; fail=1; }

map=.github/copilot-instructions.md
lines=$(wc -l < "$map" | tr -d ' ')
[ "$lines" -le 100 ] || error "$map has $lines lines (limit 100). FIX: move detail into a document under docs/ and link to it from the map."

# Paths in backticks in the map must exist.
for path in $(grep -oE '`(docs/|scripts/|infra/|\.github/|ARCHITECTURE\.md)[^` ]*`' "$map" | tr -d '`' | sort -u); do
  [ -e "$path" ] || error "$map points to $path, which does not exist. FIX: correct the path or create the file."
done

# Relative Markdown links must resolve.
while IFS= read -r file; do
  dir=$(dirname "$file")
  for target in $(grep -oE '\]\([^)#]+' "$file" | sed 's/^](//' | grep -vE '^(https?:|mailto:)'); do
    [ -e "$dir/$target" ] || error "$file links to $target, which does not exist. FIX: correct the link or create the file."
  done
done < <(git ls-files '*.md')

# Every design doc and spec is listed in its index.
for folder in docs/design-docs docs/product-specs; do
  for doc in "$folder"/*.md; do
    name=$(basename "$doc")
    [ "$name" = index.md ] && continue
    grep -q "($name)" "$folder/index.md" || error "$doc is not listed in $folder/index.md. FIX: add a row for it to the index."
  done
done

[ "$fail" -eq 0 ] && echo "docs check passed"
exit "$fail"
