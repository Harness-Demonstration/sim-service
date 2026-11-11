#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

mode="${1:-check}"
if [ "$mode" != check ] && [ "$mode" != --update ]; then
  echo "Usage: scripts/check-generated-docs.sh [--update]"
  exit 2
fi

output=$(mktemp -d)
trap 'rm -rf "$output"' EXIT

npx --yes @asyncapi/cli@6.3.0 generate fromTemplate \
  docs/contracts/sim-events.asyncapi.yaml \
  @asyncapi/markdown-template@2.0.0 \
  --output "$output" \
  --force-write \
  --no-interactive

generated="$output/asyncapi.md"
committed=docs/generated/kafka-topics.md
if [ ! -f "$generated" ]; then
  echo "::error::AsyncAPI generation did not produce $generated"
  exit 1
fi
perl -0pi -e 's/\n+\z/\n/' "$generated"
if [ "$mode" = --update ]; then
  mkdir -p "$(dirname "$committed")"
  cp "$generated" "$committed"
  exit 0
fi
if ! diff -u "$committed" "$generated"; then
  echo "::error::$committed is out of date. Run scripts/check-generated-docs.sh --update and commit the regenerated file."
  exit 1
fi
